#!/usr/bin/env python3
"""Reference ALIS v5.2 training worker service (stdlib-only orchestration, no bundled trainer).

This service runs trainers only inside a pinned, network-isolated container image. It intentionally
has no host-process fallback: if the runtime, image digest, dependency lock, resource limits,
artifact allow-list, or redacted logging policy are unavailable, training remains disabled.
Deploy behind HTTPS (e.g. a TLS reverse proxy) for remote clients.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import os
import re
import shutil
import signal
import subprocess
import threading
import time
import urllib.parse
import urllib.request
import urllib.error
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Callable

HOST = os.environ.get("ALIS_BIND_HOST", "127.0.0.1")
PORT = int(os.environ.get("ALIS_PORT", "8765"))
API_KEY = os.environ.get("ALIS_API_KEY", "")
PUBLIC_BASE_URL = os.environ.get("ALIS_PUBLIC_BASE_URL", f"http://127.0.0.1:{PORT}").rstrip("/")
ROOT = Path(os.environ.get("ALIS_TRAINING_ROOT", "./alis-training-data")).resolve()
DATASET_ALLOWED_HOSTS = {h.strip().lower() for h in os.environ.get("ALIS_DATASET_ALLOWED_HOSTS", "").split(",") if h.strip()}
TRAINER = os.environ.get("ALIS_TRAINER_EXECUTABLE", "").strip()
try:
    ARG_TEMPLATE = json.loads(os.environ.get("ALIS_TRAINER_ARGUMENTS_JSON", "[]"))
except json.JSONDecodeError as exc:
    raise SystemExit(f"Invalid ALIS_TRAINER_ARGUMENTS_JSON: {exc}")
DATASET_MAX_BYTES = int(os.environ.get("ALIS_MAX_DATASET_BYTES", str(20 * 1024**3)))
ARTIFACT_MAX_BYTES = int(os.environ.get("ALIS_MAX_ARTIFACT_BYTES", str(200 * 1024**3)))
JOB_TIMEOUT_MAX_MS = int(os.environ.get("ALIS_MAX_JOB_TIMEOUT_MS", str(24 * 60 * 60 * 1000)))
MAX_JOBS = max(1, int(os.environ.get("ALIS_MAX_CONCURRENT_JOBS", "1")))
MAX_PENDING_JOBS = max(MAX_JOBS, int(os.environ.get("ALIS_MAX_PENDING_JOBS", str(MAX_JOBS * 4))))
ARTIFACT_SUFFIXES = {
    ".safetensors", ".gguf", ".pt", ".pth", ".bin", ".adapter", ".ckpt", ".onnx",
    ".json", ".model", ".txt", ".tokenizer", ".tiktoken", ".zip", ".vocab", ".merges",
    ".index", ".yaml", ".yml", ".jinja", ".dat",
}

if len(API_KEY) < 32:
    raise SystemExit("Set ALIS_API_KEY to a random secret of at least 32 characters.")
if not isinstance(ARG_TEMPLATE, list) or not all(isinstance(x, str) for x in ARG_TEMPLATE):
    ARG_TEMPLATE = []

# The allow-list is intentionally empty by default. A worker without an explicit artifact
# policy can serve health information, but it cannot accept or run a training job.
ARTIFACT_ALLOWLIST = {
    suffix.strip().lower() for suffix in os.environ.get("ALIS_ARTIFACT_SUFFIX_ALLOWLIST", "").split(",")
    if suffix.strip()
}


def redact_text(value: str) -> str:
    """Redact common credentials and signed URL query strings before anything is persisted or returned."""
    text = value
    text = re.sub(r"(?i)\bBearer\s+[A-Za-z0-9._~+/-]+=*", "Bearer [REDACTED]", text)
    # Recognize common provider/cloud key prefixes even when they appear without a label.
    text = re.sub(r"\bsk-[A-Za-z0-9_-]{20,}\b", "[REDACTED_OPENAI_KEY]", text)
    text = re.sub(r"\bAIza[0-9A-Za-z_-]{30,}\b", "[REDACTED_GOOGLE_KEY]", text)
    text = re.sub(r"\bAKIA[0-9A-Z]{16}\b", "[REDACTED_AWS_KEY]", text)
    text = re.sub(
        r"(?s)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----",
        "[REDACTED_PRIVATE_KEY]", text,
    )
    text = re.sub(
        r"(?i)(authorization|api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret)\s*([:=])\s*(\"[^\"]*\"|'[^']*'|[^\s,;]+)",
        r"\1\2[REDACTED]", text,
    )
    text = re.sub(r"https?://[^\s?]+\?[^\s]+", "[URL_QUERY_REDACTED]", text)
    text = re.sub(r"(?i)([?&](?:token|key|sig|signature|credential|password)=)[^&\s]+", r"\1[REDACTED]", text)
    return text


def validate_dependency_lock_text(content: str) -> bool:
    """Accept only one-line exact package pins with SHA-256 hashes; no floating specs or URLs."""
    entries = [line.strip() for line in content.splitlines() if line.strip() and not line.lstrip().startswith("#")]
    if not entries:
        return False
    for line in entries:
        match = re.match(r"^[A-Za-z0-9][A-Za-z0-9_.-]*==[^\s;]+(.*)$", line)
        if not match:
            return False
        hashes = re.findall(r"--hash=sha256:([a-fA-F0-9]{64})", match.group(1))
        residue = re.sub(r"--hash=sha256:[a-fA-F0-9]{64}", "", match.group(1)).strip()
        if not hashes or residue not in {"", "\\"}:
            return False
    return True


def _runtime_path(runtime_value: str) -> str | None:
    candidate = runtime_value.strip()
    if not candidate:
        return None
    if os.path.isabs(candidate):
        return candidate if os.path.isfile(candidate) and os.access(candidate, os.X_OK) else None
    return shutil.which(candidate)


def assess_sandbox_configuration(env: dict[str, str], inspect_image: Callable[[str, str, str], str] | None = None) -> tuple[dict[str, Any] | None, str]:
    """Validate every required sandbox control. Missing/invalid configuration is a hard disable."""
    runtime = _runtime_path(env.get("ALIS_SANDBOX_RUNTIME", "docker"))
    if not runtime:
        return None, "container runtime is unavailable"
    image = env.get("ALIS_SANDBOX_IMAGE", "").strip()
    image_match = re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:/-]*@sha256:([a-fA-F0-9]{64})", image)
    if not image_match:
        return None, "sandbox image must be pinned by sha256 digest"
    lock_file = env.get("ALIS_DEPENDENCY_LOCK_FILE", "").strip()
    expected_lock = env.get("ALIS_DEPENDENCY_LOCK_SHA256", "").strip().lower()
    if not lock_file or not os.path.isabs(lock_file) or not re.fullmatch(r"[a-f0-9]{64}", expected_lock):
        return None, "a dependency lock file and SHA-256 are required"
    try:
        lock_path = Path(lock_file)
        lock_content = lock_path.read_text(encoding="utf-8")
        actual_lock = hashlib.sha256(lock_path.read_bytes()).hexdigest()
    except OSError:
        return None, "dependency lock file is unavailable"
    if not hmac.compare_digest(actual_lock, expected_lock) or not validate_dependency_lock_text(lock_content):
        return None, "dependency lock is not hash-verified and fully pinned"

    memory = env.get("ALIS_SANDBOX_MEMORY_LIMIT", "").strip().lower()
    cpus = env.get("ALIS_SANDBOX_CPUS", "").strip()
    pids = env.get("ALIS_SANDBOX_PIDS_LIMIT", "").strip()
    tmpfs = env.get("ALIS_SANDBOX_TMPFS_MB", "").strip()
    if not re.fullmatch(r"[1-9][0-9]*(?:m|g)", memory):
        return None, "a bounded container memory limit is required"
    try:
        cpu_value = float(cpus)
        pids_value = int(pids)
        tmpfs_value = int(tmpfs)
    except (TypeError, ValueError):
        return None, "container CPU, PID and tmpfs limits are invalid"
    if not (0.25 <= cpu_value <= 64) or not (32 <= pids_value <= 8192) or not (64 <= tmpfs_value <= 4096):
        return None, "container CPU, PID or tmpfs limits are outside safe bounds"

    artifact_allowlist = {
        suffix.strip().lower() for suffix in env.get("ALIS_ARTIFACT_SUFFIX_ALLOWLIST", "").split(",")
        if suffix.strip()
    }
    if not artifact_allowlist or not artifact_allowlist.issubset(ARTIFACT_SUFFIXES):
        return None, "an explicit artifact suffix allow-list is required"
    if not TRAINER.startswith("/") or not ARG_TEMPLATE:
        return None, "the trainer executable must be an absolute path inside the sandbox image"
    try:
        timeout_limit = int(env.get("ALIS_MAX_JOB_TIMEOUT_MS", str(JOB_TIMEOUT_MAX_MS)))
    except ValueError:
        return None, "the maximum job timeout is invalid"
    if not (1_000 <= timeout_limit <= 86_400_000):
        return None, "the maximum job timeout must be between 1 second and 24 hours"

    if inspect_image is None:
        def inspect_image(runtime_path: str, image_ref: str, timeout: str) -> str:
            result = subprocess.run(
                [runtime_path, "image", "inspect", "--format", '{{ index .Config.Labels "org.vibes.dependency-lock-sha256" }}', image_ref],
                check=True, capture_output=True, text=True, timeout=10,
            )
            # Verify the configured interpreter exists and is executable inside the image.
            subprocess.run(
                [runtime_path, "run", "--rm", "--network=none", "--read-only", "--entrypoint", "/bin/sh",
                 image_ref, "-c", 'test -x "$1"', "sandbox-check", TRAINER],
                check=True, capture_output=True, text=True, timeout=10,
            )
            return result.stdout.strip().lower()
    try:
        embedded_lock = inspect_image(runtime, image, "10")
    except Exception:
        return None, "pinned sandbox image is not locally available or could not be inspected"
    if not hmac.compare_digest(embedded_lock, expected_lock):
        return None, "sandbox image dependency-lock label does not match the verified lock file"

    return {
        "runtime": runtime,
        "image": image,
        "imageDigest": image_match.group(1).lower(),
        "dependencyLockSha256": expected_lock,
        "memoryLimit": memory,
        "cpus": cpus,
        "pidsLimit": pids_value,
        "tmpfsMb": tmpfs_value,
        "maxJobTimeoutMs": timeout_limit,
        "artifactSuffixAllowlist": sorted(artifact_allowlist),
    }, "ready"


SANDBOX_CONFIG, SANDBOX_REASON = assess_sandbox_configuration(dict(os.environ))
SANDBOX_READY = SANDBOX_CONFIG is not None
JOB_TIMEOUT_MAX_MS = int(SANDBOX_CONFIG["maxJobTimeoutMs"]) if SANDBOX_CONFIG else min(JOB_TIMEOUT_MAX_MS, 86_400_000)
PUBLIC_URL = urllib.parse.urlparse(PUBLIC_BASE_URL)
PUBLIC_LOOPBACK_HOSTS = {"127.0.0.1", "localhost", "::1"}
try:
    _ = PUBLIC_URL.port
except ValueError as exc:
    raise SystemExit(f"ALIS_PUBLIC_BASE_URL port is invalid: {exc}") from exc
if PUBLIC_URL.username or PUBLIC_URL.password or not PUBLIC_URL.hostname or PUBLIC_URL.query or PUBLIC_URL.fragment:
    raise SystemExit("ALIS_PUBLIC_BASE_URL must be a valid URL without embedded credentials, query or fragment.")
if PUBLIC_URL.path not in {"", "/"}:
    raise SystemExit("ALIS_PUBLIC_BASE_URL must be an origin URL (scheme + host + optional port), without a path prefix.")
if PUBLIC_URL.scheme != "https" and not (PUBLIC_URL.scheme == "http" and PUBLIC_URL.hostname.lower() in PUBLIC_LOOPBACK_HOSTS):
    raise SystemExit("ALIS_PUBLIC_BASE_URL must use HTTPS, except for exact localhost development hosts.")
if HOST not in PUBLIC_LOOPBACK_HOSTS and not (PUBLIC_URL.scheme == "https"):
    raise SystemExit("Non-loopback deployments must sit behind HTTPS and set ALIS_PUBLIC_BASE_URL=https://…")
if not DATASET_ALLOWED_HOSTS:
    raise SystemExit("Set ALIS_DATASET_ALLOWED_HOSTS to the host(s) serving approved dataset artifacts.")

ROOT.mkdir(parents=True, exist_ok=True)
JOBS: dict[str, dict[str, Any]] = {}
LOCK = threading.RLock()
SLOTS = threading.BoundedSemaphore(MAX_JOBS)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while True:
            chunk = stream.read(1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def tail_text(path: Path, limit: int = 16000) -> str:
    try:
        with path.open("rb") as stream:
            stream.seek(0, os.SEEK_END)
            size = stream.tell()
            stream.seek(max(0, size - limit * 4), os.SEEK_SET)
            return redact_text(stream.read().decode("utf-8", "replace")[-limit:])
    except OSError:
        return ""


def safe_segment(value: str, default: str = "item") -> str:
    text = re.sub(r"[^A-Za-z0-9_.-]", "_", value).strip("._")[:120]
    return text or default


def save_job(job: dict[str, Any]) -> None:
    folder = ROOT / job["id"]
    folder.mkdir(parents=True, exist_ok=True)
    path = folder / "job.json"
    temp = folder / ".job.json.tmp"
    # Do not persist dataset URLs, API keys, or environment secrets.
    safe = {k: v for k, v in job.items() if k not in {"dataset_uri", "api_key", "environment", "process", "container_name"}}
    temp.write_text(json.dumps(safe, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    os.replace(temp, path)


def build_artifacts(job: dict[str, Any]) -> list[dict[str, Any]]:
    root = Path(job["output_dir"]).resolve()
    if not root.is_dir():
        return []
    files: list[dict[str, Any]] = []
    seen_names: set[str] = set()
    total = 0
    for path in sorted(root.rglob("*")):
        if not path.is_file() or path.name == "training.log":
            continue
        if path.suffix.lower() not in ARTIFACT_ALLOWLIST:
            continue
        resolved = path.resolve()
        if root not in resolved.parents:
            continue
        total += path.stat().st_size
        if total > ARTIFACT_MAX_BYTES:
            raise RuntimeError("Total model artifacts exceed ALIS_MAX_ARTIFACT_BYTES.")
        relative = path.relative_to(root).as_posix()
        # Flatten to unique, URL-safe file names. For a directory adapter the downloaded files
        # can be placed together in a model artifact folder by the client.
        served_name = safe_segment(relative.replace("/", "__"))
        if served_name in seen_names:
            suffix = hashlib.sha256(relative.encode("utf-8")).hexdigest()[:12]
            dot = served_name.rfind(".")
            served_name = f"{served_name[:dot]}-{suffix}{served_name[dot:]}" if dot > 0 else f"{served_name}-{suffix}"
        if served_name in seen_names:
            raise RuntimeError("Could not construct a unique artifact download name.")
        seen_names.add(served_name)
        files.append({
            "name": served_name,
            "url": f"{PUBLIC_BASE_URL}/v1/training/jobs/{job['id']}/artifacts/{urllib.parse.quote(served_name)}",
            "sha256": sha256_file(path),
            "sizeBytes": path.stat().st_size,
            "relativePath": relative,
        })
    return files



class AllowlistedRedirectHandler(urllib.request.HTTPRedirectHandler):
    """Validate every redirect before following it; an allowlisted URL cannot redirect to localhost/private hosts."""
    def __init__(self, allowed_hosts: set[str]) -> None:
        super().__init__()
        self.allowed_hosts = allowed_hosts

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        parsed = urllib.parse.urlparse(newurl)
        host = (parsed.hostname or "").lower()
        safe_scheme = parsed.scheme == "https" or (parsed.scheme == "http" and host in {"localhost", "127.0.0.1", "::1"})
        if parsed.username or parsed.password or not safe_scheme or host not in self.allowed_hosts:
            raise urllib.error.HTTPError(req.full_url, code, "redirect target is not on the approved dataset host allowlist", headers, fp)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def download_dataset(uri: str, target: Path, expected_sha256: str) -> int:
    parsed = urllib.parse.urlparse(uri)
    host = (parsed.hostname or "").lower()
    if parsed.username or parsed.password:
        raise ValueError("Dataset URI must not embed credentials.")
    if parsed.scheme != "https" and not (parsed.scheme == "http" and host in {"localhost", "127.0.0.1", "::1"}):
        raise ValueError("Dataset artifact URI must use HTTPS (except loopback development).")
    if host not in DATASET_ALLOWED_HOSTS:
        raise ValueError(f"Dataset host '{host}' is not in ALIS_DATASET_ALLOWED_HOSTS.")
    req = urllib.request.Request(uri, headers={"User-Agent": "Vibes-ALIS-Training-Service/5.2"})
    opener = urllib.request.build_opener(AllowlistedRedirectHandler(DATASET_ALLOWED_HOSTS))
    digest = hashlib.sha256()
    total = 0
    with opener.open(req, timeout=60) as response, target.open("wb") as output:
        final_uri = urllib.parse.urlparse(response.geturl())
        final_host = (final_uri.hostname or "").lower()
        if final_host not in DATASET_ALLOWED_HOSTS or final_uri.username or final_uri.password:
            raise ValueError("Dataset redirect ended at a host outside the allowlist.")
        while True:
            chunk = response.read(1024 * 1024)
            if not chunk:
                break
            total += len(chunk)
            if total > DATASET_MAX_BYTES:
                raise ValueError("Dataset artifact exceeds ALIS_MAX_DATASET_BYTES.")
            digest.update(chunk)
            output.write(chunk)
    if not hmac.compare_digest(digest.hexdigest(), expected_sha256.lower()):
        target.unlink(missing_ok=True)
        raise ValueError("Downloaded dataset SHA-256 does not match the approved snapshot.")
    return total


def validate_jsonl_dataset(path: Path, expected_count: int, expected_approvers: set[str]) -> None:
    count = 0
    hashes: set[str] = set()
    seen_approvers: set[str] = set()
    with path.open("r", encoding="utf-8") as stream:
        for line_number, line in enumerate(stream, start=1):
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError(f"Dataset JSONL row {line_number} is invalid: {exc}") from exc
            for key in ("id", "task_type", "input", "target", "source_correction_id", "content_hash", "approved_by"):
                if not isinstance(row.get(key), str) or not row[key].strip():
                    raise ValueError(f"Dataset row {line_number} is missing required field '{key}'.")
            content_hash = row["content_hash"]
            if not re.fullmatch(r"[a-fA-F0-9]{64}", content_hash):
                raise ValueError(f"Dataset row {line_number} has a malformed content_hash.")
            normalized = "\0".join(re.sub(r"\s+", " ", row[key].lower()).strip() for key in ("task_type", "input", "target"))
            calculated = hashlib.sha256(normalized.encode("utf-8")).hexdigest()
            if not hmac.compare_digest(calculated, content_hash.lower()):
                raise ValueError(f"Dataset row {line_number} content_hash does not match normalized content.")
            if content_hash.lower() in hashes:
                raise ValueError(f"Duplicate content_hash in dataset row {line_number}.")
            hashes.add(content_hash.lower())
            seen_approvers.add(row["approved_by"].strip())
            count += 1
    if count != expected_count:
        raise ValueError(f"Dataset example count mismatch: metadata={expected_count}, actual={count}.")
    if expected_approvers != seen_approvers:
        raise ValueError("Dataset approval identities do not match the exported approval metadata.")
    if count == 0:
        raise ValueError("Approved dataset has no examples.")


def sandbox_attestation() -> dict[str, Any]:
    if not SANDBOX_READY or SANDBOX_CONFIG is None:
        return {
            "sandboxReady": False,
            "sandboxIsolation": "disabled",
            "reason": "Training is disabled until the container sandbox policy is fully configured.",
            "resourceLimits": False,
            "artifactAllowlist": False,
            "logsRedacted": True,
        }
    return {
        "sandboxReady": True,
        "sandboxIsolation": "container",
        "imageDigest": SANDBOX_CONFIG["imageDigest"],
        "dependencyLockSha256": SANDBOX_CONFIG["dependencyLockSha256"],
        "resourceLimits": True,
        "artifactAllowlist": True,
        "logsRedacted": True,
    }


def write_redacted_log_line(path: Path, line: str) -> None:
    sanitized = redact_text(line)
    encoded = sanitized.encode("utf-8", "replace")
    max_log_bytes = 2 * 1024 * 1024
    try:
        current = path.stat().st_size if path.exists() else 0
        if current >= max_log_bytes:
            return
        if current + len(encoded) > max_log_bytes:
            encoded = encoded[:max_log_bytes - current]
        with path.open("ab") as stream:
            stream.write(encoded)
    except OSError:
        # Logging is best-effort; log failures must not expose unredacted output or leak job secrets.
        return


def stop_sandbox_container(container_name: str) -> None:
    if not SANDBOX_READY or SANDBOX_CONFIG is None or not container_name:
        return
    runtime = SANDBOX_CONFIG["runtime"]
    for action in (("stop", "--time", "2", container_name), ("rm", "--force", container_name)):
        try:
            subprocess.run([runtime, *action], check=False, capture_output=True, timeout=8)
        except Exception:
            pass


def build_sandbox_command(job_id: str, data_path: Path, output_dir: Path, trainer_args: list[str]) -> tuple[list[str], str]:
    if not SANDBOX_READY or SANDBOX_CONFIG is None:
        raise RuntimeError("Training sandbox is not ready; refusing to run a host process.")
    if os.name != "posix" or not hasattr(os, "getuid") or not hasattr(os, "getgid"):
        raise RuntimeError("The isolated training runtime requires a POSIX worker host.")
    container_name = "vibes-training-" + safe_segment(job_id)[:48]
    dataset_host = str(data_path.resolve())
    output_host = str(output_dir.resolve())
    if "," in dataset_host or "," in output_host:
        raise RuntimeError("Sandbox bind-mount paths contain unsupported characters.")
    command = [
        SANDBOX_CONFIG["runtime"], "run", "--rm", "--name", container_name,
        "--network=none", "--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges",
        f"--pids-limit={SANDBOX_CONFIG['pidsLimit']}",
        f"--memory={SANDBOX_CONFIG['memoryLimit']}",
        f"--cpus={SANDBOX_CONFIG['cpus']}",
        "--ulimit", "nofile=1024:1024",
        "--tmpfs", f"/tmp:rw,noexec,nosuid,nodev,size={SANDBOX_CONFIG['tmpfsMb']}m",
        "--user", f"{os.getuid()}:{os.getgid()}",
        "--volume", f"{dataset_host}:/workspace/input/approved-dataset.jsonl:ro",
        "--volume", f"{output_host}:/workspace/output:rw",
        "--workdir", "/workspace",
        "--env", "HOME=/tmp", "--env", "TMPDIR=/tmp", "--env", "PYTHONUNBUFFERED=1",
        SANDBOX_CONFIG["image"],
        *trainer_args,
    ]
    return command, container_name


def terminate_container_process(process: subprocess.Popen, container_name: str, grace_seconds: float = 3.0) -> None:
    stop_sandbox_container(container_name)
    terminate_training_process(process, grace_seconds=grace_seconds)


def terminate_training_process(process: subprocess.Popen, grace_seconds: float = 3.0) -> None:
    """Stop the configured trainer and its child processes without shell-based command execution."""
    if process.poll() is not None:
        return
    try:
        if os.name == "posix":
            os.killpg(process.pid, signal.SIGTERM)
        else:
            process.terminate()
    except ProcessLookupError:
        return
    try:
        process.wait(timeout=grace_seconds)
    except subprocess.TimeoutExpired:
        try:
            if os.name == "posix":
                os.killpg(process.pid, signal.SIGKILL)
            else:
                process.kill()
        except ProcessLookupError:
            pass
        process.wait()


def run_job(job_id: str, payload: dict[str, Any]) -> None:
    with SLOTS:
        job = JOBS[job_id]
        folder = ROOT / job_id
        data_path = folder / "approved-dataset.jsonl"
        output_dir = folder / "model-output"
        output_dir.mkdir(parents=True, exist_ok=True)
        log_path = output_dir / "training.log"
        process: subprocess.Popen | None = None
        container_name = ""
        drain_thread: threading.Thread | None = None
        try:
            if not SANDBOX_READY:
                raise RuntimeError("Training sandbox is not ready; refusing to execute training.")
            with LOCK:
                if job.get("cancel_requested"):
                    job.update(status="CANCELLED", completedAt=int(time.time() * 1000), error="Cancelled before execution.")
                    save_job(job)
                    return
                job.update(status="DOWNLOADING_DATASET", updatedAt=int(time.time() * 1000))
                save_job(job)
            size = download_dataset(payload["datasetArtifactUri"], data_path, payload["datasetSha256"])
            validate_jsonl_dataset(data_path, int(payload["datasetExampleCount"]), set(payload["datasetApprovedBy"]))

            placeholders = {
                "{jobId}": job_id,
                "{jobName}": str(payload["name"]),
                "{dataset}": "/workspace/input/approved-dataset.jsonl",
                "{datasetVersionId}": str(payload["datasetVersionId"]),
                "{baseModel}": str(payload["baseModel"]),
                "{modelName}": str(payload["modelName"]),
                "{outputDir}": "/workspace/output",
                "{resumeDir}": "",
            }
            trainer_args: list[str] = []
            for raw in ARG_TEMPLATE:
                arg = raw
                for marker, value in placeholders.items():
                    arg = arg.replace(marker, value)
                trainer_args.append(arg)
            trainer_command = [TRAINER, *trainer_args]
            command, container_name = build_sandbox_command(job_id, data_path, output_dir, trainer_command)

            with LOCK:
                if job.get("cancel_requested"):
                    job.update(status="CANCELLED", completedAt=int(time.time() * 1000), error="Cancelled before sandbox start.")
                    save_job(job)
                    return
                job.update(status="RUNNING", startedAt=int(time.time() * 1000), updatedAt=int(time.time() * 1000),
                           datasetBytes=size, container_name=container_name)
                save_job(job)

            timeout_seconds = max(1, min(int(payload.get("timeoutMillis", 21_600_000)) / 1000, JOB_TIMEOUT_MAX_MS / 1000))
            runtime_env = {key: os.environ[key] for key in ("PATH", "HOME", "XDG_RUNTIME_DIR", "DOCKER_HOST") if key in os.environ}
            process = subprocess.Popen(command, cwd=str(folder), stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                       shell=False, close_fds=True, env=runtime_env,
                                       start_new_session=(os.name == "posix"), text=True, encoding="utf-8", errors="replace", bufsize=1)
            with LOCK:
                job["process"] = process
                cancelled_before_reader = bool(job.get("cancel_requested"))
            if cancelled_before_reader:
                terminate_container_process(process, container_name, grace_seconds=2)
                with LOCK:
                    job.update(status="CANCELLED", completedAt=int(time.time() * 1000), updatedAt=int(time.time() * 1000), error="Training was cancelled before output collection.")
                    job.pop("process", None)
                    job.pop("container_name", None)
                    save_job(job)
                return

            def drain_output() -> None:
                if process is None or process.stdout is None:
                    return
                for line in process.stdout:
                    write_redacted_log_line(log_path, line)

            drain_thread = threading.Thread(target=drain_output, name=f"alis-log-{job_id[:8]}", daemon=True)
            drain_thread.start()
            try:
                exit_code = process.wait(timeout=timeout_seconds)
            except subprocess.TimeoutExpired:
                terminate_container_process(process, container_name, grace_seconds=3)
                raise TimeoutError(f"Trainer exceeded the configured {int(timeout_seconds)} second time limit.")
            finally:
                if drain_thread is not None:
                    drain_thread.join(timeout=5)

            with LOCK:
                cancelled = bool(job.get("cancel_requested"))
            if cancelled:
                state, error = "CANCELLED", "Training was cancelled."
            elif exit_code != 0:
                state, error = "FAILED", f"Sandbox trainer exited with code {exit_code}."
            else:
                artifacts = build_artifacts(job)
                if not artifacts:
                    state, error = "FAILED", "Sandbox trainer exited 0 but produced no allow-listed artifacts."
                else:
                    state, error = "SUCCEEDED", None
            with LOCK:
                job.update(status=state, exitCode=exit_code, completedAt=int(time.time() * 1000), updatedAt=int(time.time() * 1000),
                           artifacts=(build_artifacts(job) if state == "SUCCEEDED" else []), error=error,
                           log=tail_text(log_path))
                job.pop("process", None)
                job.pop("container_name", None)
                save_job(job)
        except Exception as exc:
            if container_name:
                stop_sandbox_container(container_name)
            if process is not None and process.poll() is None:
                terminate_training_process(process, grace_seconds=2)
            if drain_thread is not None:
                drain_thread.join(timeout=2)
            with LOCK:
                job.update(status="CANCELLED" if job.get("cancel_requested") else "FAILED",
                           completedAt=int(time.time() * 1000), updatedAt=int(time.time() * 1000),
                           error=("Training cancelled." if job.get("cancel_requested") else f"{type(exc).__name__}: job execution failed; inspect redacted worker logs."),
                           log=tail_text(log_path))
                job.pop("process", None)
                job.pop("container_name", None)
                save_job(job)
        finally:
            data_path.unlink(missing_ok=True)


class Handler(BaseHTTPRequestHandler):
    server_version = "ALIS-Training-Service/5.2"

    def version_string(self) -> str:
        # Do not expose the embedded Python interpreter version in HTTP Server headers.
        return self.server_version

    def log_message(self, fmt: str, *args: Any) -> None:
        # Avoid writing authorization headers, signed dataset URLs, or request body to server logs.
        print(f"{self.address_string()} - {self.command or 'REQUEST'} [path and query redacted]")

    def send_json(self, status: int, payload: dict[str, Any]) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def authenticated(self) -> bool:
        auth = self.headers.get("Authorization", "")
        supplied = auth[7:] if auth.startswith("Bearer ") else ""
        if hmac.compare_digest(supplied, API_KEY):
            return True
        self.send_json(401, {"error": "unauthorized"})
        return False

    def read_body(self) -> dict[str, Any]:
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            raise ValueError("Invalid Content-Length.")
        if length <= 0 or length > 1_000_000:
            raise ValueError("Request body is empty or exceeds 1 MB.")
        raw = self.rfile.read(length)
        value = json.loads(raw.decode("utf-8"))
        if not isinstance(value, dict):
            raise ValueError("Request body must be a JSON object.")
        return value

    def do_GET(self) -> None:  # noqa: N802
        if not self.authenticated():
            return
        parts = urllib.parse.urlparse(self.path).path.strip("/").split("/")
        if parts == ["v1", "training", "health"]:
            status = 200 if SANDBOX_READY else 503
            self.send_json(status, sandbox_attestation())
            return
        if not SANDBOX_READY and len(parts) >= 3 and parts[:3] == ["v1", "training", "jobs"]:
            self.send_json(503, {"error": "training_sandbox_unavailable"})
            return
        if len(parts) == 4 and parts[:3] == ["v1", "training", "jobs"]:
            with LOCK:
                job = JOBS.get(parts[3])
                if not job:
                    self.send_json(404, {"error": "job_not_found"})
                    return
                result = {k: v for k, v in job.items() if k not in {"process", "dataset_uri", "cancel_requested", "container_name"}}
            if result.get("status") == "SUCCEEDED":
                result["artifacts"] = build_artifacts(job)
            self.send_json(200, result)
            return
        if len(parts) == 6 and parts[:3] == ["v1", "training", "jobs"] and parts[4] == "artifacts":
            job_id, requested_name = parts[3], urllib.parse.unquote(parts[5])
            with LOCK:
                job = JOBS.get(job_id)
            if not job or job.get("status") != "SUCCEEDED":
                self.send_json(404, {"error": "artifact_not_found"})
                return
            artifacts = build_artifacts(job)
            metadata = next((a for a in artifacts if a["name"] == requested_name), None)
            if metadata is None:
                self.send_json(404, {"error": "artifact_not_found"})
                return
            source = Path(job["output_dir"]).resolve()
            found = (source / metadata["relativePath"]).resolve()
            if source not in found.parents or not found.is_file():
                self.send_json(404, {"error": "artifact_not_found"})
                return
            size = found.stat().st_size
            if size > ARTIFACT_MAX_BYTES:
                self.send_json(413, {"error": "artifact_too_large"})
                return
            if size != int(metadata["sizeBytes"]) or not hmac.compare_digest(sha256_file(found), metadata["sha256"]):
                self.send_json(409, {"error": "artifact_integrity_mismatch"})
                return
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(size))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            with found.open("rb") as stream:
                shutil.copyfileobj(stream, self.wfile)
            return
        self.send_json(404, {"error": "not_found"})

    def do_POST(self) -> None:  # noqa: N802
        if not self.authenticated():
            return
        if urllib.parse.urlparse(self.path).path != "/v1/training/jobs":
            self.send_json(404, {"error": "not_found"})
            return
        if not SANDBOX_READY:
            self.send_json(503, {"error": "training_sandbox_unavailable", "message": "Training is disabled until the pinned isolated runtime, dependency lock, resource limits and artifact policy are verified."})
            return
        try:
            payload = self.read_body()
            required = ("name", "datasetVersionId", "datasetArtifactUri", "datasetSha256", "datasetExampleCount", "datasetApprovedBy", "baseModel", "modelName")
            if any(key not in payload for key in required):
                raise ValueError("Required job metadata is missing.")
            if not re.fullmatch(r"[a-fA-F0-9]{64}", str(payload["datasetSha256"])):
                raise ValueError("datasetSha256 must be a 64-character SHA-256.")
            if int(payload["datasetExampleCount"]) <= 0 or not isinstance(payload["datasetApprovedBy"], list) or not payload["datasetApprovedBy"]:
                raise ValueError("An approved dataset must contain examples and named approvers.")
            if any(not isinstance(name, str) or not name.strip() for name in payload["datasetApprovedBy"]):
                raise ValueError("Every dataset approver must have a non-empty human-readable identity.")
            normalized_approvers = [name.strip() for name in payload["datasetApprovedBy"]]
            if len(normalized_approvers) != len(set(normalized_approvers)):
                raise ValueError("Dataset approver identities must be unique after trimming whitespace.")
            if not str(payload["baseModel"]).strip() or not str(payload["modelName"]).strip():
                raise ValueError("baseModel and modelName are required.")
            try:
                timeout = int(payload.get("timeoutMillis", 21_600_000))
            except (TypeError, ValueError):
                raise ValueError("timeoutMillis must be an integer.")
            if timeout < 1000 or timeout > JOB_TIMEOUT_MAX_MS:
                raise ValueError("timeoutMillis is outside the service limit.")
            with LOCK:
                active_states = {"QUEUED", "DOWNLOADING_DATASET", "RUNNING"}
                pending_count = sum(1 for job in JOBS.values() if job.get("status") in active_states)
                if pending_count >= MAX_PENDING_JOBS:
                    self.send_json(429, {"error": "training_queue_full", "maxPendingJobs": MAX_PENDING_JOBS})
                    return
                job_id = str(uuid.uuid4())
                folder = ROOT / job_id
                output_dir = folder / "model-output"
                folder.mkdir(parents=True, exist_ok=False)
                output_dir.mkdir(parents=True, exist_ok=True)
                job = {"id": job_id, "clientJobId": str(payload.get("jobId", "")), "name": str(payload["name"])[:200],
                       "datasetVersionId": str(payload["datasetVersionId"]), "modelName": str(payload["modelName"]),
                       "baseModel": str(payload["baseModel"]), "status": "QUEUED", "createdAt": int(time.time() * 1000),
                       "updatedAt": int(time.time() * 1000), "output_dir": str(output_dir), "dataset_uri": str(payload["datasetArtifactUri"]),
                       "timeoutMillis": timeout, "cancel_requested": False, "artifacts": [], "log": ""}
                JOBS[job_id] = job
                save_job(job)
            threading.Thread(target=run_job, args=(job_id, payload), name=f"alis-training-{job_id[:8]}", daemon=True).start()
            self.send_json(202, {"id": job_id, "status": "QUEUED"})
        except (ValueError, TypeError, json.JSONDecodeError) as exc:
            self.send_json(400, {"error": redact_text(str(exc))[:1000]})
        except Exception as exc:
            self.send_json(500, {"error": redact_text(f"{type(exc).__name__}: {str(exc)}")[:1000]})

    def do_DELETE(self) -> None:  # noqa: N802
        if not self.authenticated():
            return
        if not SANDBOX_READY:
            self.send_json(503, {"error": "training_sandbox_unavailable"})
            return
        parts = urllib.parse.urlparse(self.path).path.strip("/").split("/")
        if len(parts) != 4 or parts[:3] != ["v1", "training", "jobs"]:
            self.send_json(404, {"error": "not_found"})
            return
        with LOCK:
            job = JOBS.get(parts[3])
            if not job:
                self.send_json(404, {"error": "job_not_found"})
                return
            if job.get("status") in {"SUCCEEDED", "FAILED", "CANCELLED"}:
                self.send_json(409, {"error": "job_already_finished"})
                return
            job["cancel_requested"] = True
            process = job.get("process")
            container_name = str(job.get("container_name", ""))
            job["updatedAt"] = int(time.time() * 1000)
            save_job(job)
        if container_name:
            stop_sandbox_container(container_name)
        if process is not None:
            terminate_training_process(process, grace_seconds=3)
        self.send_json(202, {"id": parts[3], "status": "CANCEL_REQUESTED"})


def load_persisted_jobs() -> None:
    for path in ROOT.glob("*/job.json"):
        try:
            job = json.loads(path.read_text(encoding="utf-8"))
            if not isinstance(job, dict) or not job.get("id"):
                continue
            job["output_dir"] = str((path.parent / "model-output").resolve())
            # A worker process cannot be resumed across service restarts. Mark incomplete jobs honestly.
            if job.get("status") not in {"SUCCEEDED", "FAILED", "CANCELLED"}:
                job.update(status="FAILED", completedAt=int(time.time() * 1000), error="Worker restarted while this job was in progress.")
                save_job(job)
            JOBS[job["id"]] = job
        except Exception as exc:
            print(f"Skipping unreadable job metadata {path.name}: {exc}")


def main() -> None:
    load_persisted_jobs()
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    print(f"ALIS training service listening on {HOST}:{PORT}; jobs root: {ROOT}")
    print("Training sandbox status: ready" if SANDBOX_READY else f"Training sandbox status: disabled ({SANDBOX_REASON}); no host execution fallback")
    try:
        server.serve_forever(poll_interval=0.5)
    except KeyboardInterrupt:
        print("Shutting down ALIS training service…")
    finally:
        server.shutdown()
        server.server_close()


if __name__ == "__main__":
    main()

# ALIS training worker sandbox deployment

The reference worker deliberately runs with training disabled unless every sandbox control passes. It never falls back to `subprocess.Popen(TRAINER, ...)` on the host.

## 1. Create a verified dependency lock

Start from `requirements-training.txt` as a human-reviewed input list. Resolve packages for the exact Python/platform target used by the image, with all transitive dependencies and hashes. For example, use a pinned `pip-tools` environment and run `pip-compile --generate-hashes`; flatten each selected package into one line of the accepted format:

```text
torch==2.5.1 --hash=sha256:<64-hex-wheel-hash>
transformers==4.46.3 --hash=sha256:<64-hex-wheel-hash>
```

A real lock contains real wheel hashes and all transitive packages. Do not copy these illustrative package versions or placeholder hashes. Save the complete lock as `requirements-training.lock`, review it, and compute:

```bash
sha256sum requirements-training.lock
```

The worker checks that the lock digest matches `ALIS_DEPENDENCY_LOCK_SHA256`, and that every non-comment line is an exact `==` pin with at least one SHA-256 hash.

## 2. Build an immutable sandbox image

Choose a Python base image by verified digest (not a floating tag), then build:

```bash
docker build -f Dockerfile.sandbox \
  --build-arg PYTHON_BASE_IMAGE='python:3.11-slim@sha256:<verified-base-image-digest>' \
  --build-arg DEPENDENCY_LOCK_SHA256="$(sha256sum requirements-training.lock | cut -d' ' -f1)" \
  -t vibes-alis-training:reviewed .
```

The Dockerfile installs dependencies with `pip --require-hashes` and stamps the dependency lock digest into `org.vibes.dependency-lock-sha256`. Scan and test the image, publish it to a trusted registry, and configure the worker with the immutable registry digest returned by the registry:

```text
registry.example.com/vibes/alis-training@sha256:<64-hex-image-digest>
```

The image must contain the trainer scripts and the executable path configured by `ALIS_TRAINER_EXECUTABLE`.

## 3. Configure the worker

Required environment variables on the worker host:

```bash
ALIS_SANDBOX_RUNTIME=docker
ALIS_SANDBOX_IMAGE=registry.example.com/vibes/alis-training@sha256:<64-hex-image-digest>
ALIS_DEPENDENCY_LOCK_FILE=/opt/vibes/requirements-training.lock
ALIS_DEPENDENCY_LOCK_SHA256=<sha256-of-that-lock-file>
ALIS_SANDBOX_MEMORY_LIMIT=8g
ALIS_SANDBOX_CPUS=2
ALIS_SANDBOX_PIDS_LIMIT=512
ALIS_SANDBOX_TMPFS_MB=512
ALIS_ARTIFACT_SUFFIX_ALLOWLIST=.safetensors,.gguf,.pt
ALIS_TRAINER_EXECUTABLE=/usr/local/bin/python
ALIS_TRAINER_ARGUMENTS_JSON=["/opt/vibes/alis_tiny_sft_trainer.py","--dataset","{dataset}","--output-dir","{outputDir}","--device","cpu"]
```

Use a private bind address, a random worker API key of at least 32 characters, HTTPS at the trusted reverse proxy, and a dedicated persistent data volume. The Docker/Podman socket is highly privileged: run the worker under a dedicated service account, restrict access to that socket, and do not expose the worker port publicly.

For each job, the worker creates a container with networking disabled, read-only root filesystem, all Linux capabilities dropped, `no-new-privileges`, a non-root UID, bounded memory/CPU/PIDs/tmpfs, and a wall-clock timeout. Only the approved dataset (read-only) and output directory (writable) are mounted. The container receives a minimal environment; host provider credentials are not passed to the trainer.

## 4. Readiness and gateway enablement

Authenticated `GET /v1/training/health` returns HTTP 200 and a positive sandbox attestation only after the runtime can inspect the local pinned image and its dependency-lock label matches the verified lock file. Any missing or mismatched control returns HTTP 503. The Quantum gateway checks this attestation before every job create/get/cancel request.

Keep `VIBES_TRAINING_ENABLED=false` until the worker health route is 200 and the sandbox has passed a reviewed smoke job. Then configure the server's worker URL, worker API secret and HTTPS dataset artifact host allow-list. Training remains unavailable if health later returns 503.

## 5. Logs and artifacts

Worker stdout/stderr is streamed through a redaction filter before it is written to `training.log`; common bearer credentials, labelled token/key/password assignments, OpenAI/Google/AWS key prefixes, PEM private-key blocks and signed URL query strings are removed. Logs are capped at 2 MiB. Only suffixes explicitly listed in `ALIS_ARTIFACT_SUFFIX_ALLOWLIST` are returned as artifacts, and each artifact is path-contained and SHA-256 checked before serving.

The allow-list is a policy boundary, not a statement that every format is safe to load. Downstream model loaders must still treat weights, tokenizer files and metadata as untrusted input.

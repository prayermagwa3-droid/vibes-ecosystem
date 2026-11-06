"""Unit tests for the reference worker. These tests use tiny labelled test artifacts, not model weights."""
from __future__ import annotations

import hashlib
import importlib.util
import json
import os
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).resolve().parents[1] / "alis_training_service.py"
_TEST_ROOT = tempfile.TemporaryDirectory(prefix="alis-worker-tests-")
os.environ["ALIS_API_KEY"] = "local-test-api-key-with-more-than-32-characters"
os.environ["ALIS_TRAINER_EXECUTABLE"] = sys.executable
os.environ["ALIS_TRAINER_ARGUMENTS_JSON"] = json.dumps(["-c", "pass"])
os.environ["ALIS_DATASET_ALLOWED_HOSTS"] = "127.0.0.1"
os.environ["ALIS_TRAINING_ROOT"] = str(Path(_TEST_ROOT.name) / "service-root")
os.environ["ALIS_BIND_HOST"] = "127.0.0.1"
os.environ["ALIS_PUBLIC_BASE_URL"] = "http://127.0.0.1:8765"

spec = importlib.util.spec_from_file_location("alis_training_service_under_test", MODULE_PATH)
assert spec and spec.loader
worker = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = worker
spec.loader.exec_module(worker)


class TrainingServiceTests(unittest.TestCase):
    def make_dataset(self, path: Path, input_text: str = "What is 2+2?", target: str = "4") -> dict[str, str]:
        task_type = "coding"
        normalized = "\0".join(re.sub(r"\s+", " ", value.lower()).strip()
                                  for value in (task_type, input_text, target))
        digest = hashlib.sha256(normalized.encode("utf-8")).hexdigest()
        row = {
            "id": "example-1", "task_type": task_type, "input": input_text, "target": target,
            "source_correction_id": "correction-1", "content_hash": digest, "approved_by": "owner",
        }
        path.write_text(json.dumps(row) + "\n", encoding="utf-8")
        return row

    def test_valid_approved_dataset_passes_hash_count_and_approver_checks(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            dataset = Path(directory) / "approved.jsonl"
            self.make_dataset(dataset)
            worker.validate_jsonl_dataset(dataset, 1, {"owner"})

    def test_dataset_hash_tampering_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            dataset = Path(directory) / "approved.jsonl"
            row = self.make_dataset(dataset)
            row["target"] = "five"
            dataset.write_text(json.dumps(row) + "\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "content_hash"):
                worker.validate_jsonl_dataset(dataset, 1, {"owner"})

    def test_duplicate_approvals_or_content_rows_do_not_fake_more_data(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            dataset = Path(directory) / "approved.jsonl"
            row = self.make_dataset(dataset)
            dataset.write_text("\n".join((json.dumps(row), json.dumps(row))) + "\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "Duplicate content_hash"):
                worker.validate_jsonl_dataset(dataset, 2, {"owner"})

    def test_artifact_paths_are_unique_even_when_relative_names_sanitize_to_same_name(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "model-output"
            (output / "a?b").mkdir(parents=True)
            (output / "a_b").mkdir(parents=True)
            (output / "a?b" / "model.safetensors").write_bytes(b"smoke artifact one")
            (output / "a_b" / "model.safetensors").write_bytes(b"smoke artifact two")
            (output / "metadata.json").write_text("{\"not\":\"allow-listed\"}", encoding="utf-8")
            prior_allowlist = worker.ARTIFACT_ALLOWLIST
            worker.ARTIFACT_ALLOWLIST = {".safetensors"}
            try:
                entries = worker.build_artifacts({"id": "job-test", "output_dir": str(output)})
            finally:
                worker.ARTIFACT_ALLOWLIST = prior_allowlist
            self.assertEqual(2, len(entries))
            self.assertTrue(all(entry["name"].endswith(".safetensors") for entry in entries))
            self.assertEqual(2, len({entry["name"] for entry in entries}))
            self.assertTrue(all(len(entry["sha256"]) == 64 for entry in entries))

    def test_training_worker_is_fail_closed_without_complete_sandbox_configuration(self) -> None:
        config, reason = worker.assess_sandbox_configuration({})
        self.assertIsNone(config)
        self.assertTrue(reason)
        self.assertFalse(worker.sandbox_attestation()["sandboxReady"])

    def test_dependency_lock_requires_exact_versions_and_sha256_hashes(self) -> None:
        good = "torch==2.5.1 --hash=sha256:" + ("a" * 64) + "\ntransformers==4.46.3 --hash=sha256:" + ("b" * 64)
        self.assertTrue(worker.validate_dependency_lock_text(good))
        self.assertFalse(worker.validate_dependency_lock_text("torch>=2.2,<3"))
        self.assertFalse(worker.validate_dependency_lock_text("https://example.invalid/torch.whl"))
        self.assertFalse(worker.validate_dependency_lock_text("torch==2.5.1"))

    def test_sandbox_policy_requires_digest_image_matching_lock_and_all_resource_controls(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            lock_path = Path(directory) / "requirements.lock"
            lock_text = "torch==2.5.1 --hash=sha256:" + ("c" * 64) + "\n"
            lock_path.write_text(lock_text, encoding="utf-8")
            lock_sha = hashlib.sha256(lock_path.read_bytes()).hexdigest()
            image_digest = "d" * 64
            env = {
                "ALIS_SANDBOX_RUNTIME": "/bin/echo",
                "ALIS_SANDBOX_IMAGE": f"registry.example/vibes/train@sha256:{image_digest}",
                "ALIS_DEPENDENCY_LOCK_FILE": str(lock_path),
                "ALIS_DEPENDENCY_LOCK_SHA256": lock_sha,
                "ALIS_SANDBOX_MEMORY_LIMIT": "8g",
                "ALIS_SANDBOX_CPUS": "2",
                "ALIS_SANDBOX_PIDS_LIMIT": "512",
                "ALIS_SANDBOX_TMPFS_MB": "512",
                "ALIS_ARTIFACT_SUFFIX_ALLOWLIST": ".safetensors,.gguf",
                "ALIS_MAX_JOB_TIMEOUT_MS": "3600000",
            }
            config, reason = worker.assess_sandbox_configuration(env, inspect_image=lambda _r, _i, _t: lock_sha)
            self.assertEqual("ready", reason)
            self.assertIsNotNone(config)
            self.assertEqual(image_digest, config["imageDigest"])
            self.assertEqual(lock_sha, config["dependencyLockSha256"])
            bad_config, _ = worker.assess_sandbox_configuration(env, inspect_image=lambda _r, _i, _t: "0" * 64)
            self.assertIsNone(bad_config)

    def test_worker_redacts_bearer_provider_cloud_and_private_keys_and_signed_urls(self) -> None:
        openai_key = "sk-" + "x" * 40
        google_key = "AIza" + "G" * 36
        aws_key = "AKIA" + "A" * 16
        private_key = "-----BEGIN PRIVATE KEY-----\nprivate-material\n-----END PRIVATE KEY-----"
        value = (
            "Authorization: Bearer abc.def.ghi api_key=supersecret "
            f"{openai_key} {google_key} {aws_key} {private_key} "
            "https://files.invalid/model?token=signed-value"
        )
        redacted = worker.redact_text(value)
        for secret in ("abc.def.ghi", "supersecret", openai_key, google_key, aws_key, "private-material", "signed-value"):
            self.assertNotIn(secret, redacted)

    def test_host_execution_fallback_is_refused_when_sandbox_is_not_ready(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaisesRegex(RuntimeError, "refusing to run a host process"):
                worker.build_sandbox_command("job-test", root / "dataset.jsonl", root / "output", ["/usr/bin/python"])

    def test_cancel_terminates_a_real_test_process(self) -> None:
        process = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)"], start_new_session=(os.name == "posix"))
        try:
            worker.terminate_training_process(process, grace_seconds=1.0)
            self.assertIsNotNone(process.poll())
        finally:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=2)


if __name__ == "__main__":
    unittest.main()

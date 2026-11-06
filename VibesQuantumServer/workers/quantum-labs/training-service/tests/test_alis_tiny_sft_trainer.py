import hashlib
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "alis_tiny_sft_trainer.py"


def content_hash(task, prompt, target):
    normalized = "\0".join(" ".join(value.lower().split()) for value in (task, prompt, target))
    return hashlib.sha256(normalized.encode()).hexdigest()


class TinySftTrainerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.dataset = self.root / "approved.jsonl"
        rows = []
        for i in range(9):
            task = "simple-facts"
            prompt = f"What is code {i}?"
            target = f"Code {i} maps to value {i + 1}."
            rows.append({"id": f"example-{i}", "task_type": task, "input": prompt, "target": target,
                         "content_hash": content_hash(task, prompt, target), "approved_by": "test-reviewer"})
        self.dataset.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")
        self.output = self.root / "out"

    def tearDown(self): self.temp.cleanup()

    def run_trainer(self, *extra):
        env = dict(os.environ)
        env["OMP_NUM_THREADS"] = "1"
        return subprocess.run([sys.executable, str(SCRIPT), "--dataset", str(self.dataset), "--output-dir", str(self.output),
                               "--epochs", "2", "--batch-size", "2", "--hidden-size", "16", "--max-sequence-length", "96",
                               "--device", "cpu", *extra], capture_output=True, text=True, timeout=120, env=env)

    def test_real_training_changes_parameters_and_saves_restorable_checkpoint(self):
        first = self.run_trainer()
        self.assertEqual(0, first.returncode, first.stderr)
        metrics = json.loads((self.output / "metrics.json").read_text())
        self.assertTrue(metrics["parameters_changed"])
        self.assertNotEqual(metrics["parameter_fingerprint_before"], metrics["parameter_fingerprint_after"])
        self.assertTrue((self.output / "checkpoint-last.pt").is_file())
        self.assertTrue((self.output / "model-state.pt").is_file())
        self.assertTrue((self.output / "metrics.json").is_file())
        resumed = self.run_trainer("--resume-dir", str(self.output))
        self.assertEqual(0, resumed.returncode, resumed.stderr)
        resumed_metrics = json.loads((self.output / "metrics.json").read_text())
        self.assertEqual(2, resumed_metrics["resumed_from_epoch"])
        self.assertTrue(resumed_metrics["parameters_changed"])
        self.assertGreaterEqual(resumed_metrics["heldout_test_loss"], 0.0)

    def test_mismatched_dataset_content_hash_is_rejected(self):
        row = json.loads(self.dataset.read_text().splitlines()[0])
        row["target"] = "tampered target"
        self.dataset.write_text(json.dumps(row) + "\n", encoding="utf-8")
        result = self.run_trainer()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("content hash", (result.stdout + result.stderr).lower())


if __name__ == "__main__": unittest.main()

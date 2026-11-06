import json
import tempfile
import unittest
from pathlib import Path
from alis_evaluation_lab import compare, normalize, read_holdout, summarize, token_f1, validate_base_url

class EvaluationLabTests(unittest.TestCase):
    def test_lexical_metrics(self):
        self.assertEqual(normalize("Hello, World!"), normalize("hello world"))
        self.assertEqual(1.0, token_f1("the cat", "cat the"))
        self.assertEqual(0.0, token_f1("apple", "orange"))

    def test_rejects_remote_cleartext_endpoint(self):
        self.assertEqual("http://127.0.0.1:11434", validate_base_url("http://127.0.0.1:11434"))
        with self.assertRaises(ValueError): validate_base_url("http://example.com")
        with self.assertRaises(ValueError): validate_base_url("http://user:pass@127.0.0.1")

    def test_holdout_leakage_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            holdout = root / "holdout.jsonl"; training = root / "training.jsonl"
            row = {"id":"1", "task_type":"math", "input":"1+1?", "expected":"2"}
            holdout.write_text(json.dumps(row) + "\n")
            training.write_text(json.dumps({"task_type":"math", "input":"1+1?", "target":"2"}) + "\n")
            with self.assertRaises(ValueError): read_holdout(holdout, training)

    def test_candidate_regression_fails_gate(self):
        baseline = {"token_f1": .8, "empty_or_failed_rate": 0.0, "by_task": {"math":{"token_f1":.8}}}
        candidate = {"token_f1": .82, "empty_or_failed_rate": 0.0, "by_task": {"math":{"token_f1":.7}}}
        result = compare(baseline, candidate, .01, .05)
        self.assertFalse(result["passed_configured_gates"])
        self.assertEqual("REJECT_CANDIDATE", result["decision"])

    def test_length_guard_rejects_verbosity_inflation(self):
        baseline = {"token_f1": .5, "empty_or_failed_rate": 0.0, "by_task": {}, "mean_length_ratio": 1.0}
        candidate = {"token_f1": .7, "empty_or_failed_rate": 0.0, "by_task": {}, "mean_length_ratio": 4.0}
        result = compare(baseline, candidate, .01, .05, max_length_ratio=3.0)
        self.assertFalse(result["passed_configured_gates"])
        self.assertFalse(result["length_guard_passed"])

if __name__ == "__main__": unittest.main()

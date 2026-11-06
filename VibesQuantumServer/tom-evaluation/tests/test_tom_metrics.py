import importlib.util
from pathlib import Path
import unittest

MODULE_PATH = Path(__file__).resolve().parents[1] / "tom_metrics.py"
spec = importlib.util.spec_from_file_location("tom_metrics", MODULE_PATH)
module = importlib.util.module_from_spec(spec)
assert spec and spec.loader
spec.loader.exec_module(module)


class TheoryOfMindMetricTests(unittest.TestCase):
    def test_basic_classification_metrics(self):
        rows = [
            {"expected_emotion": "uncertain", "predicted_emotion": "uncertain", "expected_intent": "question", "predicted_intent": "question"},
            {"expected_emotion": "happy", "predicted_emotion": "uncertain", "expected_intent": "greeting", "predicted_intent": "greeting"},
        ]
        report = module.evaluate(rows)
        self.assertEqual(report["emotion_accuracy"], 0.5)
        self.assertEqual(report["intent_accuracy"], 1.0)
        self.assertIsNotNone(report["emotion_macro_f1"])

    def test_evidence_quotes_must_be_grounded_in_source_text(self):
        rows = [{
            "source_text": "I may need some time",
            "predicted_evidence_quotes": ["I may need some time", "invented sentence"],
        }]
        self.assertEqual(module.evaluate(rows)["evidence_quote_grounding_rate"], 0.5)

    def test_slice_metrics_ignore_small_groups(self):
        rows = [
            {"language": "en", "expected_emotion": "calm", "predicted_emotion": "calm"},
            {"language": "sn", "expected_emotion": "calm", "predicted_emotion": "angry"},
        ]
        report = module.evaluate(rows, minimum_slice_size=2)
        self.assertEqual(report["language_slice_accuracy"], {})
        self.assertIsNone(report["observed_slice_accuracy_gap"])

    def test_abstention_rate(self):
        rows = [{"abstained": True}, {"abstained": False}]
        self.assertEqual(module.evaluate(rows)["abstention_rate"], 0.5)


if __name__ == "__main__":
    unittest.main()

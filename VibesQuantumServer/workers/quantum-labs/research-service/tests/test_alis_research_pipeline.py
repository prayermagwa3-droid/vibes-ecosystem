import tempfile
import unittest
from pathlib import Path

from alis_research_pipeline import ResearchDocument, ResearchPipeline, ResearchPolicy, extract_text, validate_url


class ResearchPipelineTests(unittest.TestCase):
    def setUp(self):
        self.policy = ResearchPolicy(
            allowed_domains=("example.org",), allowed_licenses=("CC-BY-4.0",),
            min_text_characters=20, require_robots_txt=True,
        )
        self.temp = tempfile.TemporaryDirectory()
        self.pipeline = ResearchPipeline(self.policy, Path(self.temp.name))

    def tearDown(self):
        self.temp.cleanup()

    def test_only_allowlisted_https_host_is_accepted(self):
        self.assertEqual("docs.example.org", validate_url("https://docs.example.org/guide#part", self.policy).hostname)
        with self.assertRaises(ValueError): validate_url("http://example.org/", self.policy)
        with self.assertRaises(ValueError): validate_url("https://example.org.evil.test/", self.policy)
        with self.assertRaises(ValueError): validate_url("https://user:pass@example.org/", self.policy)

    def test_private_ip_is_rejected(self):
        with self.assertRaises(ValueError): validate_url("https://127.0.0.1/", self.policy)
        with self.assertRaises(ValueError): validate_url("https://192.168.1.10/", self.policy)

    def test_html_extraction_drops_script_and_navigation(self):
        html = b"<html><head><title>Useful Guide</title><script>secret()</script></head><body><nav>Menu</nav><main><h1>Heading</h1><p>Verified content.</p></main></body></html>"
        title, text = extract_text(html, "text/html; charset=utf-8")
        self.assertEqual("Useful Guide", title)
        self.assertIn("Verified content", text)
        self.assertNotIn("secret", text)
        self.assertNotIn("Menu", text)

    def test_training_approval_requires_explicit_license_permission(self):
        document = ResearchDocument(
            id="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", canonical_url="https://example.org/guide", title="Guide", text="Long enough verified text",
            sha256="a" * 64, license_name="CC-BY-4.0", attribution="Example Org", collected_at=1.0,
            byte_count=100, quality_score=0.9, quality_flags=(), training_use_allowed=False,
        )
        self.pipeline._persist(document)
        with self.assertRaises(ValueError): self.pipeline.approve_for_training("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "reviewer")

    def test_training_approval_is_separate_from_collection(self):
        document = ResearchDocument(
            id="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", canonical_url="https://example.org/guide2", title="Guide", text="Long enough verified text",
            sha256="b" * 64, license_name="CC-BY-4.0", attribution="Example Org", collected_at=1.0,
            byte_count=100, quality_score=0.9, quality_flags=(), training_use_allowed=True,
        )
        self.pipeline._persist(document)
        approved = self.pipeline.approve_for_training("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "human-reviewer", "license checked")
        self.assertTrue(approved.training_eligible)
        self.assertEqual("APPROVED", approved.approval_status)
        self.assertEqual("human-reviewer", approved.approved_by)

    def test_cleaning_reassesses_flagged_document_before_approval(self):
        document = ResearchDocument(
            id="cccccccccccccccccccccccccccccccc", canonical_url="https://example.org/guide3", title="Guide", text="repeat repeat repeat",
            sha256="c" * 64, license_name="CC-BY-4.0", attribution="Example Org", collected_at=1.0,
            byte_count=20, quality_score=0.1, quality_flags=("TOO_SHORT",), training_use_allowed=True,
        )
        self.pipeline._persist(document)
        with self.assertRaises(ValueError): self.pipeline.approve_for_training("cccccccccccccccccccccccccccccccc", "reviewer")
        cleaned = self.pipeline.clean_and_reassess(
            "cccccccccccccccccccccccccccccccc", "This is a cleaned and distinct educational document about model training, evaluation, data provenance, and safe deployment.",
            "editor", "Removed repeated navigation and retained the substantive material.")
        self.assertEqual((), cleaned.quality_flags)
        approved = self.pipeline.approve_for_training("cccccccccccccccccccccccccccccccc", "reviewer", "license checked")
        self.assertTrue(approved.training_eligible)
        self.assertEqual("editor", approved.cleaned_by)


if __name__ == "__main__":
    unittest.main()

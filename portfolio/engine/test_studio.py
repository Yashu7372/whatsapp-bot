import importlib.util
import json
import os
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("portfolio_studio", Path(__file__).with_name("studio.py"))
studio = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(studio)


class PortfolioStudioValidationTest(unittest.TestCase):
    def manifest(self, status="VERIFIED"):
        return {
            "article": {"id": "T-001", "number": 1, "branch": "test/branch", "title": "Test"},
            "task": {"id": "REQ", "title": "Task"},
            "source": {},
            "claims": [{"claim": "claim", "status": status, "evidence": ["test"]}],
        }

    def series(self):
        return {
            "id": "test",
            "name": "Test Series",
            "length": 70,
            "done_policy": {
                "minimum_article_words": 20,
                "maximum_article_words": 5000,
                "minimum_linkedin_words": 10,
            },
        }

    def pattern(self):
        return {
            "sections": [
                {"id": "hero", "type": "hero", "required": True},
                {"id": "incident", "type": "incident", "required": True, "min_words": 2},
                {"id": "snapshot_before", "type": "task_snapshot", "required": True},
                {"id": "snapshot_after", "type": "task_snapshot", "required": True},
                {"id": "verification", "type": "evidence", "required": True},
            ]
        }

    def model(self, status="VERIFIED"):
        sections = [
            {"id": "hero", "word_count": 5, "content": "hero"},
            {"id": "incident", "word_count": 5, "content": "generalized incident narrative"},
            {"id": "snapshot_before", "word_count": 5, "content": "before"},
            {"id": "snapshot_after", "word_count": 5, "content": "after"},
            {"id": "verification", "word_count": 5, "content": "verification"},
        ]
        return {
            "article": {"id": "T-001"},
            "incident_source_type": "generalized",
            "claims": [{"claim": "claim", "status": status, "evidence": ["test"]}],
            "sections": sections,
            "generation_errors": [],
        }

    def materialize(self, d: Path):
        (d / "article.md").write_text(("word " * 50).strip(), encoding="utf-8")
        (d / "linkedin.md").write_text(("word " * 20).strip(), encoding="utf-8")
        (d / "visual-model.json").write_text('{"schema_version": 1, "scenes": []}\n', encoding="utf-8")
        (d / "visual.html").write_text("<html><body>ok</body></html>\n", encoding="utf-8")

    def test_done_requires_every_stage(self):
        with tempfile.TemporaryDirectory() as td:
            d = Path(td)
            self.materialize(d)
            old = os.environ.pop("PORTFOLIO_STRICT_BRANCH", None)
            try:
                result = studio.validate(d, self.manifest(), self.series(), self.pattern(), self.model())
            finally:
                if old is not None:
                    os.environ["PORTFOLIO_STRICT_BRANCH"] = old
            self.assertTrue(result["done"])
            self.assertTrue(all(stage["status"] == "PASS" for stage in result["stages"].values()))

    def test_unsupported_claim_blocks_done(self):
        with tempfile.TemporaryDirectory() as td:
            d = Path(td)
            self.materialize(d)
            result = studio.validate(d, self.manifest("UNSUPPORTED"), self.series(), self.pattern(), self.model("UNSUPPORTED"))
            self.assertFalse(result["done"])
            self.assertEqual("FAIL", result["stages"]["claim_validation"]["status"])

    def test_missing_visual_blocks_done(self):
        with tempfile.TemporaryDirectory() as td:
            d = Path(td)
            self.materialize(d)
            (d / "visual.html").unlink()
            result = studio.validate(d, self.manifest(), self.series(), self.pattern(), self.model())
            self.assertFalse(result["done"])
            self.assertEqual("FAIL", result["stages"]["visual_generated"]["status"])

    def test_unlabelled_incident_blocks_done(self):
        with tempfile.TemporaryDirectory() as td:
            d = Path(td)
            self.materialize(d)
            model = self.model()
            model["incident_source_type"] = ""
            result = studio.validate(d, self.manifest(), self.series(), self.pattern(), model)
            self.assertFalse(result["done"])
            self.assertEqual("FAIL", result["stages"]["incident_grounded"]["status"])


if __name__ == "__main__":
    unittest.main()

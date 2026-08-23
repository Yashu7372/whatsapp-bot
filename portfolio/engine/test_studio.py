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
            "article": {"id":"T-001","branch":"test/branch"},
            "task": {"id":"REQ","title":"Task"},
            "story": {},
            "claims": [{"claim":"claim","status":status,"evidence":["test"]}],
        }

    def materialize_required_files(self, d: Path):
        for name in ("source-pack.json","evidence.json","story.json","visual-spec.json"):
            (d/name).write_text("{\"ok\":true}\n", encoding="utf-8")
        (d/"visual.svg").write_text("<svg><text>ok</text></svg>\n", encoding="utf-8")
        (d/"article.md").write_text(("word " * 520).strip(), encoding="utf-8")
        (d/"linkedin.md").write_text(("word " * 70).strip(), encoding="utf-8")

    def test_done_requires_every_flag(self):
        with tempfile.TemporaryDirectory() as td:
            d=Path(td); self.materialize_required_files(d)
            old=os.environ.pop("PORTFOLIO_STRICT_BRANCH", None)
            try:
                result=studio.validate(d,self.manifest())
            finally:
                if old is not None: os.environ["PORTFOLIO_STRICT_BRANCH"]=old
            self.assertTrue(result["done"])
            self.assertTrue(all(result["flags"].values()))

    def test_unsupported_claim_blocks_done(self):
        with tempfile.TemporaryDirectory() as td:
            d=Path(td); self.materialize_required_files(d)
            result=studio.validate(d,self.manifest("UNSUPPORTED"))
            self.assertFalse(result["done"])
            self.assertFalse(result["flags"]["technical_validation_passed"])

    def test_missing_visual_blocks_done(self):
        with tempfile.TemporaryDirectory() as td:
            d=Path(td); self.materialize_required_files(d); (d/"visual.svg").unlink()
            result=studio.validate(d,self.manifest())
            self.assertFalse(result["done"])
            self.assertFalse(result["flags"]["visual_rendered"])


if __name__ == "__main__":
    unittest.main()

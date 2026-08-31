import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SPEC = importlib.util.spec_from_file_location("portfolio_context_pack", Path(__file__).with_name("context_pack.py"))
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


class PortfolioContextPackTests(unittest.TestCase):
    def write(self, directory: Path, name: str, value: dict) -> Path:
        path = directory / name
        path.write_text(json.dumps(value), encoding="utf-8")
        return path

    def test_designed_lab_is_explicitly_non_result_authority(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            lab = self.write(
                root,
                "lab.json",
                {
                    "schema_version": "lab-manifest.v1",
                    "id": "LAB-AI-001",
                    "title": "Routing Lab",
                    "family": "agentic",
                    "status": "DESIGNED",
                    "question": "Which route is more stable?",
                    "hypothesis": "Deterministic routing is more stable.",
                    "variants": [],
                    "task_fixtures": [],
                    "evidence_requirements": [],
                    "metrics": [],
                    "success_criteria": [],
                    "publication": {},
                },
            )
            pack = module.build_context_pack([lab])
            context = pack["sources"][0]["context"]
            self.assertEqual("DESIGN_ONLY_NO_RESULT_CLAIMS", context["result_authority"])
            self.assertEqual("grounding-context-not-verdict-authority", pack["authority"])

    def test_candidate_export_keeps_candidate_authority_and_evidence_refs(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            candidates = self.write(
                root,
                "candidates.json",
                {
                    "schema_version": "portfolio-candidates.v1",
                    "authority": "candidate-only",
                    "concepts": [
                        {
                            "concept_id": "idempotency",
                            "name": "Idempotency",
                            "domain": "architecture-patterns",
                            "confidence": 0.9,
                            "evidence_state": "EXTRACTED",
                            "evidence": [{"document_id": "d1", "chunk_id": "c1", "url": "https://example.com"}],
                        }
                    ],
                    "relationships": [],
                },
            )
            pack = module.build_context_pack([candidates])
            context = pack["sources"][0]["context"]
            self.assertEqual("candidate-only", context["authority"])
            self.assertEqual("d1", context["concepts"][0]["evidence_refs"][0]["document_id"])

    def test_attach_preserves_existing_manifest_and_adds_context(self):
        manifest = {"article": {"id": "T-001"}, "claims": []}
        pack = {"schema_version": "portfolio-context.v1", "sources": []}
        result = module.attach_to_manifest(manifest, pack)
        self.assertEqual("T-001", result["article"]["id"])
        self.assertIs(pack, result["portfolio_context"])
        self.assertNotIn("portfolio_context", manifest)

    def test_unknown_schema_is_rejected(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            source = self.write(root, "unknown.json", {"schema_version": "unknown.v1"})
            with self.assertRaises(ValueError):
                module.build_context_pack([source])


if __name__ == "__main__":
    unittest.main()

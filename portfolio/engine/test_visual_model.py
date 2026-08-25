import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("portfolio_visual_model", Path(__file__).with_name("visual_model.py"))
visual_model = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(visual_model)
build_visual_model = visual_model.build_visual_model


class VisualModelContractTest(unittest.TestCase):
    def test_rules_and_composites_are_contract_driven(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            contract_path = root / "portfolio/visuals/contracts/test.json"
            contract_path.parent.mkdir(parents=True)
            contract_path.write_text(json.dumps({
                "id": "test-visual",
                "schema_version": 1,
                "profile": "test-profile",
                "scene_rules": [
                    {
                        "match": {"section_id": "hero"},
                        "intent": "hero",
                        "order": 10,
                        "display": {"span": "full", "body": "full"},
                        "include": [{"as": "article", "pointer": "/article", "required": True}],
                    }
                ],
                "default_scene": {"enabled": False},
                "composites": [
                    {
                        "id": "flow",
                        "intent": "system_flow",
                        "order": 20,
                        "title": "Flow",
                        "source_sections": ["hero", "verification"],
                        "display": {"span": "full", "body": "none"},
                        "graph": {"engine": "elk", "algorithm": "layered", "direction": "RIGHT", "connect": "sequential", "node_display": "title"},
                    }
                ],
            }), encoding="utf-8")

            model = {
                "article": {"id": "A-1", "title": "Article"},
                "sections": [
                    {"id": "hero", "type": "hero", "title": "Hero", "content": "Body", "writer": "deterministic"},
                    {"id": "verification", "type": "evidence", "title": "Verification", "content": "Evidence", "writer": "deterministic"},
                ],
            }
            pattern = {"sections": [{"id": "hero"}, {"id": "verification"}]}
            series = {"visual_language": {"contract": "portfolio/visuals/contracts/test.json"}}

            visual = build_visual_model(root, model, pattern, series)
            self.assertEqual("test-visual", visual["contract_id"])
            self.assertEqual("test-profile", visual["profile_id"])
            self.assertEqual(["hero", "flow"], [scene["id"] for scene in visual["scenes"]])
            self.assertEqual("hero", visual["scenes"][0]["intent"])
            self.assertEqual("system_flow", visual["scenes"][1]["intent"])
            self.assertEqual(1, len(visual["scenes"][1]["graph"]["edges"]))


if __name__ == "__main__":
    unittest.main()

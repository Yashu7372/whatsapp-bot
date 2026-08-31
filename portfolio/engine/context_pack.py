from __future__ import annotations

import argparse
import hashlib
import json
from copy import deepcopy
from datetime import datetime, timezone
from pathlib import Path


SUPPORTED_SCHEMAS = {
    "portfolio-candidates.v1",
    "graph-candidate-stage.v1",
    "lab-manifest.v1",
}
PACK_SCHEMA = "portfolio-context.v1"


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _candidate_context(value: dict) -> dict:
    concepts = []
    for item in value.get("concepts", []):
        concepts.append(
            {
                "concept_id": item.get("concept_id") or item.get("source_concept_id"),
                "name": item.get("name") or item.get("suggested_node", {}).get("name"),
                "domain": item.get("domain") or item.get("suggested_node", {}).get("domain"),
                "confidence": item.get("confidence"),
                "evidence_state": item.get("evidence_state"),
                "review_status": item.get("review_status"),
                "evidence_refs": [
                    {
                        "document_id": evidence.get("document_id"),
                        "chunk_id": evidence.get("chunk_id"),
                        "url": evidence.get("url"),
                    }
                    for evidence in item.get("evidence", [])[:8]
                ],
            }
        )

    relationships = []
    for item in value.get("relationships", []):
        suggested = item.get("suggested_relationship", {})
        relationships.append(
            {
                "source": item.get("subject_concept_id") or suggested.get("source"),
                "type": item.get("predicate") or suggested.get("type"),
                "target": item.get("object_concept_id") or suggested.get("target"),
                "confidence": item.get("confidence"),
                "evidence_state": item.get("evidence_state"),
                "review_status": item.get("review_status"),
            }
        )

    return {
        "kind": "knowledge-candidates",
        "authority": value.get("authority"),
        "concepts": concepts,
        "relationships": relationships,
    }


def _lab_context(value: dict) -> dict:
    status = value.get("status")
    return {
        "kind": "lab",
        "lab_id": value.get("id"),
        "title": value.get("title"),
        "family": value.get("family"),
        "status": status,
        "question": value.get("question"),
        "hypothesis": value.get("hypothesis"),
        "principles": value.get("principles", []),
        "variants": value.get("variants", []),
        "task_fixtures": value.get("task_fixtures", []),
        "evidence_requirements": value.get("evidence_requirements", []),
        "metrics": value.get("metrics", []),
        "success_criteria": value.get("success_criteria", []),
        "publication": value.get("publication", {}),
        "result_authority": "VERIFIED_RESULTS_ALLOWED" if status in {"VERIFIED", "PUBLISHED"} else "DESIGN_ONLY_NO_RESULT_CLAIMS",
    }


def normalize_source(path: Path) -> dict:
    value = load_json(path)
    schema = value.get("schema_version")
    if schema not in SUPPORTED_SCHEMAS:
        raise ValueError(f"unsupported portfolio context schema {schema!r}: {path}")

    if schema == "lab-manifest.v1":
        context = _lab_context(value)
    else:
        context = _candidate_context(value)

    return {
        "schema_version": schema,
        "source_path": str(path.resolve()),
        "source_sha256": sha256_file(path),
        "context": context,
    }


def build_context_pack(paths: list[Path]) -> dict:
    if not paths:
        raise ValueError("at least one context source is required")
    return {
        "schema_version": PACK_SCHEMA,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "authority": "grounding-context-not-verdict-authority",
        "sources": [normalize_source(path) for path in paths],
        "rules": [
            "candidate knowledge must be described as candidate/discovered until curated",
            "a DESIGNED or RUNNABLE lab must not be narrated as if results were measured",
            "VERIFIED claims still require explicit evidence in the article manifest",
            "context packs provide grounding but do not replace publication validation",
        ],
    }


def attach_to_manifest(manifest: dict, pack: dict) -> dict:
    if pack.get("schema_version") != PACK_SCHEMA:
        raise ValueError(f"unsupported context pack schema: {pack.get('schema_version')!r}")
    updated = deepcopy(manifest)
    updated["portfolio_context"] = pack
    return updated


def save_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Build or attach evidence-aware portfolio context packs.")
    sub = parser.add_subparsers(dest="command", required=True)

    build = sub.add_parser("build")
    build.add_argument("sources", nargs="+", type=Path)
    build.add_argument("--output", type=Path, required=True)

    attach = sub.add_parser("attach")
    attach.add_argument("article_dir", type=Path)
    attach.add_argument("sources", nargs="+", type=Path)

    args = parser.parse_args()
    if args.command == "build":
        pack = build_context_pack(args.sources)
        save_json(args.output, pack)
        print(json.dumps({"status": "PASS", "output": str(args.output), "sources": len(pack["sources"])}, indent=2))
        return 0

    article_dir = args.article_dir.resolve()
    manifest_path = article_dir / "manifest.json"
    if not manifest_path.exists():
        raise FileNotFoundError(f"missing article manifest: {manifest_path}")
    pack = build_context_pack(args.sources)
    save_json(article_dir / "portfolio-context.json", pack)
    updated = attach_to_manifest(load_json(manifest_path), pack)
    save_json(manifest_path, updated)
    print(
        json.dumps(
            {
                "status": "PASS",
                "article_dir": str(article_dir),
                "context_file": str(article_dir / "portfolio-context.json"),
                "sources": len(pack["sources"]),
            },
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

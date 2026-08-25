#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from urllib import request

ROOT = Path(__file__).resolve().parents[2]
ENGINE_DIR = Path(__file__).resolve().parent
if str(ENGINE_DIR) not in sys.path:
    sys.path.insert(0, str(ENGINE_DIR))

from visual_model import build_visual_model as build_visual_model_from_contract
from visual_model import required_visual_artifacts, run_configured_renderer

DEFAULT_SERIES = ROOT / "portfolio/series/engineering-control-plane/series.json"
PATTERN_DIR = ROOT / "portfolio/patterns"


def load(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def save(path: Path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def git(*args: str, allow_fail: bool = False) -> str:
    proc = subprocess.run(["git", *args], cwd=ROOT, text=True, capture_output=True)
    if proc.returncode and not allow_fail:
        raise RuntimeError(proc.stderr.strip() or proc.stdout.strip())
    return proc.stdout.strip()


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


def word_count(text: str) -> int:
    return len(text.split())


def load_contracts(manifest: dict):
    series_path = ROOT / manifest.get("series_config", str(DEFAULT_SERIES.relative_to(ROOT)))
    series = load(series_path)
    pattern_id = manifest.get("article_pattern") or series["article_pattern"]
    pattern = load(PATTERN_DIR / f"{pattern_id}.json")
    return series, pattern


def collect_source(article_dir: Path, manifest: dict) -> dict:
    source = manifest.get("source", {})
    base = source.get("base_branch") or "develop"
    current = git("branch", "--show-current", allow_fail=True)
    diff_range = f"{base}...HEAD"
    changed = [x for x in git("diff", "--name-only", diff_range, allow_fail=True).splitlines() if x]
    commits = [x for x in git("log", "--pretty=%H|%h|%s", f"{base}..HEAD", allow_fail=True).splitlines() if x]
    pack = {
        "article_id": manifest["article"]["id"],
        "repository": source.get("repository"),
        "expected_branch": manifest["article"].get("branch"),
        "current_branch": current,
        "base_branch": base,
        "changed_files": changed,
        "commits": commits,
        "implementation_notes": manifest.get("implementation_notes", []),
        "claims": manifest.get("claims", []),
        "collected_at": now(),
    }
    save(article_dir / "source-pack.json", pack)
    return pack


def build_engineering_analysis(manifest: dict, source_pack: dict) -> dict:
    article = manifest["article"]
    task = manifest["task"]
    claims = manifest.get("claims", [])
    incident = manifest.get("incident", {})
    return {
        "article_id": article["id"],
        "title": article["title"],
        "thesis": article.get("thesis", ""),
        "problem": article.get("problem", ""),
        "task": task,
        "engineering_tension": manifest.get("engineering_tension", {}),
        "incident": {
            "source_type": incident.get("source_type", "generalized"),
            "facts": incident.get("facts", []),
            "setting": incident.get("setting", ""),
            "note": incident.get("note", "Generalized engineering scenario unless explicitly backed by source evidence."),
        },
        "implementation": {
            "notes": manifest.get("implementation_notes", []),
            "changed_files": source_pack.get("changed_files", []),
            "commits": source_pack.get("commits", []),
        },
        "claims": claims,
        "verified": [c for c in claims if c.get("status") == "VERIFIED"],
        "design_intent": [c for c in claims if c.get("status") == "DESIGN_INTENT"],
        "unknown": [c for c in claims if c.get("status") in {"UNKNOWN", "UNSUPPORTED"}],
        "open_questions": manifest.get("open_questions", []),
        "story": manifest.get("story", {}),
    }


def content_studio_generate(prompt: str, manifest: dict) -> str:
    base = os.getenv("CONTENT_STUDIO_BASE_URL", "").rstrip("/")
    token = os.getenv("CONTENT_STUDIO_TOKEN", "")
    payload = manifest.get("generation", {}).get("content_studio_payload")
    if not base or not token or not payload:
        raise RuntimeError("CONTENT_STUDIO_BASE_URL, CONTENT_STUDIO_TOKEN and content_studio_payload are required for grounded_llm sections")
    body = dict(payload)
    body["topic"] = prompt
    req = request.Request(
        base + "/api/v1/content-ideas/generate",
        data=json.dumps(body).encode("utf-8"),
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
        method="POST",
    )
    with request.urlopen(req, timeout=90) as response:
        idea = json.loads(response.read().decode("utf-8"))
    req = request.Request(
        base + f"/api/v1/content-ideas/{idea['id']}/variants",
        headers={"Authorization": f"Bearer {token}"},
    )
    with request.urlopen(req, timeout=90) as response:
        variants = json.loads(response.read().decode("utf-8"))
    if not variants or not variants[0].get("body"):
        raise RuntimeError("Content Studio returned no article variant")
    return variants[0]["body"].strip()


def section_prompt(section: dict, series: dict, manifest: dict, analysis: dict, existing: dict) -> str:
    policy = section.get("policy", {})
    return "\n".join([
        "Generate exactly ONE section of a source-grounded engineering portfolio article.",
        f"Section id: {section['id']}",
        f"Section purpose: {section.get('purpose', section['type'])}",
        f"Minimum words: {section.get('min_words', 0)}",
        "Use the task as the narrative spine. Explain decisions from the task perspective, not as a generic architecture tour.",
        "Do not invent implementation facts, production incidents, metrics, files, commits, tests or outcomes.",
        "VERIFIED means backed by supplied evidence. DESIGN_INTENT means intended architecture. UNKNOWN must remain unknown.",
        "If this is the incident section, clearly label it real/generalized/hypothetical based on supplied incident.source_type.",
        f"Sarcasm policy: {json.dumps(policy, ensure_ascii=False)}",
        "Dry engineering sarcasm may be used only if policy allows it; never mock people or teams.",
        "Return prose only; do not repeat the article title.",
        "",
        "SERIES:", json.dumps(series, ensure_ascii=False),
        "ARTICLE MANIFEST:", json.dumps(manifest, ensure_ascii=False),
        "ENGINEERING ANALYSIS:", json.dumps(analysis, ensure_ascii=False),
        "ALREADY WRITTEN SECTION IDS:", json.dumps(list(existing)),
    ])


def deterministic_section(section: dict, manifest: dict, analysis: dict, series: dict) -> str:
    sid = section["id"]
    article = manifest["article"]
    task = manifest["task"]
    story = manifest.get("story", {})
    claims = manifest.get("claims", [])
    if sid == "hero":
        return f"**{article.get('subtitle','')}**\n\n**Article {article['number']} / {series['length']} · {series['name']}**"
    if sid == "concrete_task":
        return f"**{task['id']} — {task['title']}**\n\n{task.get('description','')}"
    if sid in {"snapshot_before", "snapshot_after"}:
        key = "before" if sid.endswith("before") else "after"
        snapshot = task.get("snapshots", {}).get(key, {})
        rows = [
            ("TASK", task["id"]),
            ("GOAL", snapshot.get("goal") or task.get("title", "—")),
            ("KNOWN", ", ".join(snapshot.get("known", [])) or "—"),
            ("UNKNOWN", ", ".join(snapshot.get("unknown", [])) or "—"),
            ("CONFIDENCE", str(snapshot.get("confidence", "—"))),
            ("NEXT CONTROL ACTION", snapshot.get("next_control_action", "—")),
        ]
        return "\n".join(f"**{k}:** {v}" for k, v in rows)
    if sid == "request_journey":
        return "\n".join(f"{i}. **{step.get('name','Step')}** — {step.get('why','')}" for i, step in enumerate(story.get("journey", []), 1)) or "No journey supplied."
    if sid == "verification":
        lines = ["| Claim | Evidence | Verdict |", "|---|---|---|"]
        for claim in claims:
            evidence = ", ".join(claim.get("evidence", [])) or "—"
            lines.append(f"| {claim.get('claim','')} | {evidence} | {claim.get('status','UNKNOWN')} |")
        return "\n".join(lines)
    if sid == "proof_status":
        buckets = {"VERIFIED": [], "DESIGN_INTENT": [], "UNKNOWN": [], "UNSUPPORTED": []}
        for claim in claims:
            buckets.setdefault(claim.get("status", "UNKNOWN"), []).append(claim.get("claim", ""))
        parts = []
        for status in ("VERIFIED", "DESIGN_INTENT", "UNKNOWN", "UNSUPPORTED"):
            items = buckets.get(status, [])
            if items:
                parts.append(f"### {status}\n" + "\n".join(f"- {x}" for x in items))
        return "\n\n".join(parts) or "No proof-status claims supplied."
    if sid == "takeaway":
        return article.get("takeaway", "")
    if sid == "next_article":
        return article.get("next_article", "")
    if sid == "references":
        source = manifest.get("source", {})
        refs = [
            f"- Repository: `{source.get('repository','—')}`",
            f"- Source branch: `{article.get('branch','—')}`",
            f"- Base branch: `{source.get('base_branch','—')}`",
        ]
        refs.extend(f"- Changed file: `{x}`" for x in analysis.get("implementation", {}).get("changed_files", []))
        refs.extend(f"- Commit: `{x}`" for x in analysis.get("implementation", {}).get("commits", []))
        return "\n".join(refs)
    raise KeyError(f"No deterministic writer registered for section {sid}")


def build_article_model(article_dir: Path, manifest: dict, series: dict, pattern: dict, analysis: dict) -> dict:
    sections = []
    written = {}
    generation_errors = []
    for contract in pattern["sections"]:
        try:
            if contract.get("writer") == "deterministic":
                content = deterministic_section(contract, manifest, analysis, series)
            else:
                content = content_studio_generate(section_prompt(contract, series, manifest, analysis, written), manifest)
            entry = {
                "id": contract["id"],
                "type": contract["type"],
                "title": contract.get("title") or contract["id"].replace("_", " ").title(),
                "content": content,
                "word_count": word_count(content),
                "writer": contract.get("writer"),
            }
            sections.append(entry)
            written[contract["id"]] = entry
        except Exception as exc:
            generation_errors.append({"section": contract["id"], "error": str(exc)})
    model = {
        "schema_version": 1,
        "article": {
            "id": manifest["article"]["id"],
            "number": manifest["article"]["number"],
            "title": manifest["article"]["title"],
            "series_id": series["id"],
            "series_name": series["name"],
            "series_length": series["length"],
        },
        "task": manifest["task"],
        "story": manifest.get("story", {}),
        "implementation": analysis.get("implementation", {}),
        "incident_source_type": analysis["incident"]["source_type"],
        "claims": manifest.get("claims", []),
        "sections": sections,
        "generation_errors": generation_errors,
        "generated_at": now(),
    }
    save(article_dir / "article-model.json", model)
    return model


def render_markdown(model: dict, pattern: dict) -> str:
    by_id = {s["id"]: s for s in model["sections"]}
    article = model["article"]
    chunks = [f"# {article['title']}\n"]
    for contract in pattern["sections"]:
        section = by_id.get(contract["id"])
        if not section:
            continue
        if contract["id"] != "hero":
            chunks.append(f"## {section['title']}\n")
        chunks.append(section["content"].strip() + "\n")
    return "\n".join(chunks)


def render_linkedin(model: dict) -> str:
    article = model["article"]
    by_id = {s["id"]: s for s in model["sections"]}
    selected = ["engineering_tension", "incident", "system_idea", "takeaway"]
    body = []
    for sid in selected:
        content = by_id.get(sid, {}).get("content", "")
        if content:
            body.append(content)
    return f"{article['title']}\n\n" + "\n\n".join(body) + f"\n\nArticle {article['number']} / {article['series_length']} · {article['series_name']}\n"


def build_visual_model(model: dict, pattern: dict, series: dict) -> dict:
    return build_visual_model_from_contract(ROOT, model, pattern, series)


def validate(article_dir: Path, manifest: dict, series: dict, pattern: dict, model: dict | None = None) -> dict:
    model = model or load(article_dir / "article-model.json")
    stages = {}
    errors = []
    warnings = []

    required_ids = [s["id"] for s in pattern["sections"] if s.get("required")]
    generated = {s["id"]: s for s in model.get("sections", [])}
    missing = [sid for sid in required_ids if sid not in generated]
    stages["structure_validated"] = {"status": "PASS" if not missing else "FAIL", "missing": missing}
    if missing:
        errors.append(f"missing required sections: {', '.join(missing)}")

    shallow = []
    for contract in pattern["sections"]:
        minimum = contract.get("min_words", 0)
        section = generated.get(contract["id"])
        if section and minimum and section.get("word_count", 0) < minimum:
            shallow.append({"section": contract["id"], "words": section.get("word_count", 0), "minimum": minimum})
    stages["section_depth_validated"] = {"status": "PASS" if not shallow else "FAIL", "shallow_sections": shallow}
    if shallow:
        errors.append("one or more generated sections are below configured depth")

    incident_type = model.get("incident_source_type")
    allowed = {"real", "generalized", "hypothetical"}
    incident_ok = incident_type in allowed
    stages["incident_grounded"] = {"status": "PASS" if incident_ok else "FAIL", "source_type": incident_type}
    if not incident_ok:
        errors.append("incident source type must be real, generalized or hypothetical")

    unsupported = [c for c in model.get("claims", []) if c.get("status") == "UNSUPPORTED"]
    verified_without_evidence = [c for c in model.get("claims", []) if c.get("status") == "VERIFIED" and not c.get("evidence")]
    claims_ok = not unsupported and not verified_without_evidence
    stages["claim_validation"] = {
        "status": "PASS" if claims_ok else "FAIL",
        "verified": sum(1 for c in model.get("claims", []) if c.get("status") == "VERIFIED"),
        "design_intent": sum(1 for c in model.get("claims", []) if c.get("status") == "DESIGN_INTENT"),
        "unknown": sum(1 for c in model.get("claims", []) if c.get("status") == "UNKNOWN"),
        "unsupported": len(unsupported),
        "verified_without_evidence": len(verified_without_evidence),
    }
    if unsupported:
        errors.append("UNSUPPORTED claims are not publishable")
    if verified_without_evidence:
        errors.append("VERIFIED claims require evidence")

    article_path = article_dir / "article.md"
    words = word_count(article_path.read_text(encoding="utf-8")) if article_path.exists() else 0
    minimum = series["done_policy"].get("minimum_article_words", 0)
    maximum = series["done_policy"].get("maximum_article_words", 10**9)
    article_ok = article_path.exists() and minimum <= words <= maximum
    stages["article_rendered"] = {"status": "PASS" if article_ok else "FAIL", "words": words, "minimum": minimum, "maximum": maximum}
    if not article_ok:
        errors.append(f"article word count {words} outside configured range {minimum}-{maximum}")

    linkedin_path = article_dir / "linkedin.md"
    linkedin_words = word_count(linkedin_path.read_text(encoding="utf-8")) if linkedin_path.exists() else 0
    linkedin_ok = linkedin_path.exists() and linkedin_words >= series["done_policy"].get("minimum_linkedin_words", 0)
    stages["linkedin_rendered"] = {"status": "PASS" if linkedin_ok else "FAIL", "words": linkedin_words}
    if not linkedin_ok:
        errors.append("LinkedIn variant missing or below configured minimum")

    artifact_names = required_visual_artifacts(series)
    visual_files = [article_dir / name for name in artifact_names]
    artifacts_ok = all(path.exists() and path.stat().st_size > 20 for path in visual_files)
    renderer_ok = True
    render_report_path = article_dir / "visual-render.json"
    render_report = None
    if render_report_path.exists():
        try:
            render_report = load(render_report_path)
            renderer_ok = render_report.get("status") == "PASS"
        except Exception as exc:
            renderer_ok = False
            warnings.append(f"could not parse visual-render.json: {exc}")
    elif "visual-render.json" in artifact_names:
        renderer_ok = False
    visual_ok = artifacts_ok and renderer_ok
    stages["visual_generated"] = {
        "status": "PASS" if visual_ok else "FAIL",
        "artifacts": artifact_names,
        "renderer_status": render_report.get("status") if render_report else None,
    }
    if not visual_ok:
        errors.append("required visual artifacts are missing or visual renderer failed")

    generation_errors = model.get("generation_errors", [])
    stages["generation_complete"] = {"status": "PASS" if not generation_errors else "FAIL", "errors": generation_errors}
    if generation_errors:
        errors.append("one or more article sections failed generation")

    strict = os.getenv("PORTFOLIO_STRICT_BRANCH", "false").lower() == "true"
    expected = manifest["article"].get("branch")
    current = git("branch", "--show-current", allow_fail=True)
    branch_ok = (not strict) or not expected or current == expected
    stages["branch_validated"] = {"status": "PASS" if branch_ok else "FAIL", "expected": expected, "actual": current, "strict": strict}
    if not branch_ok:
        errors.append(f"branch mismatch expected={expected} actual={current}")

    done = all(value["status"] == "PASS" for value in stages.values())
    result = {
        "article_id": manifest["article"]["id"],
        "stages": stages,
        "done": done,
        "errors": errors,
        "warnings": warnings,
        "validated_at": now(),
    }
    save(article_dir / "validation.json", result)
    return result


def generate(article_dir: Path) -> dict:
    manifest = load(article_dir / "manifest.json")
    series, pattern = load_contracts(manifest)
    source_pack = collect_source(article_dir, manifest)
    analysis = build_engineering_analysis(manifest, source_pack)
    save(article_dir / "engineering-analysis.json", analysis)
    model = build_article_model(article_dir, manifest, series, pattern, analysis)
    (article_dir / "article.md").write_text(render_markdown(model, pattern), encoding="utf-8")
    (article_dir / "linkedin.md").write_text(render_linkedin(model), encoding="utf-8")
    visual = build_visual_model(model, pattern, series)
    save(article_dir / "visual-model.json", visual)
    run_configured_renderer(ROOT, article_dir, series)
    return validate(article_dir, manifest, series, pattern, model)


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    for command in ("generate", "validate"):
        subparser = sub.add_parser(command)
        subparser.add_argument("article_dir")
    args = parser.parse_args()
    article_dir = Path(args.article_dir).resolve()
    manifest = load(article_dir / "manifest.json")
    series, pattern = load_contracts(manifest)
    result = generate(article_dir) if args.command == "generate" else validate(article_dir, manifest, series, pattern)
    print(json.dumps(result, indent=2))
    raise SystemExit(0 if result["done"] else 2)


if __name__ == "__main__":
    main()

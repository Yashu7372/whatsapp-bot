from __future__ import annotations

import json
import subprocess
from copy import deepcopy
from datetime import datetime, timezone
from pathlib import Path


def _load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def _save(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _pointer_get(document, pointer: str):
    if pointer in {"", "/"}:
        return document
    current = document
    for raw in pointer.lstrip("/").split("/"):
        token = raw.replace("~1", "/").replace("~0", "~")
        if isinstance(current, list):
            current = current[int(token)]
        else:
            current = current[token]
    return current


def _matches(section: dict, match: dict) -> bool:
    if match.get("any") is True:
        return True
    section_id = match.get("section_id")
    if section_id is not None and section.get("id") != section_id:
        return False
    section_ids = match.get("section_ids")
    if section_ids is not None and section.get("id") not in section_ids:
        return False
    section_type = match.get("section_type")
    if section_type is not None and section.get("type") != section_type:
        return False
    section_types = match.get("section_types")
    if section_types is not None and section.get("type") not in section_types:
        return False
    return True


def _included_data(rule: dict, model: dict) -> dict:
    data = {}
    for include in rule.get("include", []):
        alias = include["as"]
        try:
            data[alias] = deepcopy(_pointer_get(model, include["pointer"]))
        except (KeyError, IndexError, ValueError, TypeError):
            if include.get("required", True):
                raise
            data[alias] = None
    return data


def _scene_from_rule(section: dict, rule: dict, model: dict, fallback_order: int) -> dict:
    return {
        "id": section["id"],
        "intent": rule["intent"],
        "order": rule.get("order", fallback_order),
        "source": {
            "section_id": section["id"],
            "section_type": section.get("type"),
            "writer": section.get("writer"),
        },
        "content": {
            "title": section.get("title", section["id"]),
            "body": section.get("content", ""),
        },
        "data": _included_data(rule, model),
        "display": deepcopy(rule.get("display", {})),
    }


def _composite_scene(config: dict, model: dict, by_id: dict[str, dict]) -> dict | None:
    source_sections = [by_id[sid] for sid in config.get("source_sections", []) if sid in by_id]
    if not source_sections:
        return None

    graph_config = config.get("graph", {})
    node_display = graph_config.get("node_display", "title")
    nodes = []
    for section in source_sections:
        label = section.get("title", section["id"]) if node_display == "title" else section.get("content", "")
        nodes.append({
            "id": section["id"],
            "label": label,
            "source": {"section_id": section["id"], "section_type": section.get("type")},
        })

    edges = []
    if graph_config.get("connect") == "sequential":
        for index in range(len(nodes) - 1):
            source = nodes[index]["id"]
            target = nodes[index + 1]["id"]
            edges.append({"id": f"{source}--{target}", "source": source, "target": target})
    else:
        edges.extend(deepcopy(graph_config.get("edges", [])))

    return {
        "id": config["id"],
        "intent": config["intent"],
        "order": config.get("order", 0),
        "source": {"section_ids": [section["id"] for section in source_sections]},
        "content": {"title": config.get("title", config["id"]), "body": config.get("body", "")},
        "data": _included_data(config, model),
        "display": deepcopy(config.get("display", {})),
        "layout": {
            "engine": graph_config.get("engine"),
            "algorithm": graph_config.get("algorithm"),
            "direction": graph_config.get("direction"),
        },
        "graph": {"nodes": nodes, "edges": edges},
    }


def build_visual_model(root: Path, model: dict, pattern: dict, series: dict) -> dict:
    language = series.get("visual_language", {})
    contract_path = language.get("contract")
    if not contract_path:
        raise RuntimeError("visual_language.contract is required")

    contract = _load(root / contract_path)
    by_id = {section["id"]: section for section in model.get("sections", [])}
    scenes = []

    for index, section_contract in enumerate(pattern.get("sections", []), start=1):
        section = by_id.get(section_contract["id"])
        if not section:
            continue
        matching_rule = next((rule for rule in contract.get("scene_rules", []) if _matches(section, rule.get("match", {}))), None)
        if matching_rule:
            scenes.append(_scene_from_rule(section, matching_rule, model, index * 100))
            continue
        default_scene = contract.get("default_scene", {})
        if default_scene.get("enabled"):
            scenes.append(_scene_from_rule(section, default_scene, model, index * 100))

    for composite in contract.get("composites", []):
        scene = _composite_scene(composite, model, by_id)
        if scene:
            scenes.append(scene)

    scenes.sort(key=lambda scene: (scene.get("order", 0), scene["id"]))
    profile_id = language.get("profile") or contract.get("profile")
    if not profile_id:
        raise RuntimeError("visual profile id is required")

    return {
        "schema_version": contract.get("schema_version", 1),
        "contract_id": contract["id"],
        "profile_id": profile_id,
        "document": deepcopy(model["article"]),
        "scenes": scenes,
        "generated_at": _now(),
    }


def run_configured_renderer(root: Path, article_dir: Path, series: dict) -> dict:
    renderer = series.get("visual_language", {}).get("renderer", {})
    if not renderer.get("enabled", False):
        return {"status": "SKIPPED", "reason": "renderer disabled"}

    command = renderer.get("command")
    if not command:
        raise RuntimeError("visual renderer is enabled but visual_language.renderer.command is missing")

    substitutions = {
        "repo_root": str(root),
        "article_dir": str(article_dir),
        "visual_model": str(article_dir / "visual-model.json"),
    }
    resolved = [part.format_map(substitutions) for part in command]
    process = subprocess.run(resolved, cwd=root, text=True, capture_output=True)
    if process.returncode != 0:
        report = {
            "status": "FAIL",
            "command": resolved,
            "exit_code": process.returncode,
            "stdout": process.stdout.strip(),
            "stderr": process.stderr.strip(),
            "failed_at": _now(),
        }
        _save(article_dir / "visual-render.json", report)
        return report

    return {
        "status": "PASS",
        "command": resolved,
        "exit_code": process.returncode,
        "stdout": process.stdout.strip(),
        "stderr": process.stderr.strip(),
    }


def required_visual_artifacts(series: dict) -> list[str]:
    configured = series.get("visual_language", {}).get("renderer", {}).get("required_artifacts")
    if configured:
        return list(configured)
    return ["visual-model.json", "visual.html"]

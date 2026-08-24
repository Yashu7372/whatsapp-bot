#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
STUDIO = ROOT / "portfolio/engine/studio.py"
ARTICLES = ROOT / "portfolio/articles"


def run(command: str, limit: int | None) -> int:
    article_dirs = sorted(p for p in ARTICLES.glob("ECP-*") if p.is_dir())
    if limit is not None:
        article_dirs = article_dirs[:limit]
    if not article_dirs:
        print("No article workspaces found", file=sys.stderr)
        return 2

    summary = []
    exit_code = 0
    for article_dir in article_dirs:
        proc = subprocess.run(
            [sys.executable, str(STUDIO), command, str(article_dir)],
            cwd=ROOT,
            text=True,
            capture_output=True,
        )
        try:
            result = json.loads(proc.stdout) if proc.stdout.strip() else {"done": False, "errors": [proc.stderr.strip()]}
        except json.JSONDecodeError:
            result = {"done": False, "errors": [proc.stdout.strip(), proc.stderr.strip()]}
        summary.append({"article": article_dir.name, "done": bool(result.get("done")), "errors": result.get("errors", [])})
        if proc.returncode != 0:
            exit_code = proc.returncode

    print(json.dumps({"command": command, "articles": summary, "done": all(x["done"] for x in summary)}, indent=2))
    return exit_code


def main():
    parser = argparse.ArgumentParser(description="Generate or validate portfolio articles using the configured article pattern.")
    parser.add_argument("command", choices=["generate", "validate"])
    parser.add_argument("--limit", type=int, default=None, help="Process only the first N article workspaces.")
    args = parser.parse_args()
    raise SystemExit(run(args.command, args.limit))


if __name__ == "__main__":
    main()

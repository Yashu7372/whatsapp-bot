#!/usr/bin/env bash
set -euo pipefail
ARTICLE_JSON="portfolio/active-article.json"
if [[ ! -f "$ARTICLE_JSON" ]]; then
  echo "portfolio/active-article.json is missing. Checkout a series/ecp-* branch." >&2
  exit 2
fi
ARTICLE_DIR="$(python -c 'import json; print(json.load(open("portfolio/active-article.json"))["article_dir"])')"
export PORTFOLIO_STRICT_BRANCH=true
python portfolio/engine/studio.py generate "$ARTICLE_DIR"

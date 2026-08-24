# Portfolio Content Studio

Portfolio-only publishing pipeline inside the Spring Boot `whatsapp-bot` repository.

The canonical flow is:

`source branch -> source-pack.json -> engineering-analysis.json -> article-model.json -> article.md / linkedin.md / visual-model.json / visual.html -> validation.json`

`article-model.json` is the semantic source of truth. Markdown, LinkedIn copy and visuals are projections of that model.

## Article contract

The article structure is configuration-driven from `portfolio/patterns/engineering-deep-dive-v1.json`.

The current pattern is task-first and contains:

- hero / article promise
- engineering tension
- real engineering problem
- labelled real, generalized or hypothetical incident
- controlled dry engineering sarcasm
- why the naive approach fails
- system idea and responsibility boundary
- where the pattern is useful / unnecessary
- one concrete task
- before snapshot
- bounded task context
- request journey
- explicit decision point
- source-grounded implementation
- trade-offs
- failure / stop path
- after snapshot
- deterministic verification table
- VERIFIED / DESIGN_INTENT / UNKNOWN proof status
- architecture reference when useful
- broader industry usage
- one-line takeaway
- next article
- source / branch / commit references

Changing the order, required sections, word-depth rules or writer strategy is a pattern/config change, not a Python-code change.

## 70-day series

Series metadata lives only in `portfolio/series/engineering-control-plane/series.json`. Renderers read `length` from that file; article code must not hardcode `70`.

## Generation authority boundary

Facts are deterministic: task snapshots, branch/commit references, changed files, evidence tables, proof status and article numbering.

Interpretation is AI-assisted: engineering tension, incident narrative, why existing approaches fail, bounded-context explanation, system explanation, trade-offs, failure narrative and industry usage.

Verdicts are deterministic again. `UNSUPPORTED` is never publishable and `VERIFIED` requires evidence.

The incident source type must be `real`, `generalized` or `hypothetical`. When no explicit real evidence exists, the safe default is `generalized`; the system must not invent a production incident.

## Existing Spring Content Studio integration

Narrative sections use the existing Spring Boot content-generation endpoints:

- `POST /api/v1/content-ideas/generate`
- `GET /api/v1/content-ideas/{id}/variants`

Configure:

- `CONTENT_STUDIO_BASE_URL`
- `CONTENT_STUDIO_TOKEN`

Generation fails explicitly when grounded narrative generation is required and Content Studio is unavailable. It does not manufacture fallback article prose.

## Generate one article

`python portfolio/engine/studio.py generate portfolio/articles/ECP-001`

## Generate the first five

`python portfolio/engine/generate_all.py generate --limit 5`

## Validate the first five

`python portfolio/engine/generate_all.py validate --limit 5`

## Strict source-branch validation

`PORTFOLIO_STRICT_BRANCH=true python portfolio/engine/studio.py validate portfolio/articles/ECP-001`

## Visual generation

Visual generation is model-driven. `visual-model.json` lists components and source sections; `visual.html` uses responsive HTML/CSS layout rather than fixed SVG coordinates, item-count limits or string truncation. Browser capture can later export this HTML to PNG/PDF without changing article semantics.

## Definition of done

`validation.json` stores stage state, evidence/metrics and errors. `done=true` only when every required stage passes, including:

- article structure
- configured section depth
- incident grounding
- claim/evidence validation
- article word-depth policy
- LinkedIn variant policy
- visual model/render artifact
- generation completeness
- optional strict branch match

The quality policy is configured in the series and article-pattern files rather than hardcoded into the engine.

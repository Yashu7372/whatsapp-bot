# Portfolio Content Studio

Portfolio-only publishing pipeline inside the Spring Boot `whatsapp-bot` repository.

The canonical flow is:

`source branch -> source-pack.json -> engineering-analysis.json -> article-model.json -> article.md / linkedin.md / visual-model.json -> external visual worker -> visual.html / visual-render.json -> validation.json`

`article-model.json` is the semantic source of truth. Markdown, LinkedIn copy and `visual-model.json` are projections of that model. `visual-model.json` is renderer-neutral: the Node worker is one renderer implementation, not part of the semantic contract.

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

## Visual architecture

The visual path is intentionally split into small single-purpose stages and composed as a pipeline:

`article-model.json -> visual contract -> visual-model.json -> schema validation -> profile resolution -> graph layout -> template rendering -> artifact write`

The responsibilities are separated as follows:

- `portfolio/engine/visual_model.py` builds the portable `visual-model.json` from declarative rules.
- `portfolio/visuals/contracts/engineering-visual-v1.json` decides which semantic article sections become scenes and which scenes become composites.
- `portfolio/visuals/schemas/visual-model.schema.json` defines the portable renderer contract.
- `portfolio/visuals/profiles/engineering-explainer-v1.json` selects templates, theme tokens and graph-layout parameters.
- `portfolio/visuals/components/*.hbs` contain component markup.
- `portfolio/visuals/themes/*.css` contains presentation styling.
- `portfolio/visual-worker` is the current renderer implementation.

The Node worker uses AJV for contract validation, ELK.js for automatic graph layout, Handlebars for component/template composition and Marked for Markdown rendering. The worker does not know article IDs, article numbers, series length, fixed item counts, fixed text truncation limits or article-specific coordinates.

The `system_flow` visual is a composite scene. Its source sections and sequencing are configured in the visual contract, while ELK.js calculates positions at render time. A different renderer can consume the same `visual-model.json` without changing article generation.

## Local visual-worker setup

Install the renderer dependencies once with `npm --prefix portfolio/visual-worker install`.

Render any already-generated visual model with `node portfolio/visual-worker/src/index.mjs render portfolio/articles/ECP-001/visual-model.json --output portfolio/articles/ECP-001`.

The normal Python generation command invokes the configured renderer automatically after writing `visual-model.json`.

## Generate one article

`python portfolio/engine/studio.py generate portfolio/articles/ECP-001`

## Generate the first five

`python portfolio/engine/generate_all.py generate --limit 5`

## Validate the first five

`python portfolio/engine/generate_all.py validate --limit 5`

## Run visual model tests

`python -m unittest portfolio.engine.test_visual_model`

Run the Node visual pipeline tests with `npm --prefix portfolio/visual-worker test`.

## Strict source-branch validation

`PORTFOLIO_STRICT_BRANCH=true python portfolio/engine/studio.py validate portfolio/articles/ECP-001`

## Definition of done

`validation.json` stores stage state, evidence/metrics and errors. `done=true` only when every required stage passes, including:

- article structure
- configured section depth
- incident grounding
- claim/evidence validation
- article word-depth policy
- LinkedIn variant policy
- required visual artifacts and renderer PASS status
- generation completeness
- optional strict branch match

The quality policy, visual contract, visual profile, renderer command and required artifacts are configuration rather than article-specific renderer code.

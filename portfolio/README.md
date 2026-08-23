# Portfolio Content Studio

This adds a deterministic, evidence-gated engineering publishing pipeline on top of the existing WhatsApp Bot content-generation capability.

## End-to-end flow

`Git branch -> source pack -> evidence -> story -> canonical article -> LinkedIn variant -> visual spec -> SVG -> validation gate`

The pipeline is **not done** because a model returned prose. It is done only when every required validation flag is true.

## Run one article

`python portfolio/engine/studio.py generate portfolio/articles/ECP-001`

## Validate one article

`PORTFOLIO_STRICT_BRANCH=true python portfolio/engine/studio.py validate portfolio/articles/ECP-001`

## Existing Content Studio integration

Set `generation.mode` to `content-studio` in the manifest and configure:

- `CONTENT_STUDIO_BASE_URL`
- `CONTENT_STUDIO_TOKEN`

The adapter calls the existing `/api/v1/content-ideas/generate` and `/api/v1/content-ideas/{id}/variants` endpoints. If the service is not configured, generation remains deterministic from the manifest so CI and local runs still work.

## Definition of done

Every article writes `validation.json`. `done=true` only when all flags are true:

- manifest_valid
- source_pack_ready
- evidence_ready
- story_ready
- article_generated
- linkedin_generated
- visual_spec_ready
- visual_rendered
- technical_validation_passed
- publication_validation_passed
- branch_validation_passed

`UNSUPPORTED` technical claims fail the gate. `VERIFIED` and clearly labelled `DESIGN_INTENT` claims are publishable.

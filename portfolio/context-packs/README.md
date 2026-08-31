# Portfolio Context Packs

Portfolio Content Studio does not collect architecture research and does not own lab truth. It consumes bounded, versioned context produced by the other portfolio repositories.

Supported inputs in the first slice:

- `portfolio-candidates.v1` — Architecture Vault evidence-backed candidate concepts/relationships;
- `graph-candidate-stage.v1` — Knowledge Graph review-staging export;
- `lab-manifest.v1` — Enterprise Architecture Lab design/status/metrics/evidence requirements.

The adapter intentionally strips large/raw research bodies and keeps bounded concept metadata, evidence references and lab contracts.

## Build a context pack

```bash
python portfolio/engine/context_pack.py build \
  /path/to/architecture-vault/engineering-knowledge-base/output/exports/portfolio-candidates.v1.json \
  /path/to/enterprise-architecture-graph/labs/agentic-architecture-labs/LAB-AI-001-deterministic-routing/lab.json \
  --output /tmp/portfolio-context.json
```

## Attach context to an article

```bash
python portfolio/engine/context_pack.py attach \
  portfolio/articles/ECP-001 \
  /path/to/architecture-vault/engineering-knowledge-base/output/exports/portfolio-candidates.v1.json \
  /path/to/enterprise-architecture-graph/labs/agentic-architecture-labs/LAB-AI-001-deterministic-routing/lab.json
```

The command writes `portfolio-context.json` beside the article and adds the same structured object to `manifest.json` as `portfolio_context`.

This works with the existing generator because the grounded section prompt already receives the complete article manifest. The rules in the context pack make the authority boundary explicit:

- candidate knowledge is not curated knowledge;
- DESIGNED/RUNNABLE labs cannot be written as measured results;
- verified article claims still require explicit evidence in the article manifest;
- the context pack is grounding input, never verdict authority.

This is intentionally a thin adapter. Once the flow is proven across several labs/articles, it can become a native first-class `KnowledgeContextPack` contract without changing the ArticleModel/rendering architecture.

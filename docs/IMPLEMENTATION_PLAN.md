# Multi-Platform AI Marketing Platform — Implementation Plan

Consolidated from the four planning documents spread across the doc branches.
This is the single source of truth for the working implementation.

---

## Vision

Evolve the current WhatsApp AI bot + CRM into a multi-tenant, multi-platform
AI Marketing SaaS: trend intelligence → content generation → human approval →
scheduled publishing → analytics → learning feedback.

WhatsApp becomes one plugin. The platform must support Instagram, LinkedIn,
TikTok, YouTube, Pinterest, Google Business, Reddit, and Website forms through
the same plugin interface, without changing core business services.

---

## Non-Negotiables (applies to every phase)

1. Existing WhatsApp webhook behavior is never broken.
2. Every new table has `tenant_id` — no cross-tenant data access possible.
3. Platform-specific code lives inside `platform/<name>/` packages only.
4. No auto-publishing: every generated content item needs human approval first.
5. No private feed scraping, fake engagement, or unauthorized DMs.
6. All secrets and tokens go through a credential abstraction — never plain text.
7. Migrations are additive: never drop or alter columns that existing code reads.
8. Every PR: backend `mvn test` must pass. Frontend `npm run build` must pass.

---

## Architecture Layers

```
HTTP / Webhook layer
        │
        ▼
WebhookOutboxProcessor  ◄──  webhook_outbox (transactional outbox, async)
        │
        ▼
WebhookApplicationService  (existing, unchanged)
        │
        ▼
PlatformPluginRegistry ──► MarketingPlatformPlugin (per-platform)
        │                           │
   Core services            Platform-specific code
   (tenant-scoped)          (platform/whatsapp, platform/instagram …)
        │
        ▼
PostgreSQL (tenant_id on every business table)
```

---

## Phase 0 — Baseline (DONE)

- [x] Async webhook outbox (`V3__webhook_outbox.sql`, `WebhookOutboxProcessor`)
- [x] All existing tests pass after outbox change
- [x] `WhatsappBotApplication` has `@EnableScheduling`

---

## Phase 1 — Platform Foundation

**Goal:** Introduce platform abstraction without changing any runtime behaviour.

### Backend — `whatsapp-bot`

New packages (do not rename existing ones yet):

```
com.whatsappbot.platform.core
com.whatsappbot.platform.core.dto
com.whatsappbot.platform.whatsapp
```

Files:

```
platform/core/PlatformCode.java                    enum — all supported platforms
platform/core/PlatformCapabilities.java            record — capability flags per platform
platform/core/MarketingPlatformPlugin.java         interface — the plugin contract
platform/core/PlatformPluginRegistry.java          @Component — Spring-managed plugin map
platform/core/PlatformAccountEntity.java           @Entity — platform_accounts table
platform/core/PlatformAccountRepository.java       JpaRepository
platform/core/dto/TrendCollectionRequest.java
platform/core/dto/TrendSignalDto.java
platform/core/dto/LeadCollectionRequest.java
platform/core/dto/LeadSignalDto.java
platform/core/dto/PublishCommand.java
platform/core/dto/PublishResult.java
platform/core/dto/AnalyticsRequest.java
platform/core/dto/AnalyticsResult.java
platform/core/dto/WebhookParseResult.java

platform/whatsapp/WhatsAppPlatformCapabilities.java  static capability definition
platform/whatsapp/WhatsAppPlatformPlugin.java        @Component, wraps existing client
```

Migration:

```
V4__platform_foundation.sql
  tables: platforms, platform_accounts
  seed: WHATSAPP + MANUAL_IMPORT enabled; others seeded disabled
```

### Frontend — `whatsapp-crm`

```
src/api/httpClient.ts          fetch wrapper, base URL from VITE_API_BASE_URL
src/api/platformApi.ts         listPlatforms, listAccounts
src/api/trendApi.ts            listTrends
src/types/platform.ts          Platform, PlatformAccount
src/types/trend.ts             TrendSignal

src/pages/TrendIntelligence.tsx    placeholder
src/pages/ContentStudio.tsx        placeholder
src/pages/ApprovalQueue.tsx        placeholder
src/pages/ContentCalendar.tsx      placeholder
src/pages/LeadIntelligence.tsx     placeholder
src/pages/PlatformIntegrations.tsx placeholder
src/pages/LearningInsights.tsx     placeholder

src/App.tsx                    add 7 new lazy routes
src/components/layout/Sidebar.tsx  add Intelligence + Content + Connections nav sections
```

**Validation:**
```bash
# Backend
cd whatsapp-bot && mvn test

# Frontend
cd whatsapp-crm && npm run build
```

---

## Phase 2 — Campaign & Content Foundation

**Goal:** Users can create campaigns, generate draft content ideas, approve or
reject them. No auto-publishing.

### Backend

```
campaign/CampaignEntity.java
campaign/CampaignRepository.java
campaign/CampaignService.java
campaign/CampaignController.java        GET/POST /api/v1/campaigns
campaign/CampaignStatus.java            enum: DRAFT, ACTIVE, PAUSED, COMPLETED
campaign/CampaignGoal.java              enum: AWARENESS, LEADS, ENGAGEMENT, TRAFFIC

content/ContentIdeaEntity.java
content/ContentVariantEntity.java
content/ContentIdeaRepository.java
content/ContentVariantRepository.java
content/ContentGenerationService.java   calls TenantAiService pattern
content/ContentController.java          GET/POST /api/v1/content-ideas
content/ContentStatus.java              enum: GENERATED, REVIEW, APPROVED, REJECTED, NEEDS_CHANGES
content/ContentType.java                enum: REEL, POST, STORY, CAROUSEL, ARTICLE, TEXT

approval/ApprovalTaskEntity.java
approval/ApprovalTaskRepository.java
approval/ApprovalService.java
approval/ApprovalController.java        POST /api/v1/approvals/{id}/approve|reject
approval/ApprovalStatus.java            enum: PENDING, APPROVED, REJECTED, NEEDS_CHANGES
```

Migration: `V5__campaign_content_approval.sql`
```
tables: campaigns, content_ideas, content_variants, approval_tasks
```

### Frontend

```
src/api/campaignApi.ts
src/api/contentApi.ts
src/api/approvalApi.ts
src/types/campaign.ts
src/types/content.ts
src/types/approval.ts

src/pages/ContentStudio.tsx     — real content idea list + generate button
src/pages/ApprovalQueue.tsx     — real approve/reject UI
src/pages/Campaigns.tsx         — update to real campaign CRUD
```

---

## Phase 3 — Trend Intelligence MVP

**Goal:** Manual trend import + scoring engine. AI can use top trends when
generating content.

### Backend

```
trend/TrendSourceEntity.java
trend/TrendSignalEntity.java
trend/TrendSourceRepository.java
trend/TrendSignalRepository.java
trend/TrendScoringService.java          scoring formula (see below)
trend/TrendImportService.java           manual import endpoint
trend/TrendController.java              GET /api/v1/trends, POST /api/v1/trends/import
trend/TrendSourceType.java              enum: MANUAL, API, RSS, CSV
```

Scoring formula:
```
final_score =
  freshness_score  * 0.25
+ growth_score     * 0.25
+ relevance_score  * 0.25
+ engagement_score * 0.15
+ brand_safety     * 0.10
```

Migration: `V6__trend_intelligence.sql`
```
tables: trend_sources, trend_signals
```

### Frontend

```
src/pages/TrendIntelligence.tsx   — real trend list, filters, manual import form
src/api/trendApi.ts               — extend with import endpoint
src/types/trend.ts                — extend with source info
```

---

## Phase 4 — Publishing Foundation

**Goal:** Approved content can be scheduled. A mock publisher records the job
result. Real platform API adapters come later.

### Backend

```
publishing/PublishJobEntity.java
publishing/PublishResultEntity.java
publishing/PublishJobRepository.java
publishing/PublishResultRepository.java
publishing/PublishingService.java
publishing/PublishingScheduler.java     @Scheduled, picks up SCHEDULED jobs
publishing/MockPublisherAdapter.java    implements MarketingPlatformPlugin.publish()
publishing/PublishStatus.java           enum: SCHEDULED, RUNNING, PUBLISHED, FAILED
```

Migration: `V7__publishing.sql`
```
tables: publish_jobs, publish_results
```

### Frontend

```
src/pages/ContentCalendar.tsx     — real calendar with publish job status
src/api/publishingApi.ts
src/types/publishing.ts
```

---

## Phase 5 — Lead Intelligence MVP

**Goal:** WhatsApp inbound messages and manual imports produce scored lead
signals visible on the Leads screen.

### Backend

```
lead/LeadSignalEntity.java
lead/LeadSignalRepository.java
lead/LeadSignalService.java
lead/LeadScoringService.java
lead/LeadController.java                GET /api/v1/leads
lead/LeadSignalType.java                enum: INBOUND_MESSAGE, FORM, COMMENT, MANUAL
lead/LeadIntentCategory.java            enum: PRICE_INQUIRY, SERVICE_REQUEST,
                                              BOOKING_REQUEST, DEMO_REQUEST,
                                              COMPLAINT, SUPPORT_REQUEST,
                                              COMPETITOR_INTEREST, GENERAL_QUESTION
```

Integration point: `WebhookApplicationService` emits lead signal after each
inbound text message via `LeadSignalService.extractFromInbound()`.

Migration: `V8__lead_signals.sql`

### Frontend

```
src/pages/LeadIntelligence.tsx    — real lead list with intent badge + score
src/api/leadApi.ts
src/types/lead.ts
```

---

## Phase 6 — Analytics & Learning

**Goal:** Close the feedback loop. Store content performance metrics and
derive learning insights for future content scoring.

### Backend

```
analytics/AnalyticsSnapshotEntity.java
analytics/AnalyticsSnapshotRepository.java
analytics/AnalyticsIngestionService.java
analytics/AnalyticsController.java          POST /api/v1/analytics/ingest

learning/LearningInsightEntity.java
learning/LearningInsightRepository.java
learning/LearningInsightService.java
learning/LearningController.java            GET /api/v1/learning/insights
```

Migration: `V9__analytics_learning.sql`

### Frontend

```
src/pages/LearningInsights.tsx    — insight cards with pattern recommendations
src/pages/Analytics.tsx           — extend with campaign/content performance
src/api/analyticsApi.ts
src/types/analytics.ts
```

---

## Files Never to Touch Without Review

Backend:
```
V1__phase1_multi_tenant_inbox.sql
V2__automobile_service_provider.sql
V3__webhook_outbox.sql
WebhookController.java
WebhookApplicationService.java
TenantAiService.java
TenantContext.java / TenantExecutionContext.java
```

Frontend:
```
server/src/         (Node backend — demo only, not production)
src/services/api.ts (existing — keep for backward compat with existing pages)
vite.config.*
tsconfig.*
```

---

## PR Branching Strategy

```
feat/platform-core               ← Phase 1 backend
feat/frontend-nav-shell          ← Phase 1 frontend
feat/campaign-content            ← Phase 2
feat/trend-intelligence          ← Phase 3
feat/publishing-foundation       ← Phase 4
feat/lead-intelligence           ← Phase 5
feat/analytics-learning          ← Phase 6
```

Target: < 500 lines per PR. Never mix backend schema changes with frontend
redesign in the same PR.

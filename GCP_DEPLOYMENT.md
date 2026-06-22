# WhatsApp Bot — Google Cloud Deployment Guide

This guide walks you through deploying the multi-tenant WhatsApp AI bot on Google Cloud Platform using:

| Component | GCP Service |
|---|---|
| Application | **Cloud Run** (serverless containers) |
| Database | **Cloud SQL** — PostgreSQL 15 + pgvector |
| Secrets | **Secret Manager** |
| Container Registry | **Artifact Registry** |
| CI/CD | **Cloud Build** or **GitHub Actions** |

---

## Prerequisites

- GCP project with billing enabled
- `gcloud` CLI installed and authenticated (`gcloud auth login`)
- `docker` installed locally
- Your Meta/WhatsApp Business API credentials ready
- Your Google Gemini API key ready

---

## 1 — Repository File Structure

After applying these deployment files, your repo should contain:

```
├── Dockerfile                         ← Multi-stage Docker build
├── .dockerignore
├── cloudbuild.yaml                    ← Cloud Build CI/CD trigger
├── pom.xml                            ← Updated with Cloud SQL socket factory
├── deploy/
│   └── setup-gcp.sh                  ← One-time infrastructure setup script
├── .github/
│   └── workflows/
│       └── deploy.yml                ← GitHub Actions alternative
└── src/main/resources/
    ├── application.yml               ← Local dev config (unchanged)
    ├── application-prod.yml          ← Production config for Cloud Run
    └── db/migration/
        ├── V0__enable_extensions.sql ← pgvector/pgcrypto (run before V1)
        └── V1__phase1_multi_tenant_inbox.sql ← Your existing migration (remove the CREATE EXTENSION lines from top)
```

---

## 2 — One-Time Infrastructure Setup

### 2a. Edit the setup script

Open `deploy/setup-gcp.sh` and fill in the top section:

```bash
PROJECT_ID="your-gcp-project-id"        # Your actual GCP project ID
REGION="me-central1"                     # UAE/Dubai region — change if needed
WHATSAPP_VERIFY_TOKEN="make-up-a-token"  # Any string — you'll paste this into Meta console
WHATSAPP_ACCESS_TOKEN="EAAxx..."         # From Meta Developer Portal
GEMINI_API_KEY="AIzaSy..."               # From Google AI Studio
```

> **Choosing a region**: `me-central1` = Doha (Qatar). Closest to Dubai.
> Alternatives: `europe-west1` (Belgium), `asia-south1` (Mumbai).

### 2b. Run the setup script

```bash
chmod +x deploy/setup-gcp.sh
./deploy/setup-gcp.sh
```

This script will:
1. Enable all required GCP APIs
2. Create an Artifact Registry repository
3. Create a service account with least-privilege roles
4. Create a Cloud SQL PostgreSQL 15 instance with `cloudsql.enable_pgvector=on`
5. Create the database and user with a generated password
6. Store all secrets in Secret Manager
7. Build your Docker image and push it
8. Deploy to Cloud Run

**Runtime: ~8–12 minutes** (Cloud SQL takes the longest)

---

## 3 — Fix V1 Migration (Important)

Your existing `V1__phase1_multi_tenant_inbox.sql` starts with:
```sql
create extension if not exists pgcrypto;
create extension if not exists vector;
```

**Remove those two lines** from `V1__phase1_multi_tenant_inbox.sql` since `V0__enable_extensions.sql` now handles them. This prevents a Flyway checksum error on re-runs.

---

## 4 — Verify pgvector is Enabled on Cloud SQL

After Cloud SQL is created, connect and enable the extensions:

```bash
gcloud sql connect whatsapp-bot-db --user=whatsapp_bot --database=whatsapp_bot
```

Inside the SQL prompt:
```sql
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS vector;
\q
```

> The setup script also prints these commands at the end.

---

## 5 — CI/CD Setup (Choose One)

### Option A — Cloud Build (recommended, stays within GCP)

1. Go to **Cloud Build → Triggers** in the GCP Console
2. Click **Connect Repository** → select GitHub → authorize
3. Select your repo → click **Create Trigger**
4. Set:
   - **Trigger type**: Push to branch → `^main$`
   - **Configuration**: `cloudbuild.yaml`
5. Every push to `main` now builds and deploys automatically.

### Option B — GitHub Actions

Add these secrets to your GitHub repo (**Settings → Secrets → Actions**):

| Secret | Value |
|---|---|
| `GCP_PROJECT_ID` | Your GCP project ID |
| `GCP_SERVICE_ACCOUNT` | `whatsapp-bot-sa@YOUR_PROJECT.iam.gserviceaccount.com` |
| `GCP_WORKLOAD_IDENTITY_PROVIDER` | See setup below |

To get the Workload Identity Provider:
```bash
# Create Workload Identity Pool
gcloud iam workload-identity-pools create "github-pool" \
  --location="global" \
  --display-name="GitHub Actions Pool"

# Create provider
gcloud iam workload-identity-pools providers create-oidc "github-provider" \
  --location="global" \
  --workload-identity-pool="github-pool" \
  --display-name="GitHub Actions Provider" \
  --attribute-mapping="google.subject=assertion.sub,attribute.repository=assertion.repository" \
  --issuer-uri="https://token.actions.githubusercontent.com"

# Grant access
PROJECT_NUMBER=$(gcloud projects describe YOUR_PROJECT_ID --format="value(projectNumber)")
gcloud iam service-accounts add-iam-policy-binding \
  "whatsapp-bot-sa@YOUR_PROJECT_ID.iam.gserviceaccount.com" \
  --role="roles/iam.workloadIdentityUser" \
  --member="principalSet://iam.googleapis.com/projects/${PROJECT_NUMBER}/locations/global/workloadIdentityPools/github-pool/attribute.repository/YOUR_GITHUB_ORG/YOUR_REPO"

# Get the provider name for the secret
gcloud iam workload-identity-pools providers describe github-provider \
  --location="global" \
  --workload-identity-pool="github-pool" \
  --format="value(name)"
```

---

## 6 — Configure Meta Webhook

1. Go to **Meta Developer Portal** → your app → WhatsApp → Configuration
2. Set **Webhook URL** to: `https://YOUR_CLOUD_RUN_URL/webhook`
3. Set **Verify Token** to the same value as `WHATSAPP_VERIFY_TOKEN` in Secret Manager
4. Subscribe to the `messages` webhook field

Get your Cloud Run URL:
```bash
gcloud run services describe whatsapp-bot --region=me-central1 --format="value(status.url)"
```

---

## 7 — Seed Knowledge Embeddings

After the first deploy and Flyway migration, seed knowledge embeddings:

```bash
# Option 1: Trigger via API
curl -X POST https://YOUR_URL/api/tenants/localbites/knowledge \
  -H "Content-Type: application/json" \
  -d '{
    "title": "LocalBites Menu and FAQ",
    "documentType": "MENU",
    "content": "LocalBites serves burgers, pizza, pasta..."
  }'
```

Or set `KNOWLEDGE_REBUILD_ON_STARTUP=true` in Secret Manager for a one-time startup rebuild, then set it back to `false`.

---

## 8 — Local Development

For local development, keep using `docker-compose.yml` with your existing `.env` file:

```bash
# .env (never commit this)
DB_URL=jdbc:postgresql://localhost:5432/whatsapp_bot
DB_USERNAME=whatsapp_bot
DB_PASSWORD=whatsapp_bot
WHATSAPP_VERIFY_TOKEN=local-test-token
WHATSAPP_ACCESS_TOKEN=your-token
WHATSAPP_MOCK_SEND_ENABLED=true
GEMINI_API_KEY=your-key
```

```bash
docker-compose up -d          # Start PostgreSQL with pgvector
mvn spring-boot:run           # Run the app
```

---

## 9 — Useful Commands

```bash
# View live logs
gcloud run services logs tail whatsapp-bot --region=me-central1

# Update a secret
echo -n "new-token-value" | gcloud secrets versions add WHATSAPP_ACCESS_TOKEN --data-file=-

# Redeploy latest image without code change
gcloud run deploy whatsapp-bot \
  --image=me-central1-docker.pkg.dev/PROJECT_ID/whatsapp-bot-repo/whatsapp-bot:latest \
  --region=me-central1

# Connect to Cloud SQL
gcloud sql connect whatsapp-bot-db --user=whatsapp_bot --database=whatsapp_bot

# Check Flyway migrations
gcloud sql connect whatsapp-bot-db --user=whatsapp_bot --database=whatsapp_bot
# SELECT * FROM flyway_schema_history ORDER BY installed_rank;
```

---

## 10 — Cost Estimate (GCP me-central1)

| Service | Tier | Est. Monthly Cost |
|---|---|---|
| Cloud Run | 1 min instance, ~1M requests | ~$10–25 |
| Cloud SQL | `db-g1-small` (shared core, 1.7GB) | ~$25–35 |
| Artifact Registry | ~1GB storage | ~$0.10 |
| Secret Manager | 6 secrets, ~1000 accesses | < $0.01 |
| Cloud Build | 120 free build-minutes/day | $0 |
| **Total** | | **~$35–60/month** |

> Upgrade Cloud SQL to `db-f1-micro` to save ~$10/month, or `db-custom-2-3840` for production load.

---

## Security Notes

- Access tokens are stored in **Secret Manager**, never in environment variables or source code
- Cloud Run uses a **least-privilege service account** (only Cloud SQL client + Secret Accessor)
- The Cloud SQL instance has **no public IP** — Cloud Run connects via Unix socket through the Cloud SQL Auth Proxy
- Webhook endpoint is public (required by Meta) but verified by token
- For production: add **Cloud Armor** WAF in front of Cloud Run via a load balancer

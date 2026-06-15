#!/usr/bin/env bash
# =============================================================================
# deploy/setup-gcp.sh
# One-time GCP infrastructure setup + secrets seeding for whatsapp-bot.
# Run this ONCE from your local machine with Owner/Editor access.
#
# Usage:
#   chmod +x deploy/setup-gcp.sh
#   ./deploy/setup-gcp.sh
# =============================================================================
set -euo pipefail

# ─── CONFIG — edit these before running ──────────────────────────────────────
PROJECT_ID="your-gcp-project-id"          # ← Replace
REGION="me-central1"                       # ← me-central1 = UAE (Dubai area). Change if needed.
DB_INSTANCE_NAME="whatsapp-bot-db"
DB_NAME="whatsapp_bot"
DB_USER="whatsapp_bot"
SERVICE_NAME="whatsapp-bot"
REPO_NAME="whatsapp-bot-repo"
SA_NAME="whatsapp-bot-sa"

# ─── Secret values — fill these in ───────────────────────────────────────────
WHATSAPP_VERIFY_TOKEN="your-webhook-verify-token"
WHATSAPP_ACCESS_TOKEN="your-meta-access-token"
GEMINI_API_KEY="your-gemini-api-key"
# DB_PASSWORD is auto-generated below

echo "============================================================"
echo "  WhatsApp Bot — GCP Setup"
echo "  Project : $PROJECT_ID"
echo "  Region  : $REGION"
echo "============================================================"

# ── 0. Set project ────────────────────────────────────────────────────────────
gcloud config set project "$PROJECT_ID"

# ── 1. Enable required APIs ───────────────────────────────────────────────────
echo "▶ Enabling GCP APIs..."
gcloud services enable \
  run.googleapis.com \
  sqladmin.googleapis.com \
  secretmanager.googleapis.com \
  artifactregistry.googleapis.com \
  cloudbuild.googleapis.com \
  vpcaccess.googleapis.com \
  servicenetworking.googleapis.com \
  --quiet

# ── 2. Create Artifact Registry repository ────────────────────────────────────
echo "▶ Creating Artifact Registry..."
gcloud artifacts repositories create "$REPO_NAME" \
  --repository-format=docker \
  --location="$REGION" \
  --description="WhatsApp bot Docker images" \
  --quiet 2>/dev/null || echo "   (already exists)"

# ── 3. Create service account ─────────────────────────────────────────────────
echo "▶ Creating service account..."
gcloud iam service-accounts create "$SA_NAME" \
  --display-name="WhatsApp Bot Runtime SA" \
  --quiet 2>/dev/null || echo "   (already exists)"

SA_EMAIL="${SA_NAME}@${PROJECT_ID}.iam.gserviceaccount.com"

# Grant roles to the service account
for ROLE in \
  "roles/cloudsql.client" \
  "roles/secretmanager.secretAccessor" \
  "roles/artifactregistry.reader"; do
  gcloud projects add-iam-policy-binding "$PROJECT_ID" \
    --member="serviceAccount:${SA_EMAIL}" \
    --role="$ROLE" \
    --quiet
done

# Grant Cloud Build permission to deploy to Cloud Run
CLOUDBUILD_SA="${PROJECT_NUMBER}@cloudbuild.gserviceaccount.com" 2>/dev/null || true
PROJECT_NUMBER=$(gcloud projects describe "$PROJECT_ID" --format="value(projectNumber)")
CLOUDBUILD_SA="${PROJECT_NUMBER}@cloudbuild.gserviceaccount.com"

for ROLE in \
  "roles/run.admin" \
  "roles/iam.serviceAccountUser" \
  "roles/artifactregistry.writer"; do
  gcloud projects add-iam-policy-binding "$PROJECT_ID" \
    --member="serviceAccount:${CLOUDBUILD_SA}" \
    --role="$ROLE" \
    --quiet
done

# ── 4. Create Cloud SQL (PostgreSQL 15 with pgvector) ─────────────────────────
echo "▶ Creating Cloud SQL instance (this takes ~5 minutes)..."
gcloud sql instances create "$DB_INSTANCE_NAME" \
  --database-version=POSTGRES_15 \
  --tier=db-g1-small \
  --region="$REGION" \
  --storage-type=SSD \
  --storage-size=20GB \
  --storage-auto-increase \
  --backup-start-time=03:00 \
  --maintenance-window-day=SUN \
  --maintenance-window-hour=5 \
  --database-flags=cloudsql.enable_pgvector=on \
  --quiet 2>/dev/null || echo "   (already exists)"

# Generate a strong DB password
DB_PASSWORD=$(openssl rand -base64 24 | tr -d '/+=' | head -c 24)

echo "▶ Setting DB user password..."
gcloud sql users set-password "$DB_USER" \
  --instance="$DB_INSTANCE_NAME" \
  --password="$DB_PASSWORD" \
  --quiet 2>/dev/null || \
gcloud sql users create "$DB_USER" \
  --instance="$DB_INSTANCE_NAME" \
  --password="$DB_PASSWORD" \
  --quiet

echo "▶ Creating database..."
gcloud sql databases create "$DB_NAME" \
  --instance="$DB_INSTANCE_NAME" \
  --quiet 2>/dev/null || echo "   (already exists)"

# Build Cloud SQL JDBC URL using Unix socket (Cloud Run best practice)
INSTANCE_CONNECTION_NAME=$(gcloud sql instances describe "$DB_INSTANCE_NAME" \
  --format="value(connectionName)")
DB_URL="jdbc:postgresql:///whatsapp_bot?cloudSqlInstance=${INSTANCE_CONNECTION_NAME}&socketFactory=com.google.cloud.sql.postgres.SocketFactory&ipTypes=PRIVATE"

# ── 5. Store secrets in Secret Manager ───────────────────────────────────────
echo "▶ Creating secrets in Secret Manager..."

create_or_update_secret() {
  local SECRET_NAME=$1
  local SECRET_VALUE=$2
  if gcloud secrets describe "$SECRET_NAME" --quiet 2>/dev/null; then
    echo "   Updating $SECRET_NAME"
    echo -n "$SECRET_VALUE" | gcloud secrets versions add "$SECRET_NAME" --data-file=-
  else
    echo "   Creating $SECRET_NAME"
    echo -n "$SECRET_VALUE" | gcloud secrets create "$SECRET_NAME" \
      --data-file=- \
      --replication-policy=automatic
  fi
}

create_or_update_secret "DB_URL"                  "$DB_URL"
create_or_update_secret "DB_USERNAME"             "$DB_USER"
create_or_update_secret "DB_PASSWORD"             "$DB_PASSWORD"
create_or_update_secret "WHATSAPP_VERIFY_TOKEN"   "$WHATSAPP_VERIFY_TOKEN"
create_or_update_secret "WHATSAPP_ACCESS_TOKEN"   "$WHATSAPP_ACCESS_TOKEN"
create_or_update_secret "GEMINI_API_KEY"          "$GEMINI_API_KEY"

# ── 6. First Docker build + push ─────────────────────────────────────────────
echo "▶ Configuring Docker auth for Artifact Registry..."
gcloud auth configure-docker "${REGION}-docker.pkg.dev" --quiet

IMAGE="${REGION}-docker.pkg.dev/${PROJECT_ID}/${REPO_NAME}/${SERVICE_NAME}:latest"

echo "▶ Building Docker image locally..."
docker build -t "$IMAGE" .

echo "▶ Pushing image..."
docker push "$IMAGE"

# ── 7. Initial Cloud Run deploy ───────────────────────────────────────────────
echo "▶ Deploying to Cloud Run..."
gcloud run deploy "$SERVICE_NAME" \
  --image="$IMAGE" \
  --region="$REGION" \
  --platform=managed \
  --allow-unauthenticated \
  --min-instances=1 \
  --max-instances=10 \
  --memory=1Gi \
  --cpu=1 \
  --concurrency=80 \
  --timeout=60s \
  --set-env-vars=SPRING_PROFILES_ACTIVE=prod \
  --set-secrets="DB_URL=DB_URL:latest,DB_USERNAME=DB_USERNAME:latest,DB_PASSWORD=DB_PASSWORD:latest,WHATSAPP_VERIFY_TOKEN=WHATSAPP_VERIFY_TOKEN:latest,WHATSAPP_ACCESS_TOKEN=WHATSAPP_ACCESS_TOKEN:latest,GEMINI_API_KEY=GEMINI_API_KEY:latest" \
  --service-account="$SA_EMAIL" \
  --add-cloudsql-instances="$INSTANCE_CONNECTION_NAME" \
  --quiet

# ── 8. Connect Cloud Build to GitHub ─────────────────────────────────────────
echo ""
echo "============================================================"
echo "  ✅  Infrastructure setup complete!"
echo "============================================================"
SERVICE_URL=$(gcloud run services describe "$SERVICE_NAME" \
  --region="$REGION" --format="value(status.url)")

echo ""
echo "  Cloud Run URL : $SERVICE_URL"
echo "  Webhook URL   : $SERVICE_URL/webhook"
echo ""
echo "  ⚠  Next steps:"
echo "  1. Go to Meta Developer Console and set the Webhook URL to:"
echo "     $SERVICE_URL/webhook"
echo "  2. Set Webhook verify token to: $WHATSAPP_VERIFY_TOKEN"
echo "  3. Connect your GitHub repo to Cloud Build:"
echo "     https://console.cloud.google.com/cloud-build/triggers"
echo "  4. Enable pgvector in Cloud SQL (see below)"
echo ""
echo "  DB password saved to Secret Manager (DB_PASSWORD)."
echo "  To retrieve it: gcloud secrets versions access latest --secret=DB_PASSWORD"
echo ""
echo "  🔑 Enable pgvector on Cloud SQL:"
echo "  gcloud sql connect $DB_INSTANCE_NAME --user=$DB_USER"
echo "  Then run: CREATE EXTENSION IF NOT EXISTS vector;"
echo "            CREATE EXTENSION IF NOT EXISTS pgcrypto;"
echo "============================================================"

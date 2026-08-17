# Google Cloud on-demand demo environment

This is the authoritative staging/demo deployment for the enterprise-control feature branch.

## Goal

Keep the React UI available on Cloudflare Pages, but run Spring Boot + PostgreSQL only when a demo/test session is needed.

```text
GitHub Actions: Start
  -> start Google Compute Engine VM
  -> PostgreSQL + Spring Boot via Docker Compose
  -> host cloudflared Quick Tunnel
  -> random *.trycloudflare.com backend URL
  -> rebuild Cloudflare Pages with that API URL
  -> print both URLs in the Actions summary

GitHub Actions: Stop
  -> stop Compute Engine VM
  -> CPU/RAM billing stops
  -> persistent disk/database/uploads remain
```

Pushes to `feature/enterprise-document-control` verify the backend and publish the latest `staging` image to GHCR, but do **not** start the VM.

## Important Quick Tunnel limitation

TryCloudflare Quick Tunnels are appropriate for development/demo use and require no Cloudflare account/token for the tunnel itself. The URL changes every time `cloudflared` starts. Quick Tunnels do not support Server-Sent Events (SSE), so live SSE features should be considered unavailable until a named tunnel/domain is introduced.

## Accounts

### Google Cloud

Use an existing Google Cloud account/project or create one. Billing must be attached to the project even when usage is tiny.

Enable Compute Engine API and create one Ubuntu VM. Recommended demo starting point:

- Ubuntu 24.04 LTS
- e2-medium (2 vCPU / 4 GB RAM)
- 30 GB persistent boot disk
- ephemeral external IPv4 is fine
- stop the VM when not testing

The VM can be resized later while stopped.

### Cloudflare

A Cloudflare account is needed only for Pages in this temporary architecture. A custom domain is **not** required.

Create one Pages project, for example `enterprise-control-demo`. The app will be available under a `pages.dev` hostname.

The backend Quick Tunnel is anonymous and does not require a Cloudflare API token.

## One-time VM bootstrap

Create a dedicated SSH key pair for GitHub Actions. Add the **public** key to the VM's SSH keys and store the **private** key only in the GitHub secret `DEMO_VM_SSH_PRIVATE_KEY`.

Connect to the VM once, copy `deploy/gcp/bootstrap.sh`, and run:

```bash
chmod +x bootstrap.sh
./bootstrap.sh
```

Log out and back in once after the script completes so Docker group membership takes effect.

The bootstrap installs Docker/Compose and `cloudflared`, then creates a systemd service named `enterprise-demo-tunnel`. That service starts a new Quick Tunnel every time the VM boots.

## Google authentication from GitHub Actions

The workflow intentionally uses Workload Identity Federation (WIF), not a long-lived Google API/service-account JSON key.

The repository already had a Cloud Run workflow using these names, so reuse them if they are already configured:

### GitHub repository secrets

`Yashu7372/whatsapp-bot` -> Settings -> Secrets and variables -> Actions -> Secrets

- `GCP_PROJECT_ID`
- `GCP_WORKLOAD_IDENTITY_PROVIDER`
- `GCP_SERVICE_ACCOUNT`

The GitHub deployment service account needs permission to start/stop/describe the demo VM. A project-level `roles/compute.instanceAdmin.v1` grant is sufficient for this demo control workflow; keep the WIF provider restricted to the `Yashu7372/whatsapp-bot` repository.

If WIF is not configured yet, follow Google/GitHub's OIDC/WIF setup and grant the GitHub principal `roles/iam.workloadIdentityUser` on the deployment service account.

## GitHub secrets and variables

### Backend repository secrets

Repository: `Yashu7372/whatsapp-bot`

Add under **Settings -> Secrets and variables -> Actions -> Secrets**:

- `GCP_PROJECT_ID`
- `GCP_WORKLOAD_IDENTITY_PROVIDER`
- `GCP_SERVICE_ACCOUNT`
- `DEMO_VM_SSH_PRIVATE_KEY`
- `DEMO_ENV_FILE`
- `CLOUDFLARE_API_TOKEN`
- `CLOUDFLARE_ACCOUNT_ID`

`DEMO_ENV_FILE` should contain the real version of `deploy/gcp/.env.example`.

The Gemini key belongs inside `DEMO_ENV_FILE` as:

```text
AI_GEMINI_API_KEY=...
```

Do not commit the Gemini key.

### Backend repository variables

Add under **Settings -> Secrets and variables -> Actions -> Variables**:

- `GCP_DEMO_ZONE` — example: `us-central1-a`
- `GCP_DEMO_VM_NAME` — example: `enterprise-control-demo`
- `DEMO_VM_SSH_USER` — Linux username associated with the VM SSH public key
- `CLOUDFLARE_PAGES_PROJECT` — example: `enterprise-control-demo`

## Cloudflare Pages API token

In Cloudflare, create an API token with the minimum Pages edit permission needed to deploy this project. Store it in the backend GitHub repository as `CLOUDFLARE_API_TOKEN`. Store the Cloudflare account ID as `CLOUDFLARE_ACCOUNT_ID`.

The same token/account can also be stored in the frontend repository if its standalone Pages workflow is used.

## Runtime application secrets

Start from `deploy/gcp/.env.example` and put the complete real content into the GitHub secret `DEMO_ENV_FILE`.

At minimum set strong values for:

- `POSTGRES_PASSWORD`
- `JWT_SECRET`
- `AI_GEMINI_API_KEY` if AI is enabled

WhatsApp can remain mocked initially:

```text
WHATSAPP_MOCK_SEND_ENABLED=true
```

When real WhatsApp integration is enabled later, replace the verify/access-token values in the same GitHub secret.

## Start / stop usage

Go to:

`Yashu7372/whatsapp-bot` -> Actions -> `Demo Environment - GCP` -> Run workflow

Choose:

- `start` — verifies/builds latest backend, starts VM, deploys containers, discovers the new Quick Tunnel URL, rebuilds the frontend against that URL, deploys Pages, and prints the demo links.
- `stop` — stops the VM. PostgreSQL data and uploaded files remain on persistent disk/Docker volumes.

No laptop process is involved.

## Normal development behavior

A normal push to the feature branch:

1. runs tests against PostgreSQL/pgvector;
2. builds the Spring Docker image;
3. publishes `ghcr.io/yashu7372/whatsapp-bot:staging`;
4. leaves the Google VM stopped.

When a demo is needed, run `start`. When finished, run `stop`.

## Later custom-domain migration

When the product is ready for a stable domain, replace the anonymous Quick Tunnel with a named Cloudflare Tunnel. The VM/Compose/PostgreSQL architecture does not need to change; only the tunnel/public URL wiring changes. A named tunnel also removes the Quick Tunnel SSE limitation.

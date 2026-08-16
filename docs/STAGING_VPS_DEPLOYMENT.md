# Always-on staging deployment

This staging target removes the laptop from the runtime path while keeping the architecture intentionally small.

## Runtime

```text
Internet
  -> Cloudflare hostname
  -> Cloudflare Tunnel
  -> Spring Boot :8080
  -> PostgreSQL/pgvector :5432 (private Docker network)

GitHub push
  -> GitHub Actions verify
  -> build Docker image
  -> push GHCR
  -> SSH to VM
  -> docker compose pull/up
  -> /actuator/health verification
```

PostgreSQL and uploaded documents use named Docker volumes and survive application redeployments.

The VM does not publish PostgreSQL. Spring Boot is bound only to `127.0.0.1:8080` for deployment health checks. Public application traffic enters through `cloudflared`.

## Recommended VM size

For staging/demo use, start with a small x86 Ubuntu VM around:

- 2 vCPU
- 4 GB RAM
- 40 GB disk

This is deliberately not a production HA topology. It is an inexpensive always-on environment for the enterprise-control vertical slice, WhatsApp webhook testing and AI/event-worker development.

## One-time VM bootstrap

Create an Ubuntu VM and add an SSH public key. Copy `deploy/vps/bootstrap.sh` to the VM and run it as the same account GitHub Actions will SSH as.

The script installs Docker + Compose, enables Docker, and prepares `~/enterprise-control`.

After it adds the account to the `docker` group, log out and back in once.

## Cloudflare Tunnel

Create a remotely-managed Cloudflare Tunnel and add a published application route:

```text
Hostname: api.<your-domain>
Service:  http://app:8080
```

The `cloudflared` container and Spring Boot container share the same Docker network, so `app` resolves by Compose service name.

Put the tunnel token into `VPS_ENV_FILE` as `CLOUDFLARE_TUNNEL_TOKEN`. Do not commit the token.

For the current React frontend, configure:

```text
VITE_API_BASE_URL=https://api.<your-domain>/api/v1
```

The frontend can later be deployed independently to Cloudflare Pages; it does not need to run on the VM.

## GitHub configuration

Create these repository Actions secrets on `Yashu7372/whatsapp-bot`:

- `VPS_HOST` - VM IPv4 address or DNS name
- `VPS_PORT` - optional; defaults to `22`
- `VPS_USER` - non-root SSH user
- `VPS_SSH_PRIVATE_KEY` - private key matching the VM authorized key
- `VPS_ENV_FILE` - full contents based on `deploy/vps/.env.example`

Then create this repository Actions variable:

```text
VPS_DEPLOY_ENABLED=true
```

Until that variable is enabled, pushes still verify but automatic deployment is skipped. This prevents a new branch commit from failing before the VM and secrets exist.

## Deployment behavior

`.github/workflows/deploy-staging-vps.yml` runs on pushes to `feature/enterprise-document-control` and can also be started manually.

The workflow:

1. starts a temporary pgvector PostgreSQL service in GitHub Actions;
2. runs `mvn clean verify` with Java 21;
3. builds the backend Docker image;
4. pushes immutable SHA and moving `staging` tags to GHCR;
5. copies the Compose file and runtime environment to the VM;
6. pulls/restarts PostgreSQL, Spring Boot and cloudflared;
7. waits for `/actuator/health`;
8. dumps container status/logs if startup fails.

The existing GCP Cloud Run deployment file is intentionally left unchanged.

## Persistence and backup

Persistent Docker volumes:

- `postgres_data`
- `app_uploads`

Before this environment contains anything important, add a small scheduled PostgreSQL dump to object storage. Staging data should still be treated as recoverable/disposable until backups are enabled.

## Event-driven AI direction

This deployment does not require Kafka or RabbitMQ. The next event-driven layer can remain inside PostgreSQL:

```text
business transaction
  -> business tables + domain-event/outbox row in the same transaction
  -> SKIP LOCKED worker
  -> deterministic workflow / AI trigger policy
  -> AI run / notification / WhatsApp
```

The VM is always on, so Spring scheduled workers and outbox consumers can run continuously without relying on an incoming HTTP request to wake the application.

# Project Control Local Docker + Cloudflare + WhatsApp E2E

This setup runs the clean Project Control product locally with:

- Java 21 `project-control-service`
- PostgreSQL 16
- the dedicated `project-control-ui` from `whatsapp-crm/feature/project-control-complete-ui`
- a named Cloudflare Tunnel for a stable HTTPS Meta webhook callback
- the bounded Project Control WhatsApp channel bridge

The legacy WhatsApp LLM/tool agent is not used by this flow. WhatsApp is a channel adapter to the same authorized Project Control assistant used by the web application.

## 1. Expected checkout layout

Keep the backend and UI repositories as siblings:

```text
workspace/
  whatsapp-bot/
  whatsapp-crm/
```

Use these branches:

```text
whatsapp-bot: feature/project-control-modulith-foundation
whatsapp-crm: feature/project-control-complete-ui
```

The Compose file builds the UI from `../whatsapp-crm/project-control-ui` by default.

## 2. Create the local environment file

From `whatsapp-bot`:

PowerShell:

```powershell
Copy-Item .env.project-control.example .env.project-control
```

Bash:

```bash
cp .env.project-control.example .env.project-control
```

Edit `.env.project-control` and set at least:

```text
PROJECT_CONTROL_POSTGRES_PASSWORD=<local password>

PROJECT_CONTROL_WHATSAPP_ENABLED=true
WHATSAPP_VERIFY_TOKEN=<private verify token you choose>
WHATSAPP_ACCESS_TOKEN=<Meta WhatsApp Cloud API token>
WHATSAPP_PHONE_NUMBER_ID=<Meta test/business phone-number ID>
WHATSAPP_API_VERSION=<the Graph API version used by your Meta app>
WHATSAPP_APP_SECRET=<Meta app secret; recommended>

PROJECT_CONTROL_WHATSAPP_USER_EMAIL=inspector@local.demo
PROJECT_CONTROL_WHATSAPP_USER_NUMBER=<your WhatsApp test recipient number>

CLOUDFLARE_TUNNEL_TOKEN=<named tunnel token>
CLOUDFLARE_PUBLIC_HOSTNAME=pc-meta.your-domain.com
```

`PROJECT_CONTROL_WHATSAPP_USER_NUMBER` is the human/test participant number that sends and receives the WhatsApp conversation. `WHATSAPP_PHONE_NUMBER_ID` is Meta's identifier for the WhatsApp business/test number that sends the messages.

Do not commit `.env.project-control`.

## 3. Create a stable Cloudflare Tunnel

Use a named/remotely-managed Cloudflare Tunnel rather than a Quick Tunnel. A Quick Tunnel hostname changes and is not appropriate as the long-lived Meta callback.

In Cloudflare:

1. Create or select a named Tunnel.
2. Copy its tunnel token into `CLOUDFLARE_TUNNEL_TOKEN`.
3. Add a published application/public hostname, for example:

```text
pc-meta.your-domain.com
```

4. Set the origin/service URL to:

```text
http://project-control-backend:8080
```

That service name works because the `cloudflared` container and backend are on the same Compose network.

The resulting Meta callback URL is:

```text
https://pc-meta.your-domain.com/webhooks/whatsapp
```

Only the webhook is anonymous in Spring Security. All normal `/api/**` Project Control resources remain authenticated even though they share the backend origin.

## 4. Start the complete stack

From `whatsapp-bot`:

```bash
docker compose --env-file .env.project-control -f docker-compose.project-control.yml --profile tunnel up --build
```

Or detached:

```bash
docker compose --env-file .env.project-control -f docker-compose.project-control.yml --profile tunnel up -d --build
```

Local endpoints:

```text
Project Control UI: http://localhost:5174
Backend:            http://localhost:8080
PostgreSQL:         localhost:5433
```

The UI and backend use the existing local Project Control credentials. All demo accounts use:

```text
Project123!
```

The Docker backend still uses the `local` profile so the demo identities bootstrap, but `DB_URL` overrides the local H2 default and points the application to the PostgreSQL container.

## 5. Configure Meta webhook

In the Meta WhatsApp app webhook configuration use:

```text
Callback URL: https://pc-meta.your-domain.com/webhooks/whatsapp
Verify token: <exact WHATSAPP_VERIFY_TOKEN from .env.project-control>
```

Subscribe the WhatsApp webhook to the `messages` field.

If `WHATSAPP_APP_SECRET` is configured, POST callbacks must contain a valid `X-Hub-Signature-256` signature. Keep this enabled for the real Meta callback.

## 6. E2E reviewer test

The default channel binding is:

```text
inspector@local.demo -> PROJECT_CONTROL_WHATSAPP_USER_NUMBER
```

1. Open `http://localhost:5174`.
2. Sign in as `admin@local.demo` / `Project123!` and create the demo project/workflow if it is not already present.
3. Advance the document workflow through Site Team, QCE and QC/DC until it reaches `Consultant Inspector Review`.
4. The backend scan resolves the current workflow-step assignment through `ProjectAccessService`.
5. When `inspector@local.demo` is actually authorized for that step, the same `ProjectControlAssistantService` creates the reviewer brief.
6. The WhatsApp adapter sends that brief to `PROJECT_CONTROL_WHATSAPP_USER_NUMBER` and stores the workflow as that channel identity's active context.
7. Reply from WhatsApp with a question such as:

```text
What changed in this revision and what should I check first?
```

8. Meta calls the Cloudflare URL, the webhook resolves the bound Project Control user and active workflow, and the bounded assistant answers using only that authorized workflow/document/evidence context.

A WhatsApp message never calls the workflow action service. Text such as `approve it` is treated only as a question/input to the read-only assistant; approval/rejection/certification must still pass through the deterministic authenticated Project Control application flow.

To receive a notification on the first workflow step instead, set:

```text
PROJECT_CONTROL_WHATSAPP_USER_EMAIL=site@local.demo
```

and restart the backend.

## 7. AI mode

The stack works without an LLM. With:

```text
PROJECT_CONTROL_AI_ENABLED=false
```

the reviewer gets the deterministic evidence-only brief.

To enable the bounded reasoning worker, set its provider values:

```text
PROJECT_CONTROL_AI_ENABLED=true
PROJECT_CONTROL_AI_BASE_URL=https://api.openai.com/v1
PROJECT_CONTROL_AI_API_KEY=<key>
PROJECT_CONTROL_AI_MODEL=<model>
```

The reasoning worker still receives no Project Control tools and has no authority to mutate workflow, verification, quantity, certification or payment state.

## 8. Useful commands

Follow backend and tunnel logs:

```bash
docker compose --env-file .env.project-control -f docker-compose.project-control.yml logs -f project-control-backend cloudflared
```

See service status:

```bash
docker compose --env-file .env.project-control -f docker-compose.project-control.yml ps
```

Stop while preserving PostgreSQL data:

```bash
docker compose --env-file .env.project-control -f docker-compose.project-control.yml down
```

Reset the local Project Control database completely:

```bash
docker compose --env-file .env.project-control -f docker-compose.project-control.yml down -v
```

## 9. Public URL troubleshooting

Verify the tunnel first. A request to the public hostname must reach the backend. Meta verification then calls the webhook with its `hub.mode`, `hub.verify_token` and `hub.challenge` values; the backend returns the challenge only when the token matches.

If Meta verification fails, check:

1. `cloudflared` is connected.
2. The Cloudflare public hostname route targets `http://project-control-backend:8080`.
3. The callback path is exactly `/webhooks/whatsapp`.
4. Meta's verify token exactly matches `WHATSAPP_VERIFY_TOKEN`.
5. The backend container has restarted after environment changes.

If outbound WhatsApp delivery fails, check `WHATSAPP_ACCESS_TOKEN`, `WHATSAPP_PHONE_NUMBER_ID`, `WHATSAPP_API_VERSION` and that the test participant number is allowed in the Meta test-number configuration.

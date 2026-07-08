# End-to-end test — `speedwheels` tenant (mock webhook → dashboard → agent handover)

Every command in this document was executed and verified against a fresh
database. No Meta account, no AI API key, and no Express server are needed —
the WhatsApp send is mocked (`WHATSAPP_MOCK_SEND_ENABLED=true`) and the AI
layer degrades to a safe fallback reply when no provider is reachable.

---

## 1. Prerequisites

- Java 21, Maven 3.9, Node 20+
- Postgres 16 with the `vector` and `pgcrypto` extensions available
  (`pgvector/pgvector:pg16` Docker image, or `postgresql-16-pgvector` apt package)

One-time database setup (skip if you use `docker compose up postgres`, which
does this for you):

```sql
CREATE ROLE whatsapp_bot LOGIN PASSWORD 'whatsapp_bot';
CREATE DATABASE whatsapp_bot OWNER whatsapp_bot;
\c whatsapp_bot
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
```

## 2. Start the backend (whatsapp-bot)

```bash
cd whatsapp-bot
mvn -DskipTests package

SPRING_PROFILES_ACTIVE=local \
DB_URL="jdbc:postgresql://localhost:5432/whatsapp_bot" \
DB_USERNAME=whatsapp_bot DB_PASSWORD=whatsapp_bot \
WHATSAPP_VERIFY_TOKEN=local-verify-token \
WHATSAPP_ACCESS_TOKEN=local-dev-token \
WHATSAPP_MOCK_SEND_ENABLED=true \
java -jar target/whatsapp-bot-phase1-0.0.1-SNAPSHOT.jar
```

Flyway applies V1–V23 on first boot, seeding:
- tenant `speedwheels` (active) with `phone_number_id = REPLACE_WITH_META_PHONE_NUMBER_ID`
- dashboard login `admin@speedwheels.com` / `admin123` (V21)
- two live-chat agents, `agent@speedwheels.com` and `manager@speedwheels.com` (V23)

Health check: `curl http://localhost:8080/actuator/health` → `{"status":"UP"}`

## 3. Start the frontend (whatsapp-crm)

```bash
cd whatsapp-crm
npm install
npm run dev        # http://localhost:5173 — proxies /api/v1 to :8080
```

**Dashboard URL: http://localhost:5173** (open from browser or phone on the
same network — use `http://<your-machine-ip>:5173` for mobile and start Vite
with `npm run dev -- --host`).

## 4. Webhook verification handshake (what Meta does)

```bash
curl "http://localhost:8080/webhook?hub.mode=subscribe&hub.verify_token=local-verify-token&hub.challenge=12345"
# → 12345
```

## 5. Simulate a customer message (mock webhook)

The fixture uses the seeded `phone_number_id`. If you changed it (dashboard →
Settings → Webhook / workspace), update `metadata.phone_number_id` in the
fixture to match — that field is how the tenant is resolved.

```bash
curl -X POST http://localhost:8080/webhook \
  -H "Content-Type: application/json" \
  -d @docs/e2e/mock-webhook-text.json
# → 200 (always — the payload is queued in webhook_outbox and processed async)
```

Wait ~2 seconds for the outbox processor, then verify persistence:

```sql
-- webhook accepted and processed
select status, retry_count from webhook_outbox order by created_at desc limit 1;
-- contact created
select display_name, wa_id from contacts where wa_id = '971529999001';
-- conversation created
select c.status, c.bot_enabled, c.unread_count
from conversations c join contacts ct on ct.id = c.contact_id
where ct.wa_id = '971529999001';
-- inbound + outbound messages persisted
select direction, message_type, ai_generated, left(text_body, 60)
from messages m
join conversations c on c.id = m.conversation_id
join contacts ct on ct.id = c.contact_id
where ct.wa_id = '971529999001' order by m.created_at;
```

Expected: outbox `DONE`, contact `E2E Test Customer`, conversation `ACTIVE`,
one `INBOUND` text and one `OUTBOUND` `ai_generated=true` reply. (Without an
AI key the reply is the fallback text; set `AI_PROVIDER`/key for real replies.)

## 6. Simulate a customer-triggered handover

A non-text message pauses the bot and requests a human:

```bash
curl -X POST http://localhost:8080/webhook \
  -H "Content-Type: application/json" \
  -d @docs/e2e/mock-webhook-handover.json
```

The conversation flips to `REQUESTING` / `bot_enabled=false` (shown as
**human** in the dashboard). Customers typing "I want to talk to a human" do
the same via the AI's `HUMAN_HANDOFF_REQUIRED` contract when a provider is
configured.

## 7. Dashboard / agent flow via API

```bash
# login
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@speedwheels.com","password":"admin123"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")

# conversations visible in dashboard inbox
curl -s http://localhost:8080/api/v1/crm/conversations -H "Authorization: Bearer $TOKEN"

# messages inside the conversation (oldest first)
curl -s http://localhost:8080/api/v1/crm/conversations/<CONV_ID>/messages -H "Authorization: Bearer $TOKEN"

# manual takeover (bot → human)
curl -s -X PUT http://localhost:8080/api/v1/crm/conversations/<CONV_ID>/status \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"status":"human"}'

# list agents, then assign one
curl -s http://localhost:8080/api/v1/crm/agents -H "Authorization: Bearer $TOKEN"
curl -s -X POST http://localhost:8080/api/v1/crm/conversations/<CONV_ID>/assign \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"agentId":"<AGENT_ID>","notes":"taking this one"}'

# agent replies to the customer (mock send — check app log for MOCK WhatsApp text send)
curl -s -X POST http://localhost:8080/api/v1/crm/conversations/<CONV_ID>/send \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"message":"Hello! This is Sam from SpeedWheels."}'

# resolve back to the bot
curl -s -X PUT http://localhost:8080/api/v1/crm/conversations/<CONV_ID>/status \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"status":"bot"}'
```

Audit trail:

```sql
select event_type, from_agent_id, to_agent_id, notes, created_at
from conversation_events order by created_at;
```

`AGENT_ASSIGNED` rows are written on every assignment; intervene/resolve
through `/api/tenants/{phoneNumberId}/conversations/...` also write
`BOT_DISABLED` / `RESOLVED` / `BOT_ENABLED` events.

## 8. Same flow in the browser

1. Open http://localhost:5173 → log in as `admin@speedwheels.com` / `admin123`.
2. **Inbox** shows the `E2E Test Customer` conversation with the message thread.
3. **Take Over** pauses the bot (status → human); the yellow banner appears.
4. **Assign to agent…** dropdown assigns the conversation; the banner shows the assignee.
5. Type a reply — it is persisted as an agent message (👤 Agent) and mock-sent.
6. **Hand to Bot** returns the conversation to the bot.

## 9. Verification checklist

- [x] `GET /webhook` handshake echoes challenge
- [x] `POST /webhook` always 200, payload queued in `webhook_outbox`, processed to `DONE`
- [x] Tenant resolved by `phone_number_id` → `speedwheels`
- [x] Contact auto-created on first message
- [x] Conversation auto-created, `ACTIVE`, unread incremented
- [x] Inbound + AI outbound messages persisted (`wa_message_id` dedup on retry)
- [x] Dashboard login (JWT) works; all CRM queries tenant-scoped
- [x] Conversations, messages, contacts, stats endpoints return the new data
- [x] Non-text message auto-flips conversation to human (`REQUESTING`)
- [x] Manual takeover, agent assignment (+ `conversation_events` audit), agent reply, hand-back-to-bot
- [x] Assigned agent visible in API response and dashboard banner

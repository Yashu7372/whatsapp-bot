# Local reel renderer

This service renders deterministic 1080x1920 MP4 reels and local two-character dialogue videos.
It uses FFmpeg for composition, Kokoro for local TTS, and optionally SadTalker for audio-driven lip-sync.

## Dialogue render modes

`RENDER_MODE=auto` is recommended.

AUTO selects in this order:

1. `dynamic` — Kokoro TTS + SadTalker lip-sync for the active speaker + the other character kept visible as listener.
2. `video-templates` — reuses uploaded animated MP4 templates if SadTalker is unavailable.
3. `static-sprites` — uses the generated PNG character pack as the final fallback.

The dynamic path measures the real generated WAV duration instead of trusting the LLM duration estimate.

## Important endpoints

Renderer:

- `GET /health`
- `GET /v2/dialogue/capabilities`
- `POST /v2/dialogue/render`
- `GET /v2/dialogue/render/{jobId}`
- `POST /v1/render`

SadTalker worker:

- `GET /health`
- `POST /v1/photos/{character}` where character is `bhaiya` or `chitti`
- `GET /v1/photos/status`
- `GET /v1/photos/{character}`
- `POST /v1/animate?character={character}`

## Local Docker run

From repository root:

```bash
git checkout claude/whatsapp-bot-ai-reel-tv5hii
git pull

docker compose --profile sadtalker up --build postgres sadtalker renderer app
```

The first SadTalker startup downloads model checkpoints and can take substantially longer than later starts.

Check services:

```bash
curl http://localhost:8091/health
curl http://localhost:8090/health
curl http://localhost:8090/v2/dialogue/capabilities
```

## Upload the two fixed character photos once

Use clear front-facing photos. These photos are persisted in the Docker volume and reused for every episode.

```bash
curl -X POST http://localhost:8091/v1/photos/bhaiya \
  -F "photo=@/absolute/path/to/bhaiya.jpg"

curl -X POST http://localhost:8091/v1/photos/chitti \
  -F "photo=@/absolute/path/to/chitti.jpg"

curl http://localhost:8091/v1/photos/status
```

When both photos and SadTalker models are ready, this should report dynamic rendering as ready:

```bash
curl http://localhost:8090/v2/dialogue/capabilities
```

## Optional background

The dialogue renderer uses this file when present:

```text
/data/character_pack/scene/background.png
```

The existing character-pack generator can create it, or you can place a fixed background into the character-pack volume.

## Spring Boot dialogue flow

The Spring application exposes the dialogue API under:

```text
/api/v1/video/dialogue
```

Generate a script first, then submit that returned script for rendering. Spring now sends the render request to `/v2/dialogue/render` on the renderer.

The renderer persists lightweight dialogue status JSON files under `/data/renders/.dialogue-status`, so status survives a renderer process restart as long as the render volume is retained.

## Ollama (optional local LLM)

If the application is configured with `AI_PROVIDER=OLLAMA`, start the Ollama profile too:

```bash
docker compose --profile sadtalker --profile ollama up --build
```

Then pull the configured model once, for example:

```bash
docker compose exec ollama ollama pull llama3.1:8b
```

Use the exact model name configured by the Spring application if it differs.

## Output

Uploaded media is mounted read-only at `/data/storage` and generated MP4 files are written under `/data/renders`.
The Spring container shares the render volume, so completed files can be served through the application APIs.

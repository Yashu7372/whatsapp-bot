# Local Gemma 4 + Reel Studio

## Prerequisites

- Docker Desktop with WSL2 integration
- Current NVIDIA driver
- NVIDIA Container Toolkit / Docker GPU support
- At least 15 GB free disk space for images, the Gemma model and cached TTS files

## Start

```bash
cp .env.local-ai.example .env
docker compose up --build
```

The `ollama-init` container downloads `gemma4:e2b-it-qat` once into the persistent
`whatsapp_bot_ollama_data` volume. The application waits for that bootstrap to
complete, then uses Ollama at `http://ollama:11434`. The renderer is available at
`http://localhost:8090`.

Verify:

```bash
docker compose ps
docker compose exec ollama ollama ps
curl http://localhost:8090/health
curl http://localhost:8080/actuator/health
```

## Frontend

Run `whatsapp-crm` normally:

```bash
npm install
npm run dev
```

Open `/content-studio`. Vite proxies legacy `/api` calls to the existing Express
dev server and `/api/v1` calls to Spring Boot on port 8080.

For the seeded local tenant, the Content Studio automatically authenticates with:

- email: `admin@demo.com`
- password: `admin123`

Override these in the frontend environment when needed:

```bash
VITE_BACKEND_EMAIL=admin@demo.com
VITE_BACKEND_PASSWORD=admin123
```

## Workflow

1. Discover topics from YouTube when `YOUTUBE_API_KEY` is configured; otherwise
   Google Trends RSS is used. If neither returns data, AI-estimated topics are
   clearly stored as `AI Estimate`.
2. Generate a reel script through the configured LangChain4j `ChatModel`.
3. Optionally search Pexels/Pixabay when their API keys are configured.
4. Choose a fixed template and queue a render job.
5. Spring Boot claims queued jobs with `FOR UPDATE SKIP LOCKED`.
6. The renderer composes 1080x1920 scenes with FFmpeg and generates local speech
   with Kokoro. If Kokoro fails to initialize, the MP4 still renders with silent audio.
7. Download the completed MP4 from the Content Studio.

## Useful commands

```bash
# Check GPU visibility
docker compose exec ollama nvidia-smi

# List/pull models
docker compose exec ollama ollama list
docker compose exec ollama ollama pull gemma4:e2b-it-qat

# Watch application and renderer logs
docker compose logs -f app renderer ollama

# Rebuild only the renderer
docker compose up -d --build renderer
```

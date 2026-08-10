# Local reel renderer

This service renders deterministic 1080x1920 MP4 reels from fixed templates.
It uses FFmpeg for video composition and Kokoro for local text-to-speech.

Endpoints:

- `GET /health`
- `POST /v1/render`

The Spring Boot application submits queued render jobs. Uploaded media is mounted
read-only at `/data/storage`; generated MP4 files are written under `/data/renders`.
If Kokoro cannot load its model, rendering still completes with silent audio and a warning.

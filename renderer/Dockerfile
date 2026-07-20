FROM python:3.12-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app.py .

RUN useradd --create-home --uid 10001 renderer \
    && chown -R renderer:renderer /app

USER renderer

ENV PORT=8090 \
    PYTHONUNBUFFERED=1 \
    KOKORO_BASE_URL=http://kokoro:8880 \
    RENDER_TIMEOUT_SECONDS=240

EXPOSE 8090

CMD ["sh", "-c", "uvicorn app:app --host 0.0.0.0 --port ${PORT:-8090}"]

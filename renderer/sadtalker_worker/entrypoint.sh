#!/bin/bash
set -e

CKPT_DIR="/opt/SadTalker/checkpoints"
GFPGAN_DIR="/opt/SadTalker/gfpgan/weights"

# Download model checkpoints on first run, persist in mounted volume.
# Subsequent container starts skip this entirely (files already present).
if [ ! -f "${CKPT_DIR}/SadTalker_V0.0.2_256.safetensors" ]; then
    echo "==> SadTalker: downloading model checkpoints (~4 GB, one-time only)..."
    mkdir -p "${CKPT_DIR}" "${GFPGAN_DIR}"
    cd /opt/SadTalker
    bash scripts/download_models.sh
    echo "==> SadTalker: models ready."
else
    echo "==> SadTalker: models already present, skipping download."
fi

exec uvicorn main:app --host 0.0.0.0 --port 8091 --app-dir /app

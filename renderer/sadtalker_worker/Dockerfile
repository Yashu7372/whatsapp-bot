FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    SADTALKER_DIR=/opt/SadTalker

RUN apt-get update && apt-get install -y --no-install-recommends \
    git wget ffmpeg curl \
    libgl1-mesa-glx libglib2.0-0 libsm6 libxext6 libxrender-dev \
    libgomp1 \
    && rm -rf /var/lib/apt/lists/*

# Clone SadTalker (pinned commit for reproducibility)
RUN git clone --depth 1 https://github.com/OpenTalker/SadTalker /opt/SadTalker

WORKDIR /opt/SadTalker

# PyTorch CPU — lighter than GPU build, sufficient for this use case
RUN pip install --no-cache-dir \
    torch==2.1.2 torchvision==0.16.2 torchaudio==2.1.2 \
    --index-url https://download.pytorch.org/whl/cpu

# SadTalker requirements (minus torch which we already installed)
RUN grep -v "^torch" requirements.txt | pip install --no-cache-dir -r /dev/stdin

# FastAPI wrapper
RUN pip install --no-cache-dir "fastapi>=0.115,<1" "uvicorn[standard]>=0.34,<1" "python-multipart>=0.0.9,<1"

COPY main.py /app/main.py
COPY entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

EXPOSE 8091
CMD ["/app/entrypoint.sh"]

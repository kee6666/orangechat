FROM python:3.12-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential libpq-dev && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY backend/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt

COPY backend/ /app/
COPY frontend_dist/ /app/frontend/dist/
COPY launcher.py /app/launcher.py
COPY oauth_shim.py /app/oauth_shim.py

ENV PYTHONUNBUFFERED=1 \
    HOST=0.0.0.0 \
    WEB_PORT=8233 \
    SNAPSHOT_DIR=/app/snapshots \
    NOCTURNE_SKIP_BUILD=1

RUN mkdir -p /app/snapshots /app/backups
EXPOSE 7860
CMD ["python", "/app/launcher.py"]

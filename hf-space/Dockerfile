FROM python:3.12-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential curl libpq-dev && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY backend/requirements.txt /app/
RUN pip install --no-cache-dir -r /app/requirements.txt

COPY backend/ /app/
COPY frontend/ /frontend_src/

RUN curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
    && apt-get install -y --no-install-recommends nodejs && rm -rf /var/lib/apt/lists/*

ENV PYTHONUNBUFFERED=1 \
    HOST=0.0.0.0 \
    WEB_PORT=8233 \
    SNAPSHOT_DIR=/app/snapshots

RUN mkdir -p /app/snapshots /app/backups
EXPOSE 7860

CMD ["python", "/app/launcher.py"]

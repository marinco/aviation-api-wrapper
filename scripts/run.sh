#!/usr/bin/env bash
# Builds and starts the full stack (app + Redis), waits until the app is ready.
#   scripts/run.sh             build and start
#   scripts/run.sh --no-build  start an image that already exists ($APP_IMAGE)
#   scripts/run.sh down        stop and remove
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ "${1:-}" == "down" ]]; then
  docker compose down
  exit 0
fi

if [[ "${1:-}" == "--no-build" ]]; then
  docker compose up -d --no-build
else
  docker compose up -d --build
fi

echo "Waiting for the app to be ready..."
for _ in $(seq 1 60); do
  if curl -fsS http://127.0.0.1:8081/actuator/health/readiness >/dev/null 2>&1; then
    echo "Ready."
    echo "  Swagger UI: http://localhost:8080/swagger-ui.html"
    echo "  Try:        curl -i http://localhost:8080/api/v1/airports/KJFK"
    exit 0
  fi
  sleep 2
done

echo "App did not become ready in time. Logs:" >&2
docker compose logs app >&2
exit 1

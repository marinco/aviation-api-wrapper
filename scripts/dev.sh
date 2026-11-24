#!/usr/bin/env bash
# Starts Redis in Docker and runs the app locally with the dev profile.
#   scripts/dev.sh        start Redis, then ./mvnw spring-boot:run
#   scripts/dev.sh down   stop Redis
set -euo pipefail
cd "$(dirname "$0")/.."

COMPOSE=(docker compose -f docker-compose.dev.yml)

if [[ "${1:-}" == "down" ]]; then
  "${COMPOSE[@]}" down
  exit 0
fi

"${COMPOSE[@]}" up -d --wait
echo "Redis is up on 127.0.0.1:6379. Starting the app..."
exec ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

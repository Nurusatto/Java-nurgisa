#!/usr/bin/env bash
set -euo pipefail
task_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$task_root"
if [[ ! -f .env ]]; then
  printf 'Create .env from .env.example first.\n' >&2
  exit 1
fi
set -a
source .env
set +a
case "${1:-help}" in
  db) docker compose up -d --wait postgres ;;
  backend) exec java -jar fixflow-backend/target/fixflow-backend-0.1.0-SNAPSHOT.jar ;;
  desktop) exec ./mvnw -pl fixflow-desktop javafx:run ;;
  verify) exec ./mvnw -B -ntp verify ;;
  *) printf 'Usage: scripts/dev-start.sh db|backend|desktop|verify\n' ;;
esac

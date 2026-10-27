#!/usr/bin/env bash
# Switch to a new empty demo database; old databases are deliberately retained.
set -euo pipefail
task_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$task_root"
if [[ "${1:-}" != --new-demo-database || ! -f .env ]]; then
  printf 'Stop backend, then run: scripts/dev-reset.sh --new-demo-database\n' >&2
  exit 1
fi
set -a
source .env
set +a
if [[ "${SPRING_PROFILES_ACTIVE:-}" != dev || "${FIXFLOW_DEMO_ENABLED:-}" != true || "${POSTGRES_DB:-}" != fixflow_demo* ]]; then
  printf 'Reset is restricted to the explicit dev profile and fixflow_demo* databases.\n' >&2
  exit 1
fi
task_new_database="fixflow_demo_$(date -u +%Y%m%d_%H%M%S)"
docker compose exec -T postgres createdb -U "$POSTGRES_USER" "$task_new_database"
mkdir -p .local/reset-backups
cp -- .env ".local/reset-backups/${task_new_database}.env"
python3 - "$task_new_database" <<'PY'
from pathlib import Path
import re, sys
path = Path('.env')
text = path.read_text()
if not re.search(r'^POSTGRES_DB=', text, flags=re.M):
    raise SystemExit('POSTGRES_DB is missing; .env was not changed')
path.write_text(re.sub(r'^POSTGRES_DB=.*$', 'POSTGRES_DB=' + sys.argv[1], text, flags=re.M))
PY
printf 'New demo database: %s. Old data retained. Restart backend to apply migrations and seed.\n' "$task_new_database"

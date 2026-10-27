#!/usr/bin/env bash
set -euo pipefail
task_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
cd "$task_root"

mode=${1:-all}
case "$mode" in
  all|--desktop-only|--services-only|--stop) ;;
  -h|--help)
    printf 'Usage: ./start.sh [--desktop-only|--services-only|--stop]\n'
    exit 0 ;;
  *) printf 'Unknown option: %s\n' "$mode" >&2; exit 1 ;;
esac
if [[ $# -gt 1 ]]; then printf 'Use one option at a time.\n' >&2; exit 1; fi

if [[ "$mode" == --stop ]]; then
  exec docker compose stop
fi
if [[ ! -f .env ]]; then cp .env.example .env; fi
# Import only launcher settings as literal values; never execute the .env file.
task_settings=$(python3 - <<'PY'
import os
from pathlib import Path
import re
keys = {'JAVA_HOME', 'MAVEN_USER_HOME', 'MAVEN_OPTS', 'SERVER_PORT', 'FIXFLOW_API_URL'}
for line in Path('.env').read_text(encoding='utf-8-sig').splitlines():
    match = re.match(r'^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$', line)
    if not match or match[1] not in keys or match[1] in os.environ:
        continue
    value = match[2].strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
        value = value[1:-1]
    else:
        value = re.split(r'\s+#', value, maxsplit=1)[0].rstrip()
    print(match[1] + '=' + value)
PY
)
if [[ -n "$task_settings" ]]; then
  while IFS= read -r task_setting; do export "$task_setting"; done <<< "$task_settings"
fi

if [[ "$mode" != --services-only ]]; then
  if [[ -z "${DISPLAY:-}" ]]; then
    printf 'Run this command in a graphical Linux session with X11/XWayland.\n' >&2
    exit 1
  fi
  task_java=java
  task_javac=javac
  if [[ -n "${JAVA_HOME:-}" ]]; then
    task_java="$JAVA_HOME/bin/java"
    task_javac="$JAVA_HOME/bin/javac"
  fi
  if ! "$task_java" --version 2>/dev/null | grep -Eq '^(openjdk|java) 21([. ]|$)' \
      || ! "$task_javac" --version 2>/dev/null | grep -Eq '^javac 21([. ]|$)'; then
    printf 'Install JDK 21 or set JAVA_HOME to its directory, then retry.\n' >&2
    exit 1
  fi
fi

if [[ "$mode" != --desktop-only ]]; then
  if ! docker info >/dev/null 2>&1; then
    printf 'Docker daemon is unavailable. Start Docker Engine/Desktop, then retry.\n' >&2
    printf 'For an already running backend, use ./start.sh --desktop-only.\n' >&2
    exit 1
  fi
  docker compose up -d --build --wait --wait-timeout 240 backend
  if [[ "$mode" == --services-only ]]; then exit 0; fi
  FIXFLOW_API_URL="http://$(docker compose port backend 8080)"
fi
export FIXFLOW_API_URL="${FIXFLOW_API_URL:-http://127.0.0.1:${SERVER_PORT:-8080}}"
curl --fail --silent --show-error --connect-timeout 3 --max-time 10 \
  "${FIXFLOW_API_URL%/}/actuator/health/readiness" >/dev/null
./mvnw -B -ntp -pl fixflow-desktop -am -DskipTests install
exec ./mvnw -pl fixflow-desktop javafx:run

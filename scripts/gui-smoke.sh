#!/usr/bin/env bash
set -euo pipefail
task_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$task_root"
if [[ -f .env ]]; then
  set -a
  source .env
  set +a
fi
: "${FIXFLOW_DEMO_PASSWORD:?Set the local demo password}"
task_classpath=$(mktemp)
trap 'rm -f "$task_classpath"' EXIT
./mvnw -B -ntp -pl fixflow-desktop test-compile dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile="$task_classpath"
java -cp "fixflow-desktop/target/test-classes:fixflow-desktop/target/classes:$(cat "$task_classpath")" com.fixflow.desktop.GuiSmoke

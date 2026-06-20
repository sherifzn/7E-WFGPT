#!/bin/bash

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
LOCAL_DIR="$ROOT_DIR/.local-dev"
VALIDATION_DATA="$LOCAL_DIR/validation-data-$$"

cd "$ROOT_DIR" || exit 1
mkdir -p "$LOCAL_DIR/logs"

run() {
  echo
  echo "== $1 =="
  shift
  "$@" || exit 1
}

run "Frontend formatting" npx prettier --check frontend
run "Frontend lint" npm run lint --workspace frontend
run "Frontend tests" npm run test --workspace frontend
run "Frontend production build" npm run build --workspace frontend
run "Backend and architecture tests" mvn -Dmaven.repo.local="$LOCAL_DIR/m2" -pl backend,architecture-tests -am test

echo
echo "== Local API smoke test =="
rm -rf "$VALIDATION_DATA"
mkdir -p "$VALIDATION_DATA"
java -Dworkflow.http.port=8080 -Dworkflow.local.data.dir="$VALIDATION_DATA" \
  -cp "backend/target/classes:domain/target/classes:contracts/target/classes:adapters/target/classes" \
  com.sevenewf.workflow.backend.BackendApplication >"$LOCAL_DIR/logs/validation-backend.log" 2>&1 &
BACKEND_PID=$!
cleanup() { kill "$BACKEND_PID" 2>/dev/null || true; rm -rf "$VALIDATION_DATA"; }
trap cleanup EXIT
for _ in $(seq 1 20); do
  curl --silent --fail http://127.0.0.1:8080/health >/dev/null 2>&1 && break
  sleep 1
done
if ! curl --silent --fail http://127.0.0.1:8080/health >/dev/null 2>&1; then
  echo "Local API smoke test could not bind to 127.0.0.1:8080."
  echo "If the message is 'Operation not permitted' inside Codex, it is the known sandbox-only socket restriction."
  exit 1
fi
curl --silent --fail http://127.0.0.1:8080/api/key-handovers >/dev/null
echo "Local API smoke test passed."
run "Full validation" npm run validate

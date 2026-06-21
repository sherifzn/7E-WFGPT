#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
MAVEN_REPOSITORY="${MAVEN_REPOSITORY:-/tmp/7e-wfgpt-m2}"

cd "$ROOT"
mkdir -p "$MAVEN_REPOSITORY"
mvn -Dmaven.repo.local="$MAVEN_REPOSITORY" -pl backend -am package -DskipTests
java -Dworkflow.http.port=8080 -cp "backend/target/classes:domain/target/classes:contracts/target/classes" com.sevenewf.workflow.backend.BackendApplication &
BACKEND_PID=$!
trap 'kill "$BACKEND_PID" 2>/dev/null || true' EXIT INT TERM

echo "Local Key Handover demo: http://localhost:5173"
echo "Synthetic local data only. Press Ctrl+C to stop."
npm run dev --workspace frontend -- --host 127.0.0.1

#!/bin/bash

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
LOCAL_DIR="$ROOT_DIR/.local-dev"
PID_DIR="$LOCAL_DIR/pids"
LOG_DIR="$LOCAL_DIR/logs"
DATA_DIR="$LOCAL_DIR/data"
BACKEND_PID_FILE="$PID_DIR/backend.pid"
FRONTEND_PID_FILE="$PID_DIR/frontend.pid"
BACKEND_URL="http://127.0.0.1:8080"
FRONTEND_URL="http://127.0.0.1:5173"

mkdir -p "$PID_DIR" "$LOG_DIR" "$DATA_DIR"

running_pid() {
  [ -f "$1" ] && [[ "$(cat "$1")" =~ ^[0-9]+$ ]] && kill -0 "$(cat "$1")" >/dev/null 2>&1
}

if running_pid "$BACKEND_PID_FILE" || running_pid "$FRONTEND_PID_FILE"; then
  echo "The local development environment is already running."
  echo "Frontend: $FRONTEND_URL"
  echo "Backend:  $BACKEND_URL"
  read -r -p "Press Return to close this window."
  exit 0
fi
rm -f "$BACKEND_PID_FILE" "$FRONTEND_PID_FILE"

if ! command -v java >/dev/null 2>&1; then
  echo "Java 21 or newer is required. Install it, then try again."
  read -r -p "Press Return to close this window."
  exit 1
fi
JAVA_VERSION="$(java -version 2>&1 | awk -F '[\".]' '/version/ {print $2; exit}')"
if ! [[ "$JAVA_VERSION" =~ ^[0-9]+$ ]] || [ "$JAVA_VERSION" -lt 21 ]; then
  echo "Java 21 or newer is required. Found: ${JAVA_VERSION:-unknown}."
  read -r -p "Press Return to close this window."
  exit 1
fi
if ! command -v node >/dev/null 2>&1 || ! command -v npm >/dev/null 2>&1; then
  echo "Node.js and npm are required. Install Node.js 20 or newer, then try again."
  read -r -p "Press Return to close this window."
  exit 1
fi

cd "$ROOT_DIR" || exit 1
if [ ! -d node_modules ]; then
  echo "Preparing the frontend tools for the first time..."
  npm ci || { read -r -p "Setup failed. Press Return to close this window."; exit 1; }
fi

echo "Building the local Java backend..."
mvn -q -Dmaven.repo.local="$LOCAL_DIR/m2" -pl backend -am package -DskipTests >"$LOG_DIR/backend-build.log" 2>&1 || {
  echo "The backend build failed. See $LOG_DIR/backend-build.log"
  read -r -p "Press Return to close this window."
  exit 1
}

echo "Starting the backend..."
java -Dworkflow.http.port=8080 -Dworkflow.local.data.dir="$DATA_DIR" \
  -cp "backend/target/classes:domain/target/classes:contracts/target/classes:adapters/target/classes" \
  com.sevenewf.workflow.backend.BackendApplication >"$LOG_DIR/backend.log" 2>&1 &
echo $! >"$BACKEND_PID_FILE"

echo "Starting the React development server..."
node "$ROOT_DIR/node_modules/vite/bin/vite.js" "$ROOT_DIR/frontend" \
  --config "$ROOT_DIR/frontend/vite.config.ts" \
  --host 127.0.0.1 >"$LOG_DIR/frontend.log" 2>&1 &
echo $! >"$FRONTEND_PID_FILE"

for _ in $(seq 1 45); do
  if curl --silent --fail "$BACKEND_URL/health" >/dev/null 2>&1 \
    && curl --silent --fail "$FRONTEND_URL" >/dev/null 2>&1; then
    echo "Local development environment is ready."
    echo "Frontend: $FRONTEND_URL"
    echo "Backend:  $BACKEND_URL"
    open "$FRONTEND_URL"
    echo "Logs: $LOG_DIR"
    echo "Use DEV_STOP.command when you are finished."
    while running_pid "$BACKEND_PID_FILE" || running_pid "$FRONTEND_PID_FILE"; do sleep 3; done
    exit 0
  fi
  sleep 1
done

echo "The local environment did not become ready."
echo "Backend log:  $LOG_DIR/backend.log"
echo "Frontend log: $LOG_DIR/frontend.log"
read -r -p "Press Return to close this window."
exit 1

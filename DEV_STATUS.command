#!/bin/bash

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
PID_DIR="$ROOT_DIR/.local-dev/pids"
LOG_DIR="$ROOT_DIR/.local-dev/logs"

status() {
  local name="$1"
  local url="$2"
  local pid_file="$PID_DIR/$name.pid"
  if [ -f "$pid_file" ] && [[ "$(cat "$pid_file")" =~ ^[0-9]+$ ]] && kill -0 "$(cat "$pid_file")" >/dev/null 2>&1; then
    echo "$name is running: $url (PID $(cat "$pid_file"))"
  else
    echo "$name is stopped: $url"
  fi
}

status frontend "http://127.0.0.1:5173"
status backend "http://127.0.0.1:8080"
echo "Logs: $LOG_DIR"
echo "Backend log: $LOG_DIR/backend.log"
echo "Frontend log: $LOG_DIR/frontend.log"
echo "Data store: $ROOT_DIR/.local-dev/data"
read -r -p "Press Return to close this window."

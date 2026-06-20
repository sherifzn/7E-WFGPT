#!/bin/bash

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
PID_DIR="$ROOT_DIR/.local-dev/pids"

stop_recorded_process() {
  local name="$1"
  local pid_file="$PID_DIR/$name.pid"
  if [ ! -f "$pid_file" ]; then
    echo "$name is not running."
    return
  fi
  local pid
  pid="$(cat "$pid_file")"
  if [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" >/dev/null 2>&1; then
    local child
    for child in $(pgrep -P "$pid" 2>/dev/null); do kill "$child" 2>/dev/null || true; done
    kill "$pid"
    echo "Stopped $name."
  else
    echo "$name was already stopped."
  fi
  rm -f "$pid_file"
}

stop_recorded_process frontend
stop_recorded_process backend
echo "The local development environment is stopped."
if [ "${1:-}" != "--no-prompt" ]; then
  read -r -p "Press Return to close this window." || true
fi

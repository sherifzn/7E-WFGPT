#!/bin/bash

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"

bash "$ROOT_DIR/DEV_STOP.command" --no-prompt
rm -rf "$ROOT_DIR/.local-dev/data"
mkdir -p "$ROOT_DIR/.local-dev/data"
echo "Local synthetic data has been reset. The initial dataset is created on the next start."
read -r -p "Press Return to close this window."

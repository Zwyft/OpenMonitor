#!/usr/bin/env bash
set -euo pipefail

HOST="${HOST:-0.0.0.0}"
PORT="${PORT:-8080}"

exec python3 "$(dirname "$0")/../web/server.py" --host "$HOST" --port "$PORT"

#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
for pidfile in "$ROOT"/deploy/logs/*.pid; do
  [ -f "$pidfile" ] || continue
  pid=$(cat "$pidfile")
  app=$(basename "$pidfile" .pid)
  if kill -0 "$pid" 2>/dev/null; then
    kill "$pid" && echo "stopped $app ($pid)"
  fi
  rm -f "$pidfile"
done

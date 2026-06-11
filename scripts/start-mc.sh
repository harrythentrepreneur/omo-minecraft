#!/usr/bin/env bash
# Launch the Paper server.

set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT/server"

if [ ! -f paper.jar ]; then
  echo "paper.jar missing — run ./scripts/setup.sh first"; exit 1
fi

MEM="${AGENTCRAFT_MC_MEM:-2G}"
exec java -Xms"$MEM" -Xmx"$MEM" -jar paper.jar --nogui

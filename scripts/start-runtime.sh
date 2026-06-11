#!/usr/bin/env bash
# Launch the AgentCraft Node.js runtime (Hermes agent bridge).

set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT/runtime"

if [ ! -d node_modules ]; then
  npm install
fi
if [ ! -f .env ]; then
  cp .env.example .env
  echo "wrote runtime/.env — set HERMES_API_KEY before sending real prompts"
fi

exec npm run dev

#!/usr/bin/env bash
# Rebuild the plugin and hot-swap it into the running server's plugins dir.

set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

mvn -B -f "$ROOT/plugin/pom.xml" package
cp "$ROOT/plugin/target/agentcraft-0.1.0.jar" "$ROOT/server/plugins/agentcraft.jar"
echo "rebuilt -> server/plugins/agentcraft.jar (run /reload confirm in-game, or restart)"

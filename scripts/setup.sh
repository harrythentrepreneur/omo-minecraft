#!/usr/bin/env bash
# AgentCraft one-shot setup: downloads Paper, prepares server, builds plugin, installs it,
# copies env templates. Run once, then use ./scripts/start-mc.sh and ./scripts/start-runtime.sh.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

PAPER_VERSION="${PAPER_VERSION:-1.21.11}"
SERVER_DIR="$ROOT/server"
PLUGIN_DIR="$SERVER_DIR/plugins"

echo "==> Checking prerequisites"
for cmd in java mvn node npm curl jq; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "  missing: $cmd"
    case "$cmd" in
      java) echo "    install: brew install --cask temurin (need JDK 21+)";;
      mvn)  echo "    install: brew install maven";;
      node|npm) echo "    install: brew install node";;
      jq)   echo "    install: brew install jq";;
    esac
    exit 1
  fi
done

mkdir -p "$SERVER_DIR" "$PLUGIN_DIR"

if [ ! -f "$SERVER_DIR/paper.jar" ]; then
  echo "==> Fetching latest Paper build for $PAPER_VERSION (via fill.papermc.io v3)"
  META=$(curl -fsSL "https://fill.papermc.io/v3/projects/paper/versions/$PAPER_VERSION/builds/latest" || true)
  URL=$(printf '%s' "$META" | jq -r '.downloads."server:default".url // empty')

  # Fallback: if the requested version is gone, find any available 1.21.x stable build.
  if [ -z "$URL" ]; then
    echo "  $PAPER_VERSION not available; finding newest 1.21.x stable…"
    LATEST=$(curl -fsSL "https://fill.papermc.io/v3/projects/paper" \
      | jq -r '.versions["1.21"] | map(select(test("^1\\.21\\.[0-9]+$"))) | .[0] // empty')
    if [ -z "$LATEST" ]; then
      echo "  could not resolve a Paper version from fill.papermc.io"
      exit 1
    fi
    echo "  using $LATEST instead of $PAPER_VERSION"
    PAPER_VERSION="$LATEST"
    URL=$(curl -fsSL "https://fill.papermc.io/v3/projects/paper/versions/$PAPER_VERSION/builds/latest" \
      | jq -r '.downloads."server:default".url')
  fi

  echo "  downloading $URL"
  curl -fL -o "$SERVER_DIR/paper.jar" "$URL"
  echo "$PAPER_VERSION" > "$SERVER_DIR/.paper-version"
fi

if [ ! -f "$SERVER_DIR/eula.txt" ]; then
  echo "==> Writing eula.txt (you accept Minecraft EULA by running this server)"
  echo "eula=true" > "$SERVER_DIR/eula.txt"
fi

if [ ! -f "$SERVER_DIR/server.properties" ]; then
  cat > "$SERVER_DIR/server.properties" <<'EOF'
motd=AgentCraft — Hermes agents inside Minecraft
online-mode=false
gamemode=creative
difficulty=peaceful
spawn-protection=0
view-distance=10
server-port=25565
allow-flight=true
EOF
fi

echo "==> Building plugin (first build downloads ~80MB of Maven deps; takes 2-4 min)"
mvn -B -f "$ROOT/plugin/pom.xml" package
JAR_OUT="$ROOT/plugin/target/agentcraft-0.1.0.jar"
[ -f "$JAR_OUT" ] || { echo "  build failed"; exit 1; }
cp "$JAR_OUT" "$PLUGIN_DIR/agentcraft.jar"
echo "    installed -> $PLUGIN_DIR/agentcraft.jar"

echo "==> Installing runtime deps"
(cd "$ROOT/runtime" && npm install --silent)

if [ ! -f "$ROOT/runtime/.env" ]; then
  cp "$ROOT/runtime/.env.example" "$ROOT/runtime/.env"
  echo "    wrote runtime/.env (edit it to add HERMES_API_KEY etc.)"
fi

# Sync the shared token from runtime/.env into the plugin config so they match.
TOKEN="$(grep '^AGENTCRAFT_WS_TOKEN=' "$ROOT/runtime/.env" | head -n1 | cut -d= -f2-)"
if [ -n "$TOKEN" ]; then
  mkdir -p "$PLUGIN_DIR/AgentCraft"
  cat > "$PLUGIN_DIR/AgentCraft/config.yml" <<EOF
bridge:
  url: "ws://127.0.0.1:8765"
  token: "$TOKEN"
  reconnect_seconds: 5
display:
  room_radius: 8
  screen_lines: 6
  log_to_console: true
EOF
  echo "    synced shared token into plugins/AgentCraft/config.yml"
fi

echo
PAPER_VER_INSTALLED="$(cat "$SERVER_DIR/.paper-version" 2>/dev/null || echo "$PAPER_VERSION")"
echo "==> Setup complete."
echo "  Paper version: $PAPER_VER_INSTALLED  (your Minecraft client must match)"
echo "  Start everything with: ./agentcraft"
echo "  Then in Minecraft: /gamemode creative && /omo village build 4"

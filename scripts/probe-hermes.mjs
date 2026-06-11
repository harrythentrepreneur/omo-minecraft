// scripts/probe-hermes.mjs — confirm the Hermes pipeline is wired.
//
//   node scripts/probe-hermes.mjs                    # mail_room kind
//   node scripts/probe-hermes.mjs ads "what's hot"   # custom prompt
//
// Connects to the running runtime over WebSocket (using the same shared
// token as the plugin), spawns a transient agent, sends one player_message,
// prints every event back, then despawns. If you see "SAY ..." within ~10
// seconds you know the loop is healthy. Otherwise look at logs/runtime.log
// and logs/hermes.log for the error.

import { readFileSync } from "fs";
import { resolve, dirname } from "path";
import { fileURLToPath, pathToFileURL } from "url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const envFile = resolve(__dirname, "..", "runtime", ".env");

// Resolve the runtime's installed `ws` package — this script lives in
// scripts/ which has no node_modules of its own.
const wsEntry = resolve(__dirname, "..", "runtime", "node_modules", "ws", "wrapper.mjs");
const { default: WebSocket } = await import(pathToFileURL(wsEntry).href);
let token = "change-me-shared-secret";
try {
  const m = readFileSync(envFile, "utf8").match(/^AGENTCRAFT_WS_TOKEN=(.+)$/m);
  if (m) token = m[1].trim();
} catch { /* fall back to default */ }

const [, , roomPrefix = "mail", ...rest] = process.argv;
const prompt = rest.length ? rest.join(" ") : "Say hi in one short sentence, no tools.";
const room = `${roomPrefix}-probe-pod`;
const agentId = "probe-" + roomPrefix + "-" + Math.floor(Math.random() * 9999);

console.log(`[probe] connecting to ws://127.0.0.1:8765 as agent=${agentId} room=${room}`);

const ws = new WebSocket("ws://127.0.0.1:8765");
const send = (o) => ws.send(JSON.stringify(o));

let idleAfterTurn = false;

ws.on("open", () => send({ type: "hello", token, serverName: "probe" }));

ws.on("message", (raw) => {
  let m; try { m = JSON.parse(raw.toString()); } catch { return; }
  const t = new Date().toISOString().slice(11, 19);
  if (m.type === "ready") {
    send({
      type: "spawn_agent",
      agentId,
      role: `${roomPrefix} probe`,
      room,
      playerName: "probe",
      home: { x: 0, y: 64, z: 0 },
    });
    setTimeout(() => send({
      type: "player_message", agentId, playerName: "probe", text: prompt,
    }), 500);
    return;
  }
  if (m.agentId !== agentId) return;
  if (m.type === "agent_status") {
    console.log(`[${t}] status → ${m.status}${m.detail ? " (" + m.detail + ")" : ""}`);
    if (m.status === "idle" && idleAfterTurn) {
      send({ type: "despawn_agent", agentId });
      setTimeout(() => ws.close(), 200);
    } else if (m.status === "thinking") {
      idleAfterTurn = true;
    }
    return;
  }
  if (m.type === "agent_say")          console.log(`[${t}] SAY  ${m.text}`);
  else if (m.type === "agent_log")     console.log(`[${t}] log  ${m.line}`);
  else if (m.type === "agent_transcript_append") {
    const e = m.entry || {};
    console.log(`[${t}] ${String(e.kind).padEnd(7)} ${e.text}`);
  }
});

ws.on("close", () => process.exit(0));
ws.on("error", (e) => { console.error("[probe] error:", e.message); process.exit(2); });
setTimeout(() => { console.error("[probe] timeout"); process.exit(3); }, 90_000);

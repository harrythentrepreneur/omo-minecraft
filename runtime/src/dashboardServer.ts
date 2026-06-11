// dashboardServer.ts — a self-contained live web dashboard server.
//
// Purpose: each in-world AI "function" can be given a glassy, neon ops console
// that the rest of the runtime feeds live business data into. A Minecraft
// cinema wall is pointed at `http://<host>:<port>/dash/<id>` and the page is
// captured by headless Chrome, so EVERYTHING here must work offline: no
// express, no CDNs, no web fonts — just Node built-ins and one inlined HTML
// page (see dashboard.html.ts).
//
// Wiring (done by the caller, not here):
//   import { startDashboardServer, setDashboardData } from "./dashboardServer.js";
//   startDashboardServer();                       // listen on OMO_DASH_PORT|8088
//   setDashboardData("growth", { title: ... });   // push live data for an id
//
// Routes:
//   GET /dash/:id        → the full HTML page (inline CSS + JS)
//   GET /dash/:id/data   → JSON DashboardData for :id (empty-state if unset)
//   GET /                 → redirect to the demo dashboard
//   GET /healthz         → "ok" (liveness for the launcher / capture pipeline)

import { createServer, type IncomingMessage, type ServerResponse, type Server } from "node:http";
import { dashboardHtml } from "./dashboard.html.js";

/** A single big-number tile. */
export type DashboardKpi = {
  label: string;
  value: string | number;
  unit?: string;
  delta?: string;
  trend?: "up" | "down" | "flat";
};

/** One line/area series for the canvas chart. */
export type DashboardSeries = {
  name: string;
  color?: string;
  points: number[];
};

/** A simple data table. First column is treated as the row label. */
export type DashboardTable = {
  columns: string[];
  rows: Array<Array<string | number>>;
};

/** One live-feed event. `tone` colors the accent bar. */
export type DashboardFeedItem = {
  ts?: string;
  text: string;
  tone?: "info" | "good" | "warn" | "bad";
};

/** The full payload a dashboard renders. All sections beyond title+kpis are optional. */
export type DashboardData = {
  title: string;
  subtitle?: string;
  status?: string;
  kpis: Array<DashboardKpi>;
  series?: Array<DashboardSeries>;
  table?: DashboardTable;
  feed?: Array<DashboardFeedItem>;
  updatedAt?: number;
};

// ---------------------------------------------------------------------------
// Society View — the whole-ecosystem board (/dash/society)
// ---------------------------------------------------------------------------

/** One function node in the Society View, joined from WorldStore + live status. */
export type SocietyNode = {
  id: string;
  role: string;
  purpose?: string;
  room: string;
  staffed: boolean;
  status: string; // live AgentStatus, or "unstaffed"
};

/** One consultation edge (who asked whom), for the live "who's talking" feed. */
export type SocietyEdge = {
  from: string;
  to: string;
  fromRole?: string;
  toRole?: string;
  question: string;
  answer?: string;
  status: "pending" | "answered" | "failed";
  at: number;
};

/** The full Society View payload. Assembled on each poll by the provider. */
export type SocietyData = {
  nodes: SocietyNode[];
  edges: SocietyEdge[];
  totals: {
    functions: number;
    staffed: number;
    working: number; // thinking/tool_call/speaking
    consults: number;
    pendingApprovals: number;
  };
  updatedAt: number;
};

// The runtime registers a provider (server.ts) that joins manager.snapshot()
// + world.recentConsults() into a SocietyData on demand. Kept as a callback so
// the dashboard server stays decoupled from AgentManager/WorldStore.
let societyProvider: (() => SocietyData) | null = null;
export function setSocietyProvider(fn: () => SocietyData): void {
  societyProvider = fn;
}

// ---------------------------------------------------------------------------
// Store
// ---------------------------------------------------------------------------

const store = new Map<string, DashboardData>();

/**
 * Store or replace the data for a dashboard id. Stamps `updatedAt` if the
 * caller didn't, so the page's "updated Xs ago" stays honest.
 */
export function setDashboardData(id: string, data: DashboardData): void {
  store.set(id, { ...data, updatedAt: data.updatedAt ?? Date.now() });
}

/** Read the current data for a dashboard id (used to seed a function's board from the live one). */
export function getDashboardData(id: string): DashboardData | undefined {
  return store.get(id);
}

/** Tasteful empty-state so a never-seeded id still renders something on-camera. */
function emptyState(id: string): DashboardData {
  return {
    title: id === "demo" ? "OMO Ops Console" : `Function · ${id}`,
    subtitle: "Awaiting live telemetry from the runtime…",
    status: "STANDBY",
    kpis: [
      { label: "Status", value: "ONLINE", trend: "flat" },
      { label: "Signals", value: 0, unit: "" },
      { label: "Throughput", value: 0, unit: "/min" },
      { label: "Uptime", value: "100", unit: "%" },
    ],
    series: [{ name: "Activity", color: "#27e7ff", points: [0, 0, 0, 0, 0, 0, 0, 0] }],
    feed: [{ ts: nowClock(), text: "Dashboard initialised — standing by for data.", tone: "info" }],
    updatedAt: Date.now(),
  };
}

// ---------------------------------------------------------------------------
// Demo seed — a believable "Growth — Ad Performance" board so /dash/demo looks
// stunning standalone, and self-animates a little so the chart/feed move even
// before the runtime pushes anything.
// ---------------------------------------------------------------------------

function nowClock(): string {
  return new Date().toLocaleTimeString("en-US", { hour12: false });
}

const DEMO_FEED_LINES: Array<{ text: string; tone: DashboardFeedItem["tone"] }> = [
  { text: "Scaled “Q3 Prospecting — Lookalike 2%” budget +20% (ROAS 4.1×).", tone: "good" },
  { text: "Paused “Retargeting — 7d” — CAC drifted above $42 target.", tone: "warn" },
  { text: "New creative “Founder POV v3” entered learning phase.", tone: "info" },
  { text: "Signup spike detected: 38 in last 10 min (+62% vs baseline).", tone: "good" },
  { text: "Welcome sequence email #2 sent to 214 new users (61% open).", tone: "info" },
  { text: "Frequency on “Broad — Interest Stack” hit 3.2 — rotating creative.", tone: "warn" },
  { text: "Stripe: 7 new subscriptions, $1,043 MRR added today.", tone: "good" },
  { text: "Anomaly: CPM up 14% on Meta auction — bidding to cap.", tone: "bad" },
];

let demoTick = 0;
let demoTimer: ReturnType<typeof setInterval> | undefined;

function makeDemoData(): DashboardData {
  // A gently rising 14-point revenue series with a little noise that drifts
  // each tick so the chart visibly breathes during a demo.
  const base = 6200;
  const points: number[] = [];
  for (let i = 0; i < 14; i++) {
    const growth = base + i * 470;
    const wobble = Math.sin((i + demoTick) * 0.7) * 320 + Math.sin((i + demoTick) * 0.23) * 180;
    points.push(Math.round(growth + wobble));
  }

  const roas = (4.0 + Math.sin(demoTick * 0.5) * 0.35).toFixed(1);
  const spend = 12480 + Math.round(Math.sin(demoTick * 0.3) * 420);
  const signups = 1284 + (demoTick % 7) * 3;
  const cac = (31.5 + Math.cos(demoTick * 0.4) * 2.1).toFixed(2);

  const feed: DashboardFeedItem[] = [];
  for (let i = 0; i < 5; i++) {
    const line = DEMO_FEED_LINES[(demoTick + i) % DEMO_FEED_LINES.length]!;
    const t = new Date(Date.now() - i * 47000).toLocaleTimeString("en-US", { hour12: false });
    feed.push({ ts: t, text: line.text, tone: line.tone });
  }

  return {
    title: "Growth — Ad Performance",
    subtitle: "Meta Ads · live blended performance across 6 active campaigns",
    status: "OPTIMIZING",
    kpis: [
      { label: "Blended ROAS", value: roas, unit: "×", delta: "+0.4× WoW", trend: "up" },
      { label: "Spend (today)", value: `$${spend.toLocaleString("en-US")}`, delta: "+8.2%", trend: "up" },
      { label: "Signups", value: signups, delta: "+62 today", trend: "up" },
      { label: "CAC", value: `$${cac}`, delta: "-6.1%", trend: "up" },
    ],
    series: [{ name: "Revenue", color: "#39ff88", points }],
    table: {
      columns: ["Campaign", "Spend", "ROAS", "CPA", "Δ 24h"],
      rows: [
        ["Prospecting — LAL 2%", "$4,210", "4.1×", "$28.40", "+12%"],
        ["Broad — Interest Stack", "$3,180", "3.6×", "$33.10", "+5%"],
        ["Retargeting — 7d", "$1,940", "5.8×", "$19.70", "-3%"],
        ["Founder POV v3", "$1,460", "3.2×", "$38.90", "+21%"],
        ["Brand — Search Defend", "$980", "6.4×", "$14.20", "+2%"],
        ["Reels — UGC Mix", "$710", "2.9×", "$44.60", "-8%"],
      ],
    },
    feed,
    updatedAt: Date.now(),
  };
}

const COMMS_FEED_LINES: Array<{ text: string; tone: DashboardFeedItem["tone"] }> = [
  { text: "Sent lifecycle email “Day 3 — Activation nudge” to 412 users (64% open).", tone: "good" },
  { text: "Founder outreach sequence: 22% reply rate (+4pt WoW).", tone: "good" },
  { text: "Drafted launch announcement — 3 A/B variants queued for review.", tone: "info" },
  { text: "Support backlog cleared: 0 tickets older than 4h.", tone: "good" },
  { text: "Press list refreshed — 22 relevant journalists added.", tone: "info" },
  { text: "Sentiment dip on an X mention — drafted a response.", tone: "warn" },
  { text: "Booked 6 demo calls from this week’s sequence.", tone: "good" },
  { text: "Newsletter #14 scheduled — 3,180 subscribers.", tone: "info" },
];

// A believable "Comms — Outreach & Lifecycle" board so the HQ comms screen looks
// alive standalone (until the Comms specialist overwrites it via dashboard_update).
function makeCommsData(): DashboardData {
  const base = 1800;
  const points: number[] = [];
  for (let i = 0; i < 14; i++) {
    const growth = base + i * 90;
    const wobble = Math.sin((i + demoTick) * 0.6) * 70 + Math.sin((i + demoTick) * 0.21) * 40;
    points.push(Math.round(growth + wobble));
  }
  const open = (61 + Math.sin(demoTick * 0.4) * 3).toFixed(1);
  const sent = 3140 + (demoTick % 9) * 6;
  const replies = (17.5 + Math.cos(demoTick * 0.35) * 1.6).toFixed(1);
  const meetings = 24 + (demoTick % 6);

  const feed: DashboardFeedItem[] = [];
  for (let i = 0; i < 5; i++) {
    const line = COMMS_FEED_LINES[(demoTick + i) % COMMS_FEED_LINES.length]!;
    const t = new Date(Date.now() - i * 53000).toLocaleTimeString("en-US", { hour12: false });
    feed.push({ ts: t, text: line.text, tone: line.tone });
  }

  return {
    title: "Comms — Outreach & Lifecycle",
    subtitle: "Email + social · live engagement across the funnel",
    status: "SENDING",
    kpis: [
      { label: "Open rate", value: open, unit: "%", delta: "+2.1pt WoW", trend: "up" },
      { label: "Sent (today)", value: sent.toLocaleString("en-US"), delta: "+312", trend: "up" },
      { label: "Reply rate", value: `${replies}%`, delta: "+4.0pt", trend: "up" },
      { label: "Meetings booked", value: meetings, delta: "+6 today", trend: "up" },
    ],
    series: [{ name: "Engaged users", color: "#5bc8ff", points }],
    table: {
      columns: ["Sequence", "Sent", "Open", "Reply", "Booked"],
      rows: [
        ["Founder outreach — warm", "640", "71%", "22%", "9"],
        ["Lifecycle — activation", "412", "64%", "11%", "—"],
        ["Newsletter #14", "3,180", "47%", "3%", "—"],
        ["Re-engagement — 30d", "880", "38%", "6%", "2"],
        ["Press — launch", "120", "58%", "19%", "4"],
      ],
    },
    feed,
    updatedAt: Date.now(),
  };
}

function seedDemo(): void {
  // Seed all three HQ boards so the command-center triptych is alive immediately:
  // demo + growth share the Ad-Performance board; comms gets its own.
  setDashboardData("demo", makeDemoData());
  setDashboardData("growth", makeDemoData());
  setDashboardData("comms", makeCommsData());
  if (!demoTimer) {
    demoTimer = setInterval(() => {
      demoTick++;
      // Keep the seeded boards gently breathing — but only while nobody has
      // overwritten them with real data (a real push changes the title), so a
      // live agent update is never clobbered by the animation.
      const demo = store.get("demo");
      if (demo && demo.title === "Growth — Ad Performance") setDashboardData("demo", makeDemoData());
      const growth = store.get("growth");
      if (growth && growth.title === "Growth — Ad Performance") setDashboardData("growth", makeDemoData());
      const comms = store.get("comms");
      if (comms && comms.title === "Comms — Outreach & Lifecycle") setDashboardData("comms", makeCommsData());
    }, 1500);
    // Don't keep the event loop alive just for the demo animation.
    if (typeof demoTimer.unref === "function") demoTimer.unref();
  }
}

// ---------------------------------------------------------------------------
// HTTP
// ---------------------------------------------------------------------------

function sendJson(res: ServerResponse, code: number, body: unknown): void {
  const buf = Buffer.from(JSON.stringify(body));
  res.writeHead(code, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": String(buf.byteLength),
    "Cache-Control": "no-store, no-cache, must-revalidate",
    "Access-Control-Allow-Origin": "*",
  });
  res.end(buf);
}

function sendHtml(res: ServerResponse, code: number, html: string): void {
  const buf = Buffer.from(html);
  res.writeHead(code, {
    "Content-Type": "text/html; charset=utf-8",
    "Content-Length": String(buf.byteLength),
    "Cache-Control": "no-store",
  });
  res.end(buf);
}

function sendText(res: ServerResponse, code: number, text: string): void {
  const buf = Buffer.from(text);
  res.writeHead(code, {
    "Content-Type": "text/plain; charset=utf-8",
    "Content-Length": String(buf.byteLength),
  });
  res.end(buf);
}

/** Pull the dashboard id (and a `/data` flag) out of the request path. */
function parseDashPath(pathname: string): { id: string; isData: boolean } | null {
  // /dash/:id           → page
  // /dash/:id/data      → json
  const m = pathname.match(/^\/dash\/([^/]+)(\/data)?\/?$/);
  if (!m) return null;
  const id = decodeURIComponent(m[1]!);
  if (!id) return null;
  return { id, isData: Boolean(m[2]) };
}

function handle(req: IncomingMessage, res: ServerResponse): void {
  let pathname = "/";
  try {
    pathname = new URL(req.url ?? "/", "http://localhost").pathname;
  } catch {
    pathname = "/";
  }

  if (req.method !== "GET" && req.method !== "HEAD") {
    sendText(res, 405, "method not allowed");
    return;
  }

  if (pathname === "/" || pathname === "") {
    res.writeHead(302, { Location: "/dash/demo" });
    res.end();
    return;
  }

  if (pathname === "/healthz") {
    sendText(res, 200, "ok");
    return;
  }

  // Society View — the whole-ecosystem board. Special-cased above the generic
  // /dash/:id route because its payload shape differs from DashboardData.
  if (pathname === "/dash/society" || pathname === "/dash/society/") {
    sendHtml(res, 200, societyHtml());
    return;
  }
  if (pathname === "/dash/society/data") {
    const data = societyProvider
      ? societyProvider()
      : { nodes: [], edges: [], totals: { functions: 0, staffed: 0, working: 0, consults: 0, pendingApprovals: 0 }, updatedAt: Date.now() };
    sendJson(res, 200, data);
    return;
  }

  const parsed = parseDashPath(pathname);
  if (parsed) {
    if (parsed.isData) {
      const data = store.get(parsed.id) ?? emptyState(parsed.id);
      sendJson(res, 200, data);
    } else {
      sendHtml(res, 200, dashboardHtml(parsed.id));
    }
    return;
  }

  sendText(res, 404, "not found — try /dash/demo");
}

// ---------------------------------------------------------------------------
// Society View page — a self-contained (offline) board showing every function,
// its live status, the consultations flowing between rooms, and a KPI strip.
// Same constraints as dashboard.html.ts: no CDNs, no web fonts, inline only.
// ---------------------------------------------------------------------------

function societyHtml(): string {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1"/>
<title>Omo — Society View</title>
<style>
  :root{--bg:#070b14;--panel:#0d1424;--line:#1d2c47;--ink:#dfeaff;--dim:#7d92b8;--cyan:#27e7ff;--green:#39ff88;--amber:#ffc24b;--blue:#4b9dff;--red:#ff5d6c}
  *{box-sizing:border-box}
  body{margin:0;background:radial-gradient(1200px 700px at 30% -10%,#0e1830 0,var(--bg) 60%);color:var(--ink);font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;-webkit-font-smoothing:antialiased}
  header{display:flex;align-items:baseline;gap:14px;padding:18px 24px;border-bottom:1px solid var(--line)}
  header h1{font-size:20px;margin:0;letter-spacing:.5px}
  header .sub{color:var(--dim);font-size:13px}
  .wrap{padding:20px 24px;max-width:1280px;margin:0 auto}
  .kpis{display:grid;grid-template-columns:repeat(5,1fr);gap:12px;margin-bottom:18px}
  .kpi{background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:14px 16px}
  .kpi .v{font-size:26px;font-weight:700}
  .kpi .l{color:var(--dim);font-size:12px;text-transform:uppercase;letter-spacing:.6px;margin-top:4px}
  .cols{display:grid;grid-template-columns:1.3fr 1fr;gap:16px}
  .card{background:var(--panel);border:1px solid var(--line);border-radius:14px;padding:14px 16px}
  .card h2{font-size:13px;text-transform:uppercase;letter-spacing:.7px;color:var(--dim);margin:0 0 12px}
  .nodes{display:grid;grid-template-columns:repeat(auto-fill,minmax(180px,1fr));gap:10px}
  .node{background:#0a1120;border:1px solid var(--line);border-radius:10px;padding:11px 12px;display:flex;flex-direction:column;gap:4px}
  .node .row{display:flex;align-items:center;gap:8px}
  .dot{width:10px;height:10px;border-radius:50%;flex:0 0 auto;box-shadow:0 0 0 0 rgba(0,0,0,0)}
  .dot.idle{background:#46597d}
  .dot.unstaffed{background:#33425f}
  .dot.done{background:var(--green)}
  .dot.thinking,.dot.speaking{background:var(--amber);animation:pulse 1.1s infinite}
  .dot.tool_call{background:var(--blue);animation:pulse 1.1s infinite}
  .dot.error{background:var(--red)}
  @keyframes pulse{0%{box-shadow:0 0 0 0 rgba(255,194,75,.5)}70%{box-shadow:0 0 0 8px rgba(255,194,75,0)}100%{box-shadow:0 0 0 0 rgba(255,194,75,0)}}
  .node .role{font-weight:650;font-size:14px}
  .node .meta{color:var(--dim);font-size:11px}
  .node .st{font-size:11px;color:var(--dim);text-transform:uppercase;letter-spacing:.5px}
  .feed{display:flex;flex-direction:column;gap:8px;max-height:60vh;overflow:auto}
  .edge{border-left:3px solid var(--line);padding:6px 10px;background:#0a1120;border-radius:0 8px 8px 0}
  .edge.pending{border-left-color:var(--amber)}
  .edge.answered{border-left-color:var(--green)}
  .edge.failed{border-left-color:var(--red)}
  .edge .who{font-size:13px;font-weight:600}
  .edge .who b{color:var(--cyan)}
  .edge .q{font-size:12px;color:var(--ink);opacity:.85;margin-top:2px}
  .edge .a{font-size:12px;color:var(--green);margin-top:3px}
  .empty{color:var(--dim);font-size:13px;padding:20px;text-align:center}
  footer{color:var(--dim);font-size:11px;padding:10px 24px;text-align:right}
</style></head>
<body>
  <header><h1>Omo · Society View</h1><span class="sub" id="sub">the whole ecosystem at a glance</span></header>
  <div class="wrap">
    <div class="kpis" id="kpis"></div>
    <div class="cols">
      <div class="card"><h2>Functions</h2><div class="nodes" id="nodes"></div></div>
      <div class="card"><h2>Consultations</h2><div class="feed" id="feed"></div></div>
    </div>
  </div>
  <footer id="foot">connecting…</footer>
<script>
  var roleById = {};
  function esc(s){return String(s==null?"":s).replace(/[&<>"]/g,function(c){return {"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c];});}
  function ago(t){var s=Math.max(0,Math.round((Date.now()-t)/1000));if(s<60)return s+"s ago";var m=Math.round(s/60);return m+"m ago";}
  function statusLabel(st,staffed){if(!staffed)return"unstaffed";return st;}
  function render(d){
    roleById={}; (d.nodes||[]).forEach(function(n){roleById[n.id]=n.role;});
    var t=d.totals||{};
    var kpis=[["Functions",t.functions||0],["Staffed",t.staffed||0],["Working now",t.working||0],["Consults",t.consults||0],["Approvals",t.pendingApprovals||0]];
    document.getElementById("kpis").innerHTML=kpis.map(function(k){return '<div class="kpi"><div class="v">'+k[1]+'</div><div class="l">'+k[0]+'</div></div>';}).join("");
    var nodes=d.nodes||[];
    document.getElementById("nodes").innerHTML=nodes.length?nodes.map(function(n){
      var st=statusLabel(n.status,n.staffed);
      return '<div class="node"><div class="row"><span class="dot '+esc(n.staffed?n.status:"unstaffed")+'"></span><span class="role">'+esc(n.role)+'</span></div>'+
             '<div class="meta">'+esc(n.room)+'</div>'+
             '<div class="st">'+esc(st)+'</div></div>';
    }).join(""):'<div class="empty">No functions yet — the org is a blank canvas.</div>';
    var edges=d.edges||[];
    document.getElementById("feed").innerHTML=edges.length?edges.map(function(e){
      var fr=e.fromRole||roleById[e.from]||e.from, to=e.toRole||roleById[e.to]||e.to;
      var a=e.status==="answered"&&e.answer?'<div class="a">↳ '+esc(e.answer)+'</div>':(e.status==="pending"?'<div class="a" style="color:var(--amber)">…awaiting answer</div>':(e.status==="failed"?'<div class="a" style="color:var(--red)">no answer</div>':''));
      return '<div class="edge '+esc(e.status)+'"><div class="who"><b>'+esc(fr)+'</b> → <b>'+esc(to)+'</b> · <span style="color:var(--dim)">'+ago(e.at)+'</span></div>'+
             '<div class="q">'+esc(e.question)+'</div>'+a+'</div>';
    }).join(""):'<div class="empty">No consultations yet. Agents consult each other with world_consult.</div>';
    document.getElementById("foot").textContent="updated "+new Date().toLocaleTimeString();
  }
  function poll(){fetch("/dash/society/data",{cache:"no-store"}).then(function(r){return r.json();}).then(render).catch(function(){document.getElementById("foot").textContent="offline — retrying";});}
  poll(); setInterval(poll,2000);
</script>
</body></html>`;
}

// ---------------------------------------------------------------------------
// Lifecycle (idempotent)
// ---------------------------------------------------------------------------

let server: Server | undefined;
let started = false;

/**
 * Start the dashboard HTTP server. Default port: env OMO_DASH_PORT or 8088.
 * Idempotent — a second call while already listening is a no-op.
 */
export function startDashboardServer(port?: number): void {
  if (started && server) return;
  started = true;

  const envPort = Number.parseInt(process.env.OMO_DASH_PORT ?? "", 10);
  const listenPort = port ?? (Number.isFinite(envPort) && envPort > 0 ? envPort : 8088);

  seedDemo();

  server = createServer(handle);
  server.on("error", (err: NodeJS.ErrnoException) => {
    if (err.code === "EADDRINUSE") {
      // Another process (or a previous tsx-watch reload) already holds the
      // port — treat as already-running rather than crashing the runtime.
      console.warn(`[dashboard] port ${listenPort} in use — assuming an instance is already up.`);
    } else {
      console.error("[dashboard] server error:", err);
    }
  });
  server.listen(listenPort, () => {
    console.log(`[dashboard] live on http://127.0.0.1:${listenPort}/dash/demo`);
  });
}

/** Stop the server (used in tests / clean shutdown). Safe to call when not started. */
export function stopDashboardServer(): void {
  if (demoTimer) {
    clearInterval(demoTimer);
    demoTimer = undefined;
  }
  if (server) {
    server.close();
    server = undefined;
  }
  started = false;
}

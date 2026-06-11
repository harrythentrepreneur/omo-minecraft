// dashboard.html.ts — the full inlined dashboard page (HTML + CSS + JS).
//
// This is served verbatim by dashboardServer.ts for `GET /dash/:id`. It must
// be 100% self-contained: NO external CDNs, NO web fonts, NO chart libraries.
// Everything (fonts, styling, the canvas line/bar/donut charts, the polling
// loop, the number/feed animations) lives in this one string so it renders
// inside a headless Chrome screen-capture pipeline with no network access.
//
// Aesthetic: "ALIEN HQ · COMMAND CENTER" — near-black holographic console,
// everything glowing cyan/aqua, glassy translucent panels with thin glowing
// borders, soft depth, monospace numerics. Tuned to look STUNNING when
// captured at 1024×640 and painted onto an in-world Minecraft screen.
//
// The page polls `GET /dash/:id/data` every POLL_MS and morphs smoothly: KPI
// numbers tween, the hero line chart redraws with eased motion, the bar chart
// and donut grow into place, the feed slides new rows in. The bar chart and
// donut are DERIVED from the existing data contract (the numeric columns of
// `table`, falling back to KPIs) so the wire shape in dashboardServer.ts never
// has to change. Every section renders gracefully from partial data.
//
// `{{DASH_ID}}` is the only template hole; dashboardServer.ts substitutes the
// requested dashboard id before sending the bytes.

export function dashboardHtml(dashId: string): string {
  return PAGE.replace(/\{\{DASH_ID\}\}/g, escapeForJsString(dashId));
}

/** Escape an id so it is safe to embed inside a JS single-quoted string. */
function escapeForJsString(s: string): string {
  return s.replace(/\\/g, "\\\\").replace(/'/g, "\\'").replace(/[\r\n]/g, "");
}

const PAGE = String.raw`<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover" />
<title>OMO · Command Center</title>
<style>
  :root{
    --bg-0:#02030a;
    --bg-1:#04060f;
    --ink:#eafcff;
    --muted:#5e7fa6;
    --faint:#33486e;
    --cyan:#27e7ff;
    --cyan-2:#5cf3ff;
    --cyan-soft:rgba(39,231,255,.16);
    --green:#3dffb0;
    --green-soft:rgba(61,255,176,.14);
    --red:#ff5d8a;
    --amber:#ffcb5a;
    --panel-edge:rgba(39,231,255,.22);
    --glow-cyan:0 0 16px rgba(39,231,255,.6);
    --glow-green:0 0 16px rgba(61,255,176,.5);
    --mono:"SF Mono","SFMono-Regular",ui-monospace,"Cascadia Mono","JetBrains Mono",Menlo,Consolas,"DejaVu Sans Mono",monospace;
    --sans:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Helvetica Neue",Arial,"Noto Sans",sans-serif;
  }

  *{box-sizing:border-box;margin:0;padding:0}
  html,body{height:100%;width:100%}
  body{
    font-family:var(--sans);
    color:var(--ink);
    background:
      radial-gradient(1100px 720px at 82% -12%, rgba(39,231,255,.13), transparent 58%),
      radial-gradient(900px 700px at -8% 112%, rgba(61,255,176,.07), transparent 55%),
      radial-gradient(1200px 800px at 50% 55%, rgba(20,52,120,.22), transparent 72%),
      linear-gradient(165deg, var(--bg-1), var(--bg-0) 72%);
    overflow:hidden;
    -webkit-font-smoothing:antialiased;
    letter-spacing:.2px;
  }

  /* ---------- atmosphere overlays ---------- */
  .fx{position:fixed;inset:0;pointer-events:none;z-index:60}
  .fx.grid{
    background-image:
      linear-gradient(rgba(39,231,255,.045) 1px, transparent 1px),
      linear-gradient(90deg, rgba(39,231,255,.045) 1px, transparent 1px);
    background-size:46px 46px, 46px 46px;
    mask-image:radial-gradient(125% 120% at 50% 42%, #000 50%, transparent 100%);
    -webkit-mask-image:radial-gradient(125% 120% at 50% 42%, #000 50%, transparent 100%);
    animation:drift 26s linear infinite;
  }
  @keyframes drift{from{background-position:0 0,0 0}to{background-position:46px 46px,46px 46px}}
  .fx.dots{
    background-image:radial-gradient(rgba(39,231,255,.13) 1px, transparent 1.5px);
    background-size:46px 46px;background-position:23px 23px;
    mask-image:radial-gradient(120% 120% at 50% 42%, #000 45%, transparent 100%);
    -webkit-mask-image:radial-gradient(120% 120% at 50% 42%, #000 45%, transparent 100%);
    opacity:.7;
  }
  .fx.scan{
    background:repeating-linear-gradient(
      to bottom, rgba(0,0,0,0) 0px, rgba(0,0,0,0) 2px,
      rgba(8,40,60,.18) 3px, rgba(0,0,0,0) 4px);
    mix-blend-mode:screen;opacity:.5;
    animation:scan 8s linear infinite;
  }
  @keyframes scan{from{background-position-y:0}to{background-position-y:180px}}
  .fx.sweep{
    background:linear-gradient(180deg, transparent 0%, rgba(39,231,255,.05) 48%, rgba(39,231,255,.10) 50%, rgba(39,231,255,.05) 52%, transparent 100%);
    height:60%;animation:sweep 9s ease-in-out infinite;opacity:.55;
  }
  @keyframes sweep{0%{transform:translateY(-70%)}50%{transform:translateY(180%)}100%{transform:translateY(-70%)}}
  .fx.vig{box-shadow:inset 0 0 260px 50px rgba(0,0,0,.78)}

  .app{
    position:relative;z-index:10;
    height:100vh;width:100vw;
    padding:clamp(14px,1.8vw,30px);
    display:grid;
    grid-template-rows:auto 1fr;
    gap:clamp(12px,1.4vw,22px);
  }

  /* ---------- header ---------- */
  header{
    display:flex;align-items:center;gap:clamp(14px,1.8vw,30px);
    padding:clamp(10px,1.1vw,18px) clamp(16px,1.6vw,26px);
    border:1px solid var(--panel-edge);
    border-radius:16px;
    background:linear-gradient(180deg, rgba(10,28,52,.5), rgba(4,8,22,.6));
    box-shadow:0 8px 40px rgba(0,0,0,.55), inset 0 1px 0 rgba(92,243,255,.18), 0 0 32px rgba(39,231,255,.08);
    backdrop-filter:blur(12px);
    position:relative;overflow:hidden;
  }
  header::after{
    content:"";position:absolute;left:0;right:0;bottom:0;height:1px;
    background:linear-gradient(90deg, transparent, var(--cyan), transparent);
    opacity:.7;animation:edgeflow 5s ease-in-out infinite;
  }
  @keyframes edgeflow{0%,100%{opacity:.25}50%{opacity:.85}}

  .wordmark{display:flex;align-items:center;gap:13px;flex:0 0 auto}
  .logo{
    width:clamp(38px,3.2vw,54px);height:clamp(38px,3.2vw,54px);
    border-radius:13px;display:grid;place-items:center;flex:0 0 auto;position:relative;
    background:radial-gradient(circle at 34% 24%, rgba(92,243,255,.95), rgba(10,60,110,.5));
    box-shadow:var(--glow-cyan), inset 0 0 18px rgba(0,0,0,.45);
    border:1px solid rgba(120,235,255,.65);
  }
  .logo svg{width:64%;height:64%;display:block}
  .logo::after{
    content:"";position:absolute;inset:-4px;border-radius:16px;
    border:1px solid rgba(39,231,255,.35);
    animation:ringspin 7s linear infinite;
  }
  @keyframes ringspin{to{transform:rotate(360deg)}}
  .brand{line-height:1}
  .brand .om{
    font-family:var(--mono);font-weight:800;
    font-size:clamp(18px,1.7vw,28px);letter-spacing:6px;
    color:#fff;text-shadow:var(--glow-cyan);
  }
  .brand .sub{
    font-size:clamp(8px,.72vw,11px);letter-spacing:4px;
    color:var(--muted);text-transform:uppercase;margin-top:5px;
  }

  .titlebox{flex:1 1 auto;min-width:0}
  .titlebox h1{
    font-size:clamp(18px,2.2vw,38px);font-weight:780;line-height:1.02;
    letter-spacing:.3px;text-transform:uppercase;
    background:linear-gradient(92deg,#fff,#9ff1ff 55%,#62ffc9);
    -webkit-background-clip:text;background-clip:text;color:transparent;
    white-space:nowrap;overflow:hidden;text-overflow:ellipsis;
    text-shadow:0 0 30px rgba(39,231,255,.25);
  }
  .titlebox .desc{
    color:var(--muted);font-size:clamp(10px,.95vw,15px);margin-top:5px;
    white-space:nowrap;overflow:hidden;text-overflow:ellipsis;letter-spacing:.6px;
  }

  .rail{display:flex;align-items:center;gap:clamp(10px,1.1vw,18px);flex:0 0 auto}
  .statuschip{
    display:inline-flex;align-items:center;gap:8px;
    padding:6px 13px;border-radius:999px;font-family:var(--mono);
    font-size:clamp(8px,.72vw,11px);letter-spacing:1.6px;text-transform:uppercase;
    color:var(--green);background:var(--green-soft);
    border:1px solid rgba(61,255,176,.4);
  }
  .statuschip .dot{width:7px;height:7px;border-radius:50%;background:var(--green);box-shadow:var(--glow-green)}
  .clock{
    font-family:var(--mono);font-weight:700;letter-spacing:2px;
    font-size:clamp(13px,1.3vw,22px);color:var(--cyan);
    text-shadow:0 0 18px rgba(39,231,255,.4);font-variant-numeric:tabular-nums;
  }
  .clock .dim{color:var(--faint);font-size:.6em;letter-spacing:2px;display:block;margin-top:2px;text-align:right}
  .live{
    display:inline-flex;align-items:center;gap:9px;
    padding:8px 15px;border-radius:999px;
    font-family:var(--mono);font-weight:800;letter-spacing:3px;
    font-size:clamp(10px,.95vw,14px);color:#fff;
    background:linear-gradient(90deg, rgba(255,93,138,.22), rgba(255,93,138,.05));
    border:1px solid rgba(255,93,138,.55);
    box-shadow:0 0 22px rgba(255,93,138,.32);
  }
  .live .pulse{
    width:10px;height:10px;border-radius:50%;background:var(--red);
    box-shadow:0 0 0 0 rgba(255,93,138,.7);
    animation:pulse 1.4s ease-out infinite;
  }
  @keyframes pulse{
    0%{box-shadow:0 0 0 0 rgba(255,93,138,.8);transform:scale(1)}
    70%{box-shadow:0 0 0 13px rgba(255,93,138,0);transform:scale(1.15)}
    100%{box-shadow:0 0 0 0 rgba(255,93,138,0);transform:scale(1)}
  }

  /* ---------- body grid ----------
     col1 = KPIs over hero chart over (bar+donut). col2 = table over feed. */
  .body{
    display:grid;
    grid-template-columns:1.62fr 1fr;
    grid-template-rows:auto 1.45fr 1fr;
    gap:clamp(12px,1.4vw,22px);
    min-height:0;
  }
  .kpis{grid-column:1;grid-row:1}
  .chartcard{grid-column:1;grid-row:2;min-height:0}
  .lowrow{grid-column:1;grid-row:3;min-height:0;display:grid;grid-template-columns:1.5fr 1fr;gap:clamp(12px,1.4vw,22px)}
  .side{grid-column:2;grid-row:1 / 4;display:grid;grid-template-rows:1.25fr 1fr;gap:clamp(12px,1.4vw,22px);min-height:0}

  .panel{
    position:relative;
    border:1px solid var(--panel-edge);
    border-radius:16px;
    background:linear-gradient(180deg, rgba(10,24,48,.46), rgba(4,8,22,.55));
    box-shadow:0 10px 44px rgba(0,0,0,.5), inset 0 1px 0 rgba(92,243,255,.12), 0 0 24px rgba(39,231,255,.05);
    backdrop-filter:blur(9px);
    overflow:hidden;
  }
  .panel::before{
    content:"";position:absolute;inset:0;border-radius:16px;pointer-events:none;
    background:linear-gradient(125deg, rgba(39,231,255,.07), transparent 38%);
  }
  /* glowing corner brackets — the "HUD" feel */
  .panel .corner{position:absolute;width:14px;height:14px;border:1.5px solid var(--cyan);opacity:.5;pointer-events:none}
  .panel .corner.tl{top:8px;left:8px;border-right:0;border-bottom:0;border-top-left-radius:5px}
  .panel .corner.tr{top:8px;right:8px;border-left:0;border-bottom:0;border-top-right-radius:5px}
  .panel .corner.bl{bottom:8px;left:8px;border-right:0;border-top:0;border-bottom-left-radius:5px}
  .panel .corner.br{bottom:8px;right:8px;border-left:0;border-top:0;border-bottom-right-radius:5px}

  .panel .ph{
    display:flex;align-items:center;gap:10px;
    padding:clamp(10px,1vw,16px) clamp(14px,1.4vw,20px) 4px;
  }
  .panel .ph .tick{width:6px;height:16px;border-radius:3px;background:var(--cyan);box-shadow:var(--glow-cyan)}
  .panel .ph h2{
    font-family:var(--mono);font-size:clamp(9px,.85vw,13px);
    letter-spacing:3px;text-transform:uppercase;color:var(--muted);font-weight:600;
    white-space:nowrap;overflow:hidden;text-overflow:ellipsis;
  }
  .panel .ph .spacer{flex:1}
  .panel .ph .meta{font-family:var(--mono);font-size:clamp(8px,.72vw,11px);letter-spacing:1.5px;color:var(--faint);white-space:nowrap}

  /* ---------- KPI cards ---------- */
  #kpis{display:grid;grid-template-columns:repeat(4,1fr);gap:clamp(10px,1.1vw,18px)}
  .kpi{
    position:relative;border:1px solid var(--panel-edge);border-radius:14px;
    padding:clamp(11px,1.1vw,18px) clamp(12px,1.2vw,20px);
    background:linear-gradient(180deg, rgba(8,22,46,.62), rgba(4,8,22,.62));
    box-shadow:inset 0 1px 0 rgba(92,243,255,.12), 0 8px 30px rgba(0,0,0,.4), 0 0 18px rgba(39,231,255,.05);
    overflow:hidden;min-width:0;
  }
  .kpi::after{
    content:"";position:absolute;left:0;top:0;height:2px;width:100%;
    background:linear-gradient(90deg,var(--cyan),transparent);opacity:.8;
    box-shadow:0 0 10px var(--cyan);
  }
  .kpi .spark{position:absolute;left:0;right:0;bottom:0;height:30%;opacity:.5}
  .kpi .klabel{
    position:relative;
    font-size:clamp(9px,.78vw,12px);letter-spacing:2px;text-transform:uppercase;
    color:var(--muted);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;
  }
  .kpi .kval{position:relative;display:flex;align-items:baseline;gap:5px;margin-top:clamp(5px,.55vw,11px)}
  .kpi .num{
    font-family:var(--mono);font-weight:800;
    font-variant-numeric:tabular-nums;font-feature-settings:"tnum" 1;
    font-size:clamp(24px,2.9vw,48px);line-height:.92;color:#fff;
    text-shadow:0 0 22px rgba(39,231,255,.4);
  }
  .kpi .unit{font-family:var(--mono);font-size:clamp(11px,1vw,17px);color:var(--cyan);font-weight:700}
  .kpi .delta{
    position:relative;
    display:inline-flex;align-items:center;gap:5px;margin-top:clamp(7px,.6vw,13px);
    padding:3px 9px;border-radius:999px;font-family:var(--mono);font-weight:700;
    font-size:clamp(9px,.8vw,13px);letter-spacing:.5px;
  }
  .kpi .delta.up{color:var(--green);background:var(--green-soft);border:1px solid rgba(61,255,176,.35)}
  .kpi .delta.down{color:var(--red);background:rgba(255,93,138,.12);border:1px solid rgba(255,93,138,.35)}
  .kpi .delta.flat{color:var(--muted);background:rgba(94,127,166,.12);border:1px solid rgba(94,127,166,.3)}
  .kpi .delta .arrow{font-size:1.05em;line-height:1}

  /* ---------- hero chart ---------- */
  .chartcard{display:flex;flex-direction:column}
  .chartcard .legend{display:flex;gap:16px;flex-wrap:wrap;padding:0 clamp(14px,1.4vw,20px) clamp(4px,.5vw,8px)}
  .chartcard .legend .lg{display:inline-flex;align-items:center;gap:7px;font-family:var(--mono);font-size:clamp(9px,.82vw,12px);color:var(--muted);letter-spacing:1px}
  .chartcard .legend .sw{width:13px;height:4px;border-radius:2px}
  .chartcard .cwrap{position:relative;flex:1 1 auto;min-height:0;padding:0 clamp(8px,.9vw,14px) clamp(8px,1vw,14px)}
  canvas{display:block;width:100%;height:100%}

  /* ---------- bar + donut ---------- */
  .barcard,.donutcard{display:flex;flex-direction:column;min-height:0}
  .barcard .cwrap,.donutcard .cwrap{position:relative;flex:1 1 auto;min-height:0;padding:0 clamp(8px,.9vw,14px) clamp(8px,1vw,14px)}
  .donutcard .cwrap{padding:0 8px 8px}
  .donutlegend{position:absolute;inset:0;display:flex;flex-direction:column;justify-content:center;gap:6px;padding-left:clamp(8px,1vw,16px);padding-right:46%;pointer-events:none}
  .donutlegend .dl{display:flex;align-items:center;gap:8px;font-family:var(--mono);font-size:clamp(8px,.74vw,11px);color:var(--muted);letter-spacing:.5px;white-space:nowrap;overflow:hidden}
  .donutlegend .dl .dot{width:8px;height:8px;border-radius:2px;flex:0 0 auto;box-shadow:0 0 8px currentColor}
  .donutlegend .dl .nm{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
  .donutlegend .dl b{color:#dff7ff;font-weight:700;margin-left:auto;padding-left:8px}

  /* ---------- table ---------- */
  .tablecard{display:flex;flex-direction:column;min-height:0}
  .twrap{flex:1 1 auto;min-height:0;overflow:hidden;padding:clamp(4px,.5vw,8px) clamp(10px,1vw,16px) clamp(8px,.9vw,14px)}
  table{width:100%;border-collapse:collapse;font-size:clamp(10px,.95vw,14px);table-layout:fixed}
  thead th{
    text-align:left;font-family:var(--mono);font-size:clamp(8px,.72vw,11px);
    letter-spacing:1.5px;text-transform:uppercase;color:var(--faint);
    padding:7px 9px;border-bottom:1px solid rgba(39,231,255,.2);font-weight:600;
  }
  thead th:not(:first-child){text-align:right}
  tbody td{
    padding:clamp(6px,.7vw,11px) 9px;border-bottom:1px solid rgba(39,231,255,.07);
    color:var(--ink);font-variant-numeric:tabular-nums;
    overflow:hidden;text-overflow:ellipsis;white-space:nowrap;
  }
  tbody td:not(:first-child){text-align:right;font-family:var(--mono);font-weight:600}
  tbody td:first-child{font-weight:600;color:#dff7ff}
  tbody tr{transition:background .3s}
  tbody tr:hover{background:rgba(39,231,255,.05)}
  td .pill{padding:3px 9px;border-radius:999px;font-size:.85em;font-family:var(--mono);letter-spacing:.5px}
  td .pos{color:var(--green);background:var(--green-soft);border:1px solid rgba(61,255,176,.25)}
  td .neg{color:var(--red);background:rgba(255,93,138,.12);border:1px solid rgba(255,93,138,.25)}
  td .stat{color:var(--cyan);background:var(--cyan-soft);border:1px solid rgba(39,231,255,.25);text-transform:uppercase;font-size:.78em;letter-spacing:1px}

  /* ---------- feed ---------- */
  .feedcard{display:flex;flex-direction:column;min-height:0}
  .feed{flex:1 1 auto;min-height:0;overflow:hidden;padding:5px clamp(12px,1.2vw,16px) clamp(10px,1vw,14px);display:flex;flex-direction:column;gap:clamp(6px,.6vw,10px)}
  .ev{
    display:grid;grid-template-columns:auto 1fr;gap:11px;align-items:stretch;
    padding:clamp(7px,.8vw,12px) clamp(10px,1vw,14px);
    border-radius:11px;border:1px solid rgba(39,231,255,.13);
    background:linear-gradient(90deg, rgba(10,26,52,.5), rgba(5,9,24,.4));
    animation:slidein .5s cubic-bezier(.2,.9,.2,1);
  }
  @keyframes slidein{from{opacity:0;transform:translateX(14px) scale(.98)}to{opacity:1;transform:none}}
  .ev .bar{width:3px;align-self:stretch;border-radius:3px;background:var(--cyan);box-shadow:var(--glow-cyan)}
  .ev.good .bar{background:var(--green);box-shadow:var(--glow-green)}
  .ev.warn .bar{background:var(--amber);box-shadow:0 0 12px rgba(255,203,90,.6)}
  .ev.bad .bar{background:var(--red);box-shadow:0 0 12px rgba(255,93,138,.6)}
  .ev.info .bar{background:var(--cyan);box-shadow:var(--glow-cyan)}
  .ev .txt{font-size:clamp(10px,.92vw,14px);line-height:1.32;color:#d6ecff}
  .ev .ts{font-family:var(--mono);font-size:clamp(8px,.7vw,11px);color:var(--faint);letter-spacing:1px;margin-top:3px;display:block}

  .empty{display:grid;place-items:center;height:100%;color:var(--faint);font-family:var(--mono);letter-spacing:2px;text-transform:uppercase;font-size:clamp(10px,.95vw,14px)}

  /* number flash on change */
  .flash{animation:flash .65s ease}
  @keyframes flash{0%{color:var(--cyan-2);text-shadow:0 0 34px rgba(92,243,255,.95)}100%{}}
</style>
</head>
<body>
  <div class="fx grid"></div>
  <div class="fx dots"></div>
  <div class="fx sweep"></div>
  <div class="fx scan"></div>
  <div class="fx vig"></div>

  <div class="app">
    <header>
      <div class="wordmark">
        <div class="logo">
          <svg viewBox="0 0 100 100" aria-hidden="true">
            <circle cx="50" cy="50" r="34" fill="none" stroke="#031a26" stroke-width="14"/>
            <circle cx="50" cy="50" r="34" fill="none" stroke="#0a3b4c" stroke-width="9"/>
            <circle cx="50" cy="50" r="13" fill="#03101e"/>
          </svg>
        </div>
        <div class="brand">
          <div class="om">OMO</div>
          <div class="sub">Command&nbsp;Center</div>
        </div>
      </div>

      <div class="titlebox">
        <h1 id="title">—</h1>
        <div class="desc" id="subtitle"></div>
      </div>

      <div class="rail">
        <span class="statuschip" id="statuschip" style="display:none"><span class="dot"></span><span id="statustext"></span></span>
        <span class="live"><span class="pulse"></span>LIVE</span>
        <span class="clock"><span id="clock">--:--:--</span><span class="dim" id="updated">SYNC —</span></span>
      </div>
    </header>

    <div class="body">
      <section class="kpis">
        <div id="kpis"></div>
      </section>

      <section class="chartcard panel">
        <span class="corner tl"></span><span class="corner tr"></span><span class="corner bl"></span><span class="corner br"></span>
        <div class="ph"><span class="tick"></span><h2 id="charttitle">Performance</h2><span class="spacer"></span><span class="meta" id="chartmeta"></span></div>
        <div class="legend" id="legend"></div>
        <div class="cwrap"><canvas id="chart"></canvas></div>
      </section>

      <section class="lowrow">
        <div class="barcard panel">
          <span class="corner tl"></span><span class="corner tr"></span><span class="corner bl"></span><span class="corner br"></span>
          <div class="ph"><span class="tick"></span><h2 id="bartitle">By Campaign</h2></div>
          <div class="cwrap"><canvas id="bars"></canvas></div>
        </div>
        <div class="donutcard panel">
          <span class="corner tl"></span><span class="corner tr"></span><span class="corner bl"></span><span class="corner br"></span>
          <div class="ph"><span class="tick"></span><h2 id="donuttitle">Breakdown</h2></div>
          <div class="cwrap"><canvas id="donut"></canvas><div class="donutlegend" id="donutlegend"></div></div>
        </div>
      </section>

      <aside class="side">
        <section class="tablecard panel">
          <span class="corner tl"></span><span class="corner tr"></span><span class="corner bl"></span><span class="corner br"></span>
          <div class="ph"><span class="tick"></span><h2 id="tabletitle">Campaigns</h2></div>
          <div class="twrap"><table><thead id="thead"></thead><tbody id="tbody"></tbody></table></div>
        </section>

        <section class="feedcard panel">
          <span class="corner tl"></span><span class="corner tr"></span><span class="corner bl"></span><span class="corner br"></span>
          <div class="ph"><span class="tick"></span><h2>Live&nbsp;Feed</h2><span class="spacer"></span><span class="meta" id="feedmeta"></span></div>
          <div class="feed" id="feed"></div>
        </section>
      </aside>
    </div>
  </div>

<script>
(function(){
  "use strict";
  var DASH_ID = '{{DASH_ID}}';
  var POLL_MS = 1500;
  var DATA_URL = "/dash/" + encodeURIComponent(DASH_ID) + "/data";
  var MONO = 'ui-monospace,Menlo,Consolas,monospace';

  // Cyan-family palette for derived bar/donut segments.
  var PALETTE=["#27e7ff","#3dffb0","#5cf3ff","#9d7bff","#ffcb5a","#ff5d8a","#62ffc9","#7ac8ff"];

  // ---------- tiny helpers ----------
  function $(id){return document.getElementById(id);}
  function clamp(n,a,b){return Math.max(a,Math.min(b,n));}
  function lerp(a,b,t){return a+(b-a)*t;}
  function easeOut(t){return 1-Math.pow(1-t,3);}
  function easeInOut(t){return t<.5?2*t*t:1-Math.pow(-2*t+2,2)/2;}

  function hexA(hex,a){
    var c=String(hex).replace("#","");
    if(c.length===3){c=c[0]+c[0]+c[1]+c[1]+c[2]+c[2];}
    var r=parseInt(c.substr(0,2),16),g=parseInt(c.substr(2,2),16),b=parseInt(c.substr(4,2),16);
    if(isNaN(r))return hex;
    return "rgba("+r+","+g+","+b+","+a+")";
  }

  // Parse "$1,234.5" / "12.3" / "8,901" into a number + remembered format.
  function parseNumeric(v){
    if(typeof v==="number"){return {ok:true,n:v,pre:"",suf:"",dec:0};}
    var s=String(v);
    var m=s.match(/^([^0-9\-]*)(-?[0-9][0-9,]*\.?[0-9]*)(.*)$/);
    if(!m){return {ok:false,raw:s};}
    var pre=m[1]||"", body=m[2], suf=m[3]||"";
    var dec=(body.split(".")[1]||"").length;
    var n=parseFloat(body.replace(/,/g,""));
    if(isNaN(n)){return {ok:false,raw:s};}
    return {ok:true,n:n,pre:pre,suf:suf,dec:dec,grouped:body.indexOf(",")>=0};
  }
  function fmtNumeric(meta,val){
    var fixed=val.toFixed(meta.dec);
    if(meta.grouped){
      var parts=fixed.split(".");
      parts[0]=parts[0].replace(/\B(?=(\d{3})+(?!\d))/g,",");
      fixed=parts.join(".");
    }
    return meta.pre+fixed+meta.suf;
  }
  // Pull the magnitude out of a cell, for deriving bars/donut from a table column.
  function numFrom(v){
    if(typeof v==="number")return v;
    var m=String(v).match(/-?[0-9][0-9,]*\.?[0-9]*/);
    if(!m)return null;
    var n=parseFloat(m[0].replace(/,/g,""));
    return isNaN(n)?null:n;
  }
  function compact(n){
    var a=Math.abs(n);
    if(a>=1e6)return (n/1e6).toFixed(1).replace(/\.0$/,"")+"M";
    if(a>=1e3)return (n/1e3).toFixed(1).replace(/\.0$/,"")+"k";
    if(a>=100)return String(Math.round(n));
    return (Math.round(n*10)/10).toString();
  }
  function humanizeAgo(ms){
    if(!ms||ms<0)ms=0;
    var s=Math.floor(ms/1000);
    if(s<2)return "JUST NOW";
    if(s<60)return s+"S AGO";
    var m=Math.floor(s/60);
    if(m<60)return m+"M AGO";
    var h=Math.floor(m/60);
    return h+"H AGO";
  }
  function escapeHtml(s){
    return String(s).replace(/[&<>"']/g,function(c){
      return {"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c];
    });
  }

  // ===================================================================
  // KPI tiles with number tween + tiny baked sparkline
  // ===================================================================
  var kpiState={}; // label -> {meta, cur, from, target, t0, dur, hist}
  function renderKpis(kpis){
    var host=$("kpis");
    if(!kpis||!kpis.length){host.innerHTML='<div class="empty" style="grid-column:1/-1">awaiting telemetry</div>';return;}
    var seen={};
    kpis.slice(0,4).forEach(function(k){
      seen[k.label]=true;
      var id="kpi_"+k.label.replace(/[^a-z0-9]/gi,"_");
      var el=$(id);
      if(!el){
        el=document.createElement("div");
        el.className="kpi";el.id=id;
        el.innerHTML='<canvas class="spark"></canvas><div class="klabel"></div><div class="kval"><span class="num">—</span><span class="unit"></span></div><span class="delta"></span>';
        host.appendChild(el);
      }
      el.querySelector(".klabel").textContent=k.label;
      el.querySelector(".unit").textContent=k.unit||"";
      var d=el.querySelector(".delta");
      if(k.delta){
        var trend=k.trend||(/^-/.test(String(k.delta))?"down":"up");
        d.className="delta "+trend;
        var arrow=trend==="up"?"▲":trend==="down"?"▼":"▶";
        d.innerHTML='<span class="arrow">'+arrow+'</span>'+escapeHtml(String(k.delta));
        d.style.display="";
      } else { d.style.display="none"; }

      var meta=parseNumeric(k.value);
      var numEl=el.querySelector(".num");
      var prev=kpiState[k.label];
      if(!meta.ok){
        numEl.textContent=String(k.value);
        if(!prev)kpiState[k.label]={meta:null,cur:0,from:0,target:0,t0:0,dur:0,hist:[]};
        return;
      }
      if(!prev||!prev.meta){
        kpiState[k.label]={meta:meta,cur:meta.n,from:meta.n,target:meta.n,t0:0,dur:0,hist:[meta.n]};
        numEl.textContent=fmtNumeric(meta,meta.n);
      } else if(prev.target!==meta.n){
        prev.meta=meta;prev.from=prev.cur;prev.target=meta.n;prev.t0=performance.now();prev.dur=750;
        prev.hist.push(meta.n);if(prev.hist.length>26)prev.hist.shift();
        numEl.classList.remove("flash"); void numEl.offsetWidth; numEl.classList.add("flash");
      } else {
        prev.meta=meta;
      }
    });
    Array.prototype.slice.call(host.children).forEach(function(c){
      if(c.id&&c.id.indexOf("kpi_")===0){
        var lbl=c.querySelector(".klabel");
        if(lbl&&!seen[lbl.textContent]){c.remove();}
      }
    });
  }
  function tickKpis(now){
    for(var label in kpiState){
      var st=kpiState[label];
      var id="kpi_"+label.replace(/[^a-z0-9]/gi,"_");
      var el=document.getElementById(id);if(!el)continue;
      if(st.meta&&st.dur){
        var p=clamp((now-st.t0)/st.dur,0,1);
        st.cur=lerp(st.from,st.target,easeOut(p));
        el.querySelector(".num").textContent=fmtNumeric(st.meta,st.cur);
        if(p>=1){st.dur=0;st.cur=st.target;}
      }
      var sc=el.querySelector(".spark");
      if(sc&&st.hist&&st.hist.length>1){drawSpark(sc,st.hist);}
    }
  }
  function drawSpark(cv,hist){
    var dpr=Math.min(window.devicePixelRatio||1,2);
    var w=cv.clientWidth,h=cv.clientHeight;
    if(!w||!h)return;
    if(cv.width!==Math.floor(w*dpr)||cv.height!==Math.floor(h*dpr)){
      cv.width=Math.max(2,Math.floor(w*dpr));cv.height=Math.max(2,Math.floor(h*dpr));
    }
    var g=cv.getContext("2d");
    g.setTransform(dpr,0,0,dpr,0,0);
    g.clearRect(0,0,w,h);
    var lo=Infinity,hi=-Infinity,i;
    for(i=0;i<hist.length;i++){lo=Math.min(lo,hist[i]);hi=Math.max(hi,hist[i]);}
    if(lo===hi){hi=lo+1;}
    var n=hist.length;
    function X(i){return (i/(n-1))*w;}
    function Y(v){return h-((v-lo)/(hi-lo))*(h*0.8)-h*0.1;}
    g.beginPath();g.moveTo(X(0),Y(hist[0]));
    for(i=1;i<n;i++)g.lineTo(X(i),Y(hist[i]));
    g.lineTo(w,h);g.lineTo(0,h);g.closePath();
    var grad=g.createLinearGradient(0,0,0,h);
    grad.addColorStop(0,"rgba(39,231,255,.28)");grad.addColorStop(1,"rgba(39,231,255,0)");
    g.fillStyle=grad;g.fill();
    g.beginPath();g.moveTo(X(0),Y(hist[0]));
    for(i=1;i<n;i++)g.lineTo(X(i),Y(hist[i]));
    g.strokeStyle="rgba(39,231,255,.7)";g.lineWidth=1.5;g.lineJoin="round";g.stroke();
  }

  // ===================================================================
  // Hero line/area chart with eased morph
  // ===================================================================
  var chartCanvas=$("chart"),ctx=chartCanvas.getContext("2d");
  var chartCur=[],chartFrom=[],chartTarget=[];
  var chartColor="#27e7ff",chartT0=0,chartDur=0,chartMin=0,chartMax=1;

  function setSeries(series){
    var s=(series&&series[0])||null;
    var leg=$("legend");leg.innerHTML="";
    if(!s||!s.points||!s.points.length){chartTarget=[];chartCur=[];$("chartmeta").textContent="";draw();return;}
    chartColor=s.color||"#27e7ff";
    (series||[]).forEach(function(ss){
      var sp=document.createElement("span");sp.className="lg";
      sp.innerHTML='<span class="sw" style="background:'+(ss.color||"#27e7ff")+';box-shadow:0 0 10px '+(ss.color||"#27e7ff")+'"></span>'+escapeHtml(ss.name||"");
      leg.appendChild(sp);
    });
    var pts=s.points.slice();
    var lo=Infinity,hi=-Infinity;
    for(var k=0;k<pts.length;k++){lo=Math.min(lo,pts[k]);hi=Math.max(hi,pts[k]);}
    $("chartmeta").textContent=pts.length+" PTS · ▲"+compact(hi)+" ▼"+compact(lo);
    if(!chartCur.length){chartCur=pts.slice();chartFrom=pts.slice();}
    else if(chartCur.length!==pts.length){
      var resampled=[];
      for(var i=0;i<pts.length;i++){
        var idx=(i/(pts.length-1))*(chartCur.length-1);
        var loi=Math.floor(idx),hii=Math.ceil(idx),f=idx-loi;
        resampled.push(lerp(chartCur[loi]||0,chartCur[hii]||0,f));
      }
      chartCur=resampled;chartFrom=resampled.slice();
    } else {
      chartFrom=chartCur.slice();
    }
    chartTarget=pts;chartT0=performance.now();chartDur=900;
  }

  function recomputeBounds(){
    var all=chartTarget.length?chartTarget:chartCur;
    if(!all.length){chartMin=0;chartMax=1;return;}
    var lo=Infinity,hi=-Infinity;
    for(var i=0;i<all.length;i++){lo=Math.min(lo,all[i]);hi=Math.max(hi,all[i]);}
    if(lo===hi){hi=lo+1;}
    var pad=(hi-lo)*0.18;
    chartMin=lo-pad;chartMax=hi+pad;
  }
  function resizeCanvas(cv,c){
    var wrap=cv.parentElement;
    var dpr=Math.min(window.devicePixelRatio||1,2);
    var w=wrap.clientWidth,h=wrap.clientHeight;
    cv.width=Math.max(2,Math.floor(w*dpr));cv.height=Math.max(2,Math.floor(h*dpr));
    c.setTransform(dpr,0,0,dpr,0,0);
  }

  function draw(){
    var w=chartCanvas.clientWidth,h=chartCanvas.clientHeight;
    ctx.clearRect(0,0,w,h);
    var padL=12,padR=14,padT=12,padB=16;
    var iw=w-padL-padR,ih=h-padT-padB;

    // grid
    var g,y,x;
    ctx.lineWidth=1;ctx.strokeStyle="rgba(39,231,255,.10)";ctx.beginPath();
    for(g=0;g<=4;g++){y=padT+ih*(g/4);ctx.moveTo(padL,y);ctx.lineTo(padL+iw,y);}
    ctx.stroke();
    ctx.strokeStyle="rgba(39,231,255,.05)";ctx.beginPath();
    for(g=0;g<=6;g++){x=padL+iw*(g/6);ctx.moveTo(x,padT);ctx.lineTo(x,padT+ih);}
    ctx.stroke();

    var pts=chartCur;
    if(!pts||pts.length<2){return;}
    recomputeBounds();
    var n=pts.length;
    function X(i){return padL+iw*(i/(n-1));}
    function Y(v){return padT+ih*(1-((v-chartMin)/(chartMax-chartMin)));}

    function buildPath(close){
      ctx.beginPath();ctx.moveTo(X(0),Y(pts[0]));
      for(var i=0;i<n-1;i++){
        var x0=X(Math.max(0,i-1)),y0=Y(pts[Math.max(0,i-1)]);
        var x1=X(i),y1=Y(pts[i]);
        var x2=X(i+1),y2=Y(pts[i+1]);
        var x3=X(Math.min(n-1,i+2)),y3=Y(pts[Math.min(n-1,i+2)]);
        var c1x=x1+(x2-x0)/6,c1y=y1+(y2-y0)/6;
        var c2x=x2-(x3-x1)/6,c2y=y2-(y3-y1)/6;
        ctx.bezierCurveTo(c1x,c1y,c2x,c2y,x2,y2);
      }
      if(close){ctx.lineTo(X(n-1),padT+ih);ctx.lineTo(X(0),padT+ih);ctx.closePath();}
    }

    // area fill
    var grad=ctx.createLinearGradient(0,padT,0,padT+ih);
    grad.addColorStop(0,hexA(chartColor,.38));
    grad.addColorStop(.65,hexA(chartColor,.08));
    grad.addColorStop(1,hexA(chartColor,0));
    buildPath(true);ctx.fillStyle=grad;ctx.fill();

    // under-glow (thick, faint) then crisp line
    ctx.lineJoin="round";ctx.lineCap="round";
    ctx.shadowColor=chartColor;ctx.shadowBlur=24;
    ctx.strokeStyle=hexA(chartColor,.35);ctx.lineWidth=6;
    buildPath(false);ctx.stroke();
    ctx.shadowBlur=14;ctx.strokeStyle=chartColor;ctx.lineWidth=2.6;
    buildPath(false);ctx.stroke();
    ctx.shadowBlur=0;

    // last-point marker + radar ping
    var lx=X(n-1),ly=Y(pts[n-1]);
    ctx.beginPath();ctx.arc(lx,ly,8,0,Math.PI*2);ctx.fillStyle=hexA(chartColor,.18);ctx.fill();
    var rp=(performance.now()/1000)%1;
    ctx.beginPath();ctx.arc(lx,ly,8+rp*9,0,Math.PI*2);ctx.strokeStyle=hexA(chartColor,(1-rp)*.5);ctx.lineWidth=1.5;ctx.stroke();
    ctx.beginPath();ctx.arc(lx,ly,3.4,0,Math.PI*2);ctx.fillStyle="#fff";ctx.shadowColor=chartColor;ctx.shadowBlur=16;ctx.fill();ctx.shadowBlur=0;
  }
  function tickChart(now){
    if(chartDur){
      var p=clamp((now-chartT0)/chartDur,0,1),e=easeOut(p),n=chartTarget.length,out=[];
      for(var i=0;i<n;i++){var f=(i<chartFrom.length)?chartFrom[i]:chartTarget[i];out.push(lerp(f,chartTarget[i],e));}
      chartCur=out;if(p>=1){chartDur=0;chartCur=chartTarget.slice();}
    }
    draw();
  }

  // ===================================================================
  // Bar chart — derived from the most "bar-worthy" numeric table column
  // ===================================================================
  var barCanvas=$("bars"),bctx=barCanvas.getContext("2d");
  var barCur=[],barFrom=[],barTarget=[],barLabels=[],barT0=0,barDur=0,barHasData=false;

  function deriveBars(table){
    if(!table||!table.columns||!table.rows||!table.rows.length){barHasData=false;$("bartitle").textContent="By Campaign";return;}
    var cols=table.columns,rows=table.rows.slice(0,7);
    var best=-1,bestScore=-1,bestName="";
    for(var c=1;c<cols.length;c++){
      var name=String(cols[c]);
      if(/Δ|delta|status|state/i.test(name))continue;
      var vals=[],ok=0,lo=Infinity,hi=-Infinity;
      for(var r=0;r<rows.length;r++){var v=numFrom(rows[r][c]);if(v!=null){vals.push(v);ok++;lo=Math.min(lo,v);hi=Math.max(hi,v);}else vals.push(0);}
      if(ok<2)continue;
      var score=(hi-lo)+hi*0.001;
      if(/spend|rev|cost|budget|sales|amount|impr|click|reach|\$/i.test(name))score*=1.4;
      if(score>bestScore){bestScore=score;best=c;bestName=name;}
    }
    if(best<0){barHasData=false;$("bartitle").textContent="By Campaign";return;}
    var labels=[],target=[];
    for(var rr=0;rr<rows.length;rr++){
      labels.push(String(rows[rr][0]));
      var vv=numFrom(rows[rr][best]);target.push(vv==null?0:Math.max(0,vv));
    }
    barLabels=labels;
    $("bartitle").textContent=bestName+" · by "+String(cols[0]);
    if(barCur.length!==target.length){barCur=target.map(function(){return 0;});}
    barFrom=barCur.slice();barTarget=target;barT0=performance.now();barDur=850;barHasData=true;
  }
  function roundRect(c,x,y,w,h,r){
    if(h<=0){c.beginPath();return;}
    r=Math.min(r,w/2,h/2);
    c.beginPath();c.moveTo(x+r,y);c.arcTo(x+w,y,x+w,y+h,r);c.arcTo(x+w,y+h,x,y+h,0);
    c.lineTo(x,y+h);c.lineTo(x,y+r);c.arcTo(x,y,x+w,y,r);c.closePath();
  }
  function drawBars(){
    var w=barCanvas.clientWidth,h=barCanvas.clientHeight;
    bctx.clearRect(0,0,w,h);
    if(!barHasData||!barTarget.length){return;}
    var padL=8,padR=8,padT=10,padB=20;
    var iw=w-padL-padR,ih=h-padT-padB;
    var hiT=-Infinity,i;for(i=0;i<barTarget.length;i++)hiT=Math.max(hiT,barTarget[i]);
    if(hiT<=0)hiT=1;
    var n=barTarget.length;
    var gap=iw*0.32/Math.max(1,n),bw=(iw-gap*(n-1))/n;
    bctx.strokeStyle="rgba(39,231,255,.12)";bctx.lineWidth=1;
    bctx.beginPath();bctx.moveTo(padL,padT+ih);bctx.lineTo(padL+iw,padT+ih);bctx.stroke();
    for(i=0;i<n;i++){
      var x=padL+i*(bw+gap);
      var v=barCur[i]||0;
      var bh=Math.max(0,(v/hiT)*ih);
      var y=padT+ih-bh;
      var col=PALETTE[i%PALETTE.length];
      var grad=bctx.createLinearGradient(0,y,0,padT+ih);
      grad.addColorStop(0,hexA(col,.95));grad.addColorStop(1,hexA(col,.12));
      bctx.shadowColor=col;bctx.shadowBlur=14;
      roundRect(bctx,x,y,bw,bh,Math.min(5,bw/2));
      bctx.fillStyle=grad;bctx.fill();
      bctx.shadowBlur=0;
      if(bh>2){bctx.fillStyle="#fff";bctx.globalAlpha=.85;roundRect(bctx,x,y,bw,Math.min(2.5,bh),1);bctx.fill();bctx.globalAlpha=1;}
      if(bh>14){
        bctx.fillStyle=hexA(col,.95);bctx.font="600 "+Math.max(8,Math.min(12,bw*0.4))+"px "+MONO;
        bctx.textAlign="center";bctx.fillText(compact(barTarget[i]),x+bw/2,y-4);
      }
      bctx.fillStyle="rgba(94,127,166,.9)";bctx.font="600 9px "+MONO;bctx.textAlign="center";
      var lbl=barLabels[i]||"";
      if(lbl.length>9)lbl=lbl.slice(0,8)+"…";
      bctx.fillText(lbl,x+bw/2,padT+ih+13);
    }
  }
  function tickBars(now){
    if(barDur){
      var p=clamp((now-barT0)/barDur,0,1),e=easeOut(p),out=[];
      for(var i=0;i<barTarget.length;i++){var f=(i<barFrom.length)?barFrom[i]:0;out.push(lerp(f,barTarget[i],e));}
      barCur=out;if(p>=1){barDur=0;barCur=barTarget.slice();}
    }
    drawBars();
  }

  // ===================================================================
  // Donut — share-of-total derived from the table (falls back to KPIs)
  // ===================================================================
  var donutCanvas=$("donut"),dctx=donutCanvas.getContext("2d");
  var donutCur=[],donutFrom=[],donutTarget=[],donutSegs=[],donutT0=0,donutDur=0,donutHasData=false,donutTotal=0;

  function deriveDonut(table,kpis){
    var segs=[];
    if(table&&table.columns&&table.rows&&table.rows.length){
      var cols=table.columns,rows=table.rows.slice(0,6);
      var best=-1,bestScore=-1;
      for(var c=1;c<cols.length;c++){
        var name=String(cols[c]);
        if(/Δ|delta|status|state|%/i.test(name))continue;
        var ok=0,hi=-Infinity;
        for(var r=0;r<rows.length;r++){var v=numFrom(rows[r][c]);if(v!=null&&v>0){ok++;hi=Math.max(hi,v);}}
        if(ok<2)continue;
        var score=hi;if(/spend|rev|cost|budget|sales|amount|\$/i.test(name))score*=1.5;
        if(score>bestScore){bestScore=score;best=c;}
      }
      if(best>=0){
        $("donuttitle").textContent=String(cols[best])+" share";
        for(var rr=0;rr<rows.length;rr++){
          var vv=numFrom(rows[rr][best]);
          if(vv!=null&&vv>0)segs.push({name:String(rows[rr][0]),val:vv});
        }
      }
    }
    if(segs.length<2&&kpis&&kpis.length){
      segs=[];
      $("donuttitle").textContent="Signal mix";
      kpis.slice(0,5).forEach(function(k){
        var m=parseNumeric(k.value);
        if(m.ok&&m.n>0)segs.push({name:k.label,val:m.n});
      });
    }
    if(segs.length<2){donutHasData=false;$("donutlegend").innerHTML="";drawDonut();return;}
    segs.forEach(function(s,i){s.color=PALETTE[i%PALETTE.length];});
    donutTotal=0;segs.forEach(function(s){donutTotal+=s.val;});
    donutSegs=segs;
    var target=segs.map(function(s){return s.val/donutTotal;});
    if(donutCur.length!==target.length)donutCur=target.map(function(){return 0;});
    donutFrom=donutCur.slice();donutTarget=target;donutT0=performance.now();donutDur=900;donutHasData=true;
    var lh="";
    segs.forEach(function(s){
      var pct=Math.round((s.val/donutTotal)*100);
      lh+='<div class="dl"><span class="dot" style="background:'+s.color+';color:'+s.color+'"></span><span class="nm">'+escapeHtml(s.name)+'</span><b>'+pct+'%</b></div>';
    });
    $("donutlegend").innerHTML=lh;
  }
  function drawDonut(){
    var w=donutCanvas.clientWidth,h=donutCanvas.clientHeight;
    dctx.clearRect(0,0,w,h);
    if(!donutHasData||!donutCur.length){return;}
    var cx=w*0.74,cy=h*0.52,R=Math.min(w*0.24,h*0.42),r=R*0.62;
    var a=-Math.PI/2,i;
    for(i=0;i<donutCur.length;i++){
      var frac=donutCur[i];if(frac<=0)continue;
      var a2=a+frac*Math.PI*2;
      var col=(donutSegs[i]&&donutSegs[i].color)||PALETTE[i%PALETTE.length];
      dctx.beginPath();
      dctx.arc(cx,cy,R,a,a2);dctx.arc(cx,cy,r,a2,a,true);dctx.closePath();
      dctx.fillStyle=hexA(col,.9);dctx.shadowColor=col;dctx.shadowBlur=16;dctx.fill();dctx.shadowBlur=0;
      dctx.strokeStyle="rgba(2,3,10,.9)";dctx.lineWidth=2;dctx.stroke();
      a=a2;
    }
    dctx.beginPath();dctx.arc(cx,cy,r-1,0,Math.PI*2);
    dctx.strokeStyle="rgba(39,231,255,.25)";dctx.lineWidth=1;dctx.stroke();
    dctx.textAlign="center";dctx.textBaseline="middle";
    dctx.fillStyle="#eafcff";dctx.font="700 "+Math.max(13,R*0.4)+"px "+MONO;
    dctx.shadowColor="#27e7ff";dctx.shadowBlur=12;
    dctx.fillText(compact(donutTotal),cx,cy-2);dctx.shadowBlur=0;
    dctx.fillStyle="rgba(94,127,166,.9)";dctx.font="600 9px "+MONO;
    dctx.fillText("TOTAL",cx,cy+R*0.46+2);
  }
  function tickDonut(now){
    if(donutDur){
      var p=clamp((now-donutT0)/donutDur,0,1),e=easeInOut(p),out=[];
      for(var i=0;i<donutTarget.length;i++){var f=(i<donutFrom.length)?donutFrom[i]:0;out.push(lerp(f,donutTarget[i],e));}
      donutCur=out;if(p>=1){donutDur=0;donutCur=donutTarget.slice();}
    }
    drawDonut();
  }

  // ===================================================================
  // Table
  // ===================================================================
  function renderTable(table,title){
    if(title)$("tabletitle").textContent=title;
    var thead=$("thead"),tbody=$("tbody");
    if(!table||!table.columns){thead.innerHTML="";tbody.innerHTML='<tr><td><span class="empty" style="height:auto;padding:20px 0">no rows</span></td></tr>';return;}
    var hr="<tr>";table.columns.forEach(function(c){hr+="<th>"+escapeHtml(c)+"</th>";});hr+="</tr>";
    thead.innerHTML=hr;
    var body="";
    (table.rows||[]).slice(0,8).forEach(function(row){
      body+="<tr>";
      row.forEach(function(cell,ci){
        var v=String(cell);
        var t=v.trim();
        if(ci===0){body+="<td>"+escapeHtml(v)+"</td>";return;}
        if(/^(active|paused|live|learning|review|on|off|enabled|disabled)$/i.test(t)){
          body+='<td><span class="pill stat">'+escapeHtml(v)+'</span></td>';
        } else if(/%/.test(v)&&/^[+\-]?\$?[\d.,]+%$/.test(t)){
          var neg=/^-/.test(t);
          body+='<td><span class="pill '+(neg?'neg':'pos')+'">'+escapeHtml(v)+'</span></td>';
        } else {
          body+="<td>"+escapeHtml(v)+"</td>";
        }
      });
      body+="</tr>";
    });
    tbody.innerHTML=body||'<tr><td>—</td></tr>';
  }

  // ===================================================================
  // Feed (diff so new rows animate)
  // ===================================================================
  var feedKey="";
  function renderFeed(feed){
    var host=$("feed");
    if(!feed||!feed.length){host.innerHTML='<div class="empty">no events yet</div>';feedKey="";$("feedmeta").textContent="";return;}
    $("feedmeta").textContent=feed.length+" EVENTS";
    var key=feed.map(function(e){return (e.ts||"")+"|"+e.text;}).join("§");
    if(key===feedKey)return;
    feedKey=key;
    var html="";
    feed.slice(0,7).forEach(function(e){
      var tone=e.tone||"info";
      html+='<div class="ev '+tone+'"><div class="bar"></div><div><div class="txt">'+escapeHtml(e.text)+'</div>'+(e.ts?'<span class="ts">'+escapeHtml(e.ts)+'</span>':'')+'</div></div>';
    });
    host.innerHTML=html;
  }

  // ===================================================================
  // Apply a data payload
  // ===================================================================
  var lastUpdatedAt=0;
  function apply(data){
    if(!data){return;}
    $("title").textContent=data.title||"Untitled";
    document.title="OMO · "+(data.title||"Command Center");
    var sub=$("subtitle");sub.textContent=data.subtitle||"";sub.style.display=data.subtitle?"":"none";
    var chip=$("statuschip");
    if(data.status){$("statustext").textContent=data.status;chip.style.display="";}
    else{chip.style.display="none";}

    renderKpis(data.kpis||[]);
    setSeries(data.series||[]);
    if(data.series&&data.series[0]){$("charttitle").textContent=(data.series[0].name||"Performance")+" · live";}
    else{$("charttitle").textContent="Performance";}
    deriveBars(data.table);
    deriveDonut(data.table,data.kpis||[]);
    renderTable(data.table,data.tableTitle);
    renderFeed(data.feed||[]);
    lastUpdatedAt=data.updatedAt||Date.now();
  }

  function pad(n){return n<10?"0"+n:""+n;}
  function refreshUpdated(){
    $("updated").textContent="SYNC "+humanizeAgo(Date.now()-lastUpdatedAt);
    var d=new Date();
    $("clock").textContent=pad(d.getHours())+":"+pad(d.getMinutes())+":"+pad(d.getSeconds());
  }

  // ---------- poll loop ----------
  function poll(){
    fetch(DATA_URL,{cache:"no-store"})
      .then(function(r){return r.ok?r.json():null;})
      .then(function(d){if(d)apply(d);})
      .catch(function(){/* offline tolerant — keep last frame */})
      .then(function(){setTimeout(poll,POLL_MS);});
  }

  // ---------- raf loop ----------
  function frame(now){
    tickKpis(now);
    tickChart(now);
    tickBars(now);
    tickDonut(now);
    refreshUpdated();
    requestAnimationFrame(frame);
  }

  function resizeAll(){
    resizeCanvas(chartCanvas,ctx);
    resizeCanvas(barCanvas,bctx);
    resizeCanvas(donutCanvas,dctx);
  }
  window.addEventListener("resize",function(){resizeAll();draw();drawBars();drawDonut();});
  resizeAll();
  draw();drawBars();drawDonut();
  poll();
  requestAnimationFrame(frame);
})();
</script>
</body>
</html>`;

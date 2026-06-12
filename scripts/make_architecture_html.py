#!/usr/bin/env python3
"""Render landing/architecture.html — the Omo Mission Control architecture as a
SITE-NATIVE page (matches landing/index.html's Minecraft pixel design system),
served at /architecture via Vercel cleanUrls. Embeds the architecture diagram
(docs/architecture.svg) as an in-world "schematic screen", plus an animated
isometric voxel HQ, the 5 layers, the self-extension loop, and the three
mandatory technologies cast as Minecraft ores (Gemini=diamond, ADK=emerald,
MCP=gold).
"""

import re

# ── Minecraft ore accents (from index.html palette) ───────────────────────────
DIAMOND, EMERALD, GOLD, REDSTONE, GRASS, STONE = (
    "#46E3D8", "#43C463", "#FFD13B", "#E5413B", "#6BBF3B", "#9A9A9A")


# ── isometric voxel cube helper ───────────────────────────────────────────────
def cube(ox, oy, s, top, left, right, body=None, cls="", edge="#0a1410"):
    b = body if body is not None else s
    w, h = s, s / 2
    T = (ox, oy); R = (ox + w, oy + h); B = (ox, oy + 2 * h); Lp = (ox - w, oy + h)
    B2 = (ox, oy + 2 * h + b); L2 = (ox - w, oy + h + b); R2 = (ox + w, oy + h + b)

    def poly(pts, fill):
        p = " ".join(f"{x:.1f},{y:.1f}" for x, y in pts)
        return f'<polygon points="{p}" fill="{fill}" stroke="{edge}" stroke-width="1.2"/>'
    g = f'<g class="{cls}">' if cls else "<g>"
    return (g + poly([Lp, B, B2, L2], left) + poly([R, B, B2, R2], right)
            + poly([T, R, B, Lp], top) + "</g>")


MAT = {
    "plinth": ("#243049", "#161f36", "#0e1626"),
    "copper": ("#e7ad77", "#c8854c", "#a35f31"),
    "quartz": ("#eef3fb", "#cbd6ea", "#aab8d2"),
    "glass":  ("#8af4ff", DIAMOND, "#1fa6b4"),
    "grass":  ("#8FE05A", GRASS, "#4d8a28"),
    "sea":    ("#b6f6f0", DIAMOND, "#1fa6b4"),
}


def voxel_hq():
    ox, oy, s = 270, 150, 24
    w, h, b = s, s / 2, s
    N = 5

    def proj(gx, gy, gz):
        return ox + (gx - gy) * w, oy + (gx + gy) * h - gz * b

    cubes = []
    for gx in range(N):
        for gy in range(N):
            edge = gx in (0, N - 1) or gy in (0, N - 1)
            cubes.append((gx, gy, 0, "copper" if edge else "plinth", ""))
    cubes += [(N - 1, N - 1, 0, "grass", ""), (N - 2, N - 1, 0, "grass", "")]
    for gz in (1, 2):
        for gx in range(N):
            for gy in range(N):
                if not (gx in (0, N - 1) or gy in (0, N - 1)):
                    continue
                if gy == 0 and gx == 2:
                    continue
                glass = (gy == 0 and gx in (1, 3)) or (gx == 0 and gy in (1, 3)) \
                    or (gx == N - 1 and gy in (1, 3)) or (gy == N - 1 and gx in (1, 3))
                cubes.append((gx, gy, gz, "glass" if glass else "quartz", ""))
    cubes.append((2, 1, 1, "sea", ""))
    for gx in range(N):
        for gy in range(N):
            cubes.append((gx, gy, 3, "sea" if gy == 0 and gx in (1, 2, 3) else "quartz", ""))
    cubes.append((2, 2, 4, "glass", "beacon"))
    cubes.sort(key=lambda c: (c[0] + c[1], c[2]))

    body = [f'<ellipse cx="{ox}" cy="{oy+150}" rx="200" ry="58" fill="{DIAMOND}" opacity="0.12" filter="url(#vg)"/>']
    for gx, gy, gz, mat, cls in cubes:
        sx, sy = proj(gx, gy, gz)
        t, l, r = MAT[mat]
        c = cube(sx, sy, s, t, l, r, cls=cls)
        if mat in ("glass", "sea"):
            c = c.replace("<g>", '<g filter="url(#vg)">', 1) if "<g>" in c \
                else c.replace('<g class', '<g filter="url(#vg)" class')
        body.append(c)
    for gx, gy, gz, mat, cls in [(0, -1, 6, "glass", "r1"), (4, -1, 7, "sea", "r2"),
                                 (2, -2, 8, "quartz", "r3"), (-1, 1, 6, "sea", "r2"),
                                 (5, 2, 7, "glass", "r1")]:
        sx, sy = proj(gx, gy, gz)
        t, l, r = MAT[mat]
        body.append(cube(sx, sy, s * 0.8, t, l, r, cls=f"rise {cls}"))

    return ('<svg class="hq" viewBox="0 -64 540 480" xmlns="http://www.w3.org/2000/svg" '
            'role="img" aria-label="Isometric Omo HQ with blocks rising">'
            '<defs><filter id="vg" x="-60%" y="-60%" width="220%" height="220%">'
            '<feGaussianBlur stdDeviation="5" result="b"/><feMerge>'
            '<feMergeNode in="b"/><feMergeNode in="SourceGraphic"/></feMerge></filter></defs>'
            + "".join(body) + "</svg>")


def mini_cube(accent, s=15):
    def shade(hexc, f):
        hexc = hexc.lstrip("#")
        r, g, bl = (int(hexc[i:i + 2], 16) for i in (0, 2, 4))
        r, g, bl = (max(0, min(255, int(v * f))) for v in (r, g, bl))
        return f"#{r:02x}{g:02x}{bl:02x}"
    inner = cube(s + 4, 4, s, shade(accent, 1.28), accent, shade(accent, 0.66))
    return (f'<svg class="vcube" viewBox="0 0 {2*s+8} {2.4*s+4:.0f}" '
            f'xmlns="http://www.w3.org/2000/svg" aria-hidden="true">{inner}</svg>')


# ── embed the architecture diagram (responsive) ───────────────────────────────
with open("/Users/harryedwards/omo-mc/docs/architecture.svg") as f:
    arch = f.read()
arch = re.sub(r'width="1720" height="1300"',
              'width="100%" height="auto" preserveAspectRatio="xMidYMid meet" class="archsvg"',
              arch, count=1)

# ── content data ──────────────────────────────────────────────────────────────
LAYERS = [
    ("01", REDSTONE, "YOU — in Minecraft",
     "An ordinary vanilla world. Speak a goal, talk to any agent, steer with "
     "<span class='cmd'>/revise</span>, and veto any outward action with one tap. "
     "Push-to-talk (V) routes your voice straight to the agents.", "the spatial interface"),
    ("02", GRASS, "THE WORLD — Java 21",
     "The Paper plugin + Omo client-mod are the only code that touches Minecraft: "
     "live block-by-block builds, villager spawning &amp; pathing, fullscreen dashboard "
     "screens, and the tap-to-approve UI.", "plugin/ · client-mod/"),
    ("03", DIAMOND, "THE ORCHESTRATOR — Node / TS",
     "One event loop: the WS bridge, the brain-selecting AgentManager, the AdkAgent SSE "
     "bridge, the WorldStore org-graph, the live dashboard server, the Gemini world-architect, "
     "and the <b>omo-tools MCP server</b> that hosts the World API.", "runtime/src/"),
    ("04", EMERALD, "THE ORGANISATION — ADK · Python",
     "A real multi-agent system: a Gemini Chief of Staff that <i>delegates</i> to Growth &amp; "
     "Comms and hires new specialists on demand. Served by <span class='cmd'>adk api_server</span>; "
     "tools arrive over MCP.", "omo-agent/"),
    ("05", GOLD, "THE OUTSIDE WORLD — over MCP",
     "Every tool reaches the agents the same way — over MCP. Real Meta Ads data is live today; "
     "Gemini is the brain of every agent. Google Workspace &amp; Stripe MCP are the roadmap.",
     "Meta Ads · Gemini · (Workspace · Stripe)"),
]
LOOP = ["world_describe", "world_add_function", "world_build", "world_staff", "world_assign"]
TECH = [
    (DIAMOND, "◆ GEMINI", "the diamond brain",
     "<span class='cmd'>gemini-flash-latest</span> runs the Chief of Staff, every specialist, and "
     "the architect that designs each building."),
    (EMERALD, "◆ ADK", "the emerald org",
     "An <span class='cmd'>LlmAgent</span> coordinator whose <span class='cmd'>sub_agents</span> "
     "delegate via <span class='cmd'>transfer_to_agent</span>, streamed token-by-token over "
     "<span class='cmd'>/run_sse</span>."),
    (GOLD, "◆ MCP", "the golden wires",
     "A Streamable-HTTP server exposes real tools <b>and the World API</b> — so the org grows itself "
     "by calling an MCP tool, not by shipping code."),
]


def layer(num, c, title, body, files):
    return f'''<div class="panel step" style="--c:{c}">
      <div class="lhead">{mini_cube(c)}<span class="lnum" style="color:{c}">{num}</span></div>
      <h3>{title}</h3><p>{body}</p><div class="files">{files}</div></div>'''


def tech(c, name, role, body):
    return f'''<div class="panel law" style="--c:{c}">
      <h3 style="color:{c}">{name}</h3><div class="trole">{role}</div><p>{body}</p></div>'''


layers_html = "".join(layer(*l) for l in LAYERS)
tech_html = "".join(tech(*t) for t in TECH)
loop_html = '<span class="larrow">▸</span>'.join(
    f'<span class="loopchip">{i+1} · {w}</span>' for i, w in enumerate(LOOP))

FAVICON = ("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16'"
           "%3E%3Crect width='16' height='16' fill='%236BBF3B'/%3E%3Crect width='16' height='5' "
           "fill='%238FE05A'/%3E%3Crect x='5' y='6' width='6' height='6' fill='%2346E3D8'/%3E%3C/svg%3E")

HTML = f'''<!DOCTYPE html>
<html lang="en"><head>
<meta charset="UTF-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1.0"/>
<title>Architecture — Omo Mission Control</title>
<meta name="description" content="How Omo Mission Control is built: a net-new Gemini + ADK + MCP multi-agent org rendered into Minecraft. The system, end to end — solid is built today, dashed is roadmap."/>
<meta name="theme-color" content="#1E1E22"/><meta name="color-scheme" content="dark"/>
<link rel="canonical" href="https://omo.computer/architecture"/>
<meta property="og:type" content="website"/>
<meta property="og:site_name" content="Omo Mission Control"/>
<meta property="og:title" content="The Architecture — a world that builds itself"/>
<meta property="og:description" content="Gemini reasons · ADK orchestrates · MCP connects — including the org's own growth. The whole system, rendered into Minecraft."/>
<meta property="og:url" content="https://omo.computer/architecture"/>
<meta property="og:image" content="https://omo.computer/architecture.png"/>
<meta name="twitter:card" content="summary_large_image"/>
<link rel="icon" href="{FAVICON}"/>
<link rel="preconnect" href="https://fonts.googleapis.com"/>
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin/>
<link href="https://fonts.googleapis.com/css2?family=Press+Start+2P&family=VT323&display=swap" rel="stylesheet"/>
<style>
  :root{{
    --sky-top:#5B93FF; --sky-mid:#79A6FF; --sky-low:#BFE0FF;
    --grass:#6BBF3B; --grass-top:#8FE05A; --dirt:#7A5132;
    --stone:#9A9A9A; --panel:#1E1E22; --panel-lt:#2C2C32; --panel-edge:#0C0C0E;
    --ink:#F4F4F4; --ink-dim:#B9C0C8; --shadow:#2A2A2A;
    --diamond:#46E3D8; --diamond-dk:#1FA6B4; --emerald:#43C463; --emerald-dk:#2C8C44;
    --gold:#FFD13B; --gold-dk:#C99A00; --redstone:#E5413B;
  }}
  *{{box-sizing:border-box}} html{{scroll-behavior:smooth}}
  body{{margin:0;background:var(--panel);color:var(--ink);font-family:'VT323',monospace;
    font-size:23px;line-height:1.35;-webkit-font-smoothing:none;image-rendering:pixelated;overflow-x:hidden}}
  h1,h2,h3,.pix{{font-family:'Press Start 2P',monospace;line-height:1.5;letter-spacing:.5px}}
  a{{color:var(--diamond);text-decoration:none}}
  .pix-shadow{{text-shadow:3px 3px 0 var(--shadow)}}
  .glow{{text-shadow:0 0 14px rgba(70,227,216,.65),3px 3px 0 var(--shadow)}}
  .panel{{background:var(--panel-lt);border:4px solid var(--panel-edge);
    box-shadow:inset 4px 4px 0 rgba(255,255,255,.07),inset -4px -4px 0 rgba(0,0,0,.45),0 0 0 4px var(--panel);
    padding:26px}}
  .btn{{display:inline-block;font-family:'Press Start 2P',monospace;font-size:12px;color:#fff;
    text-shadow:2px 2px 0 rgba(0,0,0,.5);cursor:pointer;padding:15px 20px;border:none;text-align:center;
    background:var(--stone);box-shadow:inset 3px 3px 0 #E3E3E3,inset -3px -3px 0 #555,0 4px 0 #2c2c2c;
    transition:transform .05s,filter .1s;margin:6px}}
  .btn:hover{{filter:brightness(1.12)}} .btn:active{{transform:translateY(3px)}}
  .btn.go{{background:var(--emerald);box-shadow:inset 3px 3px 0 #79e89a,inset -3px -3px 0 var(--emerald-dk),0 4px 0 #1c5e2c}}
  .btn.ice{{background:var(--diamond);color:#06262a;text-shadow:none;box-shadow:inset 3px 3px 0 #b6f6f0,inset -3px -3px 0 var(--diamond-dk),0 4px 0 #0d5660}}
  .wrap{{max-width:1120px;margin:0 auto;padding:0 22px}}
  section{{padding:58px 0}}
  .eyebrow{{font-family:'Press Start 2P',monospace;font-size:11px;color:var(--gold);
    text-shadow:2px 2px 0 var(--shadow);margin:0 0 16px;letter-spacing:1px}}
  .lead{{font-size:25px;color:var(--ink-dim)}} .center{{text-align:center}}
  .cmd{{font-family:'Press Start 2P',monospace;font-size:11px;color:var(--diamond);
    background:#06181a;padding:4px 7px;border:2px solid var(--diamond-dk);white-space:nowrap}}

  /* ── top nav ── */
  .nav{{position:sticky;top:0;z-index:50;background:var(--panel-edge);
    border-bottom:4px solid #000;display:flex;align-items:center;justify-content:space-between;
    padding:12px 22px}}
  .nav .brand{{font-family:'Press Start 2P',monospace;font-size:13px;color:#fff;text-shadow:2px 2px 0 #000}}
  .nav .brand b{{color:var(--diamond)}}
  .nav .links a{{font-family:'Press Start 2P',monospace;font-size:10px;color:var(--ink-dim);margin-left:16px}}
  .nav .links a.active{{color:var(--gold)}} .nav .links a:hover{{color:#fff}}

  /* ── hero (sky) ── */
  .hero{{position:relative;text-align:center;padding:50px 22px 0;
    background:linear-gradient(180deg,var(--sky-top) 0%,var(--sky-mid) 45%,var(--sky-low) 100%);
    color:#fff;overflow:hidden}}
  .cloud{{position:absolute;background:#fff;opacity:.9;
    box-shadow:32px 0 #fff,64px 0 #fff,32px -16px #fff,0 16px #fff,64px 16px #fff}}
  .c1{{width:32px;height:16px;top:54px;left:10%;animation:dA 11s ease-in-out infinite alternate}}
  .c2{{width:32px;height:16px;top:110px;right:12%;transform:scale(1.4);animation:dB 14s ease-in-out infinite alternate}}
  @keyframes dA{{from{{transform:translateX(0)}}to{{transform:translateX(40px)}}}}
  @keyframes dB{{from{{transform:translateX(0) scale(1.4)}}to{{transform:translateX(-46px) scale(1.4)}}}}
  .hero h1{{font-size:26px;color:#fff;margin:12px auto 14px;max-width:20ch}}
  .hero .sub{{font-size:24px;max-width:58ch;margin:0 auto 20px;color:#0c2c4a;text-shadow:1px 1px 0 rgba(255,255,255,.4)}}
  .badges{{margin:16px 0 6px;display:flex;gap:8px;justify-content:center;flex-wrap:wrap}}
  .chip{{font-family:'Press Start 2P',monospace;font-size:9px;color:#0c2c4a;
    background:rgba(255,255,255,.55);padding:9px 11px;border:3px solid rgba(255,255,255,.85)}}
  svg.hq{{width:100%;max-width:430px;height:auto;margin:6px auto -18px;display:block;
    filter:drop-shadow(0 24px 30px rgba(0,0,0,.4));position:relative;z-index:3}}
  .rise{{animation:rz 3.4s ease-in-out infinite}}
  .rise.r2{{animation-delay:.6s}} .rise.r3{{animation-delay:1.2s}}
  .beacon{{animation:pl 2.4s ease-in-out infinite}}
  @keyframes rz{{0%{{transform:translateY(26px);opacity:0}}40%{{opacity:.95}}70%{{transform:translateY(0);opacity:1}}100%{{transform:translateY(-6px);opacity:0}}}}
  @keyframes pl{{0%,100%{{opacity:.55}}50%{{opacity:1}}}}
  .ground{{height:44px;background:linear-gradient(180deg,var(--grass-top) 0 8px,var(--grass) 8px 22px,var(--dirt) 22px 100%);
    background-size:32px 100%;box-shadow:inset 0 4px 0 rgba(255,255,255,.18),inset 0 -6px 0 rgba(0,0,0,.25)}}
  .turf{{height:28px;background:linear-gradient(180deg,var(--grass-top) 0 6px,var(--grass) 6px 16px,var(--dirt) 16px 100%);background-size:32px 100%}}

  /* ── the schematic screen (diagram) ── */
  .screen{{background:#070b14;border:4px solid var(--panel-edge);position:relative;padding:14px;
    box-shadow:inset 4px 4px 0 rgba(70,227,216,.10),inset -4px -4px 0 rgba(0,0,0,.6),0 0 0 4px var(--panel)}}
  .screen::before{{content:"▣ LIVE SCHEMATIC";position:absolute;top:-2px;left:14px;transform:translateY(-50%);
    font-family:'Press Start 2P',monospace;font-size:9px;color:#06262a;background:var(--diamond);padding:5px 8px}}
  .archsvg{{display:block;border-radius:2px}}
  .scap{{margin-top:14px;color:var(--ink-dim);font-size:20px}}

  /* ── grids ── */
  .layers{{display:grid;grid-template-columns:repeat(2,1fr);gap:18px}}
  .step{{padding:20px}}
  .lhead{{display:flex;align-items:center;gap:10px;margin-bottom:6px}}
  .vcube{{width:30px;height:auto}}
  .lnum{{font-family:'Press Start 2P',monospace;font-size:12px}}
  .step h3{{font-size:13px;margin:0 0 10px;color:#fff}}
  .step p{{margin:0 0 10px;color:var(--ink-dim);font-size:21px}}
  .files{{font-family:ui-monospace,monospace;font-size:14px;color:#7d8aa0}}
  .grid3{{display:grid;grid-template-columns:repeat(3,1fr);gap:18px}}
  .law{{padding:22px}} .law h3{{font-size:14px;margin:0 0 4px}}
  .law .trole{{color:var(--ink-dim);font-size:18px;margin-bottom:10px}}
  .law p{{margin:0;color:var(--ink-dim);font-size:20px}}

  /* ── loop rail ── */
  .loop-rail{{display:flex;flex-wrap:wrap;align-items:center;gap:10px;justify-content:center}}
  .loopchip{{font-family:'Press Start 2P',monospace;font-size:10px;color:#06262a;background:var(--diamond);
    padding:11px 12px;box-shadow:inset 2px 2px 0 #b6f6f0,inset -2px -2px 0 var(--diamond-dk)}}
  .larrow{{color:var(--gold);font-size:18px}}

  /* ── legend ── */
  .legend{{display:flex;flex-wrap:wrap;gap:16px;margin-top:14px;font-size:19px;color:var(--ink-dim);justify-content:center}}
  .legend span{{display:inline-flex;align-items:center;gap:8px}}
  .legend i{{width:24px;border-top:3px solid;display:inline-block}}

  footer{{background:var(--panel-edge);text-align:center;padding:38px 22px;color:var(--ink-dim)}}
  footer .pix{{font-size:11px;color:var(--ink)}}

  @media(max-width:880px){{.layers,.grid3{{grid-template-columns:1fr}} .hero h1{{font-size:20px}}
    .nav .links a{{margin-left:10px;font-size:9px}}}}
  .reveal{{opacity:0;transform:translateY(22px);transition:opacity .6s steps(8),transform .6s steps(8)}}
  .reveal.in{{opacity:1;transform:none}}
  @media(prefers-reduced-motion:reduce){{*{{animation:none!important;transition:none!important}}.reveal{{opacity:1;transform:none}}}}
</style></head>
<body>

<nav class="nav">
  <a class="brand" href="/"><b>OMO</b> MISSION CONTROL</a>
  <div class="links">
    <a href="/">Home</a>
    <a href="/architecture" class="active">Architecture</a>
    <a href="/try">Run it</a>
  </div>
</nav>

<header class="hero" id="top">
  <div class="cloud c1"></div><div class="cloud c2"></div>
  <p class="eyebrow">▸ UNDER THE HOOD — THE WHOLE SYSTEM, END TO END</p>
  <h1 class="pix-shadow">The architecture<br>of a world that<br>builds itself.</h1>
  <p class="sub">A <b>net-new Gemini + ADK + MCP</b> multi-agent organisation — rendered into a place you
     walk through. Here's every layer, from the sentence you speak to the tools it connects.</p>
  <div class="badges">
    <span class="chip" style="border-color:#b6f6f0">◆ Gemini = diamond</span>
    <span class="chip" style="border-color:#9be8b1">◆ ADK = emerald</span>
    <span class="chip" style="border-color:#ffe9a3">◆ MCP = gold</span>
    <span class="chip">⛏ Vanilla Minecraft</span>
  </div>
  {voxel_hq()}
</header>
<div class="ground"></div>

<section id="diagram">
  <div class="wrap center">
    <p class="eyebrow">THE BLUEPRINT</p>
    <h2 class="glow">One socket, two brains,<br>a world that extends itself.</h2>
    <p class="lead" style="max-width:60ch;margin:0 auto 26px">Two processes talk over one WebSocket. The org
       grows itself by calling MCP tools — the load-bearing trick. <b>Solid = built today · dashed = roadmap.</b></p>
    <div class="screen reveal">{arch}</div>
    <p class="scap">A live schematic, generated straight from the codebase.</p>
    <div style="margin-top:18px">
      <a class="btn ice" href="/architecture.svg" download>⬇ Download SVG</a>
      <a class="btn" href="/architecture.png" download>⬇ Download PNG</a>
    </div>
  </div>
</section>

<div class="turf"></div>

<section id="layers">
  <div class="wrap">
    <p class="eyebrow center">FIVE LAYERS</p>
    <h2 class="pix-shadow center">From the sentence you speak<br>to the outside world.</h2>
    <div class="layers" style="margin-top:34px">{layers_html}</div>
  </div>
</section>

<div class="turf"></div>

<section id="loop" class="panel" style="margin:0;border-left:none;border-right:none">
  <div class="wrap center">
    <p class="eyebrow">THE NOVELTY — SELF-EXTENSION</p>
    <h2 class="glow">You never wire a thing.<br>You state intent.</h2>
    <p class="lead" style="max-width:62ch;margin:0 auto 26px">The org <i>declares</i> the function it needs and the
       world materialises it — every step is just an MCP tool call:</p>
    <div class="loop-rail">{loop_html}</div>
    <p class="lead" style="max-width:60ch;margin:24px auto 0;font-size:21px">★ Plus
       <span class="cmd">world_consult</span> — staffed functions ask each other instead of guessing,
       so the org behaves like a team, not a star of lone workers.</p>
  </div>
</section>

<div class="turf"></div>

<section id="tech">
  <div class="wrap center">
    <p class="eyebrow">THE THREE ORES THAT POWER OMO</p>
    <h2 class="pix-shadow">Gemini · ADK · MCP —<br>each one load-bearing.</h2>
    <div class="grid3" style="text-align:left;margin-top:34px">{tech_html}</div>
  </div>
</section>

<div class="turf"></div>

<section id="status">
  <div class="wrap center">
    <p class="eyebrow">HONEST STATUS</p>
    <h2 class="pix-shadow">Built today vs roadmap.</h2>
    <div class="panel" style="text-align:left;margin-top:26px;border-left:6px solid var(--emerald)">
      <p class="lead" style="font-size:21px;margin:0 0 8px">
        <b style="color:var(--emerald)">Live today —</b> the full ask → build → staff → live-dashboard →
        revise loop; real Meta&nbsp;Ads data over MCP; Gemini as every agent's brain; the World API
        self-extension surface; agent-to-agent <span class="cmd">world_consult</span>.</p>
      <p class="lead" style="font-size:21px;margin:0">
        <b style="color:var(--redstone)">Roadmap —</b> Google Workspace MCP (Gmail/Drive) and Stripe MCP
        (worlds-as-businesses), drawn dashed in the schematic.</p>
      <div class="legend">
        <span><i style="border-color:var(--emerald)"></i> built &amp; live</span>
        <span><i style="border-color:var(--redstone);border-top-style:dashed"></i> roadmap</span>
        <span><i style="border-color:var(--diamond)"></i> MCP</span>
        <span><i style="border-color:#6f8cff"></i> Gemini</span>
        <span><i style="border-color:var(--gold)"></i> human-in-the-loop</span>
      </div>
    </div>
    <div style="margin-top:34px">
      <a class="btn go" href="/try">⛏ Run it locally</a>
      <a class="btn ice" href="/">◀ Back to home</a>
    </div>
  </div>
</section>

<footer>
  <p class="pix">OMO MISSION CONTROL</p>
  <p>A world that builds itself for autonomous AI teams.</p>
  <p style="margin-top:14px">Powered by <b>Gemini</b> · Built on <b>ADK</b> · Connected over <b>MCP</b> · Runs in <b>vanilla Minecraft</b></p>
  <p style="margin-top:14px"><a href="/">Home</a> &nbsp;·&nbsp; <a href="/architecture">Architecture</a>
    &nbsp;·&nbsp; <a href="/try">Run it locally</a> &nbsp;·&nbsp; <a href="#top">Back to top</a></p>
  <p style="margin-top:14px;color:#777">© 2026 Omo · Built for the Google for Startups AI Agents Challenge — Track 1</p>
</footer>

<script>
  document.querySelectorAll('section .panel, .screen, .step, .law, .loop-rail').forEach(el=>el.classList.add('reveal'));
  const io=new IntersectionObserver(es=>{{es.forEach(e=>{{if(e.isIntersecting){{e.target.classList.add('in');io.unobserve(e.target);}}}})}},{{threshold:.1}});
  document.querySelectorAll('.reveal').forEach(el=>io.observe(el));
</script>
</body></html>'''

out = "/Users/harryedwards/omo-mc/landing/architecture.html"
with open(out, "w") as f:
    f.write(HTML)
print("wrote", out, f"({len(HTML)//1024} KB)")

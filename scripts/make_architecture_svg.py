#!/usr/bin/env python3
"""Render the Omo Mission Control architecture as a beautiful, accurate SVG.

Built straight from the code: the WS bridge (server.ts), the brain-selecting
AgentManager, the AdkAgent SSE bridge, the WorldStore org graph, the omo-tools
MCP server (Meta Ads + World API), the Gemini worldArchitect, and the dashboard
server. Solid = built today; dashed = roadmap (Workspace / Stripe MCP).

Palette is Omo's own alien-HUD dashboard design system.
"""

# ── palette (from dashboardServer.ts / dashboard.html.ts) ─────────────────────
BG0, BG1 = "#060a12", "#0e1830"
PANEL, PANEL2, LINE = "#0d1424", "#0a1120", "#22324f"
INK, DIM = "#e6efff", "#8aa0c4"
CYAN = "#27e7ff"   # MCP
GREEN = "#39ff88"  # ADK / runtime
GEM = "#6f8cff"    # Gemini (the brain)
AMBER = "#ffc24b"  # human-in-the-loop
RED = "#ff5d6c"    # roadmap / gated
JAVA = "#c9a14a"   # the Minecraft/Java world

W, H = 1720, 1300
S = []  # svg fragments


def esc(t):
    return (str(t).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))


def text(x, y, s, size=13, fill=INK, weight="400", anchor="start", spacing=None,
         opacity=1.0, family="ui", italic=False):
    fam = "'Georgia', serif" if family == "serif" else "-apple-system, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif"
    extra = f' letter-spacing="{spacing}"' if spacing else ""
    style = ' font-style="italic"' if italic else ""
    S.append(
        f'<text x="{x}" y="{y}" font-family="{fam}" font-size="{size}" '
        f'font-weight="{weight}" fill="{fill}" text-anchor="{anchor}" '
        f'opacity="{opacity}"{extra}{style}>{esc(s)}</text>'
    )


def rrect(x, y, w, h, r=14, fill=PANEL, stroke=LINE, sw=1.5, glow=False, dash=None,
          opacity=1.0):
    f = ' filter="url(#glow)"' if glow else ""
    d = f' stroke-dasharray="{dash}"' if dash else ""
    S.append(
        f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{r}" ry="{r}" '
        f'fill="{fill}" stroke="{stroke}" stroke-width="{sw}" opacity="{opacity}"{d}{f}/>'
    )


def chip(x, y, label, color, tw=None):
    w = tw if tw else 16 + len(label) * 7.2
    rrect(x, y, w, 22, r=11, fill=PANEL2, stroke=color, sw=1.3)
    S.append(f'<circle cx="{x+12}" cy="{y+11}" r="3.4" fill="{color}"/>')
    text(x + 21, y + 15, label, size=11.5, fill=color, weight="600")
    return w


def band(x, y, w, h, num, label, accent):
    rrect(x, y, w, h, r=18, fill="#0a101e", stroke=LINE, sw=1.4, opacity=0.65)
    # left accent rail
    S.append(f'<rect x="{x}" y="{y+14}" width="4" height="{h-28}" rx="2" fill="{accent}" opacity="0.9"/>')
    # number disc
    S.append(f'<circle cx="{x+30}" cy="{y+26}" r="14" fill="{accent}" filter="url(#glow)"/>')
    text(x + 30, y + 31, num, size=15, fill="#06101c", weight="800", anchor="middle")
    text(x + 54, y + 31, label, size=15.5, fill=INK, weight="700", spacing="0.4")


def module(x, y, w, h, name, lines, accent, port=None, star=False, dash=None,
           name_size=14):
    rrect(x, y, w, h, r=12, fill=PANEL, stroke=accent, sw=1.6, dash=dash)
    S.append(f'<rect x="{x}" y="{y}" width="5" height="{h}" rx="2.5" fill="{accent}"/>')
    ty = y + 24
    text(x + 16, ty, name, size=name_size, fill=INK, weight="700")
    if star:
        text(x + 16 + len(name) * (name_size * 0.62) + 8, ty, "★", size=13, fill=CYAN, weight="700")
    if port:
        pw = 12 + len(port) * 6.6
        rrect(x + w - pw - 12, y + 10, pw, 18, r=9, fill=PANEL2, stroke=accent, sw=1.1)
        text(x + w - pw - 12 + pw / 2, y + 23, port, size=10.5, fill=accent, weight="600", anchor="middle")
    ly = ty + 19
    for ln, col in lines:
        text(x + 16, ly, ln, size=11.7, fill=col, opacity=0.95)
        ly += 16.5


def arrow(x1, y1, x2, y2, color, dashed=False, w=2.4, marker="end"):
    d = ' stroke-dasharray="7 5"' if dashed else ""
    me = f' marker-end="url(#ah-{color_id(color)})"' if marker in ("end", "both") else ""
    ms = f' marker-start="url(#ah-{color_id(color)})"' if marker in ("both",) else ""
    S.append(
        f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="{color}" '
        f'stroke-width="{w}"{d}{me}{ms} stroke-linecap="round"/>'
    )


def curve(x1, y1, x2, y2, cx, cy, color, dashed=False, w=2.4, marker="end"):
    d = ' stroke-dasharray="7 5"' if dashed else ""
    me = f' marker-end="url(#ah-{color_id(color)})"' if marker in ("end", "both") else ""
    ms = f' marker-start="url(#ah-{color_id(color)})"' if marker == "both" else ""
    S.append(
        f'<path d="M {x1} {y1} Q {cx} {cy} {x2} {y2}" fill="none" stroke="{color}" '
        f'stroke-width="{w}"{d}{me}{ms} stroke-linecap="round"/>'
    )


def alabel(x, y, s, color, anchor="middle"):
    w = 12 + len(s) * 6.4
    rrect(x - (w/2 if anchor == "middle" else 0), y - 12, w, 20, r=10, fill=BG0, stroke=color, sw=1.1)
    text(x + (0 if anchor == "middle" else 6 - w/2 + w/2), y + 2, s, size=10.8, fill=color,
         weight="600", anchor=anchor)


def step(x, y, n, color):
    S.append(f'<circle cx="{x}" cy="{y}" r="11" fill="{BG0}" stroke="{color}" stroke-width="2"/>')
    text(x, y + 4, n, size=12, fill=color, weight="800", anchor="middle")


_COLOR_IDS = {}
def color_id(c):
    return _COLOR_IDS.setdefault(c, c.lstrip("#"))


# ── defs ──────────────────────────────────────────────────────────────────────
defs = ['<defs>']
defs.append(
    f'<radialGradient id="bgg" cx="32%" cy="-8%" r="120%">'
    f'<stop offset="0" stop-color="{BG1}"/><stop offset="0.55" stop-color="{BG0}"/>'
    f'<stop offset="1" stop-color="#03060c"/></radialGradient>'
)
defs.append(
    '<filter id="glow" x="-60%" y="-60%" width="220%" height="220%">'
    '<feGaussianBlur stdDeviation="4" result="b"/>'
    '<feMerge><feMergeNode in="b"/><feMergeNode in="SourceGraphic"/></feMerge></filter>'
)
defs.append(
    '<filter id="softshadow" x="-30%" y="-30%" width="160%" height="160%">'
    '<feDropShadow dx="0" dy="3" stdDeviation="6" flood-color="#000" flood-opacity="0.45"/></filter>'
)
for c in [CYAN, GREEN, GEM, AMBER, RED, JAVA, DIM, INK]:
    cid = color_id(c)
    defs.append(
        f'<marker id="ah-{cid}" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7.5" '
        f'markerHeight="7.5" orient="auto-start-reverse">'
        f'<path d="M0,0 L10,5 L0,10 L3,5 z" fill="{c}"/></marker>'
    )
defs.append('</defs>')

S.append(f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">')
S.extend(defs)
S.append(f'<rect width="{W}" height="{H}" fill="url(#bgg)"/>')
# faint grid
for gx in range(0, W, 48):
    S.append(f'<line x1="{gx}" y1="0" x2="{gx}" y2="{H}" stroke="#0f1b30" stroke-width="0.5" opacity="0.35"/>')
for gy in range(0, H, 48):
    S.append(f'<line x1="0" y1="{gy}" x2="{W}" y2="{gy}" stroke="#0f1b30" stroke-width="0.5" opacity="0.35"/>')

L, R = 60, W - 60
INW = R - L

# ── title ─────────────────────────────────────────────────────────────────────
text(L, 58, "Omo Mission Control", size=34, fill=INK, weight="800", family="serif")
text(L, 86, "System architecture — a world that builds itself for autonomous AI teams.",
     size=14.5, fill=DIM, italic=True)
# tech badges (top-right)
bx = R
for lbl, col in [("MCP  ·  every tool + the self-extension surface", CYAN),
                 ("ADK  ·  the multi-agent organisation", GREEN),
                 ("Gemini  ·  the brain of every agent", GEM)]:
    w = 22 + len(lbl) * 6.8
    bx -= w
    rrect(bx, 40, w, 28, r=14, fill=PANEL, stroke=col, sw=1.6, glow=True)
    S.append(f'<circle cx="{bx+16}" cy="54" r="4.5" fill="{col}"/>')
    text(bx + 28, 58, lbl, size=12, fill=col, weight="600")
    bx -= 12

# ── band geometry ─────────────────────────────────────────────────────────────
A_y, A_h = 110, 92
B_y, B_h = 232, 150
C_y, C_h = 412, 300
D_y, D_h = 742, 196
E_y, E_h = 968, 150
# columns (4-up) used by several bands
PAD = 22
c4w = (INW - PAD - 3 * PAD) // 4
cx0 = L + PAD
cxs = [cx0, cx0 + c4w + PAD, cx0 + 2 * (c4w + PAD), cx0 + 3 * (c4w + PAD)]

# ════════════════════════════════════════════════════════════════════════════
# BAND A — You, in Minecraft
# ════════════════════════════════════════════════════════════════════════════
band(L, A_y, INW, A_h, "1", "YOU — inside an ordinary (vanilla) Minecraft world", AMBER)
ay, ah = A_y + 36, 44
labels = [
    ("Speak a goal", [("“Check our ad spend… keep watch.”", DIM)]),
    ("/revise <prompt>", [("steer the live dashboard", DIM)]),
    ("Tap-to-approve", [("veto any outward action", DIM)]),
    ("Push-to-talk  (V)", [("voice → whisper.cpp → chat", DIM)]),
]
for i, (nm, ln) in enumerate(labels):
    module(cxs[i], ay, c4w, ah, nm, ln, AMBER, name_size=13)

# ════════════════════════════════════════════════════════════════════════════
# BAND B — The world (Java) — only code that touches Minecraft
# ════════════════════════════════════════════════════════════════════════════
band(L, B_y, INW, B_h, "2", "THE WORLD — Java 21 · the only code that touches Minecraft", JAVA)
by = B_y + 46
b2w = (INW - PAD - PAD) // 2
module(cx0, by, b2w, 86,
       "Omo client-mod  ·  Fabric",
       [("CinemaScreen — fullscreen live web dashboards (GPU-blit)", INK),
        ("voice capture · in-world terminal screens", DIM),
        ("client-mod/src/.../terminal/", DIM)],
       CYAN)
module(cx0 + b2w + PAD, by, b2w, 86,
       "Paper plugin  ·  Bukkit",
       [("live parametric builds — block-by-block · villager spawn + pathing", INK),
        ("HQ island · cinema screens · tap-to-approve UI", DIM),
        ("plugin/.../bridge/IncomingHandler.java", DIM)],
       JAVA)

# ════════════════════════════════════════════════════════════════════════════
# BAND C — The orchestrator (Node/TS runtime)
# ════════════════════════════════════════════════════════════════════════════
band(L, C_y, INW, C_h, "3", "THE ORCHESTRATOR — Node / TypeScript runtime · single event loop", GREEN)
r1y = C_y + 50
rh = 96
# Row 1 (near the world): plugin-facing + state.
module(cxs[0], r1y, c4w, rh, "WS Bridge",
       [("Paper ⇄ runtime", DIM), ("auth · broadcast()", DIM), ("server.ts", DIM)],
       GREEN, port=":8765")
module(cxs[1], r1y, c4w, rh, "AgentManager",
       [("brain select by room:", DIM), ("Hermes · Claude · ADK", INK),
        ("approvals · metrics", DIM)],
       GREEN)
module(cxs[2], r1y, c4w, rh, "WorldStore",
       [("the live ORG GRAPH", INK), ("functions + consults", DIM),
        ("worldStore.ts", DIM)],
       GREEN, star=True)
module(cxs[3], r1y, c4w, rh, "Dashboard server",
       [("/dash/:id live boards", INK), ("+ Society View", DIM),
        ("dashboardServer.ts", DIM)],
       CYAN, port=":8088")

# Row 2 (near the ADK org): the modules that face the Python service sit closest
# to it, so the SSE + MCP connectors stay short and don't cross other boxes.
r2y = r1y + rh + 24
module(cxs[0], r2y, c4w, rh, "omo-tools MCP",
       [("World API + Meta Ads", INK), ("describe/add/build/", DIM),
        ("staff/assign/consult", DIM)],
       CYAN, port=":8090", star=True)
module(cxs[1], r2y, c4w, rh, "AdkAgent  (bridge)",
       [("ADK SSE ⇄ in-world", DIM), ("reasoning→screens, say,", INK),
        ("transcript, approvals", DIM)],
       GEM)
module(cxs[2], r2y, c4w, rh, "World Architect",
       [("Gemini designs each", INK), ("building → build_ops", DIM),
        ("worldArchitect.ts", DIM)],
       GEM)
module(cxs[3], r2y, c4w, rh, "HTTP · Terminal",
       [("control + face API", DIM), ("terminal multiplex", DIM),
        ("http.ts · terminalServer", DIM)],
       GREEN, port="8766/67")

# ════════════════════════════════════════════════════════════════════════════
# BAND D — The organisation (ADK · Python)
# ════════════════════════════════════════════════════════════════════════════
band(L, D_y, INW, D_h, "4", "THE ORGANISATION — ADK service · Python · adk api_server", GREEN)
dy = D_y + 48
cos_w = 470
module(cx0, dy, cos_w, 110,
       "Chief of Staff   (Gemini)",
       [("ADK coordinator — reasons, then DELEGATES", INK),
        ("sub_agents=[Growth, Comms] · transfer_to_agent", DIM),
        ("extends the org via the World API (MCP)", CYAN),
        ("omo-agent/omo/agent.py", DIM)],
       GREEN, port=":8000", star=True)
# Gemini model chip on CoS
chip(cx0 + 218, dy + 12, "gemini-flash-latest", GEM)

sub_x = cx0 + cos_w + PAD
sub_w = (R - PAD - sub_x) // 3
subs = [
    ("Growth  (Gemini)", [("REAL Meta Ads", INK), ("ROAS · spend · CTR", DIM)]),
    ("Comms  (Gemini)", [("drafts — gated", INK), ("onboarding · outreach", DIM)]),
    ("Specialist ×N", [("hired on demand", INK), ("adopts any role", DIM)]),
]
for i, (nm, ln) in enumerate(subs):
    module(sub_x + i * (sub_w + PAD), dy, sub_w, 76, nm, ln, GEM if i < 2 else GREEN, name_size=13)
text(sub_x, dy + 100, "tools = McpToolset(StreamableHTTP) → omo-tools  ·  one reusable specialist app makes the org extensible to ANY function",
     size=11.5, fill=DIM, italic=True)

# ════════════════════════════════════════════════════════════════════════════
# BAND E — The outside world
# ════════════════════════════════════════════════════════════════════════════
band(L, E_y, INW, E_h, "5", "THE OUTSIDE WORLD — every tool over MCP · Gemini is the brain", CYAN)
ey, eh = E_y + 48, 80
module(cxs[0], ey, c4w, eh, "Meta Ads Graph API",
       [("LIVE — real campaigns + insights", GREEN),
        ("reached via the runtime's MCP", DIM)],
       CYAN, name_size=13)
module(cxs[1], ey, c4w, eh, "Gemini API",
       [("LIVE — brain of every agent", GREEN),
        ("+ the world architect", DIM)],
       GEM, name_size=13)
module(cxs[2], ey, c4w, eh, "Google Workspace MCP",
       [("ROADMAP — Gmail / Drive", RED),
        ("Google's own MCP server", DIM)],
       RED, name_size=13, dash="6 4")
module(cxs[3], ey, c4w, eh, "Stripe MCP",
       [("ROADMAP — payments", RED),
        ("worlds-as-businesses", DIM)],
       RED, name_size=13, dash="6 4")

# ════════════════════════════════════════════════════════════════════════════
# CONNECTORS  +  numbered loop
# ════════════════════════════════════════════════════════════════════════════
# module centres (after the row swap)
mcp_cx = cxs[0] + c4w / 2          # omo-tools MCP (row2 col0)
adk_cx = cxs[1] + c4w / 2          # AdkAgent      (row2 col1)
arch_cx = cxs[2] + c4w / 2         # World Architect (row2 col2)
dash_cx = cxs[3] + c4w / 2         # Dashboard server (row1 col3)
plugin_cx = cx0 + b2w + PAD + b2w / 2
client_cx = cx0 + b2w / 2
spineA = L + INW * 0.26            # A↔B spine (left)
spineB = L + INW * 0.40            # B↔C control spine
spineDash = L + INW * 0.62         # B↔C dashboard spine
r1b = r1y + rh                     # bottom of row1
r2t = r2y                          # top of row2
r2b = r2y + rh                     # bottom of row2
gapCD = (r2b + D_y) / 2            # clear band between runtime row2 and ADK band

# 1 · A → B  (you speak / revise / approve) — double-headed
arrow(spineA, A_y + A_h, spineA, B_y, AMBER, w=2.6, marker="both")
alabel(spineA, (A_y + A_h + B_y) / 2 + 2, "talk · /revise · approve", AMBER)
step(spineA - 96, (A_y + A_h + B_y) / 2, "1", AMBER)

# 2 · B ↔ C  WebSocket control bridge
arrow(spineB, B_y + B_h, spineB, C_y, GREEN, w=2.8, marker="both")
alabel(spineB, (B_y + B_h + C_y) / 2 + 2, "WebSocket :8765 (bridge)", GREEN)
step(spineB - 92, (B_y + B_h + C_y) / 2, "2", GREEN)

# 8 · B ↔ C  dashboard render channel (HTTP → CinemaScreen) — parallel cyan dashed
arrow(spineDash, C_y, spineDash, B_y + B_h, CYAN, w=2.2, dashed=True, marker="end")
alabel(spineDash, (B_y + B_h + C_y) / 2 + 2, "HTTP /dash/:id → CinemaScreen", CYAN)
step(spineDash + 92, (B_y + B_h + C_y) / 2, "8", CYAN)

# 3 · AdkAgent (row2) → Chief of Staff (band D)  — SSE cognition, short & clean
cos_top_x = cx0 + cos_w * 0.62
arrow(adk_cx, r2b, adk_cx, gapCD - 10, GEM, w=2.6, marker="none")
curve(adk_cx, gapCD - 10, cos_top_x, D_y, adk_cx, gapCD + 14, GEM, w=2.6, marker="end")
alabel(adk_cx + 8, gapCD - 6, "HTTP /run_sse · live cognition", GEM)
step(adk_cx + 150, gapCD - 6, "3", GEM)

# 4 · Chief of Staff → omo-tools MCP (row2)  — the self-extension channel
arrow(cx0 + cos_w * 0.30, D_y, cx0 + cos_w * 0.30, gapCD + 8, CYAN, w=3.2, marker="none")
curve(cx0 + cos_w * 0.30, gapCD + 8, mcp_cx, r2b, cx0 + cos_w * 0.30, gapCD - 14, CYAN, w=3.2, marker="end")
alabel(mcp_cx + 4, gapCD - 6, "MCP · World API (self-extension) ★", CYAN)
step(mcp_cx - 150, gapCD - 6, "4", CYAN)

# 5 · World Architect → build_ops → plugin (band B) — up the col2/col3 gutter
gut = cxs[3] - PAD / 2
S.append(f'<path d="M {arch_cx} {r2t} L {arch_cx} {r1b+18} L {gut} {r1b+18} L {gut} {C_y-18} '
         f'L {plugin_cx} {C_y-18} L {plugin_cx} {B_y+B_h}" fill="none" stroke="{JAVA}" '
         f'stroke-width="2.4" marker-end="url(#ah-{color_id(JAVA)})" stroke-linecap="round" stroke-linejoin="round"/>')
alabel(gut, (r1b + C_y) / 2, "build_ops — room rises live", JAVA)
step(plugin_cx + 70, B_y + B_h + 22, "5", JAVA)
text(plugin_cx, B_y + B_h + 40, "+ world_staff → a specialist walks in (6)", size=10.5, fill=CYAN, anchor="middle")

# 7 · Meta Ads (band E) → omo-tools MCP (row2) — routed up the LEFT gutter, clear of band D
lg = L - 22
S.append(f'<path d="M {cxs[0]+24} {E_y} L {cxs[0]+24} {E_y-14} L {lg} {E_y-14} L {lg} {r2b+16} '
         f'L {cxs[0]+24} {r2b+16} L {cxs[0]+24} {r2b}" fill="none" stroke="{CYAN}" '
         f'stroke-width="2.4" marker-end="url(#ah-{color_id(CYAN)})" stroke-linecap="round" stroke-linejoin="round"/>')
S.append(f'<text x="{lg-6}" y="{(E_y+r2b)/2}" font-family="-apple-system, sans-serif" '
         f'font-size="11" font-weight="600" fill="{CYAN}" text-anchor="middle" '
         f'transform="rotate(-90 {lg-6} {(E_y+r2b)/2})">Graph API · live data</text>')
step(cxs[0] + 24, E_y - 40, "7", CYAN)

# Gemini (band E) → ADK band (brain) — short
gx = cxs[1] + c4w / 2
arrow(gx, E_y, gx, D_y + D_h, GEM, w=2.6, marker="end")
alabel(gx, (E_y + D_y + D_h) / 2 + 4, "model API · brain", GEM)

# Roadmap (band E) → ADK band — Workspace + Stripe, dashed
for i in (2, 3):
    rx = cxs[i] + c4w / 2
    arrow(rx, E_y, rx, D_y + D_h, RED, w=2.0, dashed=True, marker="end")
alabel((cxs[2] + cxs[3]) / 2 + c4w / 2, (E_y + D_y + D_h) / 2 + 4, "MCP (roadmap)", RED)

# ════════════════════════════════════════════════════════════════════════════
# FOOTER — the self-extension loop + legend
# ════════════════════════════════════════════════════════════════════════════
F_y = E_y + E_h + 24
rrect(L, F_y, INW, 78, r=16, fill=PANEL, stroke=LINE, sw=1.4)
text(L + 22, F_y + 28, "The self-extension loop", size=15, fill=INK, weight="700", family="serif")
text(L + 22, F_y + 50, "You state intent — the org declares the function it needs and the world materialises it. No human pre-wires a thing:",
     size=12, fill=DIM, italic=True)
loop = "world_describe  →  world_add_function  →  world_build  →  world_staff  →  world_assign   ( + world_consult: agents ask each other )"
text(L + 22, F_y + 68, loop, size=12.5, fill=CYAN, weight="600")

# legend (right of footer title row)
lx = R - 470
items = [("Gemini — the brain", GEM), ("ADK / runtime", GREEN), ("MCP — connect + extend", CYAN),
         ("Human-in-the-loop", AMBER), ("Roadmap (dashed)", RED), ("Minecraft / Java", JAVA)]
for i, (lb, col) in enumerate(items):
    col_i = i % 2
    row_i = i // 2
    ix = lx + col_i * 235
    iy = F_y + 22 + row_i * 19
    S.append(f'<circle cx="{ix}" cy="{iy-4}" r="4.5" fill="{col}"/>')
    text(ix + 12, iy, lb, size=11.3, fill=DIM)

# baseline credit
text(L, H - 18, "Generated from the codebase · solid = built today · dashed = roadmap   ·   omo-mc",
     size=11, fill=DIM, opacity=0.7)
text(R, H - 18, "Gemini reasons · ADK orchestrates · MCP connects — including the org's own growth.",
     size=11.5, fill=INK, anchor="end", italic=True, opacity=0.9)

S.append("</svg>")

out = "/Users/harryedwards/omo-mc/docs/architecture.svg"
with open(out, "w") as f:
    f.write("\n".join(S))
print("wrote", out)

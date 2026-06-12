#!/usr/bin/env python3
"""Render Omo demo-video text overlays as transparent PNGs to drop onto a track
in DaVinci Resolve (above your footage). Uses the REAL brand fonts
(Press Start 2P headlines + VT323 terminal lines, in assets/fonts/), the site
palette, a soft cyan glow, an offset pixel shadow, and a beveled lower-third.

Outputs docs/demo-overlays/{1080p,4k}/NN_name.png + a contact sheet + captions.srt.
Edit OVERLAYS (text + tc) to match the voiceover, then re-run.
"""

import os
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT = "/Users/harryedwards/omo-mc"
PS2P = os.path.join(ROOT, "assets/fonts/PressStart2P-Regular.ttf")
VT323 = os.path.join(ROOT, "assets/fonts/VT323-Regular.ttf")
OUT = os.path.join(ROOT, "docs/demo-overlays")

# ── palette (Omo / Minecraft design system) ───────────────────────────────────
WHITE   = (244, 244, 244, 255)
DIAMOND = (70, 227, 216, 255)   # #46E3D8
DIAMOND_DK = (31, 166, 180, 255)
GOLD    = (255, 209, 59, 255)
DIM     = (190, 198, 208, 255)
SHADOW  = (6, 8, 12, 255)
GLOW    = (70, 227, 216, 150)
PANEL   = (16, 16, 20, 205)
PANEL_HI = (255, 255, 255, 28)
PANEL_LO = (0, 0, 0, 120)
CHIP_BG = (6, 24, 26, 240)

_fc = {}
def font(path, size):
    k = (path, size)
    if k not in _fc:
        _fc[k] = ImageFont.truetype(path, size)
    return _fc[k]

_md = ImageDraw.Draw(Image.new("RGBA", (8, 8)))
def measure(text, f):
    b = _md.textbbox((0, 0), text, font=f)
    return b[2] - b[0], b[3] - b[1], b[1]   # w, h, y-offset

def fit(path, text, target_px, max_w):
    """Largest size ≤ target_px whose text width ≤ max_w."""
    s = target_px
    while s > 8:
        if measure(text, font(path, s))[0] <= max_w:
            return s
        s -= 1
    return s

def draw_text(img, xy, text, f, color, shadow=True, glow=False, shadow_off=None):
    d = ImageDraw.Draw(img)
    x, y = xy
    if glow:
        layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
        ImageDraw.Draw(layer).text((x, y), text, font=f, fill=GLOW)
        r = max(3, f.size // 7)
        layer = layer.filter(ImageFilter.GaussianBlur(r))
        img.alpha_composite(layer)
    if shadow:
        off = shadow_off if shadow_off else max(2, f.size // 12)
        d.text((x + off, y + off), text, font=f, fill=SHADOW)
    d.text((x, y), text, font=f, fill=color)

def bevel_panel(img, x0, y0, x1, y1):
    d = ImageDraw.Draw(img)
    d.rectangle([x0, y0, x1, y1], fill=PANEL)
    # inset highlight (top/left) + shadow (bottom/right) → Minecraft GUI bevel
    d.line([(x0, y0), (x1, y0)], fill=PANEL_HI, width=3)
    d.line([(x0, y0), (x0, y1)], fill=PANEL_HI, width=3)
    d.line([(x0, y1), (x1, y1)], fill=PANEL_LO, width=3)
    d.line([(x1, y0), (x1, y1)], fill=PANEL_LO, width=3)


# ── overlays — captions aligned to the RECORDED voiceover (the "Omo Minecraft" edit).
# Timecodes match the VO transcript. type: title (centered) | lower (lower-third).
# head=Press Start 2P. kick=small cyan PS2P. mono=VT323. sub=VT323 dim. accent=cyan prefix.
OVERLAYS = [
 # VO 0:00 "soon every company will run on a fleet of AI agents… nobody can see, steer or grow them"
 dict(n="01", name="agents",    tc="0:00.3", dur=6.9, type="lower",
      head="RUN BY A FLEET OF AI AGENTS.", kick="AND YOU CAN'T SEE, STEER, OR GROW THEM."),
 # VO 0:10 "Agents are black boxes." 0:12 "Org charts are frozen."
 dict(n="02", name="blackbox",  tc="0:10.2", dur=3.2, type="lower",
      head="AGENTS ARE BLACK BOXES.", kick="ORG CHARTS ARE FROZEN."),
 # VO 0:13 "So we built the opposite — not a dashboard, a company you walk into."
 dict(n="03", name="opposite",  tc="0:13.6", dur=5.2, type="lower",
      head="SO WE BUILT THE OPPOSITE.", kick="NOT A DASHBOARD — A COMPANY YOU WALK INTO."),
 # VO 0:19 "This is Omo Mission Control."
 dict(n="04", name="title",     tc="0:19.0", dur=2.3, type="title",
      head="OMO MISSION CONTROL", accent="OMO", sub="A company you walk into."),
 # VO 0:21 "built on Google's ADK, reasoning with Gemini, reaching the real world through MCP"
 dict(n="05", name="stack",     tc="0:21.4", dur=5.4, type="lower",
      head="BUILT ON ADK · GEMINI · MCP", kick="REASON WITH GEMINI · ORCHESTRATE ON ADK · CONNECT OVER MCP"),
 # VO 0:27 "ads, payments, support, your inbox, any system, one protocol"
 dict(n="06", name="protocol",  tc="0:27.0", dur=5.0, type="lower",
      head="ANY SYSTEM. ONE PROTOCOL.", kick="ADS · PAYMENTS · SUPPORT · YOUR INBOX"),
 # VO 0:32 "I speak a goal to my chief of staff."
 dict(n="07", name="speak",     tc="0:32.2", dur=2.9, type="lower",
      head="SPEAK A GOAL.", kick="TO YOUR CHIEF OF STAFF"),
 # VO 0:35 "It delegates to specialists that pull live data and act on real systems."
 dict(n="08", name="delegate",  tc="0:35.2", dur=3.9, type="lower",
      head="IT DELEGATES TO SPECIALISTS.", kick="PULL LIVE DATA · ACT ON REAL SYSTEMS"),
 # VO 0:39 "Every decision unfolding right in front of me."
 dict(n="09", name="unfold",    tc="0:39.2", dur=2.5, type="lower",
      head="EVERY DECISION, UNFOLDING LIVE."),
 # VO 0:41 "when the mission needs a capability the company doesn't have, the company grows itself"
 dict(n="10", name="grows",     tc="0:41.8", dur=4.1, type="lower",
      head="THE COMPANY GROWS ITSELF.", kick="WHEN THE MISSION NEEDS MORE"),
 # VO 0:46 "a function is born, an agent walks in, a building rises, a dashboard renders itself to live data"
 dict(n="11", name="rises",     tc="0:46.0", dur=5.8, type="lower",
      head="A BUILDING RISES.", kick="AN AGENT WALKS IN · A DASHBOARD RENDERS TO LIVE DATA"),
 # VO 0:52 "No engineer, no deploy — just say it."
 dict(n="12", name="saying",    tc="0:52.0", dur=2.8, type="lower",
      head="NO ENGINEER. NO DEPLOY.", kick="JUST SAY IT."),
 # VO 0:54 "Observability becomes the world you stand in, extensibility becomes everything you say"
 dict(n="13", name="laws",      tc="0:54.9", dur=5.4, type="lower",
      head="OBSERVABILITY IS WHERE YOU STAND.", kick="EXTENSIBILITY IS EVERYTHING YOU SAY"),
 # VO 1:00 "nothing reaches the outside world without your one tap"
 dict(n="14", name="onetap",    tc="1:00.4", dur=3.5, type="lower",
      head="NOTHING SHIPS WITHOUT YOUR ONE TAP."),
 # VO 1:04 "this isn't a tool for running agents — it's where they live, work and multiply"
 dict(n="15", name="multiply",  tc="1:04.0", dur=4.9, type="lower",
      head="NOT A TOOL FOR RUNNING AGENTS.", kick="IT'S WHERE THEY LIVE, WORK, AND MULTIPLY"),
 # VO 1:09 "the operating system for the autonomous company"
 dict(n="16", name="endcard",   tc="1:09.0", dur=5.0, type="title",
      head="OMO MISSION CONTROL", accent="OMO",
      sub="The operating system for the autonomous company."),
]


def render(o, W, H):
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    M = round(W * 0.072)

    if o["type"] == "title":
        head = o["head"]
        hs = fit(PS2P, head, round(W * 0.044), W - 2 * M)
        hf = font(PS2P, hs)
        hw, hh, hoff = measure(head, hf)
        hx = (W - hw) // 2
        hy = round(H * 0.40)
        acc = o.get("accent")
        if acc and head.startswith(acc):
            # glow under whole line, then accent prefix cyan + rest white
            layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
            ImageDraw.Draw(layer).text((hx, hy), head, font=hf, fill=GLOW)
            img.alpha_composite(layer.filter(ImageFilter.GaussianBlur(max(3, hs // 7))))
            aw = measure(acc, hf)[0]
            d = ImageDraw.Draw(img)
            off = max(2, hs // 12)
            d.text((hx + off, hy + off), head, font=hf, fill=SHADOW)
            d.text((hx, hy), acc, font=hf, fill=DIAMOND)
            d.text((hx + aw, hy), head[len(acc):], font=hf, fill=WHITE)
        else:
            draw_text(img, (hx, hy), head, hf, WHITE, glow=True)
        sub = o.get("sub")
        if sub:
            sf = font(VT323, round(W * 0.024))
            sw = measure(sub, sf)[0]
            sy = hy + hh + round(H * 0.05)
            draw_text(img, ((W - sw) // 2, sy), sub, sf, DIM, shadow=True)
        return img

    # ── lower-third ──
    head = o["head"]
    second = o.get("kick") or o.get("mono")
    pad = round(W * 0.022)
    bar = max(5, round(W * 0.006))
    max_text_w = round(W * 0.84)
    hs = fit(PS2P, head, round(W * 0.030), max_text_w)
    hf = font(PS2P, hs)
    hw, hh, _ = measure(head, hf)
    if o.get("kick"):
        kf = font(PS2P, fit(PS2P, o["kick"], round(W * 0.0125), max_text_w))
        kw, kh, _ = measure(o["kick"], kf)
    elif o.get("mono"):
        kf = font(VT323, round(W * 0.020))
        kw, kh, _ = measure(o["mono"], kf)
    else:
        kw = kh = 0
    gap = round(H * 0.022)
    block_w = max(hw, kw)
    panel_w = bar + pad + block_w + pad
    panel_h = pad + hh + (gap + kh if second else 0) + pad
    x0 = M
    y1 = H - round(H * 0.085)
    y0 = y1 - panel_h
    bevel_panel(img, x0, y0, x0 + panel_w, y1)
    ImageDraw.Draw(img).rectangle([x0, y0, x0 + bar, y1], fill=DIAMOND)
    tx = x0 + bar + pad
    ty = y0 + pad
    draw_text(img, (tx, ty), head, hf, WHITE, glow=True)
    if o.get("kick"):
        draw_text(img, (tx, ty + hh + gap), o["kick"], kf, DIAMOND, shadow=True)
    elif o.get("mono"):
        my = ty + hh + gap
        d = ImageDraw.Draw(img)
        cpad = round(W * 0.007)
        d.rectangle([tx - cpad, my - cpad, tx + kw + cpad, my + kh + cpad], fill=CHIP_BG)
        d.rectangle([tx - cpad, my - cpad, tx - cpad + max(3, bar // 2), my + kh + cpad], fill=DIAMOND)
        draw_text(img, (tx, my), o["mono"], kf, DIAMOND, shadow=True, shadow_off=2)
    return img


def srt_time(t):
    h = int(t // 3600); m = int((t % 3600) // 60); s = t % 60
    return f"{h:02d}:{m:02d}:{s:06.3f}".replace(".", ",")

def parse_tc(tc):
    p = tc.split(":")
    return int(p[0]) * 60 + float(p[1]) if len(p) == 2 else float(p[0])

def write_srt():
    lines = []
    for i, o in enumerate(OVERLAYS, 1):
        start = parse_tc(o["tc"]); end = start + o.get("dur", 2.5)
        txt = o["head"] + (("\n" + (o.get("kick") or o.get("mono") or o.get("sub") or "")) if (o.get("kick") or o.get("mono") or o.get("sub")) else "")
        lines.append(f"{i}\n{srt_time(start)} --> {srt_time(end)}\n{txt}\n")
    with open(os.path.join(OUT, "captions.srt"), "w") as f:
        f.write("\n".join(lines))


def main():
    os.makedirs(OUT, exist_ok=True)
    sizes = {"1080p": (1920, 1080), "4k": (3840, 2160)}
    for label, (W, H) in sizes.items():
        d = os.path.join(OUT, label)
        os.makedirs(d, exist_ok=True)
        thumbs = []
        for o in OVERLAYS:
            img = render(o, W, H)
            img.save(os.path.join(d, f"{o['n']}_{o['name']}.png"))
            if label == "1080p":
                thumbs.append(img)
        if label == "1080p":
            cols, tw, th = 2, 960, 540
            rows = (len(thumbs) + cols - 1) // cols
            ck = Image.new("RGBA", (tw, th))
            for yy in range(0, th, 30):
                for xx in range(0, tw, 30):
                    on = ((xx // 30) + (yy // 30)) % 2 == 0
                    ImageDraw.Draw(ck).rectangle([xx, yy, xx + 29, yy + 29],
                        fill=(46, 48, 58, 255) if on else (34, 36, 46, 255))
            sheet = Image.new("RGBA", (cols * tw, rows * th), (24, 26, 34, 255))
            for i, t in enumerate(thumbs):
                r, c = divmod(i, cols)
                cell = ck.copy(); cell.alpha_composite(t.resize((tw, th)))
                sheet.alpha_composite(cell, (c * tw, r * th))
            sheet.save(os.path.join(OUT, "_contact_sheet.png"))
    write_srt()
    print("wrote overlays + captions.srt to", OUT)


if __name__ == "__main__":
    main()

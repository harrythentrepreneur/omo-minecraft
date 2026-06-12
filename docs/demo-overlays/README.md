# Omo — demo video text overlays (aligned to your voiceover)

Transparent PNG overlays you drop **straight onto a track in DaVinci Resolve**,
above your footage. Captions are written to match your **recorded VO** (from the
"Omo Minecraft" Resolve edit) and timed to its transcript.

Real brand fonts: **Press Start 2P** (headlines/kickers) + **VT323** (terminal
lines), in `assets/fonts/`. Look: soft cyan glow, offset pixel shadow, beveled
lower-third with a diamond bar — same system as the site.

```
docs/demo-overlays/
├── 1080p/   ← 1920×1080 overlays (use if your timeline is 1080p)
├── 4k/      ← 3840×2160 overlays (use if your timeline is 4K)
├── captions.srt        ← same beats as a subtitle file (alt to the PNGs)
└── _contact_sheet.png  ← all 16 at a glance
```

Each PNG is full-frame with a transparent background → it sits 1:1 on your
timeline. No scaling, no positioning. Use the folder matching your timeline res.

## The beats (timed to your VO)

| # | File | TC | On screen |
|---|------|-----|-----------|
| 01 | `01_agents` | 0:00 | RUN BY A FLEET OF AI AGENTS. · *and you can't see, steer, or grow them.* |
| 02 | `02_blackbox` | 0:10 | AGENTS ARE BLACK BOXES. · *org charts are frozen.* |
| 03 | `03_opposite` | 0:14 | SO WE BUILT THE OPPOSITE. · *not a dashboard — a company you walk into.* |
| 04 | `04_title` | 0:19 | **OMO MISSION CONTROL** (title) |
| 05 | `05_stack` | 0:21 | BUILT ON ADK · GEMINI · MCP |
| 06 | `06_protocol` | 0:27 | ANY SYSTEM. ONE PROTOCOL. · *ads · payments · support · your inbox* |
| 07 | `07_speak` | 0:32 | SPEAK A GOAL. · *to your chief of staff* |
| 08 | `08_delegate` | 0:35 | IT DELEGATES TO SPECIALISTS. · *pull live data · act on real systems* |
| 09 | `09_unfold` | 0:39 | EVERY DECISION, UNFOLDING LIVE. |
| 10 | `10_grows` | 0:42 | THE COMPANY GROWS ITSELF. |
| 11 | `11_rises` | 0:46 | A BUILDING RISES. · *an agent walks in · a dashboard renders to live data* |
| 12 | `12_saying` | 0:52 | NO ENGINEER. NO DEPLOY. · *just say it.* |
| 13 | `13_laws` | 0:55 | OBSERVABILITY IS WHERE YOU STAND. · *extensibility is everything you say* |
| 14 | `14_onetap` | 1:00 | NOTHING SHIPS WITHOUT YOUR ONE TAP. |
| 15 | `15_multiply` | 1:04 | NOT A TOOL FOR RUNNING AGENTS. · *where they live, work, and multiply* |
| 16 | `16_endcard` | 1:09 | **OMO MISSION CONTROL** · *the operating system for the autonomous company* |

## How to use them in DaVinci Resolve (60 seconds)

1. **Media Pool → Import Media** → select all PNGs in `1080p/` (or `4k/`).
2. Right-click the track header → **Add Track** (a video track above your footage).
3. Drag each overlay onto that track at its TC above. The transparent background
   means only the text shows — no scaling, no positioning.
4. Trim each to its beat (~2.5–6s); snap the **start to the spoken line**.
5. Optional: a ~6-frame **Cross Dissolve** on each end so they fade, not cut.

**Subtitle alternative:** instead of the PNGs you can use `captions.srt` —
Resolve: *File → Import → Subtitle*, drop on a subtitle track. (The PNGs look far
better; the SRT is a fallback / for accessibility.)

## Two things to confirm in the VO

whisper auto-transcribed your recording; I corrected obvious mishears
(*Omar→Omo, steal→steer, "all shots are frozen"→org charts are frozen,
Liveability→Observability, "I'm folding"→unfolding*). One line was unclear —
"No engineer, no deploy, **just ___**" — I used **"JUST SAY IT."** If you actually
said something else there, tell me and I'll fix caption 12.

## Regenerate

```bash
python3 scripts/make_demo_overlays.py
```

Edit the `OVERLAYS` list (text + `tc`) at the top of the script and re-run — it
rewrites both resolutions + `captions.srt` in ~2s.

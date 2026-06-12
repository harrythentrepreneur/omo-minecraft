// The dashboard architect — Gemini designs a UNIQUE, purpose-fit web dashboard
// for each function the org runs. This is the screen sibling of worldArchitect
// (which designs the BUILDING): a Payments board looks nothing like a Comms
// board — different hero visualization, different layout, different palette —
// never the generic template. Output is a complete, self-contained HTML page
// that fetches the SAME /dash/<id>/data JSON contract and renders it however
// suits that function, so live updates (dashboard_update) keep working.
//
// Robust by construction: results are disk-cached (so a given function is only
// designed once, and survives restarts), validated before use, and on ANY
// failure or missing API key we simply keep serving the generic template.

import OpenAI from "openai";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { setDashboardHtml } from "./dashboardServer.js";

const MODEL = process.env.OMO_GEMINI_MODEL ?? "gemini-flash-latest";
const GEMINI_BASE =
  process.env.OMO_GEMINI_OPENAI_BASE ?? "https://generativelanguage.googleapis.com/v1beta/openai/";
const KEY = process.env.GOOGLE_API_KEY ?? process.env.GEMINI_API_KEY ?? "";
const CACHE_DIR = join(process.cwd(), "data", "dashboards");

let client: OpenAI | null = null;
function gemini(): OpenAI {
  if (!client) client = new OpenAI({ apiKey: KEY, baseURL: GEMINI_BASE });
  return client;
}

const SYSTEM = `You are a world-class product + data-visualization designer for OMO — an autonomous AI company you run from inside a 3D world, where every function (department) has ONE big wall-mounted screen showing its live operating dashboard.

Your job: design that screen for ONE function. It must look UNMISTAKABLY built for THAT function — its own layout, its own hero visualization, its own accent palette and personality. Two different functions must produce two visibly different dashboards; never a generic template.

HARD RULES
• Output ONE complete, self-contained HTML5 document (<!doctype html> … </html>) with inline <style> and <script> only.
• NO external resources whatsoever — no CDNs, no web fonts, no remote images/scripts/styles. Use system font stacks and draw ALL visuals with CSS / inline SVG / <canvas>.
• Target a 1280×800 wall display viewed from a distance: dark background, high contrast, large legible type, a clear hierarchy with ONE dominant "hero" element.
• Premium "mission-control" aesthetic: deep dark base, a tasteful glow, restrained motion (a live pulse, smooth count-ups, a breathing chart). Performant — no heavy loops, no memory leaks.

LIVE DATA (critical — do not hard-code data)
• On load AND every 1500ms, fetch the data endpoint = location.pathname.replace(/\\/+$/,'') + '/data', parse JSON, and render the latest payload. Degrade gracefully when a field is missing or arrays are empty (tasteful placeholders, never throw, never blank-crash).
• Payload shape (everything after title+kpis is optional):
  {
    "title": string, "subtitle"?: string, "status"?: string,
    "kpis": [{ "label": string, "value": string|number, "unit"?: string, "delta"?: string, "trend"?: "up"|"down"|"flat" }],
    "series"?: [{ "name": string, "color": string, "points": number[] }],
    "table"?: { "columns": string[], "rows": (string|number)[][] },
    "feed"?: [{ "ts"?: string, "text": string, "tone"?: "info"|"good"|"warn"|"bad" }],
    "updatedAt"?: number
  }
• Put the title + status + a live "updated Ns ago" in a header.

DESIGN FOR THE FUNCTION — pick the hero + layout from what this function actually watches:
• Finance / Payments → a big runway / MRR / cash hero (ring or count-up), the table as a transaction ledger, green-gold palette.
• Growth / Marketing → a hero ROAS/CAC stat + the series as a dominant trend or funnel, the table as campaigns, electric-cyan palette.
• Comms / Support → the feed as the hero live activity stream, kpis as open-rate/SLA gauges, warm palette.
• Analytics / Data → a dense grid of sparklines from series + KPI tiles, cool indigo palette.
• Legal / Compliance / Ops → a review-queue / checklist from the table + status chips, calm slate palette.
• Anything else → invent a layout that genuinely fits that function's work.
Choose ONE hero element and make everything else support it. Different function ⇒ different hero, palette, and layout.

Return ONLY the HTML document — no markdown fences, no commentary.`;

function userPrompt(role: string, purpose: string, extra?: string): string {
  let p = `Design the live wall dashboard for OMO's "${role}" function.
Its SPECIFIC job / topic: ${purpose}

This page is about THAT specific subject — not a generic "${role}" template. Title it for the actual topic, and choose the hero visualization, the sections, the framing, the accent palette, and the personality to fit what THIS function specifically works on. A function may be a department (finance, growth, comms…) OR a focused task (research on a particular topic, monitoring one project, tracking one metric) — either way the page must read as purpose-built for it: someone glancing at it should immediately know what this specific function is and how it's doing. A clean page that is genuinely specific beats a flashy generic one. It must fetch and render the live JSON described above.`;
  if (extra && extra.trim()) {
    p += `\n\nThe owner just asked for this SPECIFIC change to the dashboard — apply it faithfully: "${extra.trim()}". Keep the page live-wired (still fetch and render /data) and fully self-contained.`;
  }
  return `${p}\n\nOutput the complete HTML document now.`;
}

/** One Gemini design pass → validated HTML (or null on failure/no key). */
async function generate(role: string, purpose: string, extra?: string): Promise<string | null> {
  if (!KEY) return null;
  const res = await gemini().chat.completions.create({
    model: MODEL,
    messages: [
      { role: "system", content: SYSTEM },
      { role: "user", content: userPrompt(role, purpose, extra) },
    ],
    temperature: 0.85,
    max_tokens: 16384,
    reasoning_effort: "low",
  } as OpenAI.Chat.Completions.ChatCompletionCreateParamsNonStreaming);
  const html = extractHtml(res.choices[0]?.message?.content ?? "");
  return looksValid(html) ? html : null;
}

function extractHtml(text: string): string {
  let t = (text ?? "").trim();
  const fence = t.match(/```(?:html)?\s*([\s\S]*?)```/i);
  if (fence?.[1]) t = fence[1].trim();
  const lt = t.search(/<!doctype|<html|<head|<body/i);
  if (lt > 0) t = t.slice(lt);
  return t.trim();
}

/** A complete, self-contained, live-wired page — reject anything that would
 *  render broken or blank on the headless wall (truncated, external deps, no fetch). */
function looksValid(html: string): boolean {
  if (!html || html.length < 800) return false;
  const lower = html.toLowerCase();
  if (!lower.includes("<script") || !lower.includes("</script>")) return false; // complete, not truncated
  if (!lower.includes("fetch(") || !lower.includes("/data")) return false;       // wired to live data
  if (/(?:src|href)\s*=\s*["']?https?:\/\//i.test(html)) return false;            // must be self-contained
  if (/@import\s+url\(\s*["']?https?:/i.test(html)) return false;
  return true;
}

async function fromCache(id: string): Promise<string | null> {
  try {
    const html = await readFile(join(CACHE_DIR, `${id}.html`), "utf8");
    return looksValid(html) ? html : null;
  } catch {
    return null;
  }
}

async function toCache(id: string, html: string): Promise<void> {
  try {
    await mkdir(CACHE_DIR, { recursive: true });
    await writeFile(join(CACHE_DIR, `${id}.html`), html, "utf8");
  } catch {
    /* cache is best-effort */
  }
}

const inFlight = new Set<string>();
const ready = new Set<string>();

/**
 * Ensure a bespoke, function-designed dashboard page exists for `id` and register
 * it with the dashboard server. Idempotent + non-blocking: the generic template
 * is served until the custom page lands, then the page upgrades itself. On a
 * missing key, an invalid design, or any error we keep the generic template.
 *
 * Trigger this as EARLY as the role/purpose are known (e.g. when a function's
 * room is built) so the design is ready before the in-world wall navigates to it.
 */
export function ensureCustomDashboard(id: string, role: string, purpose: string): void {
  if (!id || ready.has(id) || inFlight.has(id)) return;
  inFlight.add(id);
  void (async () => {
    try {
      const cached = await fromCache(id);
      if (cached) {
        setDashboardHtml(id, cached);
        ready.add(id);
        return;
      }
      const html = await generate(role, purpose);
      if (html) {
        setDashboardHtml(id, html);
        ready.add(id);
        await toCache(id, html);
        console.log(`[dash-arch] ${id} (${role}) — bespoke dashboard ready (${html.length} bytes)`);
      } else {
        console.warn(`[dash-arch] ${id} (${role}) kept generic — no key or invalid design`);
      }
    } catch (e) {
      console.warn(`[dash-arch] ${id} (${role}) kept generic — ${(e as Error)?.message ?? e}`);
    } finally {
      inFlight.delete(id);
    }
  })();
}

/**
 * Re-design an existing dashboard from the OWNER's instructions — the path an
 * agent takes when you tell it "make the board show X" / "change the colours" /
 * "redesign the page". Always regenerates (ignores cache) with the instructions
 * applied, overwrites the page (which bumps its revision so the in-world wall
 * reloads into the new design), and re-caches. Returns whether it succeeded.
 */
export async function redesignDashboard(
  id: string,
  role: string,
  purpose: string,
  instructions: string,
): Promise<boolean> {
  if (!id) return false;
  try {
    const html = await generate(role, purpose, instructions);
    if (!html) return false;
    setDashboardHtml(id, html); // bumps the page revision → the wall reloads
    ready.add(id);
    await toCache(id, html);
    console.log(`[dash-arch] ${id} (${role}) — redesigned on request: "${instructions.slice(0, 80)}"`);
    return true;
  } catch (e) {
    console.warn(`[dash-arch] ${id} (${role}) redesign failed — ${(e as Error)?.message ?? e}`);
    return false;
  }
}

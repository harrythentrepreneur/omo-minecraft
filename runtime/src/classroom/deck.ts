// DECK: pre-generate a real classroom "PowerPoint" for a subject with a fast
// Haiku one-shot. When a classroom opens/themes for a SUBJECT, `ensureDeck`
// fires this off (fire-and-forget, idempotent) and the tutor teaches THROUGH the
// resulting slides, auto-advancing with `show_slide`. Modelled EXACTLY on
// runtime/src/listening/distill.ts — the same Claude Agent SDK one-shot (same
// OAuth as the workshop CodeAgents — no new key), NO tools, a single pure-text
// turn that must answer with strict JSON. The ONLY difference is the model:
// Haiku 4.5 ("claude-haiku-4-5-20251001"), chosen for speed/cost.

import { query, type Options as SdkOptions } from "@anthropic-ai/claude-agent-sdk";
import { tmpdir } from "node:os";
import { warn, info } from "../debug.js";
import { whiteboardStore } from "../whiteboard.js";

const SYSTEM = `You are a lesson designer. Produce a concise SLIDE DECK to teach a subject to a beginner, from the basics, building up.

Output ONLY a single JSON object (no prose, no markdown fences) with exactly this shape:
{
  "slides": [
    {
      "title": "short concept title",
      "bullets": ["short", "a few words each"],
      "example": "optional worked example",
      "diagram": { "kind": "steps|compare|number_line|timeline|bars", ... }
    }
  ]
}

Rules:
- 6–9 slides total.
- The FIRST slide is a short intro/agenda for the subject.
- ONE concept per slide.
- bullets: at most 4, each only a few words (they're read across a room).
- Add a "diagram" ONLY where it genuinely helps, choosing the kind that fits:
  - steps: a process or worked solution — { "kind":"steps", "items":["step one", ".."] }
  - compare: two things side by side — { "kind":"compare", "left":{"head":"A","items":["..."]}, "right":{"head":"B","items":["..."]} }
  - number_line: a value or range on a line — { "kind":"number_line", "min":0, "max":10, "mark":4, "label":"x" }
  - timeline: a sequence or history — { "kind":"timeline", "events":[{"when":"1789","what":"..."}] }
  - bars: quantities to compare — { "kind":"bars", "items":[{"label":"A","value":3},{"label":"B","value":7}] }
- The LAST slide is a short recap / "try this" slide.
- Most slides need no diagram — only include one when it truly clarifies the concept.
- JSON only. No markdown fences, no commentary.`;

function extractJson(text: string): unknown | null {
  const start = text.indexOf("{");
  const end = text.lastIndexOf("}");
  if (start < 0 || end <= start) return null;
  try {
    return JSON.parse(text.slice(start, end + 1));
  } catch {
    return null;
  }
}

/**
 * Run the subject through Haiku and return raw slide objects (still loose — the
 * store's `normalizeSlide` does the defensive validation). Returns [] on ANY
 * error or if no JSON / no slides array could be parsed; the caller falls back
 * to a welcome slide. Never throws.
 */
export async function generateDeck(subject: string): Promise<any[]> {
  const subj = subject.trim();
  if (!subj) return [];

  const options: SdkOptions = {
    cwd: tmpdir(),
    systemPrompt: SYSTEM,
    model: "claude-haiku-4-5-20251001", // Haiku 4.5 — fast/cheap one-shot.
    allowedTools: [],
    maxTurns: 1,
    permissionMode: "default",
    // Pure text turn — refuse any tool the model might reach for.
    canUseTool: async () => ({ behavior: "deny", message: "no tools in deck generation" }),
  };

  const prompt = `Design the slide deck to teach this subject:\n\n"""\n${subj}\n"""`;
  let collected = "";
  try {
    for await (const msg of query({ prompt, options })) {
      if ((msg as { type?: string }).type !== "assistant") continue;
      const content = (msg as { message?: { content?: unknown } }).message?.content;
      if (!Array.isArray(content)) continue;
      for (const block of content as Array<Record<string, unknown>>) {
        if (block?.type === "text" && typeof block.text === "string") collected += block.text;
      }
    }
  } catch (err) {
    warn("classroom", "deck generation query failed", err);
    return [];
  }

  const parsed = extractJson(collected);
  if (!parsed) {
    warn("classroom", "deck generation returned no JSON, using fallback");
    return [];
  }
  const slides = (parsed as { slides?: unknown }).slides;
  if (!Array.isArray(slides)) {
    warn("classroom", "deck generation JSON had no slides array, using fallback");
    return [];
  }
  info("classroom", `generated deck for "${subj}" → ${slides.length} slides`);
  return slides;
}

// Subjects with a generation currently in flight. Belt-and-braces alongside the
// store's `generating` flag so two near-simultaneous ensureDeck calls for the
// same subject can't both kick off a Haiku run.
const inFlight = new Set<string>();

/**
 * Idempotent, fire-and-forget orchestrator: ensure the whiteboard holds a deck
 * for `subject`. If a deck for this exact subject is already generating or
 * already ready, this is a no-op (so a resync / same-subject re-spawn never
 * regenerates or wipes an in-progress deck). Otherwise it seeds the board with a
 * "Preparing…" slide and kicks off the Haiku generation. NEVER throws and never
 * awaits — callers fire it and move on.
 */
export function ensureDeck(subject: string): void {
  const subj = (subject || "").trim() || "Algebra";

  // Already building this subject's deck — leave it alone.
  if (inFlight.has(subj)) return;

  // Idempotent guard: if a deck for this EXACT subject is already generating or
  // already loaded/ready, no-op (a resync / same-subject re-spawn must never
  // regenerate or wipe an in-progress/ready deck). The seeded welcome slide is
  // NOT "ready", so the very first open of the default subject still generates.
  const { subject: deckSubject, generating, ready } = whiteboardStore.deckStatus();
  if (deckSubject === subj && (generating || ready)) return;

  inFlight.add(subj);
  whiteboardStore.beginGenerating(subj);
  generateDeck(subj)
    .then((slides) => {
      whiteboardStore.loadDeck(subj, slides); // [] → welcome fallback inside the store.
    })
    .catch((err) => {
      // generateDeck never throws, but be defensive: still land on a usable board.
      warn("classroom", "ensureDeck generation rejected", err);
      whiteboardStore.loadDeck(subj, []);
    })
    .finally(() => {
      inFlight.delete(subj);
    });
}

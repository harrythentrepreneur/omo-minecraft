// DISTILL: turn the rolling transcript into a clean, organised plan — a set of
// discrete WORK ITEMS, each with its own task breakdown and a ready-to-paste,
// agent-grade prompt for a coding agent (Claude Code). Reuses the Claude Agent
// SDK one-shot (same OAuth as the workshop CodeAgents — no new key), NO tools:
// a single pure-text turn that must answer with strict JSON.

import { query, type Options as SdkOptions } from "@anthropic-ai/claude-agent-sdk";
import { tmpdir } from "node:os";
import { warn, info } from "../debug.js";

export type ItemCategory = "feature" | "bug" | "chore" | "research" | "idea";
export type ItemPriority = "high" | "medium" | "low";

export type DistillItem = {
  id: number;
  title: string;
  category: ItemCategory;
  priority: ItemPriority;
  tasks: string[];
  prompt: string; // a complete, self-contained prompt for a coding agent
};

export type DistillResult = {
  title: string;
  summary: string;
  items: DistillItem[];
  clipboard: string; // the full, clean, paste-ready plan (all prompts)
};

const CATEGORIES: ItemCategory[] = ["feature", "bug", "chore", "research", "idea"];
const PRIORITIES: ItemPriority[] = ["high", "medium", "low"];

const SYSTEM = `You are the scribe of a "Listening Room". You receive a raw, rambling, speech-to-text transcript of someone thinking out loud about software they want built. Turn it into a clean, organised plan that a team of AI coding agents can act on immediately.

Output ONLY a single JSON object — no prose, no markdown fences — with EXACTLY this shape:
{
  "title": "a short title for this session (≤6 words)",
  "summary": "1-2 plain sentences: what they want, overall",
  "items": [
    {
      "title": "concise work-item title (≤8 words)",
      "category": "feature | bug | chore | research | idea",
      "priority": "high | medium | low",
      "tasks": ["concrete subtask", "..."],
      "prompt": "<an agent-ready prompt — see format below>"
    }
  ]
}

Each "prompt" MUST be a complete, self-contained instruction a coding agent (Claude Code) can execute with no extra context. Write it in clean markdown using EXACTLY these sections, in this order:
**Goal:** one sentence stating the outcome.
**Context:** only the background the agent needs (what exists, why, any names/tech mentioned). Keep it tight.
**Requirements:**
- concrete, unambiguous bullet points
**Acceptance criteria:**
- bullet points describing how to verify it's done

Rules:
- Split the transcript into distinct, coherent work items. One item = one shippable piece of work. Prefer a few sharp items over many shallow ones; merge duplicates.
- Order items by priority (high first).
- Infer intent generously; silently drop filler, false starts, and transcription noise.
- Prompts must be directly usable — never use placeholders like "[TODO]" or "[fill in]". If a detail is genuinely unknown, state a sensible default and note it as an assumption inside Context.
- "tasks" is the human-facing checklist for the item (3-7 short items); the "prompt" is what gets pasted to the agent.
- If the transcript contains no real software request, return an empty "items" array and a "summary" that says so.`;

function emptyResult(message: string): DistillResult {
  return { title: "Nothing recorded", summary: message, items: [], clipboard: "" };
}

function fallback(transcript: string): DistillResult {
  if (!transcript) return emptyResult("Nothing was recorded yet.");
  // Couldn't structure it — still hand back something usable.
  const item: DistillItem = {
    id: 1,
    title: "Raw transcript",
    category: "idea",
    priority: "medium",
    tasks: [],
    prompt: transcript,
  };
  return {
    title: "Unstructured note",
    summary: "Couldn't organise this automatically — the raw transcript is below and on your clipboard.",
    items: [item],
    clipboard: transcript,
  };
}

function extractJson(text: string): unknown | null {
  const start = text.indexOf("{");
  const end = text.lastIndexOf("}");
  if (start < 0 || end <= start) return null;
  try { return JSON.parse(text.slice(start, end + 1)); } catch { return null; }
}

function oneOf<T extends string>(v: unknown, allowed: T[], def: T): T {
  return typeof v === "string" && (allowed as string[]).includes(v) ? (v as T) : def;
}

function coerce(obj: unknown, transcript: string): DistillResult {
  const o = (obj ?? {}) as Record<string, unknown>;
  const rawItems = Array.isArray(o.items) ? (o.items as unknown[]) : [];
  const items: DistillItem[] = rawItems
    .map((it) => it as Record<string, unknown>)
    .filter((it) => it && typeof it.prompt === "string" && (it.prompt as string).trim())
    .map((it, i) => ({
      id: i + 1,
      title: typeof it.title === "string" && it.title.trim() ? it.title.trim() : `Item ${i + 1}`,
      category: oneOf(it.category, CATEGORIES, "feature"),
      priority: oneOf(it.priority, PRIORITIES, "medium"),
      tasks: Array.isArray(it.tasks) ? (it.tasks.filter((t) => typeof t === "string") as string[]) : [],
      prompt: String(it.prompt).trim(),
    }));
  if (!items.length) {
    const summary = typeof o.summary === "string" ? o.summary : "No actionable work found in what was said.";
    return { title: typeof o.title === "string" ? o.title : "Nothing actionable", summary, items: [], clipboard: "" };
  }
  // High priority first, stable otherwise; renumber ids to match display order.
  const rank: Record<ItemPriority, number> = { high: 0, medium: 1, low: 2 };
  items.sort((a, b) => rank[a.priority] - rank[b.priority]);
  items.forEach((it, i) => (it.id = i + 1));
  const title = typeof o.title === "string" && o.title.trim() ? o.title.trim() : "Distilled plan";
  const summary = typeof o.summary === "string" ? o.summary : "";
  const result: DistillResult = { title, summary, items, clipboard: "" };
  result.clipboard = toMarkdown(result);
  return result;
}

/** A clean, paste-ready markdown document of the whole plan (all prompts). */
export function toMarkdown(result: DistillResult, transcript?: string): string {
  const lines: string[] = [`# ${result.title}`, ``];
  if (result.summary) lines.push(result.summary, ``);
  result.items.forEach((it) => {
    lines.push(`---`, ``, `## ${it.id}. ${it.title}`, `*${it.category} · ${it.priority} priority*`, ``);
    if (it.tasks.length) {
      lines.push(`**Checklist**`, ...it.tasks.map((t) => `- [ ] ${t}`), ``);
    }
    lines.push(it.prompt, ``);
  });
  if (transcript) lines.push(`---`, ``, `## Transcript`, ``, transcript, ``);
  return lines.join("\n");
}

/** Run the transcript through Claude and return an organised, agent-ready plan. */
export async function distill(transcript: string): Promise<DistillResult> {
  const text = transcript.trim();
  if (!text) return emptyResult("Nothing was recorded yet.");

  const options: SdkOptions = {
    cwd: tmpdir(),
    systemPrompt: SYSTEM,
    allowedTools: [],
    maxTurns: 1,
    permissionMode: "default",
    // Pure text turn — refuse any tool the model might reach for.
    canUseTool: async () => ({ behavior: "deny", message: "no tools in distill" }),
  };

  const prompt = `Here is the transcript to distill:\n\n"""\n${text}\n"""`;
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
    warn("listen", "distill query failed", err);
    return fallback(text);
  }

  const parsed = extractJson(collected);
  if (!parsed) {
    warn("listen", "distill returned no JSON, using fallback");
    return fallback(text);
  }
  const result = coerce(parsed, text);
  info("listen", `distilled → "${result.title}" · ${result.items.length} items`);
  return result;
}

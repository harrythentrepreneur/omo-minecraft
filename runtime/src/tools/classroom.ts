import type { ToolImpl } from "./registry.js";
import { whiteboardStore } from "../whiteboard.js";
import type { SlideDiagram } from "../whiteboard.js";

export const presentSlideTool: ToolImpl = {
  def: {
    type: "function",
    function: {
      name: "present_slide",
      description:
        "Show a NEW slide on the presentation wall behind you. Call this whenever you move to a new concept, BEFORE you explain it out loud. One concept per slide. Keep it short and legible — it's read across a room. " +
        "Use `bullets` for 2-4 key ideas (a few words each), `example` for a worked example, `note` for a small reminder, and `diagram` when it genuinely helps. " +
        "Diagram kinds: steps=a numbered process or worked solution (items); compare=two columns side by side (left/right, each with head+items); " +
        "number_line=a value or range on a line (min, max, mark=the highlighted value, optional ticks/label); " +
        "timeline=ordered events for a sequence or history (events: when+what); bars=quantities to compare (items: label+value).",
      parameters: {
        type: "object",
        properties: {
          title: { type: "string", description: "The concept this slide is about (short)." },
          bullets: {
            type: "array",
            items: { type: "string" },
            description: "2-4 key ideas, a few words each. Capped at 4 on the wall.",
          },
          example: { type: "string", description: "A worked example, shown in a highlighted box." },
          note: { type: "string", description: "A small footnote / 'remember' line (optional)." },
          diagram: {
            type: "object",
            description:
              "Optional visual. Set `kind` to one of steps|compare|number_line|timeline|bars and fill the matching fields.",
            properties: {
              kind: {
                type: "string",
                enum: ["steps", "compare", "number_line", "timeline", "bars"],
              },
              items: {
                type: "array",
                description: "steps: the ordered step strings. (bars uses {label,value} objects.)",
              },
              left: {
                type: "object",
                description: "compare: left column { head, items }.",
                properties: { head: { type: "string" }, items: { type: "array", items: { type: "string" } } },
              },
              right: {
                type: "object",
                description: "compare: right column { head, items }.",
                properties: { head: { type: "string" }, items: { type: "array", items: { type: "string" } } },
              },
              min: { type: "number", description: "number_line: left end." },
              max: { type: "number", description: "number_line: right end." },
              mark: { type: "number", description: "number_line: the highlighted value." },
              ticks: { type: "number", description: "number_line: how many ticks to draw." },
              label: { type: "string", description: "number_line: a label for the mark." },
              events: {
                type: "array",
                description: "timeline: ordered events, each { when, what }.",
                items: {
                  type: "object",
                  properties: { when: { type: "string" }, what: { type: "string" } },
                },
              },
            },
          },
        },
        required: ["title"],
      },
    },
  },
  needsApproval: () => false,
  async run(args, ctx) {
    // addSlide runs everything through the shared normalizeSlide (title coerced,
    // bullets capped, diagram validated against the 5 kinds), so the off-deck
    // `present_slide` path validates identically to generated decks. We pass the
    // raw args straight through.
    const n = whiteboardStore.addSlide({
      title: String(args.title ?? ""),
      bullets: Array.isArray(args.bullets) ? (args.bullets as string[]) : undefined,
      example: args.example as string | undefined,
      note: args.note as string | undefined,
      diagram: args.diagram as SlideDiagram | undefined,
    });
    const st = whiteboardStore.get();
    const title = st.slides[st.current]?.title || "Slide";
    ctx.log(`present_slide #${n}: ${title}`, "tool");
    return { ok: true, slide: n };
  },
};

export const showSlideTool: ToolImpl = {
  def: {
    type: "function",
    function: {
      name: "show_slide",
      description:
        "Put one of the PREPARED deck slides on the wall behind you (1-based). Call this as you teach to advance through the deck: show the slide you're about to explain, then explain it. Use `read_deck` first to see the slide numbers.",
      parameters: {
        type: "object",
        properties: {
          slide: {
            type: "number",
            description: "1-based slide number to show on the wall (from read_deck).",
          },
        },
        required: ["slide"],
      },
    },
  },
  needsApproval: () => false,
  async run(args, ctx) {
    const slide = Math.round(Number(args.slide));
    whiteboardStore.showSlide(slide);
    const cur = whiteboardStore.get().current + 1;
    ctx.log(`show_slide → ${cur}`, "tool");
    return { ok: true, slide: cur };
  },
};

export const readDeckTool: ToolImpl = {
  def: {
    type: "function",
    function: {
      name: "read_deck",
      description:
        "Read the PREPARED slide deck on the wall (your 'PowerPoint'): the subject, whether it's still being generated, and every slide's number + title + bullets. Call this first so you know what's on each slide and can teach through them in order with `show_slide`.",
      parameters: { type: "object", properties: {} },
    },
  },
  needsApproval: () => false,
  async run(_args, ctx) {
    const st = whiteboardStore.get();
    ctx.log(`read_deck: ${st.slides.length} slides${st.generating ? " (generating)" : ""}`, "tool");
    return {
      subject: st.subject,
      generating: st.generating,
      slides: st.slides.map((s) => ({ n: s.n, title: s.title, bullets: s.bullets })),
    };
  },
};

export const whiteboardWriteTool: ToolImpl = {
  def: {
    type: "function",
    function: {
      name: "whiteboard_write",
      description:
        "Freeform fallback: write a plain note to the presentation wall as a new slide. Prefer `present_slide` (bullets + example + diagram) for teaching; use this only for a quick paragraph.",
      parameters: {
        type: "object",
        properties: {
          title: {
            type: "string",
            description: "Short heading for what's on the board right now (optional).",
          },
          content: {
            type: "string",
            description:
              "The key point or worked example to show on the board. Line breaks are preserved.",
          },
        },
        required: ["content"],
      },
    },
  },
  needsApproval: () => false,
  async run(args, ctx) {
    const title = args.title != null ? String(args.title) : undefined;
    const content = String(args.content ?? "");
    whiteboardStore.set({ title, content });
    ctx.log(`whiteboard_write: ${title ?? "(no title)"}`, "tool");
    return { ok: true };
  },
};

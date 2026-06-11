import type { ToolImpl } from "./registry.js";

export const finishTaskTool: ToolImpl = {
  def: {
    type: "function",
    function: {
      name: "finish_task",
      description:
        "Signal that you are done with the current task. The world will mark you idle and show this summary on your home screen.",
      parameters: {
        type: "object",
        properties: {
          summary: {
            type: "string",
            description: "One or two sentence summary of what you did.",
          },
        },
        required: ["summary"],
      },
    },
  },
  async run(args, ctx) {
    const summary = String(args.summary ?? "");
    ctx.log(`finish_task: ${summary}`, "tool");
    return { ok: true, summary };
  },
};

export const requestHumanApprovalTool: ToolImpl = {
  def: {
    type: "function",
    function: {
      name: "request_human_approval",
      description:
        "Ask the owner (player) in-world to approve a sensitive action before you perform it. Returns { approved: boolean }.",
      parameters: {
        type: "object",
        properties: {
          action: {
            type: "string",
            description: "Short description of what you want to do.",
          },
        },
        required: ["action"],
      },
    },
  },
  async run(args, ctx) {
    const approved = await ctx.requestApproval(String(args.action ?? ""));
    return { approved };
  },
};

export const sayTool: ToolImpl = {
  def: {
    type: "function",
    function: {
      name: "say",
      description:
        "Speak a short message in-game above your head (chat bubble) and in the room chat log.",
      parameters: {
        type: "object",
        properties: {
          text: { type: "string", description: "What to say. 1-2 short sentences." },
        },
        required: ["text"],
      },
    },
  },
  async run(args, ctx) {
    const text = String(args.text ?? "").slice(0, 240);
    ctx.log(`[say] ${text}`, "info");
    return { ok: true, text };
  },
};

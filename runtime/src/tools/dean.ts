import type { ToolImpl } from "./registry.js";
import { ensureDeck } from "../classroom/deck.js";

export const openClassroomTool: ToolImpl = {
  def: {
    type: "function",
    function: {
      name: "open_classroom",
      description:
        "Re-theme the single adjacent classroom for a subject and re-seat the tutor 'ada' to teach it. Call this once you know what the learner wants to learn. The student then walks into the classroom and ada teaches them.",
      parameters: {
        type: "object",
        properties: {
          subject: {
            type: "string",
            description:
              "Short, clean subject name to teach (e.g. 'Spanish', 'World War II', 'chess openings').",
          },
        },
        required: ["subject"],
      },
    },
  },
  needsApproval: () => false,
  async run(args, ctx) {
    const subject = String(args.subject ?? "").trim() || "Algebra";
    // Kick off Haiku deck generation immediately — before ada's brain even
    // spawns — so the deck is ready (or visibly preparing) by the time the
    // learner sits down. Idempotent with the AgentManager spawn-time call.
    ensureDeck(subject);
    ctx.openClassroom?.({ subject });
    ctx.log(`open_classroom: ${subject}`, "tool");
    return { ok: true, room: "classroom" };
  },
};

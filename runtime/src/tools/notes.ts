import { mkdir, readFile, writeFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import path from "node:path";
import type { ToolImpl } from "./registry.js";

const NOTES_DIR = path.resolve(process.cwd(), "data", "notes");

async function ensureDir() {
  if (!existsSync(NOTES_DIR)) await mkdir(NOTES_DIR, { recursive: true });
}

export const notesWriteTool: ToolImpl = {
  def: {
    type: "function",
    function: {
      name: "notes_write",
      description: "Persist a note for the owner that survives across sessions. Stored in runtime/data/notes/<agent>.md (append).",
      parameters: {
        type: "object",
        properties: {
          title: { type: "string" },
          body: { type: "string" },
        },
        required: ["title", "body"],
      },
    },
  },
  async run(args, ctx) {
    await ensureDir();
    const file = path.join(NOTES_DIR, `${ctx.agentId}.md`);
    const stamp = new Date().toISOString();
    const entry = `\n## ${stamp} — ${args.title}\n\n${args.body}\n`;
    const prev = existsSync(file) ? await readFile(file, "utf8") : `# Notes — ${ctx.agentId}\n`;
    await writeFile(file, prev + entry, "utf8");
    return { ok: true, file };
  },
};

export const notesReadTool: ToolImpl = {
  def: {
    type: "function",
    function: {
      name: "notes_read",
      description: "Read your own notes file (everything stored via notes_write).",
      parameters: { type: "object", properties: {} },
    },
  },
  async run(_args, ctx) {
    const file = path.join(NOTES_DIR, `${ctx.agentId}.md`);
    if (!existsSync(file)) return { notes: "" };
    return { notes: await readFile(file, "utf8") };
  },
};

export const notesTools = [notesWriteTool, notesReadTool];

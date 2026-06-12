import type { HermesToolDef } from "../inference/hermes.js";

export type ToolContext = {
  agentId: string;
  ownerName: string;
  room: string;
  requestApproval: (summary: string) => Promise<boolean>;
  log: (line: string, level?: "info" | "warn" | "error" | "tool") => void;
  openClassroom?: (p: { subject: string }) => void;
  // Start a new world (host villager → plugin spawn path). Owner-explicit only.
  startCodeWorld?: (p: { agentId: string; cwd: string; task: string }) => void;
  startHermesWorld?: (p: { agentId: string; role: string }) => void;
};

export type ToolImpl = {
  def: HermesToolDef;
  needsApproval?: (args: Record<string, unknown>) => boolean;
  run: (args: Record<string, unknown>, ctx: ToolContext) => Promise<unknown>;
};

export class ToolRegistry {
  private tools = new Map<string, ToolImpl>();

  register(tool: ToolImpl): void {
    this.tools.set(tool.def.function.name, tool);
  }

  list(): HermesToolDef[] {
    return [...this.tools.values()].map((t) => t.def);
  }

  get(name: string): ToolImpl | undefined {
    return this.tools.get(name);
  }
}

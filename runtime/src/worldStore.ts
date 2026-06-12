// The Omo "world graph" — the live org the agent extends. Each function is one
// unit of the organisation: a role, its purpose, the tools it uses, the room it
// occupies, the villager staffing it, and its live dashboard. The Chief of Staff
// (and the human) extend the world by adding/building/staffing functions through
// the World API exposed over MCP. State is in-memory by design (mirrors how the
// rest of the runtime keeps conversation state ephemeral).

export type OmoFunction = {
  id: string;
  role: string;
  purpose: string;
  tools: string[];
  room: string;
  index: number;
  staffed: boolean;
  agentId?: string;
  dashboardId: string;
  // "specialist" = a normal Gemini function with a data dashboard. "school" = an
  // on-demand classroom: a live tutor + a lesson whiteboard instead of a board.
  kind: "specialist" | "school";
  createdAt: number;
};

// One agent-to-agent consultation: who asked whom, the question, the answer once
// it lands, and the lifecycle status. Feeds the Society View (/dash/society) so
// the player sees collaboration happen between the rooms.
export type Consultation = {
  id: string;
  from: string; // asking function id
  to: string; // consulted function id
  fromRole?: string;
  toRole?: string;
  question: string;
  answer?: string;
  at: number;
  status: "pending" | "answered" | "failed";
};

export class WorldStore {
  private functions = new Map<string, OmoFunction>();
  private consults: Consultation[] = [];
  private consultSeq = 0;
  hqRoom = "hq";
  owner = "Chief of Staff";

  list(): OmoFunction[] {
    return [...this.functions.values()].sort((a, b) => a.index - b.index);
  }

  get(id: string): OmoFunction | undefined {
    return this.functions.get(id);
  }

  addFunction(p: {
    role: string;
    purpose: string;
    tools?: string[];
    id?: string;
    room?: string;
    kind?: OmoFunction["kind"];
  }): OmoFunction {
    const id = p.id ?? slug(p.role);
    const existing = this.functions.get(id);
    if (existing) return existing;
    const fn: OmoFunction = {
      id,
      role: p.role,
      purpose: p.purpose,
      tools: p.tools ?? [],
      // A school routes to the classroom brain, so its room must read as a
      // classroom to roomKindFromName (not "fn-…", which routes to the ADK).
      room: p.room ?? `fn-${id}`,
      index: this.functions.size,
      staffed: false,
      dashboardId: id,
      kind: p.kind ?? "specialist",
      createdAt: Date.now(),
    };
    this.functions.set(id, fn);
    return fn;
  }

  markStaffed(id: string, agentId: string): void {
    const f = this.functions.get(id);
    if (f) {
      f.staffed = true;
      f.agentId = agentId;
    }
  }

  // ── Consultation log — the cross-room "society" memory ──────────────────────

  /** Record a new consultation (status "pending"); returns the row so the
   *  caller can resolve it once the answer (or failure) lands. */
  recordConsult(p: { from: string; to: string; question: string; fromRole?: string; toRole?: string }): Consultation {
    const c: Consultation = {
      id: `c${++this.consultSeq}`,
      from: p.from,
      to: p.to,
      fromRole: p.fromRole,
      toRole: p.toRole,
      question: p.question,
      at: Date.now(),
      status: "pending",
    };
    this.consults.push(c);
    // Keep the log bounded — it's an in-memory feed for the Society View.
    if (this.consults.length > 200) this.consults.shift();
    return c;
  }

  /** Close out a consultation with its answer (or mark it failed if answer is null). */
  resolveConsult(id: string, answer: string | null): void {
    const c = this.consults.find((x) => x.id === id);
    if (!c) return;
    if (answer == null) {
      c.status = "failed";
    } else {
      c.answer = answer;
      c.status = "answered";
    }
  }

  /** The last N consultations, newest first (default 20) — feeds /dash/society. */
  recentConsults(n = 20): Consultation[] {
    return this.consults.slice(-n).reverse();
  }
}

function slug(s: string): string {
  return s.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 24) || "fn";
}

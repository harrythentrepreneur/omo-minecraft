import type { AgentEvents } from "./HermesAgent.js";
import type { AgentStatus, Vec3 } from "../types.js";
import { ClaudePty } from "./ClaudePty.js";

const IDLE_AFTER_MS = 5_000;

/**
 * A workshop villager backed by a real `claude` CLI subprocess running in a
 * PTY. Differs from {@link CodeAgent} (which calls the Agent SDK as a library)
 * in one important way: the in-game terminal can ATTACH to this PTY and watch
 * the live claude TUI.
 *
 * Player chat → {@link handleMessage} → typed into the PTY's stdin.
 * Terminal WS clients subscribe to the PTY's stdout via {@link getPty}.
 */
export class WorkshopAgent {
  readonly id: string;
  readonly role: string;
  readonly home: Vec3;
  readonly room: string;
  readonly ownerName: string;
  readonly cwd: string;
  /** Command auto-run in the shell on open: "claude", "hermes chat", or null for a plain shell. */
  readonly launch: string | null;
  status: AgentStatus = "idle";

  private pty: ClaudePty | null = null;
  private lastActivityAt = 0;
  private idleTimer?: NodeJS.Timeout;

  constructor(
    opts: {
      id: string;
      role: string;
      home: Vec3;
      room: string;
      ownerName: string;
      cwd: string;
      launch?: string;
    },
    private events: AgentEvents,
  ) {
    this.id = opts.id;
    this.role = opts.role;
    this.home = opts.home;
    this.room = opts.room;
    this.ownerName = opts.ownerName;
    this.cwd = opts.cwd;
    // undefined → default "claude"; "" → plain shell (no auto-launch).
    this.launch = opts.launch === undefined ? "claude" : (opts.launch === "" ? null : opts.launch);
  }

  async handleMessage(playerName: string, text: string): Promise<void> {
    this.ensurePty();
    if (!this.pty) {
      this.events.onSay("(couldn't start claude — check runtime logs)", playerName);
      return;
    }
    this.setStatus("thinking", `from ${playerName}`);
    // Claude's TUI reads stdin line by line; \r submits.
    this.pty.writeStdin(text + "\r");
  }

  dispose(): void {
    if (this.idleTimer) clearTimeout(this.idleTimer);
    this.pty?.stop();
    this.pty = null;
  }

  /** Used by the terminal WS server to subscribe to live PTY output. */
  getPty(): ClaudePty | null {
    return this.pty;
  }

  /**
   * Start the claude subprocess if it isn't running yet. Safe to call from
   * anywhere — the terminal WS calls this on subscribe so the player sees the
   * claude welcome screen even if no chat has dispatched yet.
   */
  ensurePty(): ClaudePty | null {
    if (this.pty && this.pty.isAlive()) return this.pty;
    const p = new ClaudePty({ cwd: this.cwd, autoLaunch: this.launch });
    p.onData(() => this.bumpActivity());
    p.onExit((code) => {
      this.events.onLog(
        `shell exited (${code})${this.pty?.errorMessage() ? `: ${this.pty.errorMessage()}` : ""}`,
        code === 0 ? "info" : "warn",
      );
      this.setStatus("idle");
    });
    try {
      p.start();
      this.pty = p;
      this.events.onLog(
        `${this.launch ?? "shell"} session started in ${this.cwd}`, "info");
      return p;
    } catch (e) {
      this.events.onLog(`claude start failed: ${(e as Error).message}`, "error");
      this.pty = null;
      return null;
    }
  }

  private bumpActivity(): void {
    this.lastActivityAt = Date.now();
    if (this.status !== "thinking") this.setStatus("thinking");
    if (this.idleTimer) clearTimeout(this.idleTimer);
    this.idleTimer = setTimeout(() => {
      if (Date.now() - this.lastActivityAt >= IDLE_AFTER_MS) {
        this.setStatus("idle");
      }
    }, IDLE_AFTER_MS + 100);
  }

  private setStatus(status: AgentStatus, detail?: string): void {
    if (this.status === status) return;
    this.status = status;
    this.events.onStatus(status, detail);
  }
}

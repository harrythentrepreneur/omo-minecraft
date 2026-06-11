import * as pty from "node-pty";
import type { IPty } from "node-pty";

type DataListener = (bytes: Buffer) => void;
type ExitListener = (code: number, signal: number | undefined) => void;

const REPLAY_BUFFER_KB = 64;

/**
 * A login shell subprocess in a PTY that auto-launches `claude` inside it.
 *
 * The player gets full terminal control: Ctrl+C exits claude, drops back to
 * the shell prompt, where they can `ls`, `git diff`, run any command, or just
 * type `claude` again to start a new session. Each WorkshopAgent owns one of
 * these. The terminal WebSocket fans the PTY stream out to (potentially)
 * multiple in-game terminal clients.
 *
 * Auto-launch lifecycle:
 *   1. start() spawns `$SHELL -l -c "claude; exec $SHELL -l"` in opts.cwd
 *   2. claude takes over the foreground immediately on the PTY — no fragile
 *      prompt-timing paste, so the player always sees the real Claude Code TUI
 *   3. when claude exits (Ctrl+C), `exec $SHELL -l` hands back an interactive
 *      login shell — full control restored (ls, git diff, type `claude` again)
 */
export class ClaudePty {
  private proc?: IPty;
  private listeners = new Set<DataListener>();
  private exitListeners = new Set<ExitListener>();
  private replay: Buffer = Buffer.alloc(0) as Buffer;
  private lastError: string | null = null;

  constructor(
    private opts: {
      cwd: string;
      cols?: number;
      rows?: number;
      command?: string;
      args?: string[];
      /** Command typed automatically once the shell prompt appears. Pass null to disable. */
      autoLaunch?: string | null;
    },
  ) {}

  start(): void {
    if (this.proc) return;
    const shell = pickShell();
    const launchCmd = this.opts.autoLaunch === null ? null : (this.opts.autoLaunch ?? "claude");
    let cmd: string;
    let args: string[];
    if (this.opts.command) {
      cmd = this.opts.command;
      args = this.opts.args ?? shell.args;
    } else if (launchCmd) {
      // Print a ready banner FIRST so the in-game terminal is never blank
      // (some CLIs — notably claude's daemon/bg-pty mode — can render to their
      // own pty and stay silent on ours). Then run the command, then hand back
      // an interactive login shell when it exits.
      cmd = shell.cmd;
      const banner =
        "printf '\\033[2m— terminal ready · Ctrl-C drops to a shell (cd/ls, then re-run) —\\033[0m\\r\\n'; ";
      args = ["-l", "-c", `${banner}${launchCmd}; exec ${shell.cmd} -l`];
    } else {
      cmd = shell.cmd;
      args = shell.args;
    }
    try {
      this.proc = pty.spawn(cmd, args, {
        name: "xterm-256color",
        cols: this.opts.cols ?? 100,
        rows: this.opts.rows ?? 30,
        cwd: this.opts.cwd,
        env: {
          ...process.env,
          TERM: "xterm-256color",
          COLORTERM: "truecolor",
          LANG: process.env.LANG ?? "en_US.UTF-8",
          LC_ALL: process.env.LC_ALL ?? "en_US.UTF-8",
          // Make sure claude is findable when launched from the runtime.
          PATH: extendedPath(process.env.PATH),
        },
      });
    } catch (e) {
      this.lastError = e instanceof Error ? e.message : String(e);
      throw e;
    }
    this.proc.onData((data) => {
      const buf = Buffer.from(data, "utf8");
      this.appendReplay(buf);
      for (const l of this.listeners) {
        try { l(buf); } catch { /* ignore */ }
      }
    });
    this.proc.onExit(({ exitCode, signal }) => {
      const exited = this.proc;
      this.proc = undefined;
      for (const l of this.exitListeners) {
        try { l(exitCode, signal); } catch { /* ignore */ }
      }
      // Silence unused warning if exited turns out null on some platforms.
      void exited;
    });
  }

  writeStdin(text: string): void {
    if (!this.proc) return;
    this.proc.write(text);
  }

  writeStdinBytes(buf: Buffer): void {
    if (!this.proc) return;
    this.proc.write(buf.toString("utf8"));
  }

  resize(cols: number, rows: number): void {
    if (!this.proc) return;
    try { this.proc.resize(cols, rows); } catch { /* ignore */ }
  }

  onData(listener: DataListener): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  onExit(listener: ExitListener): () => void {
    this.exitListeners.add(listener);
    return () => this.exitListeners.delete(listener);
  }

  /** Replay the last ~64KB of output so a fresh subscriber sees the current frame. */
  snapshot(): Buffer {
    return this.replay;
  }

  stop(): void {
    if (!this.proc) return;
    try { this.proc.kill(); } catch { /* ignore */ }
    this.proc = undefined;
  }

  isAlive(): boolean {
    return !!this.proc;
  }

  errorMessage(): string | null {
    return this.lastError;
  }

  private appendReplay(buf: Buffer): void {
    const max = REPLAY_BUFFER_KB * 1024;
    if (buf.length >= max) {
      this.replay = Buffer.from(buf.subarray(buf.length - max));
      return;
    }
    const combined = Buffer.concat([this.replay, buf]);
    this.replay = combined.length > max
      ? Buffer.from(combined.subarray(combined.length - max))
      : combined;
  }
}

function extendedPath(current: string | undefined): string {
  const extra = ["/opt/homebrew/bin", "/usr/local/bin", `${process.env.HOME}/.local/bin`, `${process.env.HOME}/.claude/local`];
  const have = new Set((current ?? "").split(":").filter(Boolean));
  const out = (current ?? "/usr/bin:/bin").split(":").filter(Boolean);
  for (const p of extra) if (!have.has(p)) out.push(p);
  return out.join(":");
}

/**
 * Pick a login shell. Honour $SHELL first; fall back to zsh, then bash, then
 * /bin/sh. `-l` so the user's full env (PATH, aliases, claude completion) loads.
 */
function pickShell(): { cmd: string; args: string[] } {
  const fromEnv = process.env.SHELL;
  if (fromEnv && fromEnv.length > 0) return { cmd: fromEnv, args: ["-l"] };
  return { cmd: "/bin/zsh", args: ["-l"] };
}

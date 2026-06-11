// The Listening Room's capture + transcription engine.
//
// One always-available singleton (`listening`). When ARMED (the in-game lever),
// it captures the Mac microphone with ffmpeg into short WAV segments and runs
// each finished segment through whisper.cpp (`whisper-cli`) — fully local, no
// API key, no network. The rolling transcript feeds the live map-wall (via
// /api/listening/state) and the DISTILL button (via getFullText()).
//
// Design mirrors the cinema↔face relationship: capture is a child process the
// runtime owns. Loss is fine — a dropped segment just means a gap on the wall.

import { spawn, spawnSync, type ChildProcess } from "node:child_process";
import { tmpdir, homedir } from "node:os";
import { join } from "node:path";
import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  readdirSync,
  rmSync,
  statSync,
} from "node:fs";
import { info, warn, dbg } from "../debug.js";
import type { DistillResult } from "./distill.js";

const SEGMENT_SECONDS = 6; // chunk length → ~6s transcription latency
const MAX_SEGMENTS = 200; // rolling buffer cap (≈20 min of speech)
const WALL_TAIL = 60; // how many recent segments the wall renders

// whisper.cpp prints these placeholders for non-speech; never show them.
const NON_SPEECH = [
  /^\[?\s*blank[_ ]?audio\s*\]?$/i,
  /^\[?\s*silence\s*\]?$/i,
  /^\[?\s*music\s*\]?$/i,
  /^\[?\s*inaudible\s*\]?$/i,
  /^\(.*\)$/, // (typing), (no audio) etc.
];

// A lightweight projection of a distilled item for the wall board — enough to
// render the card + drive click-to-copy, without shipping every full prompt on
// each ~0.7s poll. Full prompts live in the stored result (book + copy).
export type BoardItem = {
  id: number;
  title: string;
  category: string;
  priority: string;
  taskCount: number;
  preview: string;
};
export type BoardView = {
  title: string;
  summary: string;
  at: number;
  items: BoardItem[];
};

export type ListeningState = {
  armed: boolean;
  recording: boolean;
  error: string | null;
  updatedAt: number;
  text: string;
  // Which face the wall shows: "live" transcript, or the "board" of distilled
  // work items (after DISTILL, until recording is re-armed).
  view: "live" | "board";
  // True while the Claude call is in flight (wall shows a "Distilling…" state
  // so a press is visible immediately, not after the ~20s round-trip).
  distilling: boolean;
  board: BoardView | null;
  // Transient flash so a clicked item reads "✓ copied" for a beat.
  copiedId: number | null;
  copiedAt: number;
};

type Segment = { t: number; text: string };

function resolveModel(): string | null {
  const env = process.env.AGENTCRAFT_WHISPER_MODEL;
  if (env && existsSync(env)) return env;
  const candidates = [
    env,
    join(homedir(), ".cache/whisper/ggml-large-v3-turbo.bin"),
    join(homedir(), ".cache/whisper/ggml-medium.bin"),
    join(homedir(), ".cache/whisper/ggml-base.en.bin"),
  ].filter(Boolean) as string[];
  for (const c of candidates) if (existsSync(c)) return c;
  return null;
}

/**
 * Parse `ffmpeg -list_devices` to find the avfoundation audio index whose name
 * matches AGENTCRAFT_MIC_NAME (default "MacBook Pro Microphone"), falling back
 * to the first audio device that isn't a loopback (BlackHole/Soundflower).
 */
function detectMicIndex(): number {
  const want = (process.env.AGENTCRAFT_MIC_NAME ?? "MacBook Pro Microphone").toLowerCase();
  try {
    const out = spawnSync(
      "ffmpeg",
      ["-hide_banner", "-f", "avfoundation", "-list_devices", "true", "-i", ""],
      { encoding: "utf8", timeout: 8000 },
    );
    const text = `${out.stderr ?? ""}${out.stdout ?? ""}`;
    const lines = text.split("\n");
    let inAudio = false;
    const devices: { idx: number; name: string }[] = [];
    for (const line of lines) {
      if (/AVFoundation audio devices:/i.test(line)) { inAudio = true; continue; }
      if (/AVFoundation video devices:/i.test(line)) { inAudio = false; continue; }
      if (!inAudio) continue;
      const m = line.match(/\[(\d+)\]\s+(.*)$/);
      if (m && m[1] && m[2]) devices.push({ idx: Number(m[1]), name: m[2].trim() });
    }
    const exact = devices.find((d) => d.name.toLowerCase().includes(want));
    if (exact) return exact.idx;
    const real = devices.find((d) => !/blackhole|soundflower|loopback|aggregate/i.test(d.name));
    if (real) return real.idx;
    if (devices[0]) return devices[0].idx;
  } catch (err) {
    warn("listen", "could not list avfoundation devices", err);
  }
  return 0;
}

class ListeningSession {
  private segments: Segment[] = [];
  private ffmpeg: ChildProcess | null = null;
  private watcher: ReturnType<typeof setInterval> | null = null;
  private dir: string | null = null;
  private armed = false;
  private error: string | null = null;
  private updatedAt = 0;
  private processed = new Set<string>();
  private transcribing = false;
  // Last distilled plan + which face the wall shows.
  private distillResult: DistillResult | null = null;
  private view: "live" | "board" = "live";
  private distilling = false;
  private copiedId: number | null = null;
  private copiedAt = 0;

  isArmed(): boolean {
    return this.armed;
  }

  /** Flip recording on. Idempotent. Returns false if it could not start. */
  arm(): boolean {
    // Already recording → just make sure the wall is back on the live transcript
    // (e.g. you distilled, then flipped RECORD to keep talking).
    if (this.armed) { this.view = "live"; this.updatedAt = Date.now(); return true; }
    const model = resolveModel();
    if (!model) {
      this.error =
        "no whisper model found (set AGENTCRAFT_WHISPER_MODEL or place ggml-large-v3-turbo.bin in ~/.cache/whisper)";
      warn("listen", this.error);
      return false;
    }
    const idx = detectMicIndex();
    const dir = mkdtempSync(join(tmpdir(), "agentcraft-listen-"));
    this.dir = dir;
    this.processed.clear();
    this.error = null;

    // mono 16 kHz WAV segments — exactly what whisper.cpp wants, smallest files.
    const args = [
      "-nostdin", "-hide_banner", "-loglevel", "error",
      "-f", "avfoundation", "-i", `:${idx}`,
      "-ac", "1", "-ar", "16000",
      "-f", "segment", "-segment_time", String(SEGMENT_SECONDS),
      "-reset_timestamps", "1",
      join(dir, "seg-%05d.wav"),
    ];
    info("listen", `arming mic (avfoundation :${idx}) → ${dir}`);
    const ff = spawn("ffmpeg", args, { stdio: ["ignore", "ignore", "pipe"] });
    this.ffmpeg = ff;
    this.armed = true;
    // Recording → show the live transcript again (off the board).
    this.view = "live";
    this.updatedAt = Date.now();

    ff.stderr?.on("data", (b: Buffer) => {
      const msg = b.toString().trim();
      if (msg) dbg("listen", `ffmpeg: ${msg}`);
      // avfoundation refuses to open the device without mic permission.
      if (/permission|Operation not permitted|Input\/output error|denied/i.test(msg)) {
        this.error =
          "mic blocked — grant Microphone permission to your terminal in System Settings › Privacy & Security › Microphone, then re-flip the lever";
        warn("listen", this.error);
      }
    });
    ff.on("exit", (code) => {
      dbg("listen", `ffmpeg exited (${code})`);
      // An early exit while still "armed" means capture failed to sustain.
      if (this.armed && code !== 0 && !this.error) {
        this.error = `capture stopped (ffmpeg exit ${code})`;
      }
    });

    this.watcher = setInterval(() => void this.drain(model, false), 1000);
    return true;
  }

  /** Stop recording. Keeps the transcript so DISTILL still works afterwards. */
  disarm(): void {
    if (!this.armed && !this.ffmpeg) return;
    this.armed = false;
    this.updatedAt = Date.now();
    if (this.watcher) { clearInterval(this.watcher); this.watcher = null; }
    const ff = this.ffmpeg;
    this.ffmpeg = null;
    if (ff) { try { ff.kill("SIGINT"); } catch { /* already gone */ } }
    const model = resolveModel();
    const dir = this.dir;
    // Final sweep: transcribe whatever completed, then clean up the temp dir.
    setTimeout(() => {
      if (model) void this.drain(model, true).finally(() => this.cleanup(dir));
      else this.cleanup(dir);
    }, 800);
    info("listen", "disarmed");
  }

  private cleanup(dir: string | null): void {
    if (dir && dir === this.dir && !this.armed) {
      try { rmSync(dir, { recursive: true, force: true }); } catch { /* ignore */ }
      this.dir = null;
    }
  }

  getState(): ListeningState {
    const tail = this.segments.slice(-WALL_TAIL).map((s) => s.text).join(" ");
    return {
      armed: this.armed,
      recording: this.armed && !!this.ffmpeg && !this.error,
      error: this.error,
      updatedAt: this.updatedAt,
      text: tail,
      view: this.view,
      distilling: this.distilling,
      board: this.boardView(),
      copiedId: this.copiedId,
      copiedAt: this.copiedAt,
    };
  }

  /** The lightweight board projection the wall renders after a distill. */
  private boardView(): BoardView | null {
    const r = this.distillResult;
    if (!r) return null;
    return {
      title: r.title,
      summary: r.summary,
      at: this.updatedAt,
      items: r.items.map((it) => ({
        id: it.id,
        title: it.title,
        category: it.category,
        priority: it.priority,
        taskCount: it.tasks.length,
        preview: it.prompt.replace(/\*\*|^#+\s*/gm, "").replace(/\s+/g, " ").trim().slice(0, 180),
      })),
    };
  }

  /** Flip the wall to the board and show a "Distilling…" state while Claude runs. */
  beginDistill(): void {
    this.distilling = true;
    this.view = "board";
    this.copiedId = null;
    this.updatedAt = Date.now();
  }

  /** Record the latest distilled plan and flip the wall to the board view. */
  setDistill(result: DistillResult): void {
    this.distillResult = result;
    this.distilling = false;
    this.view = "board"; // always show the outcome — even "nothing actionable"
    this.copiedId = null;
    this.updatedAt = Date.now();
  }

  getDistill(): DistillResult | null {
    return this.distillResult;
  }

  /** The full prompt for a board item (for click-to-copy). */
  itemPrompt(id: number): string | null {
    const it = this.distillResult?.items.find((x) => x.id === id);
    return it ? it.prompt : null;
  }

  /** Flash an item as "copied" for the wall. */
  markCopied(id: number): void {
    this.copiedId = id;
    this.copiedAt = Date.now();
    this.updatedAt = Date.now();
  }

  getFullText(): string {
    return this.segments.map((s) => s.text).join(" ").trim();
  }

  clear(): void {
    this.segments = [];
    this.updatedAt = Date.now();
  }

  /**
   * Transcribe finished segments in order. The highest-numbered WAV is the one
   * ffmpeg is still writing, so while armed we leave it; `final` (post-disarm)
   * sweeps everything. Serialised via `transcribing` so only one whisper-cli
   * runs at a time — bounds CPU and keeps the transcript in order.
   */
  private async drain(model: string, final: boolean): Promise<void> {
    if (this.transcribing || !this.dir) return;
    let files: string[];
    try {
      files = readdirSync(this.dir).filter((f) => f.endsWith(".wav")).sort();
    } catch {
      return;
    }
    const ready = final ? files : files.slice(0, -1); // drop the in-progress tail
    const pending = ready.filter((f) => !this.processed.has(f));
    if (!pending.length) return;
    this.transcribing = true;
    try {
      for (const f of pending) {
        const path = join(this.dir, f);
        this.processed.add(f);
        // Skip near-empty segments (e.g. the partial last one on disarm).
        try { if (statSync(path).size < 8000) { rmSync(path, { force: true }); continue; } } catch { continue; }
        const text = await this.transcribeFile(model, path);
        try { rmSync(path, { force: true }); } catch { /* ignore */ }
        if (text) this.append(text);
      }
    } finally {
      this.transcribing = false;
    }
  }

  private append(text: string): void {
    this.segments.push({ t: Date.now(), text });
    if (this.segments.length > MAX_SEGMENTS) this.segments.splice(0, this.segments.length - MAX_SEGMENTS);
    this.updatedAt = Date.now();
  }

  private transcribeFile(model: string, wav: string): Promise<string> {
    return new Promise((resolve) => {
      const args = ["-m", model, "-f", wav, "-nt", "-np", "-l", "en", "-t", "8"];
      const proc = spawn("whisper-cli", args, { stdio: ["ignore", "pipe", "ignore"] });
      let out = "";
      proc.stdout.on("data", (b: Buffer) => { out += b.toString(); });
      proc.on("error", (err) => { warn("listen", "whisper-cli failed", err); resolve(""); });
      proc.on("close", () => resolve(this.clean(out)));
    });
  }

  /** Keep real speech lines; drop whisper.cpp log noise and non-speech markers. */
  private clean(raw: string): string {
    const kept = raw
      .split("\n")
      .map((l) => l.trim())
      .filter((l) => l.length > 0)
      .filter((l) => !/^(ggml_|whisper_|load_backend|main:|system_info|\s*$)/i.test(l))
      .filter((l) => !NON_SPEECH.some((re) => re.test(l)));
    return kept.join(" ").replace(/\s+/g, " ").trim();
  }
}

export const listening = new ListeningSession();

// Ensure the transcripts archive dir exists for DISTILL writes.
export const TRANSCRIPTS_DIR = join(process.cwd(), "data", "transcripts");
try { mkdirSync(TRANSCRIPTS_DIR, { recursive: true }); } catch { /* ignore */ }

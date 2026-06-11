// In-memory state for native window captures.
//
// Mirrors the cinema store pattern: the runtime owns the config; the face
// polls /api/window-capture/list and manages the actual Swift binary processes.
//
// Each capture maps a cinema id to the parameters that tell the face which
// window to grab and at what quality.  The face is responsible for:
//   - spawning  WindowCapture stream …  for each entry in the list
//   - stopping it when the entry disappears
//   - bridging input events from /api/cinema/:id/input → binary stdin

export type CaptureFilter =
  | { kind: "window"; windowId: number }
  | { kind: "app";    appName:  string  }
  | { kind: "screen"; screenIndex: number };

export type CaptureEntry = {
  cinemaId:    string;
  filter:      CaptureFilter;
  fps:         number;    // 1-60
  quality:     number;    // 0.0-1.0
  startedAt:   number;
};

class WindowCaptureStore {
  private entries = new Map<string, CaptureEntry>();

  start(cinemaId: string, filter: CaptureFilter, fps = 60, quality = 0.70): CaptureEntry {
    const entry: CaptureEntry = {
      cinemaId,
      filter,
      fps:       Math.max(1, Math.min(60, fps)),
      quality:   Math.max(0.1, Math.min(1.0, quality)),
      startedAt: Date.now(),
    };
    this.entries.set(cinemaId, entry);
    return entry;
  }

  stop(cinemaId: string): boolean {
    return this.entries.delete(cinemaId);
  }

  get(cinemaId: string): CaptureEntry | null {
    return this.entries.get(cinemaId) ?? null;
  }

  list(): CaptureEntry[] {
    return Array.from(this.entries.values());
  }

  isActive(cinemaId: string): boolean {
    return this.entries.has(cinemaId);
  }
}

export const windowCaptureStore = new WindowCaptureStore();

// ── One-shot window listing ───────────────────────────────────────────────────
// Runs `WindowCapture list` synchronously and returns the parsed JSON.
// Used by the /api/window-capture/windows route so the plugin can show a
// clickable list of available app windows.

import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const __dirname = dirname(fileURLToPath(import.meta.url));
const BINARY = resolve(__dirname, "../../face/capture/WindowCapture");

export type WindowDescriptor = {
  type:       "window" | "screen";
  id:         number;
  label:      string;
  appName?:   string;
  title?:     string;
  pid?:       number;
  width:      number;
  height:     number;
  isOnScreen?: boolean;
};

export function listWindows(): WindowDescriptor[] {
  try {
    const r = spawnSync(BINARY, ["list"], { encoding: "utf8", timeout: 8000 });
    if (r.status !== 0 || !r.stdout) return [];
    const raw = JSON.parse(r.stdout) as unknown[];
    return raw as WindowDescriptor[];
  } catch {
    return [];
  }
}

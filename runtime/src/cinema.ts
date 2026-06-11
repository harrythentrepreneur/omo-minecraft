// In-memory state for in-game cinemas — giant map-wall screens that show
// arbitrary live webpages (e.g. http://localhost:3000 while vibe coding).
//
// Each cinema has:
//   - id            stable string the plugin uses to address it ("main")
//   - url           target page the capture browser should be on
//   - png/w/h/updatedAt   latest screenshot (set by face/headless-cinema POST)
//   - urlVersion    bumps on every URL change so face/ knows to navigate
//
// Wire shape mirrors omoFrame.ts deliberately — same store pattern, same
// "loss is fine, next frame is one tick away" stance. Cinemas refresh at
// ~1 Hz (map-wall network rate caps higher fidelity); STALE_MS is
// correspondingly larger so the wall doesn't blank on packet loss.

export type CinemaSnapshot = {
  id: string;
  url: string;
  urlVersion: number;
  png: Buffer | null;
  width: number;
  height: number;
  updatedAt: number;
};

// Input events flow plugin → runtime → face (CDP). Coordinates are
// normalised [0,1] over the wall so neither the plugin nor the runtime
// needs to know the capture browser's pixel viewport — the face scales
// them to its own viewport before dispatching to Chrome.
export type CinemaInputEvent =
  | { type: "click"; nx: number; ny: number; button?: "left" | "right" }
  | { type: "move"; nx: number; ny: number }
  | { type: "scroll"; nx: number; ny: number; dy: number; dx?: number }
  | { type: "text"; text: string }
  | { type: "key"; key: string };

class Cinema {
  png: Buffer | null = null;
  width = 0;
  height = 0;
  updatedAt = 0;
  url: string;
  urlVersion = 0;

  // Pending input the face hasn't drained yet, plus any long-poll waiters
  // parked on GET /input. `move` events coalesce (only the latest matters)
  // so a fast-moving in-game cursor can't flood the queue.
  input: CinemaInputEvent[] = [];
  waiters: Array<() => void> = [];

  constructor(public readonly id: string, url: string) {
    this.url = url;
  }
}

class CinemaStore {
  private cinemas = new Map<string, Cinema>();

  /** Get an existing cinema, or create one with the given default URL. */
  ensure(id: string, defaultUrl: string): Cinema {
    let c = this.cinemas.get(id);
    if (!c) {
      c = new Cinema(id, defaultUrl);
      this.cinemas.set(id, c);
    }
    return c;
  }

  get(id: string): Cinema | null {
    return this.cinemas.get(id) ?? null;
  }

  list(): CinemaSnapshot[] {
    return Array.from(this.cinemas.values()).map((c) => this.snapshot(c));
  }

  setFrame(id: string, png: Buffer, w: number, h: number): void {
    const c = this.cinemas.get(id);
    if (!c) return;
    c.png = png;
    c.width = w;
    c.height = h;
    c.updatedAt = Date.now();
  }

  /** Returns the new URL version so the caller (face) can avoid re-navigating. */
  setUrl(id: string, url: string, defaultUrl: string): number {
    const c = this.ensure(id, defaultUrl);
    if (c.url === url) return c.urlVersion;
    c.url = url;
    c.urlVersion += 1;
    // Reset the frame so the wall blanks for a beat instead of holding the
    // old page while the new one is loading — visually cleaner.
    c.png = null;
    c.width = 0;
    c.height = 0;
    c.updatedAt = 0;
    return c.urlVersion;
  }

  isStale(id: string, maxAgeMs: number): boolean {
    const c = this.cinemas.get(id);
    if (!c || c.png === null) return true;
    return Date.now() - c.updatedAt > maxAgeMs;
  }

  /** Plugin pushes a player gesture (click/scroll/type/cursor-move) here. */
  enqueueInput(id: string, ev: CinemaInputEvent): void {
    const c = this.cinemas.get(id);
    if (!c) return;
    if (ev.type === "move") {
      // Coalesce a run of cursor moves to the freshest one — the face only
      // ever needs the latest mouse position, not the trail.
      const last = c.input[c.input.length - 1];
      if (last && last.type === "move") c.input[c.input.length - 1] = ev;
      else c.input.push(ev);
    } else {
      c.input.push(ev);
    }
    // Backstop against a wedged face that never drains.
    if (c.input.length > 256) c.input.splice(0, c.input.length - 256);
    const waiters = c.waiters.splice(0);
    for (const w of waiters) w();
  }

  drainInput(id: string): CinemaInputEvent[] {
    const c = this.cinemas.get(id);
    if (!c || c.input.length === 0) return [];
    return c.input.splice(0);
  }

  /**
   * Long-poll drain: resolve immediately if events are queued, otherwise
   * park until the next enqueue or `timeoutMs` (whichever comes first).
   * Keeps face→runtime input latency at ~one network RTT without busy
   * polling.
   */
  waitForInput(id: string, timeoutMs: number): Promise<CinemaInputEvent[]> {
    const c = this.cinemas.get(id);
    if (!c) return Promise.resolve([]);
    if (c.input.length > 0) return Promise.resolve(this.drainInput(id));
    if (timeoutMs <= 0) return Promise.resolve([]);
    return new Promise((resolve) => {
      const waiter = () => {
        clearTimeout(timer);
        resolve(this.drainInput(id));
      };
      const timer = setTimeout(() => {
        const idx = c.waiters.indexOf(waiter);
        if (idx >= 0) c.waiters.splice(idx, 1);
        resolve(this.drainInput(id));
      }, timeoutMs);
      c.waiters.push(waiter);
    });
  }

  snapshot(c: Cinema): CinemaSnapshot {
    return {
      id: c.id,
      url: c.url,
      urlVersion: c.urlVersion,
      png: c.png,
      width: c.width,
      height: c.height,
      updatedAt: c.updatedAt,
    };
  }
}

export const cinemaStore = new CinemaStore();

/** The default URL a freshly-created cinema points at until the user swaps it.
 *  localhost:3000 is the vibe-coder dev-server convention — the cinema shows
 *  the player's app immediately, even before /omo cinema <url> is run. */
export const DEFAULT_CINEMA_URL = "http://localhost:3000";

/**
 * Default URL for a cinema id. Channels whose page is served BY the runtime
 * itself (the Listening Room transcript, the classroom whiteboard) default to
 * that internal page — so they survive a runtime restart, which wipes the
 * in-memory store and would otherwise leave them on the generic localhost:3000
 * (→ "localhost refused to connect" on the wall) until the next /omo build
 * re-pushes the URL. Everything else keeps the dev-server default.
 */
export function defaultCinemaUrl(id: string): string {
  const port = process.env.AGENTCRAFT_HTTP_PORT ?? "8766";
  const base = `http://127.0.0.1:${port}`;
  switch (id) {
    case "listening":
      return `${base}/listening`;
    case "whiteboard":
      return `${base}/whiteboard`;
    default:
      return DEFAULT_CINEMA_URL;
  }
}

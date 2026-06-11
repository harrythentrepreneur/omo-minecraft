// In-memory holder for the latest live Omo face frame.
//
// The browser overlay page (face/public/overlay.html) renders the real
// Three.js Omo head at ~12 FPS into a 256×256 transparent canvas and POSTs
// each frame here as raw PNG bytes. The Fabric client-mod's HUD layer polls
// the GET side at the same cadence and blits the frame in the top-left of
// the Minecraft window — that's how the holographic Omo actually shows up
// in-game.
//
// State is intentionally trivial:
//   - one Buffer holding the latest PNG
//   - width / height (caller-supplied via query string, cheaper than
//     parsing the PNG)
//   - `updatedAt` so the HTTP layer can answer 204 when the browser tab
//     has stopped pushing (overlay tab closed / minimised).
//
// No fan-out, no diffing, no history. Loss is fine — the next frame is
// 80ms away.

export type OmoFrameSnapshot = {
  png: Buffer | null;
  width: number;
  height: number;
  updatedAt: number;
};

class OmoFrameStore {
  private png: Buffer | null = null;
  private width = 0;
  private height = 0;
  private updatedAt = 0;

  set(buf: Buffer, width: number, height: number): void {
    this.png = buf;
    this.width = width;
    this.height = height;
    this.updatedAt = Date.now();
  }

  get(): OmoFrameSnapshot {
    return {
      png: this.png,
      width: this.width,
      height: this.height,
      updatedAt: this.updatedAt,
    };
  }

  /**
   * True when no frame has arrived in the last `maxAgeMs` ms. The HTTP GET
   * uses this to answer 204 so the in-game HUD knows to fall back to its
   * bundled sprite frames.
   */
  isStale(maxAgeMs: number): boolean {
    if (this.png === null) return true;
    return Date.now() - this.updatedAt > maxAgeMs;
  }
}

export const omoFrame = new OmoFrameStore();

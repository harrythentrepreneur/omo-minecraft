// In-memory state of the on-screen Omo face avatar. Drives the Fabric
// client-mod HUD overlay (top-left of the Minecraft window) and is updated
// by the face/ voice surface and by /api/teleport.
//
// The whole thing is intentionally small: one mode enum, a few labels,
// and a monotonic `updatedAt`. No persistence — restart resets to idle.

export type FaceMode =
  | "idle"
  | "listening"
  | "thinking"
  | "speaking"
  | "celebrating";

export type FaceStateSnapshot = {
  mode: FaceMode;
  room?: string;
  transcript?: string;
  updatedAt: number;
};

const VALID_MODES: ReadonlySet<FaceMode> = new Set([
  "idle",
  "listening",
  "thinking",
  "speaking",
  "celebrating",
]);

class FaceStateStore {
  private state: FaceStateSnapshot = {
    mode: "idle",
    updatedAt: Date.now(),
  };

  // When we push a temporary mode (e.g. "celebrating") we remember the
  // timer so a later push can cancel it. Otherwise back-to-back teleports
  // could leave the face stuck.
  private revertTimer: NodeJS.Timeout | null = null;

  get(): FaceStateSnapshot {
    return { ...this.state };
  }

  set(patch: Partial<FaceStateSnapshot>): FaceStateSnapshot {
    // Any explicit set cancels a pending auto-revert. The new caller now
    // owns the state.
    this.clearRevert();
    if (patch.mode && !VALID_MODES.has(patch.mode)) {
      throw new Error(`invalid face mode: ${patch.mode}`);
    }
    this.state = {
      ...this.state,
      ...patch,
      updatedAt: Date.now(),
    };
    return this.get();
  }

  /**
   * Push a mode that auto-reverts to `idle` after `ms` milliseconds.
   * Used by the teleport hook to fire a celebration burst.
   */
  pulse(mode: FaceMode, ms: number): void {
    this.set({ mode });
    this.revertTimer = setTimeout(() => {
      this.revertTimer = null;
      // Only revert if no one has since changed the mode.
      if (this.state.mode === mode) {
        this.state = { ...this.state, mode: "idle", updatedAt: Date.now() };
      }
    }, ms);
  }

  private clearRevert(): void {
    if (this.revertTimer) {
      clearTimeout(this.revertTimer);
      this.revertTimer = null;
    }
  }
}

export const faceState = new FaceStateStore();

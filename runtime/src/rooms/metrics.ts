// Rolling 24-hour activity buckets for one room. Newest hour at the END of the array
// (index 23 = current hour). Rolls over lazily on read/write to avoid timer drift.

const HOUR_MS = 60 * 60 * 1000;
const BUCKET_COUNT = 24;

export class RoomMetrics {
  private buckets: number[] = new Array(BUCKET_COUNT).fill(0);
  private currentHourStart: number;
  private lastActivity = 0;
  private toolFrequency = new Map<string, number>();

  constructor() {
    this.currentHourStart = hourStart(Date.now());
  }

  recordToolCall(toolName: string): void {
    this.rollover();
    this.buckets[BUCKET_COUNT - 1]! += 1;
    this.lastActivity = Date.now();
    this.toolFrequency.set(toolName, (this.toolFrequency.get(toolName) ?? 0) + 1);
  }

  snapshot(): {
    totalLast24h: number;
    lastActivity: number;
    sparkline: number[];
    topTool: { name: string; count: number } | null;
  } {
    this.rollover();
    let top: { name: string; count: number } | null = null;
    for (const [name, count] of this.toolFrequency) {
      if (!top || count > top.count) top = { name, count };
    }
    return {
      totalLast24h: this.buckets.reduce((a, b) => a + b, 0),
      lastActivity: this.lastActivity,
      sparkline: [...this.buckets],
      topTool: top,
    };
  }

  private rollover(): void {
    const now = Date.now();
    const currentHour = hourStart(now);
    const hoursPassed = Math.floor((currentHour - this.currentHourStart) / HOUR_MS);
    if (hoursPassed <= 0) return;
    const shifts = Math.min(hoursPassed, BUCKET_COUNT);
    for (let i = 0; i < shifts; i++) {
      this.buckets.shift();
      this.buckets.push(0);
    }
    this.currentHourStart = currentHour;
  }
}

function hourStart(t: number): number {
  return Math.floor(t / HOUR_MS) * HOUR_MS;
}

export function humanRelative(ms: number): string {
  if (ms < 0) return "now";
  const s = Math.round(ms / 1000);
  if (s < 60) return `${s}s ago`;
  const m = Math.round(s / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.round(m / 60);
  if (h < 24) return `${h}h ago`;
  return `${Math.round(h / 24)}d ago`;
}

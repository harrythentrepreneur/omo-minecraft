// Centralised debug logging.
//
// Turn on with AGENTCRAFT_DEBUG=true in runtime/.env (or 1/yes).
// Components log via `dbg("scope", ...)`; output looks like:
//   2026-05-26T03:45:21Z [bridge] in spawn_agent {"agentId":"li","room":"workshop-li"}

const enabled = (() => {
  const raw = (process.env.AGENTCRAFT_DEBUG ?? "").toLowerCase().trim();
  return raw === "1" || raw === "true" || raw === "yes" || raw === "on";
})();

const c = {
  dim: "\x1b[2m",
  cyan: "\x1b[36m",
  magenta: "\x1b[35m",
  yellow: "\x1b[33m",
  red: "\x1b[31m",
  green: "\x1b[32m",
  rst: "\x1b[0m",
};

const scopeColor: Record<string, string> = {
  bridge: c.cyan,
  hermes: c.magenta,
  agent: c.yellow,
  tool: c.green,
};

export const debugEnabled = enabled;

export function dbg(scope: string, msg: string, data?: unknown): void {
  if (!enabled) return;
  const ts = new Date().toISOString().replace("T", " ").slice(0, 19);
  const colour = scopeColor[scope] ?? c.dim;
  const head = `${c.dim}${ts}${c.rst} ${colour}[${scope}]${c.rst} ${msg}`;
  if (data === undefined) {
    console.log(head);
  } else {
    console.log(head, truncate(safeStringify(data), 400));
  }
}

export function info(scope: string, msg: string): void {
  const colour = scopeColor[scope] ?? c.dim;
  console.log(`${colour}[${scope}]${c.rst} ${msg}`);
}

export function warn(scope: string, msg: string, err?: unknown): void {
  console.warn(`${c.yellow}[${scope}]${c.rst} ${msg}`, err ?? "");
}

export function error(scope: string, msg: string, err?: unknown): void {
  console.error(`${c.red}[${scope}]${c.rst} ${msg}`, err ?? "");
}

function safeStringify(value: unknown): string {
  try {
    return typeof value === "string" ? value : JSON.stringify(value);
  } catch {
    return String(value);
  }
}

function truncate(s: string, n: number): string {
  return s.length > n ? `${s.slice(0, n)}…(+${s.length - n})` : s;
}

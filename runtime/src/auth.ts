import { timingSafeEqual } from "node:crypto";

/**
 * Backend access control for the two player-facing runtime ports
 * (:8766 {@link ./http.ts} and :8767 {@link ./terminalServer.ts}).
 *
 * Model: callers ON the runtime box (the face server, the Paper plugin, the
 * headless Chrome — all loopback) are trusted and never need a token. Anything
 * arriving over the network must present OMO_CLIENT_TOKEN. With no token
 * configured, remote callers are rejected outright (fail-closed) so an
 * unconfigured public deploy is locked down rather than wide open — while a
 * local-only dev box keeps working with zero config.
 *
 * This is what stops an anonymous internet client from driving the agent PTYs
 * on :8767 (a remote-code-execution path) or freeloading on the spawn-code /
 * Gemini-mint endpoints on :8766. The in-game client mod presents the token via
 * a WS `hello` frame (terminal) or an `Authorization: Bearer` header (http);
 * see client-mod RuntimeHost.token().
 */
const TOKEN = (process.env.OMO_CLIENT_TOKEN ?? "").trim();

export function authConfigured(): boolean {
  return TOKEN.length > 0;
}

/** True for connections originating on this machine (face / plugin / Chrome). */
export function isLoopback(addr: string | undefined | null): boolean {
  if (!addr) return false;
  return (
    addr === "127.0.0.1" ||
    addr === "::1" ||
    addr === "::ffff:127.0.0.1" ||
    addr.startsWith("127.")
  );
}

function tokenMatches(presented: string): boolean {
  if (TOKEN.length === 0) return false;
  const a = Buffer.from(presented);
  const b = Buffer.from(TOKEN);
  if (a.length !== b.length) return false;
  try {
    return timingSafeEqual(a, b);
  } catch {
    return false;
  }
}

/**
 * Terminal WS check: a remote client's first frame must be
 * `{ type: "hello", token }` whose token matches. Returns true if allowed.
 */
export function checkClientToken(presented: unknown): boolean {
  return typeof presented === "string" && tokenMatches(presented);
}

/**
 * HTTP gate. Loopback is always allowed; remote callers must carry
 * `Authorization: Bearer <OMO_CLIENT_TOKEN>`.
 */
export function httpAuthorized(
  remoteAddr: string | undefined | null,
  authHeader: string | string[] | undefined,
): boolean {
  if (isLoopback(remoteAddr)) return true;
  if (TOKEN.length === 0) return false; // remote + unconfigured → fail closed
  const hdr = Array.isArray(authHeader) ? authHeader[0] : authHeader;
  if (!hdr) return false;
  const m = /^Bearer\s+(.+)$/i.exec(hdr.trim());
  const presented = m?.[1];
  return presented ? tokenMatches(presented.trim()) : false;
}

import { spawnSync } from "node:child_process";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { existsSync, statSync, unlinkSync, writeFileSync } from "node:fs";

/**
 * Read an image off the OS clipboard and stash it in a temp PNG, returning the
 * path (or null if the clipboard holds no image).
 *
 * This is how the in-game "paste image" gesture works: the Minecraft (GLFW)
 * clipboard API is text-only, so the game client can't see a copied image. The
 * runtime — a normal, un-sandboxed process on the host that owns the clipboard
 * — reads it here and hands claude the file path. Dropping a path into the
 * Claude Code prompt is exactly what dragging an image into the real terminal
 * does: claude auto-detects image paths and attaches them on submit. Reading
 * the clipboard from the runtime (rather than letting claude's own Ctrl+V do
 * it) also sidesteps claude's sandbox-exec clipboard bug on macOS.
 *
 * Local-machine assumption: the host running the runtime is the host whose
 * clipboard we read. That's always true for the single-machine MVP. For the
 * remote-friends deploy it would read the runtime host's clipboard, not the
 * friend's — shipping the bytes from the client would be the fix there.
 */
let seq = 0;

export function clipboardImageToFile(): string | null {
  const path = join(tmpdir(), `agentcraft-clip-${Date.now()}-${seq++}.png`);
  switch (process.platform) {
    case "darwin":
      return macClipboardImage(path);
    case "linux":
      return linuxClipboardImage(path);
    default:
      return null;
  }
}

function macClipboardImage(path: string): string | null {
  // Coerce the clipboard to PNG and write it to `path`. The coercion runs
  // before `open for access`, so when the clipboard holds no image it throws
  // and osascript exits non-zero without ever creating the file.
  const script = [
    `set theData to (the clipboard as «class PNGf»)`,
    `set theFile to open for access POSIX file ${appleScriptString(path)} with write permission`,
    `set eof theFile to 0`,
    `write theData to theFile`,
    `close access theFile`,
  ];
  const args = script.flatMap((line) => ["-e", line]);
  spawnSync("osascript", args, { timeout: 5000 });
  return fileIfNonEmpty(path);
}

function linuxClipboardImage(path: string): string | null {
  // Wayland (wl-paste) first, then X11 (xclip). Both dump PNG bytes to stdout.
  const opts = { timeout: 5000, maxBuffer: 64 * 1024 * 1024 } as const;
  for (const [cmd, cmdArgs] of [
    ["wl-paste", ["--type", "image/png"]],
    ["xclip", ["-selection", "clipboard", "-t", "image/png", "-o"]],
  ] as const) {
    const res = spawnSync(cmd, cmdArgs, opts);
    if (res.status === 0 && res.stdout && res.stdout.length > 0) {
      try {
        writeFileSync(path, res.stdout);
      } catch {
        continue;
      }
      const ok = fileIfNonEmpty(path);
      if (ok) return ok;
    }
  }
  return null;
}

/** Quote a path as an AppleScript string literal. */
function appleScriptString(path: string): string {
  return `"${path.replace(/\\/g, "\\\\").replace(/"/g, '\\"')}"`;
}

/**
 * Write text TO the OS clipboard. This is the Listening Room's "copy and paste"
 * payload: DISTILL puts the generated prompt here so the player can ⌘V it
 * straight into a code-N terminal (Minecraft books can't reach the OS clipboard,
 * but the runtime — owner of the host clipboard — can). Same local-machine
 * assumption as the image reader above. Returns true on success.
 */
export function copyTextToClipboard(text: string): boolean {
  try {
    switch (process.platform) {
      case "darwin":
        return spawnSync("pbcopy", [], { input: text, timeout: 5000 }).status === 0;
      case "linux": {
        for (const [cmd, args] of [
          ["wl-copy", []],
          ["xclip", ["-selection", "clipboard"]],
        ] as const) {
          const res = spawnSync(cmd, args, { input: text, timeout: 5000 });
          if (res.status === 0) return true;
        }
        return false;
      }
      default:
        return false;
    }
  } catch {
    return false;
  }
}

function fileIfNonEmpty(path: string): string | null {
  try {
    if (existsSync(path) && statSync(path).size > 0) return path;
  } catch {
    /* fall through to cleanup */
  }
  try {
    if (existsSync(path)) unlinkSync(path);
  } catch {
    /* ignore */
  }
  return null;
}

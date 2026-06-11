// One-shot whisper.cpp transcription for a single WAV clip.
//
// Two callers want the *same* local-whisper behaviour:
//   • the Listening Room (./listening/session.ts) streams 6s segments for a
//     rolling transcript;
//   • push-to-talk (the in-game "V" key) records one clip on the client and
//     POSTs it to /api/voice-transcribe, which transcribes it here and hands
//     the text straight back so the client can inject it as a normal chat line.
//
// Both want identical model resolution + the same "strip whisper.cpp log noise
// and non-speech markers" cleanup, so it lives once, here. Fully local: no API
// key, no network. whisper-cli requires a 16 kHz mono WAV — that's exactly what
// the client records and what ffmpeg produces in the Listening Room.

import { spawn } from "node:child_process";
import { homedir, tmpdir } from "node:os";
import { join } from "node:path";
import { existsSync } from "node:fs";
import { unlink } from "node:fs/promises";
import { warn } from "../debug.js";

let normSeq = 0;

/**
 * Resolve a CLI binary by absolute path. The runtime is spawned detached by
 * `./agentcraft`, so we can't assume Homebrew is on the inherited PATH — prefer
 * known install locations, fall back to the bare name (PATH) as a last resort.
 */
function resolveBin(envVar: string, absoluteCandidates: string[], bareName: string): string {
  const env = process.env[envVar];
  if (env && existsSync(env)) return env;
  for (const c of absoluteCandidates) if (existsSync(c)) return c;
  return bareName; // last resort: rely on PATH
}

const whisperBin = () =>
  resolveBin("AGENTCRAFT_WHISPER_BIN", ["/opt/homebrew/bin/whisper-cli", "/usr/local/bin/whisper-cli"], "whisper-cli");

const ffmpegBin = () =>
  resolveBin("AGENTCRAFT_FFMPEG_BIN", ["/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg"], "ffmpeg");

/**
 * Resample/normalize ANY PCM WAV the client captured (44.1/48 kHz, mono or
 * stereo) down to the 16 kHz mono signed-16-LE whisper.cpp requires. This is
 * what makes voice robust to the player's actual microphone instead of forcing
 * the client to open an unsupported 16 kHz line. Resolves to the normalized
 * path, or null if ffmpeg is missing/fails (caller then tries the raw file).
 */
function normalizeTo16kMono(inputWav: string): Promise<string | null> {
  const out = join(tmpdir(), `omo-ptt-norm-${Date.now()}-${normSeq++}.wav`);
  return new Promise((resolve) => {
    const proc = spawn(
      ffmpegBin(),
      ["-hide_banner", "-loglevel", "error", "-y", "-i", inputWav, "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", out],
      { stdio: ["ignore", "ignore", "ignore"] },
    );
    proc.on("error", (e) => { warn("voice", "ffmpeg normalize failed", e); resolve(null); });
    proc.on("close", (code) => resolve(code === 0 && existsSync(out) ? out : null));
  });
}

// whisper.cpp prints these placeholders for non-speech; never surface them.
const NON_SPEECH = [
  /^\[?\s*blank[_ ]?audio\s*\]?$/i,
  /^\[?\s*silence\s*\]?$/i,
  /^\[?\s*music\s*\]?$/i,
  /^\[?\s*inaudible\s*\]?$/i,
  /^\(.*\)$/, // (typing), (no audio) etc.
];

// On silence/noise, large-v3-turbo doesn't emit [BLANK_AUDIO] — it hallucinates
// a few well-known YouTube-caption artifacts ("you", "Thank you.", "Bye."). A
// quiet or accidental V-press would otherwise fire one of these at the agent.
// We drop them ONLY when one is the ENTIRE transcript, so a real multi-word
// command that merely contains "thank you" is never affected.
const SILENCE_HALLUCINATIONS = new Set([
  "you",
  "thank you",
  "thank you very much",
  "thanks",
  "thanks for watching",
  "thank you for watching",
  "please subscribe",
  "bye",
  "bye bye",
]);

/** True when the whole transcript is a known whisper silence artifact. */
function isSilenceHallucination(text: string): boolean {
  const norm = text.toLowerCase().replace(/[\s.,!?]+$/, "").trim();
  return SILENCE_HALLUCINATIONS.has(norm);
}

/**
 * First existing ggml model, in preference order. Override with
 * AGENTCRAFT_WHISPER_MODEL. Returns null when none is installed so the caller
 * can return an empty transcript instead of spawning whisper-cli into an error.
 */
export function resolveWhisperModel(): string | null {
  const env = process.env.AGENTCRAFT_WHISPER_MODEL;
  if (env && existsSync(env)) return env;
  const candidates = [
    join(homedir(), ".cache/whisper/ggml-large-v3-turbo.bin"),
    join(homedir(), ".cache/whisper/ggml-medium.bin"),
    join(homedir(), ".cache/whisper/ggml-base.en.bin"),
  ];
  for (const c of candidates) if (existsSync(c)) return c;
  return null;
}

/** Drop whisper.cpp log lines + non-speech markers, collapse to one line. */
export function cleanTranscript(raw: string): string {
  return raw
    .split("\n")
    .map((l) => l.trim())
    .filter((l) => l.length > 0)
    .filter((l) => !/^(ggml_|whisper_|load_backend|main:|system_info|\s*$)/i.test(l))
    .filter((l) => !NON_SPEECH.some((re) => re.test(l)))
    .join(" ")
    .replace(/\s+/g, " ")
    .trim();
}

/**
 * Transcribe a single 16 kHz mono WAV file. Resolves to "" on any failure
 * (no model, whisper-cli missing, pure silence) — callers treat empty as
 * "didn't catch anything" and send nothing.
 */
export async function transcribeWav(wavPath: string): Promise<string> {
  const model = resolveWhisperModel();
  if (!model) {
    warn("voice", "no whisper model found (set AGENTCRAFT_WHISPER_MODEL)");
    return "";
  }
  // Normalize to 16 kHz mono first; fall back to the raw file if ffmpeg is
  // unavailable (works only if the client happened to capture 16 kHz already).
  const normalized = await normalizeTo16kMono(wavPath);
  const target = normalized ?? wavPath;
  try {
    const text = await runWhisper(model, target);
    if (isSilenceHallucination(text)) {
      warn("voice", `dropped silence hallucination: ${JSON.stringify(text)}`);
      return "";
    }
    return text;
  } finally {
    if (normalized) unlink(normalized).catch(() => {});
  }
}

function runWhisper(model: string, wavPath: string): Promise<string> {
  return new Promise((resolve) => {
    const args = ["-m", model, "-f", wavPath, "-nt", "-np", "-l", "en", "-t", "8"];
    const proc = spawn(whisperBin(), args, { stdio: ["ignore", "pipe", "ignore"] });
    let out = "";
    proc.stdout.on("data", (b: Buffer) => { out += b.toString(); });
    proc.on("error", (err) => { warn("voice", "whisper-cli failed", err); resolve(""); });
    proc.on("close", () => resolve(cleanTranscript(out)));
  });
}

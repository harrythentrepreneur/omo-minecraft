import { readFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import path from "node:path";
import type { ToolImpl } from "./registry.js";

// Spotify Web API tools using a refresh-token OAuth flow.
// Playback control requires Spotify Premium and these scopes on the refresh token:
//   user-read-playback-state, user-modify-playback-state, user-read-currently-playing
// Secrets file (runtime/secrets/spotify.json) expected shape:
// { "client_id": "...", "client_secret": "...", "refresh_token": "..." }

const SECRETS_PATH = path.resolve(process.cwd(), "secrets", "spotify.json");

type SpotifyCreds = {
  client_id: string;
  client_secret: string;
  refresh_token: string;
};

let cachedAccessToken: { token: string; expiresAt: number } | null = null;

async function loadCreds(): Promise<SpotifyCreds | null> {
  if (!existsSync(SECRETS_PATH)) return null;
  return JSON.parse(await readFile(SECRETS_PATH, "utf8")) as SpotifyCreds;
}

async function getAccessToken(): Promise<string> {
  if (cachedAccessToken && cachedAccessToken.expiresAt > Date.now() + 30_000) {
    return cachedAccessToken.token;
  }
  const creds = await loadCreds();
  if (!creds) throw new Error("Spotify not configured: missing runtime/secrets/spotify.json");

  const body = new URLSearchParams({
    grant_type: "refresh_token",
    refresh_token: creds.refresh_token,
  });
  const basic = Buffer.from(`${creds.client_id}:${creds.client_secret}`).toString("base64");
  const res = await fetch("https://accounts.spotify.com/api/token", {
    method: "POST",
    headers: {
      "content-type": "application/x-www-form-urlencoded",
      authorization: `Basic ${basic}`,
    },
    body,
  });
  if (!res.ok) throw new Error(`spotify token refresh failed: ${res.status} ${await res.text()}`);
  const j = (await res.json()) as { access_token: string; expires_in: number };
  cachedAccessToken = {
    token: j.access_token,
    expiresAt: Date.now() + j.expires_in * 1000,
  };
  return j.access_token;
}

async function spotifyFetch(pathStr: string, init?: RequestInit): Promise<unknown> {
  const token = await getAccessToken();
  const res = await fetch(`https://api.spotify.com/v1${pathStr}`, {
    ...init,
    headers: {
      authorization: `Bearer ${token}`,
      "content-type": "application/json",
      ...(init?.headers ?? {}),
    },
  });
  if (res.status === 204) return {};
  if (!res.ok) throw new Error(`spotify ${pathStr} -> ${res.status}: ${await res.text()}`);
  const text = await res.text();
  return text ? JSON.parse(text) : {};
}

type SearchTrack = {
  uri: string;
  name: string;
  artists: { name: string }[];
  album: { name: string };
};
type SearchResp = {
  tracks?: { items: SearchTrack[] };
  playlists?: { items: { uri: string; name: string; owner: { display_name: string } }[] };
};

export const spotifySearchTool: ToolImpl = {
  def: {
    type: "function",
    function: {
      name: "spotify_search",
      description: "Search Spotify for tracks or playlists. Returns URIs you can pass to spotify_play.",
      parameters: {
        type: "object",
        properties: {
          query: { type: "string", description: "Search query, e.g. 'Jay Chou 七里香' or 'Mandopop hits'." },
          type: { type: "string", enum: ["track", "playlist"], description: "What to search for. Default 'track'." },
          limit: { type: "number", description: "Max results (default 5, max 20)." },
        },
        required: ["query"],
      },
    },
  },
  async run(args) {
    const q = encodeURIComponent(String(args.query));
    const type = String(args.type ?? "track");
    const limit = Math.min(20, Number(args.limit ?? 5));
    const data = (await spotifyFetch(`/search?q=${q}&type=${type}&limit=${limit}`)) as SearchResp;
    if (type === "track") {
      const items = (data.tracks?.items ?? []).map((t) => ({
        uri: t.uri,
        name: t.name,
        artists: t.artists.map((a) => a.name).join(", "),
        album: t.album.name,
      }));
      return { tracks: items };
    }
    const items = (data.playlists?.items ?? []).map((p) => ({
      uri: p.uri,
      name: p.name,
      owner: p.owner.display_name,
    }));
    return { playlists: items };
  },
};

type Device = { id: string; name: string; type: string; is_active: boolean };

export const spotifyDevicesTool: ToolImpl = {
  def: {
    type: "function",
    function: {
      name: "spotify_devices",
      description: "List Spotify devices the owner is signed in on. Use a device id with spotify_play if no active device.",
      parameters: { type: "object", properties: {} },
    },
  },
  async run() {
    const data = (await spotifyFetch(`/me/player/devices`)) as { devices: Device[] };
    return { devices: data.devices };
  },
};

export const spotifyPlayTool: ToolImpl = {
  def: {
    type: "function",
    function: {
      name: "spotify_play",
      description:
        "Start or resume playback. Pass 'uri' (track/playlist/album URI from spotify_search) to play something specific, or no args to resume. Optionally 'device_id'. Requires Premium.",
      parameters: {
        type: "object",
        properties: {
          uri: { type: "string", description: "Spotify URI to play, e.g. 'spotify:track:...' or 'spotify:playlist:...'." },
          device_id: { type: "string", description: "Optional device id from spotify_devices." },
        },
      },
    },
  },
  async run(args) {
    const uri = args.uri ? String(args.uri) : undefined;
    const deviceQuery = args.device_id ? `?device_id=${encodeURIComponent(String(args.device_id))}` : "";
    const body: Record<string, unknown> = {};
    if (uri) {
      if (uri.includes(":track:")) body.uris = [uri];
      else body.context_uri = uri;
    }
    await spotifyFetch(`/me/player/play${deviceQuery}`, {
      method: "PUT",
      body: JSON.stringify(body),
    });
    return { ok: true, playing: uri ?? "(resumed)" };
  },
};

export const spotifyPauseTool: ToolImpl = {
  def: {
    type: "function",
    function: {
      name: "spotify_pause",
      description: "Pause Spotify playback on the active device.",
      parameters: { type: "object", properties: {} },
    },
  },
  async run() {
    await spotifyFetch(`/me/player/pause`, { method: "PUT" });
    return { ok: true };
  },
};

export const spotifyNextTool: ToolImpl = {
  def: {
    type: "function",
    function: {
      name: "spotify_next",
      description: "Skip to the next track on Spotify.",
      parameters: { type: "object", properties: {} },
    },
  },
  async run() {
    await spotifyFetch(`/me/player/next`, { method: "POST" });
    return { ok: true };
  },
};

export const spotifyPreviousTool: ToolImpl = {
  def: {
    type: "function",
    function: {
      name: "spotify_previous",
      description: "Go back to the previous track on Spotify.",
      parameters: { type: "object", properties: {} },
    },
  },
  async run() {
    await spotifyFetch(`/me/player/previous`, { method: "POST" });
    return { ok: true };
  },
};

type NowPlaying = {
  is_playing: boolean;
  item?: { name: string; uri: string; artists: { name: string }[]; album: { name: string } } | null;
  device?: { name: string; type: string } | null;
};

export const spotifyNowPlayingTool: ToolImpl = {
  def: {
    type: "function",
    function: {
      name: "spotify_now_playing",
      description: "Get the currently playing track (or null if nothing is playing).",
      parameters: { type: "object", properties: {} },
    },
  },
  async run() {
    const data = (await spotifyFetch(`/me/player`)) as NowPlaying | Record<string, never>;
    if (!data || !("item" in data) || !data.item) return { playing: null };
    return {
      playing: data.is_playing,
      track: data.item.name,
      artists: data.item.artists.map((a) => a.name).join(", "),
      album: data.item.album.name,
      uri: data.item.uri,
      device: data.device ? `${data.device.name} (${data.device.type})` : null,
    };
  },
};

export const spotifyTools = [
  spotifySearchTool,
  spotifyDevicesTool,
  spotifyPlayTool,
  spotifyPauseTool,
  spotifyNextTool,
  spotifyPreviousTool,
  spotifyNowPlayingTool,
];

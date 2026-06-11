import { readFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import path from "node:path";
import type { ToolImpl } from "./registry.js";

// Gmail tools using a refresh-token OAuth flow.
// Secrets file (runtime/secrets/gmail.json) expected shape:
// { "client_id": "...", "client_secret": "...", "refresh_token": "..." }

const SECRETS_PATH = path.resolve(process.cwd(), "secrets", "gmail.json");

type GmailCreds = {
  client_id: string;
  client_secret: string;
  refresh_token: string;
};

let cachedAccessToken: { token: string; expiresAt: number } | null = null;

async function loadCreds(): Promise<GmailCreds | null> {
  if (!existsSync(SECRETS_PATH)) return null;
  return JSON.parse(await readFile(SECRETS_PATH, "utf8")) as GmailCreds;
}

async function getAccessToken(): Promise<string> {
  if (cachedAccessToken && cachedAccessToken.expiresAt > Date.now() + 30_000) {
    return cachedAccessToken.token;
  }
  const creds = await loadCreds();
  if (!creds) throw new Error("Gmail not configured: missing runtime/secrets/gmail.json");

  const body = new URLSearchParams({
    client_id: creds.client_id,
    client_secret: creds.client_secret,
    refresh_token: creds.refresh_token,
    grant_type: "refresh_token",
  });
  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body,
  });
  if (!res.ok) throw new Error(`gmail token refresh failed: ${res.status} ${await res.text()}`);
  const j = (await res.json()) as { access_token: string; expires_in: number };
  cachedAccessToken = {
    token: j.access_token,
    expiresAt: Date.now() + j.expires_in * 1000,
  };
  return j.access_token;
}

async function gmailFetch(pathStr: string, init?: RequestInit): Promise<unknown> {
  const token = await getAccessToken();
  const res = await fetch(`https://gmail.googleapis.com/gmail/v1/users/me${pathStr}`, {
    ...init,
    headers: {
      authorization: `Bearer ${token}`,
      "content-type": "application/json",
      ...(init?.headers ?? {}),
    },
  });
  if (!res.ok) throw new Error(`gmail ${pathStr} -> ${res.status}: ${await res.text()}`);
  return res.json();
}

type GmailListResp = { messages?: { id: string; threadId: string }[] };
type GmailMsg = {
  id: string;
  snippet?: string;
  payload?: { headers?: { name: string; value: string }[]; body?: { data?: string }; parts?: GmailMsg["payload"][] };
};

export const gmailListTool: ToolImpl = {
  def: {
    type: "function",
    function: {
      name: "gmail_list",
      description: "List the most recent messages in the user's Gmail inbox. Returns id, from, subject, snippet.",
      parameters: {
        type: "object",
        properties: {
          query: { type: "string", description: "Optional Gmail search query, e.g. 'is:unread newer_than:1d'" },
          max: { type: "number", description: "Max number of messages to return (default 10).", default: 10 },
        },
      },
    },
  },
  async run(args) {
    const q = args.query ? `&q=${encodeURIComponent(String(args.query))}` : "";
    const max = Math.min(50, Number(args.max ?? 10));
    const list = (await gmailFetch(`/messages?maxResults=${max}${q}`)) as GmailListResp;
    if (!list.messages?.length) return { messages: [] };
    const out: { id: string; from: string; subject: string; snippet: string }[] = [];
    for (const m of list.messages.slice(0, max)) {
      const full = (await gmailFetch(`/messages/${m.id}?format=metadata&metadataHeaders=From&metadataHeaders=Subject`)) as GmailMsg;
      const headers = full.payload?.headers ?? [];
      const from = headers.find((h) => h.name === "From")?.value ?? "";
      const subject = headers.find((h) => h.name === "Subject")?.value ?? "";
      out.push({ id: full.id, from, subject, snippet: full.snippet ?? "" });
    }
    return { messages: out };
  },
};

export const gmailReadTool: ToolImpl = {
  def: {
    type: "function",
    function: {
      name: "gmail_read",
      description: "Read the full text body of a single Gmail message by id.",
      parameters: {
        type: "object",
        properties: { id: { type: "string", description: "Message id from gmail_list." } },
        required: ["id"],
      },
    },
  },
  async run(args) {
    const id = String(args.id);
    const full = (await gmailFetch(`/messages/${id}?format=full`)) as GmailMsg;
    const text = extractText(full.payload);
    const headers = full.payload?.headers ?? [];
    return {
      id,
      from: headers.find((h) => h.name === "From")?.value ?? "",
      to: headers.find((h) => h.name === "To")?.value ?? "",
      subject: headers.find((h) => h.name === "Subject")?.value ?? "",
      body: text.slice(0, 8000),
    };
  },
};

export const gmailDraftTool: ToolImpl = {
  def: {
    type: "function",
    function: {
      name: "gmail_draft",
      description: "Create a draft reply or new message. Does NOT send. Returns the draft id.",
      parameters: {
        type: "object",
        properties: {
          to: { type: "string" },
          subject: { type: "string" },
          body: { type: "string" },
          in_reply_to_id: { type: "string", description: "Optional message id to reply to (threads it)." },
        },
        required: ["to", "subject", "body"],
      },
    },
  },
  async run(args) {
    const raw = buildMimeMessage(String(args.to), String(args.subject), String(args.body));
    const body: Record<string, unknown> = { message: { raw } };
    if (args.in_reply_to_id) {
      const orig = (await gmailFetch(`/messages/${args.in_reply_to_id}?format=metadata`)) as { threadId: string };
      (body.message as Record<string, unknown>).threadId = orig.threadId;
    }
    const draft = (await gmailFetch(`/drafts`, { method: "POST", body: JSON.stringify(body) })) as { id: string };
    return { draftId: draft.id };
  },
};

export const gmailSendTool: ToolImpl = {
  def: {
    type: "function",
    function: {
      name: "gmail_send",
      description: "Send an existing draft by id. Requires owner approval.",
      parameters: {
        type: "object",
        properties: { draft_id: { type: "string" } },
        required: ["draft_id"],
      },
    },
  },
  needsApproval: () => true,
  async run(args) {
    const sent = await gmailFetch(`/drafts/send`, {
      method: "POST",
      body: JSON.stringify({ id: String(args.draft_id) }),
    });
    return { sent };
  },
};

function buildMimeMessage(to: string, subject: string, body: string): string {
  const lines = [
    `To: ${to}`,
    `Subject: ${subject}`,
    "MIME-Version: 1.0",
    'Content-Type: text/plain; charset="UTF-8"',
    "",
    body,
  ];
  return Buffer.from(lines.join("\r\n"), "utf8")
    .toString("base64")
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");
}

function extractText(payload: GmailMsg["payload"]): string {
  if (!payload) return "";
  if (payload.body?.data) {
    return Buffer.from(payload.body.data.replace(/-/g, "+").replace(/_/g, "/"), "base64").toString("utf8");
  }
  for (const part of payload.parts ?? []) {
    const t = extractText(part);
    if (t) return t;
  }
  return "";
}

export const gmailTools = [gmailListTool, gmailReadTool, gmailDraftTool, gmailSendTool];

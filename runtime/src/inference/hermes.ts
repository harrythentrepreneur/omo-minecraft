import OpenAI from "openai";
import { dbg, debugEnabled, error as logErr } from "../debug.js";

const baseURL = process.env.HERMES_BASE_URL ?? "https://openrouter.ai/api/v1";
const apiKey = process.env.HERMES_API_KEY ?? "";
const model = process.env.HERMES_MODEL ?? "nousresearch/hermes-4-405b";
const maxTokens = Number(process.env.HERMES_MAX_TOKENS ?? 2048);
const temperature = Number(process.env.HERMES_TEMPERATURE ?? 0.7);

if (!apiKey) {
  console.warn(
    "[hermes] HERMES_API_KEY is not set — inference calls will fail until you set it in runtime/.env",
  );
}

// hermes-agent's api_server gates session-related headers behind API_SERVER_KEY.
// If the user has set HERMES_API_SERVER_KEY on our side, we also send
// Authorization: Bearer <key> and enable the per-agent session headers.
const hermesApiServerKey = process.env.HERMES_API_SERVER_KEY ?? "";
const sessionHeadersAllowed = Boolean(hermesApiServerKey);

const defaultHeaders: Record<string, string> = {
  // OpenRouter likes these for app identification; harmless elsewhere.
  "HTTP-Referer": "https://agentcraft.local",
  "X-Title": "AgentCraft",
};
if (hermesApiServerKey) {
  defaultHeaders["Authorization"] = `Bearer ${hermesApiServerKey}`;
}

const client = new OpenAI({
  apiKey: apiKey || "missing",
  baseURL,
  defaultHeaders,
});

export type HermesMessage =
  | { role: "system"; content: string }
  | { role: "user"; content: string }
  | { role: "assistant"; content: string; tool_calls?: HermesToolCall[] }
  | { role: "tool"; content: string; tool_call_id: string };

export type HermesToolCall = {
  id: string;
  type: "function";
  function: { name: string; arguments: string };
};

export type HermesToolDef = {
  type: "function";
  function: {
    name: string;
    description: string;
    parameters: Record<string, unknown>;
  };
};

export type HermesResponse = {
  content: string;
  toolCalls: HermesToolCall[];
  raw: unknown;
};

export async function callHermes(
  messages: HermesMessage[],
  tools: HermesToolDef[] = [],
  sessionId?: string,
): Promise<HermesResponse> {
  // Session headers are only accepted by hermes-agent when API_SERVER_KEY is
  // configured on both sides. Skip them entirely when we don't have a key,
  // so the default zero-config setup just works.
  const headers: Record<string, string> = {};
  if (sessionId && sessionHeadersAllowed) {
    headers["X-Hermes-Session-Id"] = sessionId;
    headers["X-Hermes-Session-Key"] = sessionId;
  }

  const reqBody = {
    model,
    messages: messages as never,
    tools: tools.length ? tools : undefined,
    tool_choice: tools.length ? ("auto" as const) : undefined,
    max_tokens: maxTokens,
    temperature,
  };

  dbg("hermes", `→ POST ${baseURL}/chat/completions`, {
    sessionId,
    model,
    messages: messages.length,
    tools: tools.length,
    sessionHeaders: sessionHeadersAllowed,
    auth: hermesApiServerKey ? "Bearer …" : "(none)",
  });

  if (debugEnabled) {
    // Show the last user/tool turn so we can see what we just asked the model.
    const last = messages.at(-1);
    if (last) dbg("hermes", "last message", last);
  }

  const t0 = Date.now();
  let completion;
  try {
    completion = await client.chat.completions.create(
      reqBody,
      Object.keys(headers).length ? { headers } : undefined,
    );
  } catch (err) {
    const ms = Date.now() - t0;
    logErr("hermes", `request failed after ${ms}ms`, err);
    throw err;
  }
  const ms = Date.now() - t0;

  const choice = completion.choices[0];
  dbg("hermes", `← ${ms}ms`, {
    finish: choice?.finish_reason,
    content_len: choice?.message?.content?.length ?? 0,
    tool_calls: choice?.message?.tool_calls?.length ?? 0,
    usage: completion.usage,
  });
  if (!choice) {
    return { content: "", toolCalls: [], raw: completion };
  }
  const msg = choice.message;
  const toolCalls: HermesToolCall[] = (msg.tool_calls ?? [])
    .filter((t): t is Extract<typeof t, { type: "function" }> => t.type === "function")
    .map((t) => ({
      id: t.id,
      type: "function",
      function: { name: t.function.name, arguments: t.function.arguments },
    }));

  return {
    content: msg.content ?? "",
    toolCalls,
    raw: completion,
  };
}

export const hermesModelName = model;

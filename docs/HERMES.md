# Hermes inference setup

The AgentCraft runtime calls Hermes through any OpenAI-compatible
`/v1/chat/completions` endpoint. Three env vars in `runtime/.env` control it:

```
HERMES_BASE_URL=...
HERMES_API_KEY=...
HERMES_MODEL=...
```

## Option A — local `nousresearch/hermes-agent` (recommended)

If you already have https://github.com/NousResearch/hermes-agent installed and
logged into a provider (ChatGPT / OpenRouter / etc), AgentCraft can drive
**that** instead of talking to a model directly. Big upside: every AgentCraft
agent gets hermes-agent's skills, memory, and learning loop for free, and you
don't pay per token from us — hermes-agent already knows where to route.

### 1. Enable hermes-agent's API server

`hermes-agent` ships an OpenAI-compatible API adapter that listens on
`http://127.0.0.1:8642/v1`. Enable it via env vars in `~/.hermes/.env`:

```bash
echo 'API_SERVER_ENABLED=true' >> ~/.hermes/.env
echo 'GATEWAY_ALLOW_ALL_USERS=true' >> ~/.hermes/.env   # accept local requests
# optional overrides:
# echo 'API_SERVER_PORT=8642' >> ~/.hermes/.env
# echo 'API_SERVER_HOST=127.0.0.1' >> ~/.hermes/.env
```

The AgentCraft launcher starts the gateway for you. If you want to start it
manually:

```bash
hermes gateway
```

Verify it's listening:

```bash
curl http://127.0.0.1:8642/health
```

### 2. Point AgentCraft at it

`runtime/.env`:

```
HERMES_BASE_URL=http://127.0.0.1:8642/v1
HERMES_API_KEY=hermes-agent     # any non-empty string; ignored
HERMES_MODEL=hermes-agent
```

Restart `./scripts/start-runtime.sh`.

### 3. Per-agent isolation

AgentCraft passes the in-game agent id (e.g. `alice`, `bob`) as the
`X-Hermes-Session-Id` and `X-Hermes-Session-Key` headers on every request. So
each villager gets its own hermes-agent session, its own conversation, and
its own slice of long-term memory. Spawn `alice` in the mail room and `bob`
in the ads room and they won't share state.

## Option B — hosted provider

Comment out the local block in `.env` and use one of these instead.

### OpenRouter

```
HERMES_BASE_URL=https://openrouter.ai/api/v1
HERMES_API_KEY=sk-or-v1-...
HERMES_MODEL=nousresearch/hermes-4-405b
```

### Together AI

```
HERMES_BASE_URL=https://api.together.xyz/v1
HERMES_API_KEY=tgp_...
HERMES_MODEL=NousResearch/Hermes-3-Llama-3.1-70B
```

### Nous portal (direct)

```
HERMES_BASE_URL=https://inference-api.nousresearch.com/v1
HERMES_API_KEY=...
HERMES_MODEL=Hermes-4-405B
```

## Tuning

```
HERMES_MAX_TOKENS=2048
HERMES_TEMPERATURE=0.7
```

AgentCraft runs a tool-call loop with a 12-step cap per turn. Smaller models
(< 70B) sometimes get OpenAI-format tool calls wrong; if you see `alice`
ignoring `finish_task` or producing empty tool calls, that's the failure
mode. On Option A, switch the model inside hermes-agent (`hermes model`); on
Option B, bump to a larger Hermes variant.

"""Omo specialist — the brain for a hired, staffed function (Gemini via ADK + MCP).

When the Chief of Staff extends the org (world_add_function → world_build →
world_staff), a new villager walks into the freshly-built wing. That villager's
brain is this generic specialist: it adopts whatever role it was hired for (the
runtime seeds the role on the first turn) and does that function's work, pulling
real data over MCP. One reusable app makes the org extensible to ANY function.
"""

import os

from google.adk.agents import LlmAgent
from google.adk.tools.mcp_tool import McpToolset, StreamableHTTPConnectionParams

MODEL = os.environ.get("OMO_GEMINI_MODEL", "gemini-flash-latest")
MCP_URL = os.environ.get("OMO_MCP_URL", "http://127.0.0.1:8090/mcp")
_HEADERS = {"Accept": "application/json, text/event-stream", "Content-Type": "application/json"}

root_agent = LlmAgent(
    model=MODEL,
    name="Specialist",
    description="A specialist agent hired into the Omo organisation for a specific function.",
    instruction=(
        "You are a specialist the Omo Chief of Staff just hired into the organisation. "
        "Your role is given to you at the start of the conversation — adopt it fully and "
        "introduce yourself by it on your first reply. Do your function's work concisely "
        "and concretely. When your role involves data you can fetch with your tools (ads, "
        "marketing, performance), pull the REAL numbers — never fabricate. ALWAYS keep the "
        "screen in your room current: call dashboard_update (your function_id is given to you) "
        "whenever you have something worth showing — right after you pull fresh data, when you "
        "reach a finding or decision, and at the end of every task — with a clear title, the key "
        "KPIs, and a short feed of what you just did. Treat the board as your live status: if it "
        "would change what the owner sees, push an update. Revise it on request too. When you need a number, fact, "
        "or judgement that ANOTHER function owns, don't guess — use world_consult(from_function, "
        "to_function, question) to ask that function's specialist and use their answer. Keep replies to "
        "1-3 sentences; your reasoning streams onto the screens in your room."
    ),
    tools=[
        McpToolset(
            connection_params=StreamableHTTPConnectionParams(url=MCP_URL, headers=_HEADERS),
            tool_filter=["meta_ads_list_campaigns", "meta_ads_insights", "dashboard_update", "world_consult"],
        )
    ],
)

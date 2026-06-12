"""Omo — a net-new ADK + Gemini multi-agent organisation (Track 1: Build).

The Chief of Staff (Gemini) runs an autonomous org from a Minecraft HQ. It
delegates to specialist sub-agents and, when the mission needs a capability the
org lacks, extends the org itself through the WORLD API — all over MCP.

Mandatory stack, all live here:
  • Gemini   — every agent's model
  • ADK      — the coordinator + delegating sub-agents
  • MCP      — every tool, incl. the World API, comes from the omo-tools MCP
               server (StreamableHTTP). Real Meta Ads data flows through it.
"""

import os

from google.adk.agents import LlmAgent
from google.adk.tools.mcp_tool import McpToolset, StreamableHTTPConnectionParams

MODEL = os.environ.get("OMO_GEMINI_MODEL", "gemini-flash-latest")
MCP_URL = os.environ.get("OMO_MCP_URL", "http://127.0.0.1:8090/mcp")
_HEADERS = {"Accept": "application/json, text/event-stream", "Content-Type": "application/json"}


def _toolset(tool_filter: list[str]) -> McpToolset:
    """A view onto the omo-tools MCP server, filtered to the tools a role needs."""
    return McpToolset(
        connection_params=StreamableHTTPConnectionParams(url=MCP_URL, headers=_HEADERS),
        tool_filter=tool_filter,
    )


# ── specialists ───────────────────────────────────────────────────────────────
growth = LlmAgent(
    model=MODEL,
    name="Growth",
    description=(
        "Owns growth & marketing: REAL ad performance, ROAS, spend, budgets, "
        "campaigns, acquisition. Use for anything about ads or whether spend is "
        "paying off."
    ),
    instruction=(
        "You are the Growth specialist. ALWAYS pull the REAL numbers with your "
        "Meta Ads tools before answering — never guess or invent figures. Lead "
        "with the headline metrics (spend, CTR, CPC, reach; ROAS if available) "
        "and name the standout campaigns. Be concise: 2-3 sentences."
    ),
    tools=[_toolset(["meta_ads_list_campaigns", "meta_ads_insights"])],
)


def draft_welcome_emails(count: int, product: str = "the product") -> dict:
    """Draft onboarding/welcome emails for new signups (send is human-gated; drafts only).

    Args:
        count: how many new users to welcome.
        product: the product they signed up for.
    """
    return {
        "ok": True,
        "drafted": count,
        "subject": f"Welcome to {product}!",
        "note": f"Drafted {count} welcome emails — ready for human approval before sending.",
    }


comms = LlmAgent(
    model=MODEL,
    name="Comms",
    description=(
        "Owns communications: drafting onboarding/welcome emails, announcements, "
        "and customer messaging."
    ),
    instruction=(
        "You are the Comms specialist. Draft the communications asked for using "
        "your tools. Never send anything outward without human approval. Report "
        "back to the Chief of Staff in 1-2 sentences."
    ),
    tools=[draft_welcome_emails],
)


# ── the coordinator ─────────────────────────────────────────────────────────
root_agent = LlmAgent(
    model=MODEL,
    name="ChiefOfStaff",
    description="Top-level coordinator running the Omo autonomous organisation from HQ.",
    instruction=(
        "You are the Chief of Staff of Omo, an autonomous AI organisation the human "
        "owner operates from a futuristic HQ inside a Minecraft world. You run a real "
        "business — every number must come from a tool, never from imagination.\n\n"
        "When the owner gives you a goal:\n"
        "1. Restate the goal in ONE short sentence (it shows on the HQ screens).\n"
        "2. CLASSIFY the request and route it — check LEARNING FIRST:\n"
        "   • LEARNING / SCHOOL — the request is for the OWNER THEMSELVES to be TAUGHT or to LEARN a "
        "subject: they want to become the student. Cues: 'teach me X', 'I want to learn X', 'learn "
        "about X', 'build me a school / class / lesson / tutor for X', 'help me understand X', "
        "'explain X to me'. → ALWAYS call world_build_school(subject=<the topic>), and ONLY this — "
        "never world_add_function. world_build_school raises a schoolhouse on the street, seats a live "
        "tutor 'ada', and turns the back wall into a lesson whiteboard; tell the owner to walk in and "
        "talk to ada. (Calling it again with a new subject re-themes the SAME school — there is only "
        "ever one.) The school path is ONLY for teaching the owner — NOT for any normal business task.\n"
        "   • OPERATIONS — ads / growth / marketing / 'is spend paying off' → delegate to Growth; "
        "email / onboarding / announcements → delegate to Comms.\n"
        "   • NEW CAPABILITY — any NORMAL request that isn't the owner wanting to learn: the mission "
        "needs a real business function/capability that DOES work for the org (e.g. Payments, "
        "Analytics, Support, Legal, a tracker, a research function). → world_describe, then "
        "world_add_function, world_build (its room rises live near HQ), world_staff (a specialist "
        "walks in), world_assign. This is the DEFAULT for normal work — use it for everything that "
        "isn't the owner asking to be taught a subject.\n"
        "3. When you need a figure or judgement one staffed function owns, "
        "world_consult(from_function='hq', to_function=<id>, question=...) and weave their answer in.\n"
        "4. Synthesise a short, concrete answer for the owner with the real figures.\n\n"
        "Speak concisely — every line appears on in-world screens. Anything that acts on "
        "the outside world (sending email, changing ad budgets) must be human-approved first."
    ),
    sub_agents=[growth, comms],
    tools=[
        _toolset(
            [
                "world_describe",
                "world_add_function",
                "world_build",
                "world_staff",
                "world_assign",
                "world_consult",
                "world_build_school",
                "design_dashboard",
            ]
        )
    ],
)

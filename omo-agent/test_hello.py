"""De-risk: confirm GOOGLE_API_KEY drives a Gemini LlmAgent through ADK's Runner.

Run from omo-agent/:  .venv/bin/python test_hello.py
"""

import asyncio
import os
import sys

# Load the agent-folder .env (GOOGLE_API_KEY, model) into the process env.
try:
    from dotenv import load_dotenv

    load_dotenv(os.path.join(os.path.dirname(__file__), "omo", ".env"))
except Exception:  # noqa: BLE001
    pass

from google.adk.runners import Runner  # noqa: E402
from google.adk.sessions import InMemorySessionService  # noqa: E402
from google.genai import types  # noqa: E402

from omo.agent import root_agent  # noqa: E402

APP, USER, SESSION = "omo", "harry", "s1"


async def main() -> int:
    if not os.environ.get("GOOGLE_API_KEY"):
        print("FAIL: GOOGLE_API_KEY not in env", file=sys.stderr)
        return 2
    ss = InMemorySessionService()
    runner = Runner(agent=root_agent, app_name=APP, session_service=ss)
    await ss.create_session(app_name=APP, user_id=USER, session_id=SESSION)
    msg = types.Content(
        role="user",
        parts=[types.Part(text="Self-test: call ping with 'hi', then confirm you are online in one sentence.")],
    )
    final = None
    async for ev in runner.run_async(user_id=USER, session_id=SESSION, new_message=msg):
        for c in ev.get_function_calls() or []:
            print(f"[tool] {c.name}({dict(c.args)})", flush=True)
        if ev.is_final_response() and ev.content and ev.content.parts:
            final = ev.content.parts[0].text
    print("FINAL:", (final or "").strip())
    return 0 if final else 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))

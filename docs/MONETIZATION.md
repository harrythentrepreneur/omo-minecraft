# Omo Studio — Monetization Strategy Report

*Prepared for the founder. Inputs: a verified feature map of the AgentCraft/Omo stack and a stress-tested set of 28 scored business concepts (41 agents, 7 market lenses, adversarial scoring).*

---

## 1. TL;DR

1. **Sell the share link, not the world.** *Replay* — one line of SDK wrap turns any Claude/OpenAI agent run into a `replay.dev/<run-id>` URL your team watches live or replays. The "watch the agent think" UX already exists in `HermesActivityStream`; the wedge is distribution (Loom for agent runs), not storage.
2. **Sell the output, not the theater.** *Meta Ads Night Watch* — an approval-gated autonomous ads guardian that watches client spend 24/7 and texts a one-tap PAUSE. The Meta tools + approval gate ship today; the glass booth becomes *your* sales-demo footage, never a client install.
3. **Sell the procurement bake-off, not the spectator sport.** *Stadium (enterprise edition)* — VP-Eng can't compare Claude Code vs Cursor vs Devin on anything but vibes. Run each candidate in its own instrumented station on *their* real tickets; the 3D world makes the eval exec-presentable; the telemetry scoreboard is what they buy. $15–30k/eval.

Every other concept either strips the Minecraft layer to nothing (commodity) or weds the value to a Java-client install the buyer won't tolerate. The three above resolve that tension correctly: **Minecraft is the demo and the operator's cockpit, never the customer's gate.**

---

## 2. What you've actually built

Strip the marketing and there are four primitives here that genuinely do not exist as polished products anywhere else. They are worth naming precisely, because most concepts live or die on which primitive they actually exploit.

**A. A live, interactive browser rendered inside a 3D world.**
The cinema pipeline (CDP screencast ~15fps → quantize to map palette → dirty-tile push; ray-traced aim → normalized `[0,1]` → CDP Input domain) turns a Minecraft wall into a *real, operable* webpage — click, scroll, type, drive a live configurator. No other "spatial" platform (Gather, Roblox, web canvas) gives you a genuinely interactive third-party web app inside the world. This is the single most defensible asset because nobody can clone it in a weekend.

**B. Watchable multi-agent orchestration with visible cognition.**
Reasoning boards, lectern transcript books, BossBar HUD, the puppeted mason walking the build front — abstract LLM cognition rendered as in-world spectacle. The differentiated thing is *legibility of work*: a non-technical exec can watch an agent reason and act and understand it. That's the unlock for procurement bake-offs, sales demos, and edutainment.

**C. Real PTY terminals + read-only reasoning streams, multiplexed over WebSocket.**
`WorkshopAgent` runs a real `claude` CLI in a `node-pty` with a 64KB replay buffer and multi-subscriber fan-out; `HermesActivityStream` mirrors any agent's reasoning into the same terminal interface (`SourceLike` abstracts real-PTY vs synthetic). Late-joiners see current screen; Shift+←/→ cycles a fleet. **This is the strongest dev-tooling lift.** Honest gap: there is *no write-arbitration* — every subscriber's input hits the same stdin. So the sellable posture is **supervision (read-many), not co-typing.**

**D. Room-gated real tools behind an in-world approval gate.**
`roomKindFromName → RoomKind → ToolRegistry` means location *is* the permission model: mail-room villager gets Gmail, ads-room gets Meta, and nothing else. Sensitive calls (`gmail_send`, `meta_ads_pause/budget`, dangerous Bash) funnel through one uniform `tool_request_approval → /omo approve` path. The real tools (Gmail OAuth, Meta Graph v21.0) work today.

**The unfair advantage, stated plainly:** you can take a process nobody can see — an AI agent acting on money, code, or an inbox — and make it *watchable, gated, and exec-legible* in real time. Where that legibility is load-bearing (procurement, agency client-trust, sales demos, debugging an incident), you win. Where it isn't (a solopreneur who just wants drafts in their inbox), the Minecraft layer is dead weight and you lose to a flat web app. **Pick only the businesses where watching is the product.**

**The recurring trap to internalize:** the parts you'd want to *sell as infrastructure* (approval queue = ~80 lines of in-memory Promises + 2 counters; room registry = a ~30-line switch; activity stream = a ~250-line ANSI formatter) are thin and copyable. The defensible parts are the *integrated experience* and the *accumulating system-of-record* (approval logs, per-client history, audit trails) — which you mostly haven't built yet. Every serious version below requires building that boring, sticky layer.

---

## 3. The opportunity map

Six strategic lines, ranked by how cleanly they exploit a real primitive against a buyer who'll pay.

### Line 1 — Dev tooling & AI infra (live reasoning + supervision)
- **Who pays:** AI startups and platform teams shipping Claude/OpenAI agents to prod with zero live visibility.
- **Revenue:** usage/seat SaaS — free public links, **$99/mo team** (private + retention + redaction), **$2–5k/mo** self-host.
- **Powered by:** `HermesActivityStream` + `terminalServer.ts` multiplex + the `SourceLike` PTY/stream abstraction.
- **Wedge:** the **shareable link** dropped in Slack during an incident — distribution, not storage. (Concepts: *Replay*, *Glassbox*, *Booth* → reframed as fleet supervision.)

### Line 2 — Services & agency / done-for-you (ads + inbox)
- **Who pays:** DTC/SMB advertisers ($5–15k/mo Meta spend) and agencies billing clients for agent work.
- **Revenue:** **$499/mo flat** ads guardian; agency tier with per-client audit export as a billable deliverable.
- **Powered by:** `ads_room` tools (Graph v21.0) + approval gate + notes for campaign memory.
- **Wedge:** **gated autonomy that catches weekend budget bleed** and texts a one-tap approval; the glass booth is your viral top-of-funnel, not a client install. (Concepts: *Watch-My-Ads / Night Watch*, *Full-stack pod* — narrowed hard.)

### Line 3 — Enterprise eval & procurement (the bake-off)
- **Who pays:** VP-Eng / platform teams choosing between coding agents; sponsors as demand-gen only.
- **Revenue:** **$15–30k per procurement bake-off**; quarterly public "Stadium" event as a lead magnet.
- **Powered by:** multi-station Studio (code-1..4 + dev-walls) + per-model token/cost telemetry + reasoning terminals as audit trail.
- **Wedge:** **turns an unwatchable LLM eval into a 30-min exec-presentable event** on *their* code, with a telemetry scoreboard as the artifact they keep. (Concept: *Stadium*, inverted.)

### Line 4 — Watchable compliance / supervision ops
- **Who pays:** agency ops leads who must show clients "a human approved every dollar moved."
- **Revenue:** **$3–5k/mo** for a 3-seat Meta pilot; upsell a signed, hash-chained approval log + PDF "proof of oversight."
- **Powered by:** approval gate + room-scoped capability ceilings + reasoning visualization.
- **Wedge:** sell **agency-to-client trust** (fast buyer), *not* regulator compliance (procurement death). The 3D is the operator's daily surface; the signed log is the invoice justification. (Concepts: *Glass Box*, *Gatehouse*, *Gatekeeper* — same business, agency-flavored.)

### Line 5 — Education & training (the fishbowl)
- **Who pays:** **already-employed mid/senior eng teams** whose company just mandated Claude Code/Copilot (L&D budget, urgency, no refund anxiety) — *not* career-switchers.
- **Revenue:** **$15–25k per team cohort** (2-day intensive), led by you.
- **Powered by:** full untruncated reasoning terminal + per-turn cost telemetry + Shift+←/→ cohort review.
- **Wedge:** a **fishbowl code review** — whole cohort watches one agent's reasoning + live cost on the wall — that you literally cannot do in Zoom. ROI story: "cut agent spend 40% while shipping faster." (Concepts: *DevDojo*, *Onboard Quest* — B2B-only versions.)

### Line 6 — Consumer & gaming / creator (top-of-funnel, not revenue)
- **Who pays:** essentially nobody, directly, at sustainable margin. MC creators are cash-poor and churny; reselling Claude OAuth and charging for server access are both EULA/economics landmines.
- **Right move:** treat this entire line as **free, viral distribution** for Lines 1–4. A streamer running "I gave my server a Claude that builds things" is an ad, not a customer. (Concepts: *Omo Realms*, *Twitch Co-Pilot*, *AgentTwitch*, *Villager Studio* — demand-gen only.)

---

## 4. Ranked shortlist

Scores are the council's (1–10). "Sharpest version" is the reframe that actually survives contact with a buyer.

| # | Concept | Mkt | Feas | Moat | Speed | Fit | Verdict | Sharpest version |
|---|---------|----|----|----|----|----|---------|------------------|
| 1 | **Replay** — shareable live reasoning terminals | 6 | 7 | 3 | 7 | 8 | **Bet** | The *share link* (Loom for agent runs), not observability. Redaction = the moat. Sit on top of Langfuse, don't fight it. |
| 2 | **Watch-My-Ads → Night Watch** — managed Meta Ads | 6 | 6 | 3 | 8 | 7 | **Bet** | Kill the client-side MC install. Sell 24/7 gated guardian + SMS one-tap PAUSE. Per-client token scoping + a real cron first. $499 flat, one vertical (Shopify DTC). |
| 3 | **Stadium → Procurement Bake-Off** | 5 | 7 | 4 | 6 | 9 | **Bet** | Drop spectator revenue. Enterprise coding-agent eval on the buyer's own tickets; telemetry scoreboard is the deliverable. $15–30k/eval. |
| 4 | **Bullpen** — watchable multi-agent workrooms | 6 | 6 | 3 | 6 | 7 | Promising | Sell **approval queue + signed client work-receipt** for agencies, not "watchable terminals." Audit trail = switching cost. |
| 5 | **DevDojo** — AI-pair-programming training | 5 | 7 | 4 | 7 | 6 | Park→Promote | B2B only: $15–25k team intensives for eng teams forced onto Claude Code. Cost-telemetry = the ROI wedge. |
| 6 | **Glass Box** — watchable AI ops / supervision | 6 | 5 | 4 | 6 | 8 | Park→Promote | "Mission control for ad-ops agents" + signed log as **agency-to-client** trust artifact. Defer FINRA entirely. |
| 7 | **Camp Omo** — kids' coding camp in MC | 6 | 4 | 4 | 7 | 6 | Promising | Instructor-led paid cohort *first* (you are the manned approver). Earn the right to build the COPPA/sandbox layer. |
| 8 | **Code Lab Realms** — multiplayer pair-programming | 4 | 8 | 4 | 6 | 8 | Promising | Not edtech. Sponsored **AI Build Arena** events to dev-rel budgets ($1.5–5k/event); learners are free top-of-funnel. |
| 9 | **Gatekeeper / Gatehouse** — approval middleware | 6–7 | 8 | 2 | 6–7 | 3–7 | Park | Vertical only: **spend-bearing agents** (ad budget, refunds). Monetize the tamper-evident audit ledger, give the hook away. |
| 10 | **Roomscope** — least-privilege for agent fleets | 7 | 6 | 3 | 5 | 4 | Park | Reframe as **multi-tenant client isolation** for agencies ("put it in your MSA"), not a security-team platform sale. |

**Below the line (skip or demand-gen only):** Front-Row, Storefront Worlds, Idea Booth, Foreman, Omo Realms, Villager Studio, AgentTwitch, Subject-on-Demand, Inbox Triage-as-a-Service, Class Live. Reasons in §7.

---

## 5. The 3 I'd bet on

### Bet #1 — Replay: the universal share link for agent runs

**Why this wins.** It's the only Line-1 concept where your real primitive (`HermesActivityStream`'s watchable, read-only ANSI reasoning stream + the multiplex fan-out) maps onto a behavior that spreads *by itself*. Observability incumbents (Langfuse, Braintrust, LangSmith) own the data plane and will treat "watch the agent think" as a weekend feature — so **do not sell observability.** Sell the *shareable link*. Every `replay.dev/<run-id>` URL pasted into a Slack incident thread is a logo impression on a non-technical viewer, which is exactly how Loom spread inside companies. You sit *on top of* whatever they already run; you're the link they drop in chat, not the system of record.

**Riskiest assumption.** That the defensible thing — redaction safe enough to paste an external link — can be shipped fast and well. This is the #1 enterprise blocker and therefore the moat. If redaction is shaky, the whole "shareable" thesis collapses to a pretty internal demo nobody renews.

**30-day first-dollar plan.**
- Days 1–7: npm wrapper around the Claude Agent SDK message stream (`CodeAgent.ts` is the working reference) → emits a `HermesActivityStream`-style feed → hosted web terminal at `replay.dev/<run-id>`, live + replay.
- Days 8–14: ship the **redaction pass** (token/secret/PII masking on the stream) as a first-class feature, not a footnote.
- Days 15–21: instrument **one** design-partner AI startup's agent *live on a call in under 5 minutes* — speed-to-first-trace is the entire pitch. Bill **$99/mo** for private team replays.
- Days 22–30: add line-level comments ("why did it call this tool?") so a thread forms around a reasoning step. Add the OpenAI Agents adapter *only* once 3 teams ask. Explicitly position as complement to Langfuse, never competitor.

### Bet #2 — Meta Ads Night Watch: gated autonomy that catches weekend bleed

**Why this wins.** The ads tools (`meta_ads_list_campaigns/insights/pause/update_budget`, Graph v21.0) and the approval gate are real *today*, and the buyer pain is dollar-denominated and acute: budget bleeds on dead ad sets over the weekend while a sub-$15k-spend DTC owner sleeps and can't afford a $2k agency. The trust wedge no autonomous tool offers — **"nothing pauses or changes budget without your one tap"** — is your approval gate, delivered over SMS. The glass booth is repurposed as *your* jaw-dropping sales-demo recording, never something the client installs.

**Riskiest assumption.** Two real ones, both buildable in ~3 days and both currently vaporware: (1) **multi-tenant token scoping** — the code reads a single global `META_ADS_ACCESS_TOKEN` from env, so you cannot safely serve client #2 without a rebuild; (2) **an actual scheduler** — "watches 24/7" requires the cron that doesn't exist. The headline promise *is* the unbuilt part. Plus Meta's Advanced Access / Business Verification for managing client accounts at scale is a real gate — start it day one.

**30-day first-dollar plan.**
- Days 1–10: build the three load-bearing pieces — per-client token/account scoping (drop the global env read), a 6am daily `insights → flag → approval` cron, and SMS/email approval delivery (Gmail tool exists for the email path).
- Days 11–17: narrow to **one vertical** (Shopify supplement/skincare DTC at $5–15k/mo) and codify "what's a loser" as a ruleset, not bespoke judgment — that's the only escape from hours-per-account.
- Days 18–24: free **7-day audit** on a real friend's account (the legit first-dollar move); deliver a flagged-waste report.
- Days 25–30: convert to **$499/mo flat**. Record the glass-booth footage as viral top-of-funnel. Defensible asset = the vertical rules library + the gated-autonomy safety record.

### Bet #3 — Procurement Bake-Off: the exec-legible coding-agent eval

**Why this wins.** This is the highest founder-fit concept (9/10) and the one place a buyer with *real* budget wants exactly what only you can render. Eng leaders cannot compare Claude Code vs Cursor vs Devin vs Copilot Workspace on anything but vibes and vendor decks. You run each candidate agent in its own instrumented station on the buyer's *own* backlog tickets; the spatial world is the demo theater that makes a normally-unwatchable LLM eval screenshareable to non-technical execs; the load-bearing deliverable is the **telemetry scoreboard** (cost / tokens / turns / diff-quality per agent on their code) plus reasoning terminals as audit trail. Drop the spectator and sponsorship models entirely — they suffer adverse selection (only the leader who doesn't need it, or the desperate challenger who signals weakness, shows up).

**Riskiest assumption.** That VP-Eng will run candidate agents against *real* (even sanitized) tickets inside your environment — a security/IP question. De-risk by running on a sanitized fixture repo or the buyer's designated sandbox first; the eval's value is comparative, so a representative-but-safe repo is enough for v1.

**30-day first-dollar plan.**
- Days 1–10: harden the multi-station Studio into a repeatable eval rig — N stations, each a candidate agent + dev-wall + isolated PTY — and make the per-model cost/token/turns telemetry export as a clean scoreboard artifact (PDF/CSV).
- Days 11–20: line up **one** design-partner VP-Eng already in an agent-procurement decision; offer the first bake-off at a steep discount in exchange for a case study.
- Days 21–30: run it on 2–4 of their real tickets; deliver the scoreboard + reasoning-terminal audit trail as the exec-presentable artifact. Price the next one at **$15–30k**. Run one *public* "Stadium" bake-off quarterly purely as a lead magnet — spectacle is demand-gen, eval is the business.

---

## 6. Contrarian wildcard

**Run one flagship channel yourself: "Omo Office."** Not a SaaS sold to streamers (that's selling air — the overlay renders in their normal client the moment they screen-capture). Instead, *you* operate a single 24/7 stream where embodied villagers do **real, auditable, high-stakes work**: a villager actually triaging a public inbox, an agent running a small real ad budget with live spend on a board, the mason taking one viewer build request per hour. The watchable hook — visible chain-of-thought on floating boards + the puppeted mason walking its build front + the terminal mirror — is precisely what a flat app cannot replicate.

Why it's worth a side bet: it monetizes nothing directly and that's the point. It's a **self-running, always-on demo reel** that converts viewers into trials of the actual product (AgentCraft doing real Gmail/Meta work) and into design-partner leads for Bets #1–#3. Net-new build is small and honest: a Twitch/YouTube chat → existing `spawn_team`/`chat_message` bridge (thin adapter), and a clean **OBS browser-source overlay rendering the reasoning boards as real HTML** instead of capturing muddy in-game item frames. Defer all micro-payment/rake nonsense. Spend ≤2 weeks; if it pulls a retained audience, it's the cheapest top-of-funnel you'll ever have.

---

## 7. What NOT to do

**Kill these outright:**
- **Storefront Worlds (skip).** Upside-down funnel: a fintech/CRM buyer must install a Java client + Fabric mod to reach a demo that converts *worse* than the live web app the agent is already showing. The system is built for trusted audiences (0.0.0.0, no multi-tenant auth) — the opposite of strangers walking into a storefront.
- **Idea Booth (skip).** The headline loop — "watch agents execute your distilled plan" — isn't wired; distill drops a prompt on the *host's* clipboard and a human pastes it. The fundable version is voice→agent-backlog for *technical builders* with Minecraft removed entirely.
- **Inbox Triage-as-a-Service (skip).** A solopreneur will never boot a Java client each morning to right-click-approve sends. Strip the gate and it's a generic Gmail+LLM tool Superhuman/Fyxer/Shortwave already beat. Code is single-tenant (global `gmail.json`).

**Park until a precondition is met:**
- **Anything sold to compliance/audit as a "regulator artifact."** The "audit trail" is two in-memory integers that die on restart. Auditors want a queryable, signed, SSO-gated log — which a flat web app does strictly better, making the 3D a liability. Sell *agency-to-client trust* instead; build the hash-chained signed log *before* you say "compliance."
- **Any consumer subscription touching minors** (Camp Omo self-serve, Subject-on-Demand B2C, Omo Realms). The repo is a single-operator dev tool: a kid's booth is a real PTY with your Claude OAuth and full filesystem reach, no per-kid auth, no FS jail, no moderation, no COPPA. Run *instructor-led, manned-approver* cohorts first; earn the right to build safety.
- **Reselling Claude compute** (Omo Realms hosting). The watchable agents run on Claude Code's *personal* OAuth — you cannot legally resell that, and the cheap-Hermes alternative needs a GPU per tenant that $15/mo can't cover. Plus Minecraft's commercial-use terms restrict charging for server access. Make the gaming layer free viral distribution; never the revenue line.

**Structural traps to avoid everywhere:**
1. **Don't sell the thin primitive as infrastructure.** The approval queue, the room switch, and the ANSI formatter are weekend builds — incumbents (LangGraph interrupts, native tool-approval hooks, tmate/sshx, Langfuse trace viewers) absorb each as a free feature. Sell the *integrated experience* or the *accumulating system-of-record*, not the glue.
2. **Don't wed value to a client-side Minecraft install.** The moment a buyer must run Java + Fabric to get value, you've added more friction than booking an SDR. Minecraft is the *operator's cockpit* and the *sales demo* — never the customer's gate.
3. **Don't claim "co-typing" or "hands off while you sleep" until built.** `terminalServer.ts` has no write-arbitration (everyone types into one stdin); the ads stack has no scheduler. Sell **supervision (read-many)** and ship the cron before the autonomy claim.
4. **Don't build marketplaces or platforms first.** Villager Studio / Omo Realms are two-sided liquidity bets on a single-tenant hobby stack where the inventory (a prompt string) is trivially copyable and triggers uncaptured inference cost. Win the authoring tool / the single channel first; bolt commerce on later.

**The one-line filter for every future idea:** *Is watching the agent the product, and does the buyer get value without installing Minecraft?* If yes to both, build it. If no to either, the 3D world is a costume, and a flat web app wins.

export const meta = {
  name: 'implement-on-demand-classroom',
  description: 'Implement Dean + re-themeable on-demand classroom across runtime + plugin, verify, review',
  phases: [
    { title: 'Implement', detail: 'R1 runtime wiring, R2 whiteboard, P1 plugin — disjoint files' },
    { title: 'Verify', detail: 'typecheck runtime + mvn compile plugin, fix errors' },
    { title: 'Review', detail: 'adversarial review of the diff' },
  ],
}

const REPO = '/Users/harryedwards/omo-mc'
const SPEC = '/tmp/on-demand-classroom-spec.md'

const IMPL_SCHEMA = {
  type: 'object',
  properties: {
    area: { type: 'string' },
    filesChanged: { type: 'array', items: { type: 'string' } },
    summary: { type: 'string', description: 'what you implemented, key decisions' },
    contractNotes: { type: 'string', description: 'how you honoured the PINNED CONTRACT names/shapes exactly' },
    openConcerns: { type: 'array', items: { type: 'string' }, description: 'anything you were unsure about or could not verify' },
  },
  required: ['area', 'filesChanged', 'summary'],
}

const VERIFY_SCHEMA = {
  type: 'object',
  properties: {
    process: { type: 'string' },
    clean: { type: 'boolean', description: 'true if the check passes with no errors' },
    command: { type: 'string' },
    fixesApplied: { type: 'array', items: { type: 'string' } },
    remainingErrors: { type: 'string', description: 'verbatim remaining errors if not clean, else empty' },
  },
  required: ['process', 'clean'],
}

const REVIEW_SCHEMA = {
  type: 'object',
  properties: {
    lens: { type: 'string' },
    findings: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          severity: { type: 'string', description: 'blocker | major | minor | nit' },
          file: { type: 'string' },
          issue: { type: 'string' },
          fix: { type: 'string', description: 'concrete suggested fix' },
        },
        required: ['severity', 'issue'],
      },
    },
    verdict: { type: 'string', description: 'overall: ship | fix-then-ship | rework' },
  },
  required: ['lens', 'findings', 'verdict'],
}

// ───────────────────────────── Implement ─────────────────────────────
phase('Implement')

const R1_PROMPT = `You are implementing AREA R1 of a feature in the AgentCraft repo (${REPO}).
READ THE SPEC FIRST (in full): ${SPEC}. Then implement ONLY "AREA R1 — runtime agent wiring + tools + prompts".
This is the runtime/ TypeScript process. Edit ONLY the files listed under AREA R1 (types.ts,
rooms/registry.ts, agents/prompts.ts, tools/registry.ts, agents/HermesAgent.ts, agents/AgentManager.ts,
and NEW files tools/dean.ts + tools/classroom.ts, tools/index.ts). DO NOT edit runtime/src/whiteboard.ts
or runtime/src/http.ts (AREA R2 owns those) — but you MAY import { whiteboardStore } from '../whiteboard'
(R2 guarantees that export with get/set/reset). Honour the PINNED CONTRACT names EXACTLY.
Before editing, Read each target file to match its existing style (how OutboundMessage variants, the
RoomKind union, ToolImpl/ToolContext, and AgentEvents are actually written). Do NOT run typecheck (a sibling
agent is editing the same process concurrently; verification happens in a later phase). Make minimal, correct,
idiomatic edits. Return per the schema.`

const R2_PROMPT = `You are implementing AREA R2 of a feature in the AgentCraft repo (${REPO}).
READ THE SPEC FIRST (in full): ${SPEC}. Then implement ONLY "AREA R2 — runtime whiteboard surface".
Edit ONLY runtime/src/whiteboard.ts and runtime/src/http.ts. You MUST export a singleton
\`whiteboardStore\` from runtime/src/whiteboard.ts with EXACTLY this shape (other code imports it):
  get(): { subject: string; title: string; content: string; updatedAt: number }
  set(p: { title?: string; content: string }): void   // keeps current subject; refresh the updatedAt timestamp
  reset(subject: string): void                          // subject set, title = "Welcome to "+subject, default intro content, refresh updatedAt
  initial state seeded to subject "Algebra" with a friendly Algebra intro.
The updatedAt field is a fresh millisecond timestamp from the standard JS clock (this is normal Node
application code — the usual time API is fine here; only workflow scripts forbid it).
Rewrite whiteboardHtml() to a self-contained polling shell that fetch('/api/whiteboard/state') every ~1200ms
and renders subject (header) + big title + content (white-space: pre-wrap), BIG high-contrast fonts (the
in-game map-wall is low-res ~1fps). Model the poll loop on runtime/src/listening/page.ts and the store on
runtime/src/faceState.ts. Keep whiteboardHtml() taking NO args. In http.ts: keep GET /whiteboard ->
whiteboardHtml(); ADD GET /api/whiteboard/state -> JSON of whiteboardStore.get() (model on the existing
/api/listening/state route; import whiteboardStore from './whiteboard'). Read both files first to match style.
Do NOT run typecheck. Return per the schema.`

const P1_PROMPT = `You are implementing AREA P1 of a feature in the AgentCraft repo (${REPO}).
READ THE SPEC FIRST (in full): ${SPEC}. Then implement ONLY "AREA P1 — plugin" (plugin/, Java 21 / Paper).
Edit ONLY: plugin/src/main/java/com/agentcraft/village/SchoolBuilder.java,
plugin/src/main/java/com/agentcraft/commands/HermesCommand.java,
plugin/src/main/java/com/agentcraft/bridge/IncomingHandler.java.
CRITICAL: HermesCommand.java has active uncommitted WIP — a school placed SOUTH-WEST via
SchoolBuilder.buildInStudio(at) inside handleBuild, plus a "SW: Algebra 101" summary line. PRESERVE and
integrate with it; do NOT revert it. Honour the PINNED CONTRACT EXACTLY: classroom room name "classroom"
(change ROOM from "classroom-algebra"), tutor id "ada", tutor role = subject+" tutor", dean room "dean",
dean id "dean", dean role "Dean of the on-demand school", command "/hermes classroom <subject...>", and the
wire message {type:"open_classroom_request", subject, playerName?}.
Key correctness requirements (from the spec's gotchas):
 - Parameterize SchoolBuilder by a String subject threaded through build/buildInStudio/buildOnIsland and the
   private build(...). Default "Algebra". Interpolate subject into signage + a generic notes book.
 - Add a Dean office (buildDeanOffice) straddling the door-side path between classroom and plaza; register
   room "dean"; return deanId/deanRole/deanHome/deanRoom on Result. The classroom room radius MUST NOT
   overlap the dean room radius — READ RoomManager.java to understand currentRoom/containment and choose
   radii/centres that leave a clean corridor (spec suggests classroom radius 8, dean radius 4, dean centre
   ~14 east of classroom centre). Verify no block-collision against MvpWorldBuilder occupied zones (read it).
 - handleBuild: pass subject "Algebra" to buildInStudio AND seat the Dean (stationary, Hermes brain, cwd null).
 - handleSchool: accept optional <subject...>; build the same complex via buildInStudio (deterministic
   location, matching the re-theme path); seat both Dean + tutor.
 - handleIslandBuild: thread default subject "Algebra" so it compiles; no Dean on the island.
 - NEW handleClassroom: subject defaults "Algebra"; DESPAWN ada (send despawn_agent frame + remove local body)
   BEFORE re-seating (spawn is idempotent on agentId on both sides, so you MUST despawn first to re-theme the
   brain); rebuild classroom via buildInStudio(world spawn, subject); re-seat ada with role subject+" tutor"
   in room "classroom"; do NOT touch the Dean. Register "classroom" in the onCommand switch + usage + tab-complete.
 - IncomingHandler: add case "open_classroom_request" -> handleOpenClassroomRequest, mirroring
   handleSpawnCodeRequest (resolve player, on main thread Bukkit.dispatchCommand(player,"hermes classroom "+subject)).
Read each file (and RoomManager, MvpWorldBuilder, the despawn frame shape in wipeWorld/handleDespawn) before
editing, to match existing style and not break other callers of SchoolBuilder.ROOM / .build / .Result.
Do NOT run mvn (verification is a later phase). Return per the schema.`

const impl = await parallel([
  () => agent(R1_PROMPT, { label: 'R1 runtime-wiring', phase: 'Implement', schema: IMPL_SCHEMA }),
  () => agent(R2_PROMPT, { label: 'R2 whiteboard', phase: 'Implement', schema: IMPL_SCHEMA }),
  () => agent(P1_PROMPT, { label: 'P1 plugin', phase: 'Implement', schema: IMPL_SCHEMA }),
])

// ───────────────────────────── Verify ─────────────────────────────
phase('Verify')

const VERIFY_RUNTIME = `Verify the runtime/ TypeScript compiles after the on-demand-classroom feature was
implemented. Run: cd ${REPO}/runtime && npm run typecheck . If there are errors, READ them, fix them by
editing the runtime/src files (honour the PINNED CONTRACT in ${SPEC} — do NOT change wire-message/tool/store
names to "make it compile"; fix the real type error), and re-run. Iterate up to 6 times until clean. Common
issues to expect: the new OutboundMessage variant must be in the union; ToolContext.openClassroom optional;
AgentEvents.onOpenClassroom optional; new tools imported/registered; whiteboardStore import path. Return per
the schema with the final command output summary.`

const VERIFY_PLUGIN = `Verify the plugin/ Java compiles after the on-demand-classroom feature was implemented.
Run: cd ${REPO}/plugin && mvn -q -o package 2>&1 | tail -40 . If BUILD FAILURE, READ the compile errors, fix
them by editing the plugin Java files (honour the PINNED CONTRACT in ${SPEC}), and re-run. Iterate up to 6
times until BUILD SUCCESS. Watch for: Result record field additions used consistently, the despawn frame
shape, method signature changes to SchoolBuilder.build/buildInStudio/buildOnIsland propagated to ALL callers
(handleBuild, handleSchool, handleIslandBuild, handleClassroom), and the new IncomingHandler case + handler.
Return per the schema with the final BUILD status + any remaining errors verbatim.`

const verify = await parallel([
  () => agent(VERIFY_RUNTIME, { label: 'verify:runtime-typecheck', phase: 'Verify', schema: VERIFY_SCHEMA }),
  () => agent(VERIFY_PLUGIN, { label: 'verify:plugin-mvn', phase: 'Verify', schema: VERIFY_SCHEMA }),
])

// ───────────────────────────── Review ─────────────────────────────
phase('Review')

const reviewLens = (lens, focus) => `Adversarially review the on-demand-classroom feature diff in ${REPO}.
Run \`git -C ${REPO} status\` and \`git -C ${REPO} diff\` (and Read any NEW untracked files under runtime/src/tools/
and the new whiteboard/dean code). The spec/contract is at ${SPEC}. Your review LENS: ${lens}.
Focus specifically on: ${focus}
Be skeptical and concrete. Only report REAL issues (with file + a concrete fix). If the lens is clean, say so
with verdict "ship". Return per the schema.`

const reviews = await parallel([
  () => agent(reviewLens('Wire-contract consistency',
    'Does the open_classroom_request message have the IDENTICAL shape on both sides (runtime types.ts/AgentManager.send vs plugin IncomingHandler read)? Are tool names (open_classroom, whiteboard_write), agent ids (ada, dean), room names (classroom, dean), and the whiteboardStore get/set/reset shape consistent across every file that touches them? Is open_classroom registered for dean_room and whiteboard_write for classroom in buildRegistryForRoom? Is dean_room in BOTH the RoomKind union and roomKindFromName?'),
    { label: 'review:contract', phase: 'Review', schema: REVIEW_SCHEMA }),
  () => agent(reviewLens('Plugin correctness: re-theme idempotency, geometry, threading, room routing',
    'Does handleClassroom DESPAWN ada before re-seating (else the brain re-send is a no-op and subject never changes)? Are SchoolBuilder.build/buildInStudio/buildOnIsland signature changes propagated to ALL callers? Do the classroom and dean room radii/centres avoid overlap so chat routes correctly (read RoomManager containment)? Any block collision between the Dean office and MvpWorldBuilder structures (build studio at west-centre, Hermes worker booth south)? Is cinema.setUrl still off the main thread? Is the WIP SW-school placement preserved?'),
    { label: 'review:plugin', phase: 'Review', schema: REVIEW_SCHEMA }),
  () => agent(reviewLens('Runtime correctness: prompts, tools, brain selection, whiteboard page',
    'Is the classroom system prompt now subject-aware via opts.role (not hardcoded algebra) and does it instruct using whiteboard_write? Does the dean_room prompt instruct calling open_classroom then directing the student in? Does ToolContext.openClassroom actually get wired through HermesAgent ctx -> AgentEvents.onOpenClassroom -> AgentManager.send? Will a dean-prefixed room actually select a HermesAgent (not fall through wrongly)? Is the whiteboard page self-contained (no external assets) with big fonts, polling /api/whiteboard/state? Does whiteboardStore.set keep the subject and reset change it? Any way the tutor cannot see/use the tool?'),
    { label: 'review:runtime', phase: 'Review', schema: REVIEW_SCHEMA }),
])

return {
  implement: impl.filter(Boolean),
  verify: verify.filter(Boolean),
  review: reviews.filter(Boolean),
}

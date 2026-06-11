import type { RoomKind } from "../agents/prompts.js";

// A room's "kind" is implied by its name. The Studio world has one Hermes
// "ops" booth plus a numbered row of coding workstations — every one runs a
// real PTY shell (workshop_team → WorkshopAgent), differing only in the
// auto-launch command the plugin sends (claude vs hermes chat). Each coder
// gets their OWN station so four people don't collide on a single `claude`
// session: rooms are "code-1".."code-4" (agent id == room name), not just
// "code". The mail/ads/workshop/agent_home kinds remain for the generic
// /omo spawn + spawn-code commands.

export function roomKindFromName(room: string): RoomKind {
  const lower = room.toLowerCase();
  // Omo HQ — the futuristic circle office. The villager here is the Chief of
  // Staff, driven by the net-new ADK + Gemini crew (AdkAgent), not Hermes.
  if (lower === "hq" || lower.startsWith("hq") || lower.startsWith("mission") || lower.startsWith("omo-hq")
      || lower.startsWith("fn-"))
    return "mission_control";
  // Studio glass-box terminals — real PTY shells. "code-1".."code-N" are the
  // per-coder workstations; bare "code"/"hermes" stay for the island reskin's
  // two single boxes and the no-arg terminal fallback.
  if (lower === "code" || lower === "hermes" || lower.startsWith("code-"))
    return "workshop_team";
  // A Hermes "worker" booth (hermes-worker / hermes-N) is the operational,
  // chat-driven brain: you task it in chat and watch its reasoning, just like
  // the build mason — the generalist HermesAgent (full toolset, sensitive
  // actions approval-gated). Distinct from the bare "hermes" PTY shell above.
  if (lower.startsWith("hermes-")) return "agent_home";
  if (lower === "lobby") return "lobby";
  // School classrooms — Hermes tutor villagers (control + notes only).
  if (lower.startsWith("classroom") || lower.startsWith("class") || lower.startsWith("school"))
    return "classroom";
  if (lower.startsWith("mail")) return "mail_room";
  if (lower.startsWith("ads") || lower.startsWith("facebook")) return "ads_room";
  // Dean's office — the Hermes greeter that re-themes the classroom on request.
  if (lower.startsWith("dean") || lower.startsWith("registrar")) return "dean_room";
  // Generic coding villager from /omo spawn-code. Build Studio villagers
  // (room name starts with "build") also run on the CodeAgent brain.
  if (lower.startsWith("workshop") || lower.startsWith("dev") || lower.startsWith("build"))
    return "workshop";
  return "agent_home";
}

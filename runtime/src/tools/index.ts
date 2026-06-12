import { ToolRegistry } from "./registry.js";
import { finishTaskTool, requestHumanApprovalTool, sayTool } from "./control.js";
import { gmailTools } from "./gmail.js";
import { metaAdsTools } from "./metaAds.js";
import { notesTools } from "./notes.js";
import { spotifyTools } from "./spotify.js";
import { openClassroomTool } from "./dean.js";
import { startCodeWorldTool, startHermesWorldTool } from "./worlds.js";
import { presentSlideTool, whiteboardWriteTool, showSlideTool, readDeckTool } from "./classroom.js";
import type { RoomKind } from "../agents/prompts.js";

export function buildRegistryForRoom(kind: RoomKind): ToolRegistry {
  const reg = new ToolRegistry();
  // Always-on control tools.
  for (const t of [sayTool, finishTaskTool, requestHumanApprovalTool]) reg.register(t);
  // Always-on memory tools.
  for (const t of notesTools) reg.register(t);

  if (kind === "mail_room") {
    for (const t of gmailTools) reg.register(t);
  }
  if (kind === "ads_room") {
    for (const t of metaAdsTools) reg.register(t);
  }
  if (kind === "dean_room") {
    reg.register(openClassroomTool);
  }
  if (kind === "classroom") {
    reg.register(showSlideTool);
    reg.register(readDeckTool);
    reg.register(presentSlideTool);
    reg.register(whiteboardWriteTool);
  }
  if (kind === "agent_home" || kind === "lobby") {
    // Generalist agents get everything; the system prompt warns them to ask before sensitive actions.
    for (const t of gmailTools) reg.register(t);
    for (const t of metaAdsTools) reg.register(t);
    for (const t of spotifyTools) reg.register(t);
    // A host villager can start a new Claude Code / Hermes world on explicit
    // request — our app's structure doing what the Gemini Chief of Staff does.
    reg.register(startCodeWorldTool);
    reg.register(startHermesWorldTool);
  }
  return reg;
}

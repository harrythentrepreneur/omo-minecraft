package com.agentcraft.bridge;

import com.agentcraft.AgentCraftPlugin;
import com.agentcraft.agents.AgentManager;
import com.agentcraft.agents.AgentNpc;
import com.agentcraft.agents.ScreenEntry;
import com.agentcraft.build.BuildPlot;
import com.agentcraft.rooms.Room;
import com.agentcraft.rooms.RoomManager;
import com.agentcraft.village.MvpWorldBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import com.agentcraft.cinema.CinemaScreen;
import com.agentcraft.cinema.CinemaFrameStore;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Routes runtime -> world messages. Runs on the main thread. */
public class IncomingHandler {

    private final AgentCraftPlugin plugin;
    private final AgentManager agents;
    private final RoomManager rooms;
    private final Map<String, List<ScreenEntry>> terminalScreens = new ConcurrentHashMap<>();

    public IncomingHandler(AgentCraftPlugin plugin, AgentManager agents, RoomManager rooms) {
        this.plugin = plugin;
        this.agents = agents;
        this.rooms = rooms;
    }

    /**
     * Re-spawn every known agent's runtime brain whenever the runtime
     * (re)connects. Runtime agents live in memory only, so a runtime restart
     * leaves each villager body standing in the world with a dead brain —
     * walking up and typing would answer "(no agent here)" and right-clicking
     * would open an empty terminal. Re-sending each spawn (idempotent
     * runtime-side, so it's a no-op for any brain still alive) revives them all
     * automatically: the player walks in, types, and it goes straight to the
     * agent — no spawn command, exactly as it was before the restart.
     */
    private void resyncAgents() {
        var all = agents.all();
        if (all.isEmpty()) return;
        String cwd = plugin.getDataFolder().getAbsolutePath();
        int n = 0;
        for (AgentNpc a : all) {
            Location home = a.home();
            JsonObject m = new JsonObject();
            m.addProperty("type", "spawn_agent");
            m.addProperty("agentId", a.agentId());
            m.addProperty("role", a.role());
            m.addProperty("room", a.room());
            m.addProperty("playerName", "resync");
            // cwd only matters to workshop/build villagers; build mode ignores it
            // and Hermes villagers ignore it too — the data folder is a safe
            // default matching how the studios originally spawn their masons.
            m.addProperty("cwd", cwd);
            JsonObject vec = new JsonObject();
            vec.addProperty("x", home.getX());
            vec.addProperty("y", home.getY());
            vec.addProperty("z", home.getZ());
            m.add("home", vec);
            plugin.bridge().send(m);
            n++;
        }
        plugin.getLogger().info("re-synced " + n + " agent brain(s) after runtime (re)connect");
    }

    public void onMessage(String json) {
        JsonObject m;
        try {
            m = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            plugin.getLogger().warning("bad json from runtime: " + e.getMessage());
            return;
        }
        String type = m.get("type").getAsString();
        switch (type) {
            case "ready" -> {
                plugin.getLogger().info("runtime ready");
                resyncAgents();
            }
            case "agent_status" -> {
                String statusAgentId = m.get("agentId").getAsString();
                String status = m.get("status").getAsString();
                AgentNpc n = agents.get(statusAgentId);
                if (n != null) n.setStatus(status,
                    m.has("detail") && !m.get("detail").isJsonNull() ? m.get("detail").getAsString() : "");
                // Thinking-glow: brighten this function's island while it works.
                updateWingGlow(statusAgentId, status);
            }
            case "agent_say" -> {
                String agentId = m.get("agentId").getAsString();
                String text = m.get("text").getAsString();
                AgentNpc n = agents.get(agentId);
                if (n != null) n.chatBubble(text);
                String tag = "<" + agentId + "> ";
                if (m.has("playerName") && !m.get("playerName").isJsonNull()) {
                    String playerName = m.get("playerName").getAsString();
                    Player p = Bukkit.getPlayerExact(playerName);
                    if (p != null) {
                        p.sendMessage(Component.text(tag, NamedTextColor.AQUA).append(Component.text(text)));
                        return;
                    }
                }
                Bukkit.broadcast(Component.text(tag, NamedTextColor.AQUA).append(Component.text(text)));
            }
            case "agent_log" -> {
                String agentId = m.get("agentId").getAsString();
                String line = m.get("line").getAsString();
                String level = m.has("level") && !m.get("level").isJsonNull()
                    ? m.get("level").getAsString() : "info";
                if (plugin.getConfig().getBoolean("display.log_to_console", true)) {
                    plugin.getLogger().info("[" + agentId + "] " + line);
                }
                if (isTerminalBoxAgent(agentId) && agents.get(agentId) != null) {
                    pushTerminalScreen(agentId, new ScreenEntry(kindForLogLevel(level), line));
                }
            }
            case "agent_screen_update" -> {
                String agentId = m.get("agentId").getAsString();
                List<ScreenEntry> entries = parseEntries(m.getAsJsonArray("entries"));
                AgentNpc n = agents.get(agentId);
                if (n != null) n.setScreen(entries);
            }
            case "agent_transcript_append" -> {
                String agentId = m.get("agentId").getAsString();
                JsonObject entryObj = m.getAsJsonObject("entry");
                String kind = entryObj.has("kind") && !entryObj.get("kind").isJsonNull()
                    ? entryObj.get("kind").getAsString() : "system";
                String text = entryObj.has("text") && !entryObj.get("text").isJsonNull()
                    ? entryObj.get("text").getAsString() : "";
                boolean isNewTurn = m.has("isNewTurn") && !m.get("isNewTurn").isJsonNull()
                    && m.get("isNewTurn").getAsBoolean();
                AgentNpc n = agents.get(agentId);
                if (n != null) n.appendTranscript(new ScreenEntry(kind, text), isNewTurn);
            }
            case "room_screen_update" -> {
                String room = m.get("room").getAsString();
                List<ScreenEntry> entries = parseEntries(m.getAsJsonArray("entries"));
                for (AgentNpc n : agents.all()) {
                    if (n.room().equalsIgnoreCase(room)) n.setScreen(entries);
                }
            }
            case "tool_request_approval" -> {
                String agentId = m.get("agentId").getAsString();
                String callId = m.get("callId").getAsString();
                String tool = m.get("tool").getAsString();
                String summary = m.get("summary").getAsString();
                AgentNpc n = agents.get(agentId);
                Component msg = Component.text()
                    .append(Component.text("[APPROVAL] ", NamedTextColor.GOLD))
                    .append(Component.text(agentId + " wants to ", NamedTextColor.YELLOW))
                    .append(Component.text(tool + ": ", NamedTextColor.LIGHT_PURPLE))
                    .append(Component.text(summary, NamedTextColor.WHITE))
                    .append(Component.text("  →  /omo approve " + callId, NamedTextColor.GREEN))
                    .append(Component.text("  /omo deny " + callId, NamedTextColor.RED))
                    .build();
                // Tell every player in the same room as the agent, else broadcast.
                boolean delivered = false;
                if (n != null) {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (n.home().getWorld().equals(p.getWorld())
                            && p.getLocation().distance(n.home()) <= plugin.getConfig().getInt("display.room_radius", 8) * 2) {
                            p.sendMessage(msg);
                            agents.recordApproval(p, new AgentManager.PendingApproval(callId, agentId, tool, summary));
                            delivered = true;
                        }
                    }
                }
                if (!delivered) {
                    Bukkit.broadcast(msg);
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        agents.recordApproval(p, new AgentManager.PendingApproval(callId, agentId, tool, summary));
                    }
                }
            }
            case "teleport_player" -> handleTeleportPlayer(m);
            case "chat_message" -> handleChatMessage(m);
            case "build_ops" -> handleBuildOps(m);
            // Omo World API — the org builds its own world (see handlers below).
            case "world_build_request" -> handleWorldBuild(m);
            case "world_staff_request" -> handleWorldStaff(m);
            case "open_classroom_request" -> handleOpenClassroomRequest(m);
            // Voice-driven agent ops. The face's Gemini Live tool calls land
            // in runtime/src/http.ts → broadcast a *_request frame → we
            // re-enter the existing /omo command flow as the host player
            // (or console for the player-less ones). dispatchCommand runs
            // synchronously on the main thread, which is exactly where
            // IncomingHandler executes, so it's the cleanest reuse path.
            case "spawn_team_request"   -> handleSpawnTeamRequest(m);
            case "spawn_village_request" -> handleSpawnVillageRequest(m);
            case "spawn_code_request"   -> handleSpawnCodeRequest(m);
            case "spawn_hermes_request" -> handleSpawnHermesRequest(m);
            case "despawn_agent_request" -> handleDespawnAgentRequest(m);
            case "open_terminal_request" -> handleOpenTerminalRequest(m);
            case "ensure_terminal_agent_request" -> handleEnsureTerminalAgentRequest(m);
            case "close_terminal_request" -> handleCloseTerminalRequest(m);
            default -> plugin.getLogger().warning("unknown runtime message: " + type);
        }
    }

    // Voice-loop chat line. The runtime emits one chat_message per:
    //   role="user"   — what the player just said (Gemini Live transcript)
    //   role="omo"    — Omo's spoken reply (Gemini Live output transcript)
    //   role="system" — voice-loop status (loading progress, mic-perm
    //                   errors, dropped-session hints) — purely advisory
    //
    // Render styling per role (so the player can read along + see what
    // the voice loop is doing without alt-tabbing to the face log):
    //   user   → "[you] "   yellow prefix, gray prose
    //   omo    → "[omo] "   aqua prefix, white prose
    //   system → "⋯ "       dark-gray italic, no bracketed label
    private void handleChatMessage(JsonObject m) {
        String role = m.has("role") && !m.get("role").isJsonNull()
            ? m.get("role").getAsString().toLowerCase() : "";
        String text = m.has("text") && !m.get("text").isJsonNull()
            ? m.get("text").getAsString() : "";
        if (text.isEmpty()) return;

        final Component msg;
        if (role.equals("system")) {
            // System status: italic dark-gray with a single ellipsis prefix.
            // Reads as a quiet status whisper, distinct from a real chat line.
            msg = Component.text()
                .append(Component.text("⋯ ", NamedTextColor.DARK_GRAY))
                .append(Component.text(text, NamedTextColor.GRAY)
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, true))
                .build();
        } else {
            final boolean isOmo = role.equals("omo");
            final String prefix = isOmo ? "[omo] " : "[you] ";
            final NamedTextColor prefixColor = isOmo ? NamedTextColor.AQUA : NamedTextColor.YELLOW;
            final NamedTextColor textColor = isOmo ? NamedTextColor.WHITE : NamedTextColor.GRAY;
            msg = Component.text()
                .append(Component.text(prefix, prefixColor))
                .append(Component.text(text, textColor))
                .build();
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) p.sendMessage(msg);
        });
    }

    // Voice-driven teleport from the omo-mc face hologram. The runtime turns
    // a /api/teleport POST into a teleport_player frame; we resolve the named
    // room to a Location and hop onto the main thread to move the player.
    //
    // Resolution order (first match wins):
    //   1. `roomCandidates` (if non-empty) — the curated PRD-correct list
    //                                        from runtime/src/map.ts
    //   2. `room`           — canonical id, only used when no candidates list
    //                         was supplied (legacy callers)
    //
    // Why candidates win over `room`: the canonical id ("hq", "ads", "mail",
    // "code", "task") sometimes exists as a stale leaf entry in rooms.yml
    // from an older world build. Pre-pending the canonical id to
    // the candidate list made those stale entries shadow the PRD-correct
    // districts ("hq-atrium", "ads-command", "comms-hall", "code-lab",
    // "agent-camp"). Trusting the runtime's curated list keeps voice teleports
    // pointed at the current /omo omo build.
    //
    // If `player` is null we teleport whichever player is online (the host).
    // Unknown room names log a clear error and tell the player in chat so
    // the silent-failure path goes away (face/ used to lie about success).
    private void handleTeleportPlayer(JsonObject m) {
        String roomName = m.has("room") && !m.get("room").isJsonNull()
            ? m.get("room").getAsString() : "";
        String targetName = m.has("player") && !m.get("player").isJsonNull()
            ? m.get("player").getAsString() : null;

        // Build the ordered candidate list, deduped. When the runtime supplied
        // a non-empty `roomCandidates` array, that list is authoritative — the
        // canonical `room` field is only used as a log label and as a fallback
        // if every candidate misses (e.g. unbuilt world). When the array is
        // absent or empty (legacy senders), fall back to using `room` as the
        // single candidate.
        Set<String> candidates = new LinkedHashSet<>();
        boolean hasCuratedList = false;
        if (m.has("roomCandidates") && m.get("roomCandidates").isJsonArray()) {
            for (JsonElement el : m.getAsJsonArray("roomCandidates")) {
                if (el == null || el.isJsonNull()) continue;
                String c = el.getAsString();
                if (c != null && !c.isEmpty()) candidates.add(c);
            }
            hasCuratedList = !candidates.isEmpty();
        }
        if (!hasCuratedList && !roomName.isEmpty()) {
            candidates.add(roomName);
        } else if (hasCuratedList && !roomName.isEmpty()) {
            // Keep the canonical id as a last-resort fallback so a build that
            // somehow registered only the canonical id (and none of the
            // candidates) still resolves. It sits at the END now, not the
            // start, so stale entries don't shadow the PRD-correct districts.
            candidates.add(roomName);
        }
        if (candidates.isEmpty()) {
            plugin.getLogger().warning("teleport_player missing room");
            return;
        }

        Room resolved = null;
        String resolvedKey = null;
        for (String c : candidates) {
            Room r = rooms.get(c);
            if (r != null) { resolved = r; resolvedKey = c; break; }
        }
        if (resolved == null) {
            plugin.getLogger().warning("teleport_player: no registered room for '" + roomName
                + "' (tried: " + String.join(", ", candidates) + ")");
            // Tell the host in chat so the face/voice flow doesn't lie.
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.sendMessage(Component.text()
                        .append(Component.text("[omo] ", NamedTextColor.AQUA))
                        .append(Component.text("can't teleport — '" + roomName + "' isn't built yet.", NamedTextColor.RED))
                        .build());
                    break;
                }
            });
            return;
        }
        final Room r = resolved;
        final String resolvedRoom = resolvedKey;

        World w = Bukkit.getWorld(r.worldName());
        if (w == null) {
            plugin.getLogger().warning("teleport_player: world '" + r.worldName() + "' not loaded");
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            Player target = null;
            if (targetName != null) {
                target = Bukkit.getPlayerExact(targetName);
            } else {
                // No name specified — pick the first online player (the host
                // running ./agentcraft locally). Falls back to nothing.
                for (Player p : Bukkit.getOnlinePlayers()) { target = p; break; }
            }
            if (target == null) {
                plugin.getLogger().warning("teleport_player: no online player to move to '" + resolvedRoom + "'");
                return;
            }
            final Player player = target;

            // Find a safe Y above the registered pad. The pad may have been
            // built on, buried by terrain, or have an item-frame sitting on
            // top — scan up to 3 blocks above the stored y for the first
            // pair of passable blocks (head + feet) so we don't suffocate.
            int blockX = (int) Math.floor(r.x());
            int blockZ = (int) Math.floor(r.z());
            int baseY = (int) Math.floor(r.y());
            int safe = baseY + 1; // sane default — one above the pad
            for (int dy = 1; dy <= 3; dy++) {
                int feet = baseY + dy;
                if (feet >= w.getMaxHeight() - 1) break;
                if (w.getBlockAt(blockX, feet, blockZ).isPassable()
                    && w.getBlockAt(blockX, feet + 1, blockZ).isPassable()) {
                    safe = feet;
                    break;
                }
            }
            final int safeY = safe;
            final Location dest = new Location(w, blockX + 0.5, safeY, blockZ + 0.5,
                player.getLocation().getYaw(), player.getLocation().getPitch());

            // Pre-load the destination chunk so the teleport doesn't drop
            // the player into ungenerated terrain on first visit.
            w.getChunkAtAsync(dest).thenAccept(chunk -> {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    // Kill momentum first so we don't slide off the pad on arrival.
                    player.setVelocity(new Vector(0, 0, 0));
                    player.setFallDistance(0f);
                    boolean ok = player.teleport(dest, PlayerTeleportEvent.TeleportCause.PLUGIN);
                    if (!ok) {
                        plugin.getLogger().warning("teleport_player: refused for '" + resolvedRoom + "'");
                        player.sendMessage(Component.text("[omo] teleport refused", NamedTextColor.RED));
                        return;
                    }
                    player.sendMessage(Component.text()
                        .append(Component.text("[omo] ", NamedTextColor.AQUA))
                        .append(Component.text("teleported to ", NamedTextColor.WHITE))
                        .append(Component.text(resolvedRoom, NamedTextColor.GOLD))
                        .build());
                });
            });
        });
    }

    // ─── Live build ─────────────────────────────────────────────────────
    // The runtime's Claude brain calls a `build` tool that streams build-DSL
    // ops here as a build_ops frame. We look up the named agent's plot, clear
    // it first if asked, then enqueue the ops — BuildPlot drains them onto the
    // plot ~30 blocks/tick. onMessage already runs on the main thread (see
    // BridgeClient.onText), so the synchronous clear + enqueue are safe here.
    //
    //   { type:"build_ops", agentId:string, clearFirst:bool, ops:[BuildOp...] }
    private void handleBuildOps(JsonObject m) {
        String agentId = jsonString(m, "agentId", "");
        if (agentId.isEmpty()) {
            plugin.getLogger().warning("build_ops missing agentId");
            return;
        }
        boolean clearFirst = m.has("clearFirst") && !m.get("clearFirst").isJsonNull()
            && m.get("clearFirst").getAsBoolean();
        JsonArray ops = (m.has("ops") && m.get("ops").isJsonArray())
            ? m.getAsJsonArray("ops") : new JsonArray();

        BuildPlot plot = plugin.buildPlots().forAgent(agentId);
        if (plot == null) {
            plugin.getLogger().warning("build_ops: no build plot for agent '" + agentId
                + "' (run /omo buildstudio first)");
            return;
        }
        if (clearFirst) plot.clearNow();
        plot.enqueueOps(ops, plugin);
        aiBuilt.add(agentId);   // a building was delivered for this room — gates the world-build fallback pod
    }

    // ── Omo World API — the organisation builds its own world ────────────
    // The omo-tools MCP server (runtime) broadcasts these two frames when the
    // Chief of Staff (Gemini, over MCP) extends the org. The runtime knows only
    // room NAMES, so the plugin owns the geometry: we resolve the HQ anchor's
    // world position, fan each new function out onto a ring around it (by
    // index), raise a futuristic "alien pod" wing there live, then seat the
    // specialist villager inside it and ask the runtime to start its brain.

    // Village street: a path runs −z out of HQ; functions line both sides of it,
    // alternating left/right and advancing down the street as they're added.
    private static final int PATH_START = 26;                         // HQ centre → first building row (−z)
    private static final int ROW_SPACING = 18;                        // spacing between building rows down the street
    private static final int SIDE_OFFSET = 12;                        // street centre → a building's centre (±x)
    private static final int WING_W = 18, WING_H = 16, WING_D = 18;   // build plot — a cosy room, not a monument
    private static final int WING_CX = 9, WING_CZ = 9, WING_R = 6;    // structure centre + fallback-pod radius (local)
    private static final int WING_ISLAND_R = 10;                      // grass platform radius (world coords)
    /** Function rooms whose Gemini-designed building has arrived — gates the fallback pod. */
    private final Set<String> aiBuilt = ConcurrentHashMap.newKeySet();
    // Thinking-glow: function islands brighten while their agent works.
    private final Map<String, Location> wingCenters = new ConcurrentHashMap<>();
    private final Map<String, Boolean> wingGlowOn = new ConcurrentHashMap<>();

    /** World position of the pod centre for the function at `index` around `anchorRoom`. */
    private Location wingCenter(JsonObject m) {
        String anchorRoom = jsonString(m, "anchorRoom", "hq");
        int index = m.has("index") && !m.get("index").isJsonNull() ? m.get("index").getAsInt() : 0;
        Room anchor = plugin.rooms().get(anchorRoom);
        if (anchor == null) return null;
        World w = Bukkit.getWorld(anchor.worldName());
        if (w == null) w = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        if (w == null) return null;
        // Lay functions along a street running −z from HQ: even indices on the
        // −x (left) side, odd on the +x (right), advancing one row per pair.
        int row = index / 2;
        int side = (index % 2 == 0) ? -1 : 1;
        int dist = PATH_START + row * ROW_SPACING;
        return new Location(w,
            (int) Math.floor(anchor.x()) + side * SIDE_OFFSET,
            (int) Math.floor(anchor.y()),
            (int) Math.floor(anchor.z()) - dist);
    }

    /**
     * Thinking-glow: when a staffed function's agent is working its island rim
     * lights up bright (SEA_LANTERN); when idle it returns to a calm tint. Cheap —
     * a 16-marker ring on the island rim, repainted only when working↔idle flips.
     */
    private void updateWingGlow(String agentId, String status) {
        Location centre = wingCenters.get(agentId);
        if (centre == null) return;
        final boolean working = status != null
            && (status.equals("thinking") || status.equals("tool_call") || status.equals("speaking"));
        final Boolean prev = wingGlowOn.get(agentId);
        if (prev != null && prev == working) return;
        wingGlowOn.put(agentId, working);
        final World w = centre.getWorld();
        if (w == null) return;
        final int cx = centre.getBlockX(), cz = centre.getBlockZ(), ry = centre.getBlockY() - 1;
        final Material mat = working ? Material.SEA_LANTERN : Material.LIGHT_BLUE_STAINED_GLASS;
        Bukkit.getScheduler().runTask(plugin, () -> {
            final int r = WING_ISLAND_R - 1;
            for (int s = 0; s < 16; s++) {
                double a = s * (Math.PI / 8.0);
                int x = cx + (int) Math.round(Math.cos(a) * r);
                int z = cz + (int) Math.round(Math.sin(a) * r);
                w.getBlockAt(x, ry, z).setType(mat, false);
            }
        });
    }

    private void handleWorldBuild(JsonObject m) {
        String room = jsonString(m, "room", "");
        String role = jsonString(m, "role", "function");
        if (room.isEmpty()) { plugin.getLogger().warning("world_build_request: no room"); return; }
        Location centre = wingCenter(m);
        if (centre == null) {
            plugin.getLogger().warning("world_build_request: no HQ anchor — run /omo hq first");
            return;
        }
        final String anchorRoom = jsonString(m, "anchorRoom", "hq");
        final JsonArray ops = wingOps(role);
        Bukkit.getScheduler().runTask(plugin, () -> {
            World w = centre.getWorld();
            buildWingPlatform(w, centre);   // grass platform beside the street; the building rises on it
            int ox = centre.getBlockX() - WING_CX, oy = centre.getBlockY() - 1, oz = centre.getBlockZ() - WING_CZ;
            BuildPlot plot = plugin.buildPlots().forAgent(room);
            if (plot == null) {
                plot = new BuildPlot(w, ox, oy, oz, WING_W, WING_H, WING_D, Material.SMOOTH_QUARTZ, plugin, room);
                plugin.buildPlots().register(room, plot);
            }
            plot.clearNow();
            final BuildPlot fplot = plot;
            // The runtime's Gemini architect streams the REAL building (build_ops,
            // clearFirst) ~13s later. Only fall back to the fixed pod if it never
            // arrives — so there's no "cube then rebuild" double-build flicker.
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!aiBuilt.contains(room)) { fplot.clearNow(); fplot.enqueueOps(ops, plugin); }
            }, 360L);
            // Extend the village street from HQ to this building + a spur to its door.
            // (The naming sign is planted in handleWorldStaff AFTER the building lands,
            // so the AI build's clear-first never wipes it.)
            Room anchor = plugin.rooms().get(anchorRoom);
            if (anchor != null) buildVillagePath(w, anchor, centre);
        });
    }

    /**
     * Build/extend the village street: a 5-wide glowing walkway running −z from
     * the HQ island edge to this building's row (never through HQ), the air above
     * it cleared so nothing blocks it, plus a spur to this building's −z door —
     * all at one walk level so the platforms connect into one village.
     */
    private void buildVillagePath(World w, Room anchor, Location centre) {
        final int hx = (int) Math.floor(anchor.x()), hz = (int) Math.floor(anchor.z());
        final int wy = centre.getBlockY();                 // walk level (deck top)
        final int cx = centre.getBlockX(), cz = centre.getBlockZ();
        final int startZ = hz - 19;                        // meets the HQ processional at the island rim
        for (int z = startZ; z >= cz - 1; z--) {
            for (int dx = -2; dx <= 2; dx++) {
                int x = hx + dx;
                w.getBlockAt(x, wy - 1, z).setType(Math.abs(dx) == 2 ? Material.DARK_PRISMARINE : Material.SMOOTH_QUARTZ, false);
                for (int dy = 0; dy <= 2; dy++) w.getBlockAt(x, wy + dy, z).setType(Material.AIR, false);
            }
            if ((startZ - z) % 5 == 0) {                    // lamp posts down the street
                w.getBlockAt(hx - 2, wy, z).setType(Material.SEA_LANTERN, false);
                w.getBlockAt(hx + 2, wy, z).setType(Material.SEA_LANTERN, false);
            }
        }
        // Spur: connect the street to this building's −z door front.
        final int side = Integer.signum(cx - hx);
        final int doorZ = cz - WING_R;
        final int xa = hx + side * 2, xb = cx;
        for (int x = Math.min(xa, xb); x <= Math.max(xa, xb); x++) {
            for (int zz = doorZ; zz <= cz; zz++) {
                w.getBlockAt(x, wy - 1, zz).setType(Material.SMOOTH_QUARTZ, false);
                for (int dy = 0; dy <= 2; dy++) w.getBlockAt(x, wy + dy, zz).setType(Material.AIR, false);
            }
        }
    }

    /** A small grass platform beside the street, under a function's building — direct placement so it survives the AI build's clear-first. */
    private void buildWingPlatform(World w, Location centre) {
        int cx = centre.getBlockX(), cz = centre.getBlockZ(), fy = centre.getBlockY() - 1;
        int r = WING_ISLAND_R, r2 = r * r;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 > r2) continue;
                int x = cx + dx, z = cz + dz;
                w.getBlockAt(x, fy, z).setType(Material.GRASS_BLOCK, false);
                w.getBlockAt(x, fy - 1, z).setType(Material.DIRT, false);
                double t = 1.0 - (double) d2 / r2;
                int depth = (int) Math.round(6 * Math.sqrt(Math.max(0, t)));
                for (int y = fy - 2; y >= fy - 2 - depth; y--) {
                    w.getBlockAt(x, y, z).setType(y <= fy - 1 - depth ? Material.DEEPSLATE : Material.STONE, false);
                }
            }
        }
    }

    private void handleWorldStaff(JsonObject m) {
        String agentId = jsonString(m, "agentId", "");
        String role = jsonString(m, "role", "Specialist");
        String room = jsonString(m, "room", "");
        // Optional back-wall screen URL. A school wing passes the live /whiteboard
        // so the tutor's lesson slides show; specialists leave it blank → /dash board.
        final String screenUrl = jsonString(m, "screenUrl", "");
        if (agentId.isEmpty() || room.isEmpty()) {
            plugin.getLogger().warning("world_staff_request: missing agentId/room");
            return;
        }
        Location centre = wingCenter(m);
        if (centre == null) { plugin.getLogger().warning("world_staff_request: no HQ anchor"); return; }
        wingCenters.put(agentId, centre.clone());   // for the thinking-glow ring
        final World w = centre.getWorld();
        // HQ anchor X — the street runs to/from here; the naming signpost sits on
        // the street kerb (outside this wing's build plot) so clear-first can't wipe it.
        Room signAnchor = plugin.rooms().get(jsonString(m, "anchorRoom", "hq"));
        final int anchorX = signAnchor != null ? (int) Math.floor(signAnchor.x()) : centre.getBlockX();
        // The agent stands just in front of its lectern, facing the −z door.
        final Location home = new Location(w,
            centre.getBlockX() + 0.5, centre.getBlockY(), centre.getBlockZ() - 1.5, 180f, 0f);
        if (plugin.rooms().get(room) == null) plugin.rooms().define(room, home, 8);
        // Start the brain NOW so world_assign reaches it; render the villager +
        // screen AFTER the Gemini building lands (~15s) so it's never buried.
        JsonObject spawn = new JsonObject();
        spawn.addProperty("type", "spawn_agent");
        spawn.addProperty("agentId", agentId);
        spawn.addProperty("role", role);
        spawn.addProperty("room", room);
        spawn.addProperty("playerName", "owner");
        JsonObject vec = new JsonObject();
        vec.addProperty("x", home.getX());
        vec.addProperty("y", home.getY());
        vec.addProperty("z", home.getZ());
        spawn.add("home", vec);
        plugin.bridge().send(spawn);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // Guarantee a clear, floored spot so the specialist is never buried by
            // its own building, then seat it — NON-stationary so it roams the room
            // and walks back to its desk when you give it a task.
            int bx = centre.getBlockX(), bz = centre.getBlockZ(), wy = centre.getBlockY();
            for (int dx = -1; dx <= 1; dx++) for (int dz = -2; dz <= 1; dz++) {
                w.getBlockAt(bx + dx, wy - 1, bz + dz).setType(Material.SMOOTH_QUARTZ, false);
                for (int dy = 0; dy <= 2; dy++) w.getBlockAt(bx + dx, wy + dy, bz + dz).setType(Material.AIR, false);
            }
            agents.spawn(agentId, role, room, home, false);   // false = roams; returns to its desk when busy
            // The screen mounts flush on the back wall: CinemaScreen sets its own
            // bezel blocks and clears only the thin frame layer it needs, so the
            // display is visible WITHOUT carving an alcove out of the building.
            buildWingDashboard(centre, room, screenUrl);
            placeWingSign(w, centre, anchorX, role);   // name the building at its street turn-off
        }, 300L);
    }

    /**
     * A standing signpost at this wing's street turn-off, naming the building (its
     * role) and facing back toward HQ so you read it as you walk down the street and
     * know where to turn. Sits on the street kerb (x = hx ± 2), which is OUTSIDE the
     * wing's build plot, so neither the AI clear-first nor the fallback-pod clear
     * can wipe it.
     */
    private void placeWingSign(World w, Location centre, int hx, String role) {
        final int cx = centre.getBlockX(), cz = centre.getBlockZ(), wy = centre.getBlockY();
        final int side = Integer.signum(cx - hx);
        final int sx = hx + (side == 0 ? 3 : side * 2);   // the street kerb on this wing's side
        final int sz = cz + 1;                            // one row toward HQ from the spur mouth
        w.getBlockAt(sx, wy - 1, sz).setType(Material.SMOOTH_QUARTZ, false);   // footing
        w.getBlockAt(sx, wy, sz).setType(Material.SMOOTH_QUARTZ, false);       // post
        org.bukkit.block.Block sb = w.getBlockAt(sx, wy + 1, sz);
        sb.setType(Material.OAK_SIGN, false);
        org.bukkit.block.data.BlockData bd = sb.getBlockData();
        if (bd instanceof org.bukkit.block.data.Rotatable rot) {
            rot.setRotation(BlockFace.SOUTH);             // face +z, toward the player coming from HQ
            sb.setBlockData(bd, false);
        }
        if (sb.getState() instanceof org.bukkit.block.Sign sign) {
            sign.getSide(org.bukkit.block.sign.Side.FRONT).line(1,
                    net.kyori.adventure.text.Component.text(prettyRole(role)));
            sign.getSide(org.bukkit.block.sign.Side.FRONT).line(2,
                    net.kyori.adventure.text.Component.text("▸ this way"));
            sign.update();
        }
    }

    /** A short, title-cased building name for a sign line (signs fit ~15 chars):
     *  "payments / finance" → "Payments", "growth" → "Growth". */
    private static String prettyRole(String role) {
        if (role == null || role.isBlank()) return "Function";
        String r = role.trim();
        int cut = r.length();
        for (int i = 0; i < r.length(); i++) {
            char c = r.charAt(i);
            if (c == '/' || c == ' ' || c == ',') { cut = i; break; }
        }
        r = r.substring(0, cut).trim();
        if (r.isEmpty()) return "Function";
        r = Character.toUpperCase(r.charAt(0)) + r.substring(1);
        return r.length() > 15 ? r.substring(0, 15) : r;
    }

    // A small futuristic "alien pod" wing, in LOCAL plot coords (pod centre at
    // WING_CX,WING_CZ, radius WING_R). Quartz floor, glass drum + dome, a glowing
    // sea-lantern crown, a desk + lectern workstation, and a carved lit doorway.
    private JsonArray wingOps(String role) {
        // Fallback pod (used only if the runtime's AI architect doesn't stream a
        // custom building). The floating island it sits on is placed directly in
        // handleWorldBuild so it survives the AI build's clear-first.
        JsonArray ops = new JsonArray();
        ops.add(clearOp());
        ops.add(cyl(WING_CX, WING_CZ, 0, WING_R, 1, "smooth_quartz", false));        // floor disc
        ops.add(cyl(WING_CX, WING_CZ, 1, WING_R, 5, "cyan_stained_glass", true));    // glass wall
        ops.add(cyl(WING_CX, WING_CZ, 6, WING_R, 1, "sea_lantern", true));           // glowing crown ring
        ops.add(sphereOp(WING_CX, 6, WING_CZ, WING_R, "cyan_stained_glass", true));  // dome cap
        ops.add(boxOp(WING_CX - 2, 1, WING_CZ + 5, WING_CX + 2, 1, WING_CZ + 5, "quartz_pillar")); // desk
        ops.add(setOp(WING_CX, 1, WING_CZ + 4, "lectern"));                          // workstation lectern
        ops.add(boxOp(WING_CX - 1, 1, WING_CZ - WING_R, WING_CX + 1, 3, WING_CZ - WING_R, "air")); // doorway
        return ops;
    }

    private static JsonObject clearOp() {
        JsonObject o = new JsonObject(); o.addProperty("op", "clear"); return o;
    }
    private static JsonObject setOp(int x, int y, int z, String mat) {
        JsonObject o = new JsonObject();
        o.addProperty("op", "set"); o.addProperty("x", x); o.addProperty("y", y); o.addProperty("z", z);
        o.addProperty("material", mat); return o;
    }
    private static JsonObject boxOp(int x1, int y1, int z1, int x2, int y2, int z2, String mat) {
        JsonObject o = new JsonObject();
        o.addProperty("op", "box");
        o.addProperty("x1", x1); o.addProperty("y1", y1); o.addProperty("z1", z1);
        o.addProperty("x2", x2); o.addProperty("y2", y2); o.addProperty("z2", z2);
        o.addProperty("material", mat); return o;
    }
    private static JsonObject cyl(int cx, int cz, int y, int r, int h, String mat, boolean hollow) {
        JsonObject o = new JsonObject();
        o.addProperty("op", "cylinder");
        o.addProperty("cx", cx); o.addProperty("cz", cz); o.addProperty("y", y);
        o.addProperty("radius", r); o.addProperty("height", h); o.addProperty("material", mat);
        if (hollow) o.addProperty("hollow", true);
        return o;
    }
    private static JsonObject sphereOp(int cx, int cy, int cz, int r, String mat, boolean hollow) {
        JsonObject o = new JsonObject();
        o.addProperty("op", "sphere");
        o.addProperty("cx", cx); o.addProperty("cy", cy); o.addProperty("cz", cz);
        o.addProperty("radius", r); o.addProperty("material", mat);
        if (hollow) o.addProperty("hollow", true);
        return o;
    }

    /**
     * Raise a big live dashboard screen on the BACK wall of the room (+z), facing
     * the −z entrance — the focal "main wall" you see as you walk in, behind the
     * agent. Points at the function's own board (/dash/&lt;room&gt;).
     */
    private void buildWingDashboard(Location centre, String room, String screenUrl) {
        if (plugin.cinema() == null || centre.getWorld() == null) return;
        final int cols = 6, rows = 4;   // big — the main wall of the room
        Location topLeftWall = new Location(centre.getWorld(),
                centre.getBlockX() - (cols / 2), centre.getBlockY() + rows, centre.getBlockZ() + (WING_R - 1));
        final String id = room;   // one cinema channel per function wing
        CinemaFrameStore store = plugin.cinema().ensure(id);
        CinemaScreen.Result r = CinemaScreen.build(
                topLeftWall, BlockFace.NORTH, cols, rows, Material.POLISHED_BLACKSTONE, store);
        plugin.cinema().registerScreen(r.geometry());
        // A school wing overrides this with the live /whiteboard; specialists use
        // their own /dash board.
        final String url = (screenUrl != null && !screenUrl.isEmpty())
                ? screenUrl
                : "http://127.0.0.1:8088/dash/" + room;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> plugin.cinema().setUrl(id, url));
    }

    // ─── Voice-driven agent-ops bridges ─────────────────────────────────
    // All six helpers below reuse the existing /omo command paths via
    // Bukkit.dispatchCommand so we have one source of truth for spawn /
    // despawn semantics. The face/runtime side just decides what to call;
    // the plugin keeps its established validation and side effects.

    private Player resolveTargetPlayer(JsonObject m) {
        String name = m.has("playerName") && !m.get("playerName").isJsonNull()
            ? m.get("playerName").getAsString() : null;
        if (name != null && !name.isEmpty()) {
            Player p = Bukkit.getPlayerExact(name);
            if (p != null) return p;
        }
        for (Player p : Bukkit.getOnlinePlayers()) return p;
        return null;
    }

    private void handleSpawnTeamRequest(JsonObject m) {
        String cwd = m.has("cwd") && !m.get("cwd").isJsonNull()
            ? m.get("cwd").getAsString() : "";
        // team-up runs fine from the console — handleTeamUp takes CommandSender.
        String cmd = "omo team-up" + (cwd.isEmpty() ? "" : " " + cwd);
        Bukkit.getScheduler().runTask(plugin,
            () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));
    }

    private void handleSpawnVillageRequest(JsonObject m) {
        // village-up requires a Player (uses p.getLocation/getName). Pick the
        // host or the named player; if no one's online, log + drop.
        Player p = resolveTargetPlayer(m);
        if (p == null) {
            plugin.getLogger().warning("spawn_village_request: no online player to act as");
            return;
        }
        Bukkit.getScheduler().runTask(plugin,
            () -> Bukkit.dispatchCommand(p, "omo village-up"));
    }

    private void handleSpawnCodeRequest(JsonObject m) {
        String agentId = m.get("agentId").getAsString();
        String cwd     = m.get("cwd").getAsString();
        String task    = m.get("task").getAsString();
        Player p = resolveTargetPlayer(m);
        if (p == null) {
            plugin.getLogger().warning("spawn_code_request: no online player to act as");
            return;
        }
        String cmd = "omo spawn-code " + agentId + " " + cwd + " " + task;
        Bukkit.getScheduler().runTask(plugin,
            () -> Bukkit.dispatchCommand(p, cmd));
    }

    // A Hermes host villager started a new Hermes world via its start_hermes_world
    // tool. Mirror of handleSpawnCodeRequest: re-enter the live /omo spawn path
    // as the host player so room-kind → brain selection works unchanged.
    //   { type:"spawn_hermes_request", agentId:string, role:string, playerName?:string|null }
    private void handleSpawnHermesRequest(JsonObject m) {
        String agentId = m.get("agentId").getAsString();
        String role    = m.has("role") && !m.get("role").isJsonNull()
            ? m.get("role").getAsString() : "assistant";
        Player p = resolveTargetPlayer(m);
        if (p == null) {
            plugin.getLogger().warning("spawn_hermes_request: no online player to act as");
            return;
        }
        String cmd = "omo spawn " + agentId + " " + role;
        Bukkit.getScheduler().runTask(plugin,
            () -> Bukkit.dispatchCommand(p, cmd));
    }

    // The Dean's open_classroom tool re-themes the single classroom for a
    // subject. The runtime relays it here as an open_classroom_request; we
    // resolve the target player and re-enter the /omo classroom flow, exactly
    // like handleSpawnCodeRequest reuses /omo spawn-code.
    //   { type:"open_classroom_request", subject:string, playerName?:string|null }
    private void handleOpenClassroomRequest(JsonObject m) {
        String subject = jsonString(m, "subject", "").trim();
        if (subject.isEmpty()) subject = "Algebra";
        Player p = resolveTargetPlayer(m);
        if (p == null) {
            plugin.getLogger().warning("open_classroom_request: no online player to act as");
            return;
        }
        final String cmd = "omo classroom " + subject;
        Bukkit.getScheduler().runTask(plugin,
            () -> Bukkit.dispatchCommand(p, cmd));
    }

    private void handleDespawnAgentRequest(JsonObject m) {
        String agentId = m.get("agentId").getAsString();
        terminalScreens.remove(agentId);
        Bukkit.getScheduler().runTask(plugin,
            () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "omo despawn " + agentId));
    }

    private void handleOpenTerminalRequest(JsonObject m) {
        // The client-side terminal mod auto-opens whenever it sees the
        // §§ACT-TERMINAL§§ sentinel in chat. Emit that line to the host.
        // A no-arg request is resolved in-world: standing in the Code box opens
        // claude; standing in the Hermes box opens hermes.
        String agentId = m.has("agentId") && !m.get("agentId").isJsonNull()
            ? m.get("agentId").getAsString() : "";
        Player p = resolveTargetPlayer(m);
        if (p == null) {
            plugin.getLogger().warning("open_terminal_request: no online player");
            return;
        }
        if (agentId == null || agentId.isBlank()) {
            TerminalBox box = terminalBoxForPlayer(p);
            if (box != null) {
                ensureTerminalBoxAgent(p, box);
                agentId = box.agentId();
            } else if (agents.get("claude") != null) {
                agentId = "claude";
            } else if (agents.get("hermes") != null) {
                agentId = "hermes";
            }
        }
        final Component sentinel = Component.text(
            "§§ACT-TERMINAL§§ " + agentId);
        Bukkit.getScheduler().runTask(plugin, () -> p.sendMessage(sentinel));
    }

    private void handleEnsureTerminalAgentRequest(JsonObject m) {
        String agentId = jsonString(m, "agentId", "");
        String room = jsonString(m, "room", "");
        TerminalBox base = terminalBoxForRoom(room);
        if (base == null && agentId.equalsIgnoreCase("claude")) {
            base = terminalBoxForRoom(MvpWorldBuilder.CODE_ROOM);
        } else if (base == null && agentId.equalsIgnoreCase("hermes")) {
            base = terminalBoxForRoom(MvpWorldBuilder.HERMES_ROOM);
        }
        if (base == null) {
            plugin.getLogger().warning("ensure_terminal_agent_request: unknown terminal agent '"
                + agentId + "' in room '" + room + "'");
            return;
        }

        TerminalBox box = new TerminalBox(
            base.room(),
            base.agentId(),
            jsonString(m, "role", base.role()),
            jsonString(m, "launch", base.launch()),
            jsonString(m, "cwd", base.cwd()));
        AgentNpc n = ensureVisibleTerminalBoxAgent(resolveTargetPlayer(m), box);
        if (n == null) return;

        String launch = box.launch().isBlank() ? "shell" : box.launch();
        pushTerminalScreen(box.agentId(), new ScreenEntry("system",
            "terminal ready: " + launch));
    }

    private record TerminalBox(String room, String agentId, String role, String launch, String cwd) {}

    private TerminalBox terminalBoxForPlayer(Player p) {
        String roomName = rooms.currentRoom(p);
        TerminalBox box = terminalBoxForRoom(roomName);
        if (box != null) return box;

        Room here = rooms.roomAt(p.getLocation());
        if (here != null) {
            box = terminalBoxForRoom(here.name());
            if (box != null) return box;
        }

        // Movement room state can be stale for one tick after teleporting.
        // Fall back to the nearest registered terminal room (any code/code-N
        // workstation or the Hermes booth) in the same world.
        double bestSq = 12.0 * 12.0;
        TerminalBox best = null;
        for (Room r : rooms.all()) {
            String name = r.name();
            if (!MvpWorldBuilder.isCodeRoom(name) && !name.equalsIgnoreCase(MvpWorldBuilder.HERMES_ROOM)) continue;
            if (p.getWorld() == null || !r.worldName().equals(p.getWorld().getName())) continue;
            double dx = r.x() - p.getLocation().getX();
            double dy = r.y() - p.getLocation().getY();
            double dz = r.z() - p.getLocation().getZ();
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 < bestSq) {
                bestSq = d2;
                best = terminalBoxForRoom(name);
            }
        }
        return best;
    }

    private TerminalBox terminalBoxForRoom(String room) {
        if (room == null) return null;
        // Working dir resolved with per-room overrides (see MvpWorldBuilder#terminalCwd).
        String cwd = MvpWorldBuilder.terminalCwd(plugin.getConfig(), room);
        // Any coding workstation (code / code-N) → its own claude PTY; the agent
        // id matches what the plate listener spawns (MvpWorldBuilder#terminalAgentId).
        if (MvpWorldBuilder.isCodeRoom(room)) {
            return new TerminalBox(room, MvpWorldBuilder.terminalAgentId(room), "Claude Code engineer",
                    cfgLaunch("terminal.code_launch", "claude"), cwd);
        }
        if (room.equalsIgnoreCase(MvpWorldBuilder.HERMES_ROOM)) {
            return new TerminalBox(MvpWorldBuilder.HERMES_ROOM, MvpWorldBuilder.terminalAgentId(room),
                    "Hermes agent",
                    cfgLaunch("terminal.hermes_launch", "hermes chat"), cwd);
        }
        return null;
    }

    private AgentNpc ensureVisibleTerminalBoxAgent(Player player, TerminalBox box) {
        AgentNpc existing = agents.get(box.agentId());
        if (existing != null) return existing;
        Room r = rooms.get(box.room());
        if (r == null) return null;
        World w = plugin.getServer().getWorld(r.worldName());
        if (w == null && player != null) w = player.getWorld();
        if (w == null) return null;
        // Face north (toward the dev wall / plaza) — matches TerminalPlateListener.
        Location home = new Location(w, r.x(), r.y(), r.z(), 180f, 0f);

        return agents.spawn(box.agentId(), box.role(), box.room(), home);
    }

    private void ensureTerminalBoxAgent(Player player, TerminalBox box) {
        AgentNpc n = ensureVisibleTerminalBoxAgent(player, box);
        if (n == null) return;

        JsonObject spawn = new JsonObject();
        spawn.addProperty("type", "spawn_agent");
        spawn.addProperty("agentId", box.agentId());
        spawn.addProperty("role", box.role());
        spawn.addProperty("room", box.room());
        spawn.addProperty("playerName", player.getName());
        spawn.addProperty("cwd", box.cwd());
        spawn.addProperty("launch", box.launch());
        Location home = n.home();
        JsonObject vec = new JsonObject();
        vec.addProperty("x", home.getX());
        vec.addProperty("y", home.getY());
        vec.addProperty("z", home.getZ());
        spawn.add("home", vec);
        plugin.bridge().send(spawn);
    }

    private void pushTerminalScreen(String agentId, ScreenEntry entry) {
        AgentNpc n = agents.get(agentId);
        if (n == null) return;
        List<ScreenEntry> entries = terminalScreens.computeIfAbsent(agentId, k -> new ArrayList<>());
        entries.add(entry);
        int max = Math.max(1, plugin.getConfig().getInt("display.screen_lines", 10));
        while (entries.size() > max) entries.remove(0);
        n.setScreen(List.copyOf(entries));
        n.appendTranscript(entry, false);
    }

    private static boolean isTerminalBoxAgent(String agentId) {
        // PTY terminal agents render their board from agent_log lines (they don't
        // emit agent_screen_update). The legacy single boxes use ids claude/hermes;
        // each numbered workstation uses its room name as its id (code-1..N), so
        // include those too or their boards would stay blank.
        return agentId != null
            && (agentId.equalsIgnoreCase("claude") || agentId.equalsIgnoreCase("hermes")
                || MvpWorldBuilder.isCodeRoom(agentId));
    }

    private static String kindForLogLevel(String level) {
        if (level == null) return "system";
        return switch (level.toLowerCase()) {
            case "error" -> "error";
            case "tool" -> "tool";
            case "warn" -> "result";
            default -> "system";
        };
    }

    private static String jsonString(JsonObject obj, String key, String fallback) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return fallback;
        return obj.get(key).getAsString();
    }

    private String cfgLaunch(String key, String fallback) {
        String v = plugin.getConfig().getString(key);
        if (v == null || v.isBlank()) return fallback;
        String trimmed = v.trim();
        if (trimmed.equalsIgnoreCase("shell")
                || trimmed.equalsIgnoreCase("none")
                || trimmed.equalsIgnoreCase("off")) {
            return "";
        }
        return v;
    }

    private void handleCloseTerminalRequest(JsonObject m) {
        // Mirror of the open path — the client mod listens for this second
        // sentinel and dismisses whichever TerminalScreen / TeamTerminalScreen
        // is current. Players without the mod simply never see it.
        Player p = resolveTargetPlayer(m);
        if (p == null) {
            plugin.getLogger().warning("close_terminal_request: no online player");
            return;
        }
        final Component sentinel = Component.text("§§ACT-CLOSE-TERMINAL§§");
        Bukkit.getScheduler().runTask(plugin, () -> p.sendMessage(sentinel));
    }

    private static List<ScreenEntry> parseEntries(JsonArray arr) {
        List<ScreenEntry> out = new ArrayList<>();
        if (arr == null) return out;
        for (JsonElement e : arr) {
            if (!e.isJsonObject()) continue;
            JsonObject obj = e.getAsJsonObject();
            String kind = obj.has("kind") && !obj.get("kind").isJsonNull()
                ? obj.get("kind").getAsString() : "system";
            String text = obj.has("text") && !obj.get("text").isJsonNull()
                ? obj.get("text").getAsString() : "";
            out.add(new ScreenEntry(kind, text));
        }
        return out;
    }
}

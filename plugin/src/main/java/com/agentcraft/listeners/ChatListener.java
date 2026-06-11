package com.agentcraft.listeners;

import com.agentcraft.AgentCraftPlugin;
import com.agentcraft.agents.AgentManager;
import com.agentcraft.agents.AgentNpc;
import com.agentcraft.bridge.BridgeClient;
import com.agentcraft.rooms.RoomManager;
import com.google.gson.JsonObject;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.util.Vector;

/**
 * Routes player chat to the nearest AI agent so the player can talk to a
 * villager by just typing — no /hermes prefix. Prefix with "!" to force a
 * normal world broadcast.
 *
 * <p>Targeting is GAZE-FIRST: whatever agent you're pointing at (within a
 * narrow cone, see {@link #agentUnderGaze}) hears you — so from the Observatory
 * throne you sit still, look at an agent, and type. If you aren't pointing at
 * anyone it falls back to the agent in your room, then the nearest one.
 *
 * <p>If the routed agent's room has a terminal (the code / hermes box), a
 * hidden {@code §§ACT-TERMINAL§§} sentinel is sent so the client-side
 * terminal mod opens that agent's terminal — chatting and watching it work
 * happen together.
 */
public class ChatListener implements Listener {

    /** Gaze targeting: an agent within this range + cone of your crosshair is "pointed at". */
    private static final double GAZE_RANGE = 28.0;
    private static final double GAZE_MIN_DOT = 0.92;            // cos(~23°) cone
    private static final Vector GAZE_AIM = new Vector(0, 1.0, 0); // aim at the torso, not the feet

    private final AgentCraftPlugin plugin;
    private final AgentManager agents;
    private final BridgeClient bridge;
    private final RoomManager rooms;

    public ChatListener(AgentCraftPlugin plugin, AgentManager agents, BridgeClient bridge, RoomManager rooms) {
        this.plugin = plugin;
        this.agents = agents;
        this.bridge = bridge;
        this.rooms = rooms;
        // Live "you're pointing at X" hint on the action bar, so you know who'll
        // hear you before you type. Cheap: a few players × a handful of agents.
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickGazeHint, 20L, 5L);
    }

    // ignoreCancelled: the cinema listener (LOWEST) may already have consumed
    // this line (type-mode, or "aim at a screen + type a URL"); don't also route
    // it to an agent.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        if (text.isBlank()) return;

        // Opt-out: prefix with "!" to force a global broadcast.
        if (text.startsWith("!")) {
            event.message(Component.text(text.substring(1).trim(), NamedTextColor.WHITE));
            return;
        }

        AgentNpc target = pickTargetAgent(player);
        if (target == null) return; // no agent in scope; let it broadcast normally

        event.setCancelled(true);
        String agentId = target.agentId();
        Component display = Component.text("[→ ", NamedTextColor.DARK_GRAY)
                .append(Component.text(agentId, NamedTextColor.AQUA))
                .append(Component.text("] ", NamedTextColor.DARK_GRAY))
                .append(Component.text(text, NamedTextColor.WHITE));

        JsonObject m = new JsonObject();
        m.addProperty("type", "player_message");
        m.addProperty("agentId", agentId);
        m.addProperty("playerName", player.getName());
        m.addProperty("text", text);
        bridge.send(m);

        Bukkit.getScheduler().runTask(plugin, () -> {
            target.returnHome();   // you talked to it → it walks back to its desk
            player.sendMessage(display);
            if (roomHasTerminal(target.room())) {
                player.sendMessage(Component.text("§§ACT-TERMINAL§§ " + agentId));
            }
        });
    }

    /** The glass-box rooms whose agents back a real in-game terminal. */
    private boolean roomHasTerminal(String room) {
        if (room == null) return false;
        String lower = room.toLowerCase();
        return lower.equals("code") || lower.equals("hermes");
    }

    /**
     * Pick the best agent to route the player's chat to:
     *  0. the agent you're POINTING at (gaze cone) — the throne UX, else
     *  1. closest agent in the player's current room (any distance), else
     *  2. closest agent overall within {@code chat.routing_radius}.
     */
    private AgentNpc pickTargetAgent(Player player) {
        Location loc = player.getLocation();
        if (loc.getWorld() == null) return null;

        // Gaze first: if you're looking at an agent, that's who hears you.
        AgentNpc gazed = agentUnderGaze(player);
        if (gazed != null) return gazed;

        String roomName = rooms.currentRoom(player);
        if (roomName != null) {
            AgentNpc bestInRoom = null;
            double bestSq = Double.MAX_VALUE;
            for (AgentNpc n : agents.all()) {
                if (!n.room().equalsIgnoreCase(roomName)) continue;
                Location h = n.home();
                if (h.getWorld() == null || !h.getWorld().equals(loc.getWorld())) continue;
                double d = h.distanceSquared(loc);
                if (d < bestSq) { bestSq = d; bestInRoom = n; }
            }
            if (bestInRoom != null) return bestInRoom;
        }

        double radius = plugin.getConfig().getDouble("chat.routing_radius", 16.0);
        return agents.nearest(loc, radius);
    }

    /**
     * The agent the player is pointing at: the one whose body is closest to the
     * player's line of sight, within {@link #GAZE_RANGE} and a {@link #GAZE_MIN_DOT}
     * cone. Pure vector math on cached positions — safe to call from both the
     * async chat handler and the sync action-bar tick. Returns null if you aren't
     * pointing at any agent.
     */
    private AgentNpc agentUnderGaze(Player player) {
        Location eye = player.getEyeLocation();
        if (eye.getWorld() == null) return null;
        Vector look = eye.getDirection();   // unit length
        Vector from = eye.toVector();
        AgentNpc best = null;
        double bestDot = GAZE_MIN_DOT;
        for (AgentNpc n : agents.all()) {
            Location h = n.home();
            if (h.getWorld() == null || !h.getWorld().equals(eye.getWorld())) continue;
            Vector to = h.toVector().add(GAZE_AIM).subtract(from);
            double dist = to.length();
            if (dist < 1.0 || dist > GAZE_RANGE) continue;
            double dot = look.dot(to) / dist;   // cos(angle between look dir and the agent)
            if (dot > bestDot) { bestDot = dot; best = n; }
        }
        return best;
    }

    /**
     * Action-bar hint for what you're pointing at: a SCREEN (shows its current
     * URL + "type a URL to change") takes precedence over an AGENT (shows
     * "type to task it"). Screen aim sits higher on the wall than the agent in
     * front of it, so the two rarely collide; screen wins when both match.
     */
    private void tickGazeHint() {
        var cinema = plugin.cinema();
        for (Player p : Bukkit.getOnlinePlayers()) {
            String screen = cinema == null ? null : cinema.input().aimedCinema(p.getUniqueId());
            if (screen != null) {
                String url = cinema.currentUrl(screen);
                String shown = url == null ? "no channel" : url.replaceFirst("^https?://", "");
                p.sendActionBar(Component.text("▣ screen ", NamedTextColor.DARK_GRAY)
                        .append(Component.text(screen, NamedTextColor.AQUA))
                        .append(Component.text(" · " + shown, NamedTextColor.WHITE))
                        .append(Component.text("  — type a URL to change", NamedTextColor.DARK_GRAY)));
                continue;
            }
            AgentNpc n = agentUnderGaze(p);
            if (n == null) continue;
            p.sendActionBar(Component.text("▶ pointing at ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(n.agentId(), NamedTextColor.AQUA))
                    .append(Component.text(" — just type to task it", NamedTextColor.DARK_GRAY)));
        }
    }
}

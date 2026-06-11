package com.agentcraft.listeners;

import com.agentcraft.AgentCraftPlugin;
import com.agentcraft.agents.AgentManager;
import com.agentcraft.bridge.BridgeClient;
import com.agentcraft.rooms.Room;
import com.agentcraft.rooms.RoomManager;
import com.agentcraft.village.MvpWorldBuilder;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Steps on a glass-box pressure plate → spawn that box's PTY agent (if it
 * isn't alive yet) and open its in-game terminal. Boxes sit empty until a
 * player walks in, which is exactly the "0 agents until the plate" behaviour.
 *
 * <ul>
 *   <li>{@code code} box  → agent "claude", auto-launches the real Claude Code CLI.</li>
 *   <li>{@code hermes} box → agent "hermes", auto-launches {@code hermes chat}.
 *       Configurable via config.yml.</li>
 * </ul>
 */
public class TerminalPlateListener implements Listener {

    private static final double SEARCH_RADIUS = 9.0;
    private static final String SENTINEL = "§§ACT-TERMINAL§§ ";
    private static final long COOLDOWN_MS = 2_000;

    private final AgentCraftPlugin plugin;
    private final AgentManager agents;
    private final BridgeClient bridge;
    private final RoomManager rooms;
    private final Map<UUID, Long> lastFire = new ConcurrentHashMap<>();

    public TerminalPlateListener(AgentCraftPlugin plugin, AgentManager agents,
                                 BridgeClient bridge, RoomManager rooms) {
        this.plugin = plugin;
        this.agents = agents;
        this.bridge = bridge;
        this.rooms = rooms;
    }

    /** Per-box settings resolved from config.yml (with sensible defaults). */
    private record Box(String room, String agentId, String role, String launch, String cwd) {}

    private Box boxFor(String room) {
        // Each numbered workstation (code-1..N) maps to its OWN agent id, so four
        // coders never share one claude PTY. The legacy single boxes keep the
        // well-known claude/hermes ids (see MvpWorldBuilder#terminalAgentId). The
        // working dir is resolved with per-room overrides via MvpWorldBuilder.
        String cwd = MvpWorldBuilder.terminalCwd(plugin.getConfig(), room);
        if (MvpWorldBuilder.isCodeRoom(room)) {
            return new Box(room, MvpWorldBuilder.terminalAgentId(room), "Claude Code engineer",
                    cfgLaunch("terminal.code_launch", "claude"), cwd);
        }
        return new Box(room, MvpWorldBuilder.terminalAgentId(room), "Hermes agent",
                cfgLaunch("terminal.hermes_launch", "hermes chat"), cwd);
    }

    private String cfgLaunch(String key, String fallback) {
        String v = plugin.getConfig().getString(key);
        if (v == null || v.isBlank()) return fallback;
        String trimmed = v.trim();
        // Explicit escape hatch for a plain login shell while keeping blank
        // stale configs from disabling the expected claude/hermes default.
        if (trimmed.equalsIgnoreCase("shell")
                || trimmed.equalsIgnoreCase("none")
                || trimmed.equalsIgnoreCase("off")) {
            return "";
        }
        return v;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.PHYSICAL) return;
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.LIGHT_WEIGHTED_PRESSURE_PLATE) return;

        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        Long prev = lastFire.get(player.getUniqueId());
        // Refresh the contact timestamp on EVERY physical hit so the cooldown
        // measures "time since the player last touched a plate", not "time since
        // we last opened a terminal". A pressure plate keeps firing PHYSICAL
        // events while you stand on it; the old code let the cooldown elapse
        // mid-session and re-sent the open sentinel every COOLDOWN_MS, which the
        // client turned into a terminal "refresh" every couple of seconds.
        lastFire.put(player.getUniqueId(), now);
        if (prev != null && now - prev < COOLDOWN_MS) return;

        Box box = nearestBox(block.getLocation());
        if (box == null) return;

        Bukkit.getScheduler().runTask(plugin, () -> openOrSpawn(player, box));
    }

    /**
     * Find which box's plate was stepped — the nearest registered terminal room
     * (any {@code code}/{@code code-N} workstation, or the Hermes booth) within
     * {@link #SEARCH_RADIUS}. Booths are spaced well past the radius so a plate
     * only ever resolves to its own station.
     */
    private Box nearestBox(Location plate) {
        if (plate.getWorld() == null) return null;
        String bestRoom = null;
        double bestSq = SEARCH_RADIUS * SEARCH_RADIUS;
        for (Room r : rooms.all()) {
            if (!r.worldName().equals(plate.getWorld().getName())) continue;
            String name = r.name();
            if (!MvpWorldBuilder.isCodeRoom(name) && !name.equalsIgnoreCase(MvpWorldBuilder.HERMES_ROOM)) continue;
            double dx = r.x() - plate.getX(), dy = r.y() - plate.getY(), dz = r.z() - plate.getZ();
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 < bestSq) { bestSq = d2; bestRoom = name; }
        }
        return bestRoom == null ? null : boxFor(bestRoom);
    }

    private void openOrSpawn(Player player, Box box) {
        if (agents.get(box.agentId()) == null) {
            Room r = rooms.get(box.room());
            if (r == null) return;
            World w = plugin.getServer().getWorld(r.worldName());
            if (w == null) w = player.getWorld();
            // Face north (180° yaw): a workstation villager looks at its dev wall
            // on the north back wall; the Hermes booth villager faces the plaza
            // through its north door.
            Location home = new Location(w, r.x(), r.y(), r.z(), 180f, 0f);
            agents.spawn(box.agentId(), box.role(), box.room(), home);

            JsonObject m = new JsonObject();
            m.addProperty("type", "spawn_agent");
            m.addProperty("agentId", box.agentId());
            m.addProperty("role", box.role());
            m.addProperty("room", box.room());
            m.addProperty("playerName", player.getName());
            m.addProperty("cwd", box.cwd());
            m.addProperty("launch", box.launch());
            JsonObject vec = new JsonObject();
            vec.addProperty("x", home.getX());
            vec.addProperty("y", home.getY());
            vec.addProperty("z", home.getZ());
            m.add("home", vec);
            bridge.send(m);

            player.sendMessage(Component.text("→ starting " + box.agentId()
                    + " (cwd " + box.cwd() + ")", NamedTextColor.LIGHT_PURPLE));
            if (box.launch().isEmpty()) {
                player.sendMessage(Component.text("  shell — ls / cd around, then type: hermes chat",
                        NamedTextColor.GRAY));
            }
        }
        player.sendMessage(Component.text("→ opening " + box.agentId() + " terminal", NamedTextColor.AQUA));
        player.sendMessage(Component.text(SENTINEL + box.agentId()));
    }
}

package com.agentcraft.agents;

import com.agentcraft.AgentCraftPlugin;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AgentManager {
    private final AgentCraftPlugin plugin;
    private final Map<String, AgentNpc> agents = new ConcurrentHashMap<>();
    /** Pending approval call ids per player (most recent last). */
    private final Map<UUID, Deque<PendingApproval>> pendingByPlayer = new ConcurrentHashMap<>();

    public record PendingApproval(String callId, String agentId, String tool, String summary) {}

    public AgentManager(AgentCraftPlugin plugin) { this.plugin = plugin; }

    public AgentNpc spawn(String id, String role, String room, Location home) {
        return spawn(id, role, room, home, false);
    }

    /**
     * Variant that pins the villager in place when {@code stationary} is true —
     * AI stays off and it never wanders from its spawn (used for the build-studio
     * mason so the architect always stands at the plot edge facing the build).
     */
    public AgentNpc spawn(String id, String role, String room, Location home, boolean stationary) {
        AgentNpc existing = agents.get(id);
        // Reuse a live body, but if the villager was killed/unloaded (or a stale
        // entry lingers after a rebuild) tear it down and respawn — otherwise a
        // re-run of /hermes school would "find no teacher" because the dead NPC
        // is still mapped here.
        if (existing != null && existing.isAlive()) return existing;
        if (existing != null) { existing.remove(); agents.remove(id); }
        int lines = plugin.getConfig().getInt("display.screen_lines", 10);
        AgentNpc npc = new AgentNpc(plugin, id, role, room, home, lines, stationary);
        agents.put(id, npc);
        return npc;
    }

    public AgentNpc get(String id) { return agents.get(id); }

    public void despawn(String id) {
        AgentNpc n = agents.remove(id);
        if (n != null) n.remove();
    }

    public Collection<AgentNpc> all() { return agents.values(); }

    public void removeAll() {
        for (AgentNpc n : agents.values()) n.remove();
        agents.clear();
    }

    public AgentNpc nearest(Location loc, double maxDistance) {
        AgentNpc best = null;
        double bestDist = maxDistance * maxDistance;
        for (AgentNpc n : agents.values()) {
            if (!n.home().getWorld().equals(loc.getWorld())) continue;
            double d = n.home().distanceSquared(loc);
            if (d <= bestDist) {
                bestDist = d;
                best = n;
            }
        }
        return best;
    }

    public void recordApproval(Player p, PendingApproval pa) {
        pendingByPlayer.computeIfAbsent(p.getUniqueId(), k -> new ArrayDeque<>()).addLast(pa);
    }

    public PendingApproval popApproval(Player p, String callId) {
        Deque<PendingApproval> q = pendingByPlayer.get(p.getUniqueId());
        if (q == null) return null;
        if (callId == null) return q.pollLast();
        PendingApproval found = null;
        for (PendingApproval pa : q) if (pa.callId().equals(callId)) { found = pa; break; }
        if (found != null) q.remove(found);
        return found;
    }
}

package com.agentcraft.listeners;

import com.agentcraft.AgentCraftPlugin;
import com.agentcraft.agents.AgentManager;
import com.agentcraft.agents.AgentNpc;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Right-click any agent villager → open that agent's in-game terminal. This is
 * the direct, always-available way to open a terminal: no need to find the gold
 * plate or chat near the agent first.
 *
 * <p>Two reach paths, so it works whether you're next to the villager or across
 * the hall:
 * <ul>
 *   <li><b>Up close</b> — {@link PlayerInteractEntityEvent} fires when you right-
 *       click the body inside vanilla reach (~3 blocks).</li>
 *   <li><b>From far away</b> — {@link PlayerInteractEvent} (right-click air/block)
 *       fires for clicks beyond reach; we ray-trace the player's gaze against the
 *       agent bodies (block-aware, so a wall between you and the agent blocks the
 *       click) up to {@link #MAX_AIM_DIST} and open whichever agent it lands on.
 *       This mirrors the cinema wall's "look + gesture, no reach limit" grammar
 *       (see {@link CinemaInteractListener}).</li>
 * </ul>
 *
 * <p>Mechanism is the same hidden {@code §§ACT-TERMINAL§§ <agentId>} sentinel the
 * plate and {@link ChatListener} use — the client-side terminal mod intercepts it
 * and opens the {@code TeamTerminalScreen} attached to that agent. The runtime
 * keeps every villager's brain alive (re-synced on reconnect — see
 * {@code IncomingHandler.resyncAgents}), so a click always opens onto a live
 * mind without the player ever running a spawn command.
 */
public final class AgentClickListener implements Listener {

    private static final String SENTINEL = "§§ACT-TERMINAL§§ ";

    /** How far a player's gaze can reach to open an agent terminal (blocks). */
    private static final double MAX_AIM_DIST = 48.0;
    /** Expand agent hitboxes a touch so a far-away villager is still easy to aim at. */
    private static final double RAY_SIZE = 0.3;
    /** Swallow the off-hand / close+far double-fire of a single right-click. */
    private static final long OPEN_DEBOUNCE_MS = 350L;

    private final AgentCraftPlugin plugin;
    private final AgentManager agents;
    private final Map<UUID, Long> lastOpen = new HashMap<>();

    public AgentClickListener(AgentCraftPlugin plugin, AgentManager agents) {
        this.plugin = plugin;
        this.agents = agents;
    }

    /** Close-range path: directly right-clicking the body inside vanilla reach. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        // Right-clicking an entity fires once per hand; only act on the main
        // hand so we don't send the sentinel twice.
        if (event.getHand() != EquipmentSlot.HAND) return;

        AgentNpc target = agentFor(event.getRightClicked());
        if (target == null) return; // not one of ours — leave vanilla behavior alone

        // Stop the default villager interaction (the trade GUI) from opening.
        event.setCancelled(true);
        open(event.getPlayer(), target);
    }

    /**
     * Far-range path: right-click air/block while looking at an agent. Vanilla
     * never fires the entity event past reach, so we ray-trace the gaze ourselves.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onAirInteract(PlayerInteractEvent event) {
        Action a = event.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return; // ignore the off-hand duplicate

        Player player = event.getPlayer();
        AgentNpc target = traceAgent(player);
        if (target == null) return; // not looking at an agent — leave the click alone

        event.setCancelled(true); // don't place/use the held item
        open(player, target);
    }

    /**
     * Ray-trace the player's eye line against agent bodies, stopping at the first
     * solid block so you can't open a terminal through a wall.
     */
    private AgentNpc traceAgent(Player player) {
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection();
        RayTraceResult r = player.getWorld().rayTrace(
                eye, dir, MAX_AIM_DIST, FluidCollisionMode.NEVER, true, RAY_SIZE,
                e -> e != player && agentFor(e) != null);
        if (r == null) return null;
        Entity hit = r.getHitEntity();
        return hit == null ? null : agentFor(hit); // null hitEntity ⇒ a block was closer
    }

    /** The agent whose body is {@code e}, or null. */
    private AgentNpc agentFor(Entity e) {
        if (e == null) return null;
        for (AgentNpc n : agents.all()) {
            if (n.isBody(e)) return n;
        }
        return null;
    }

    /** Emit the terminal-open sentinel for {@code target}, debounced per player. */
    private void open(Player player, AgentNpc target) {
        long now = System.currentTimeMillis();
        Long prev = lastOpen.get(player.getUniqueId());
        if (prev != null && now - prev < OPEN_DEBOUNCE_MS) return;
        lastOpen.put(player.getUniqueId(), now);

        player.sendMessage(Component.text("→ opening " + target.agentId() + " terminal", NamedTextColor.AQUA));
        player.sendMessage(Component.text(SENTINEL + target.agentId()));
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.4f, 1.6f);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastOpen.remove(event.getPlayer().getUniqueId());
    }
}

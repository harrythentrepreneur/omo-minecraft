package com.agentcraft.listeners;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * Join glue for the real-terrain overworld. The world now generates normal
 * Minecraft terrain (grass/trees/hills) at ground level, with the Omo Studio
 * floating high in the sky ({@code /omo studio} flies you up, {@code /omo
 * ground} drops you back). This listener keeps the creative sandbox feel:
 *
 *   - joining players land in creative with flight <em>available</em> (so they
 *     can lift up to the studio) but are not forced airborne — they stand on
 *     the real ground;
 *   - anyone who somehow falls out the bottom of the world (digging through
 *     bedrock in creative) is caught and bounced back to the ground spawn.
 */
public final class VoidWorldListener implements Listener {

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        var p = e.getPlayer();
        if (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) {
            p.setGameMode(GameMode.CREATIVE);
        }
        // Flight is available (double-tap to rise up to the sky studio) but we
        // don't force it — the player spawns standing on the real terrain.
        p.setAllowFlight(true);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        var to = e.getTo();
        var world = to.getWorld();
        if (world == null) return;
        // Only the genuine void floor, far below bedrock — normal play never
        // reaches it, so this is a pure safety net, not the old sky-bump.
        if (to.getY() >= world.getMinHeight() + 2) return;
        var p = e.getPlayer();
        p.setAllowFlight(true);
        p.teleport(world.getSpawnLocation());
        p.sendMessage(Component.text("caught from the void — back to spawn", NamedTextColor.GRAY));
    }
}

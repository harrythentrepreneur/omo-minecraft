package com.agentcraft.listeners;

import com.agentcraft.AgentCraftPlugin;
import com.agentcraft.rooms.Room;
import com.agentcraft.rooms.RoomManager;
import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerInteractEvent;

/**
 * Right-click a stair inside a seating room (the cinema, or any
 * {@code classroom*} room) → spawn an invisible armor stand at the seat and
 * mount the player on it. Sneak dismounts and the stand removes itself.
 *
 * <p>Scope is intentionally narrow: we only react to interactions inside a
 * registered seating room so other stairs in the world keep their vanilla
 * behavior. Rooms are identified by a radius around their registered anchor;
 * we don't have full polygons.
 */
public final class CinemaSeatListener implements Listener {

    private final AgentCraftPlugin plugin;
    private final RoomManager rooms;

    public CinemaSeatListener(AgentCraftPlugin plugin, RoomManager rooms) {
        this.plugin = plugin;
        this.rooms = rooms;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent ev) {
        if (ev.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        Block b = ev.getClickedBlock();
        if (b == null) return;
        if (!Tag.STAIRS.isTagged(b.getType())) return;
        if (!isSeatingRoom(b.getLocation())) return;
        Player p = ev.getPlayer();
        if (p.isInsideVehicle()) return;

        Location seat = b.getLocation().add(0.5, 0.4, 0.5);
        ArmorStand stand = b.getWorld().spawn(seat, ArmorStand.class, as -> {
            as.setVisible(false);
            as.setGravity(false);
            as.setMarker(false);          // markers can't carry passengers
            as.setSmall(true);
            as.setInvulnerable(true);
            as.setCustomNameVisible(false);
            as.setBasePlate(false);
            as.setArms(false);
            as.setRemoveWhenFarAway(false);
        });
        stand.addPassenger(p);
        ev.setCancelled(true);
    }

    @EventHandler
    public void onDismount(EntityDismountEvent ev) {
        if (ev.getDismounted().getType() != EntityType.ARMOR_STAND) return;
        ArmorStand stand = (ArmorStand) ev.getDismounted();
        if (stand.isVisible()) return; // not one of ours
        // Remove on the next tick so the dismount event finishes cleanly.
        plugin.getServer().getScheduler().runTask(plugin, stand::remove);
    }

    private boolean isSeatingRoom(Location loc) {
        // Cinemas: both regular ("cinema") and sci-fi ("scifi-cinema") builds.
        // Room#contains already does the radius check.
        var c = rooms.get("cinema");
        if (c != null && c.contains(loc)) return true;
        var s = rooms.get("scifi-cinema");
        if (s != null && s.contains(loc)) return true;
        // The Observatory dome — its throne sits at the world-spawn centre, so
        // the "spawn" room is the seating zone (the throne is the only stair in it).
        var spawn = rooms.get("spawn");
        if (spawn != null && spawn.contains(loc)) return true;
        // School classrooms — sit at any desk chair to talk to the tutor.
        Room here = rooms.roomAt(loc);
        return here != null && here.name().toLowerCase().startsWith("classroom");
    }
}

package com.agentcraft.listeners;

import com.agentcraft.AgentCraftPlugin;
import com.agentcraft.bridge.BridgeClient;
import com.agentcraft.rooms.Room;
import com.agentcraft.rooms.RoomManager;
import com.google.gson.JsonObject;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class MovementListener implements Listener {

    private final AgentCraftPlugin plugin;
    private final RoomManager rooms;
    private final BridgeClient bridge;

    public MovementListener(AgentCraftPlugin plugin, RoomManager rooms, BridgeClient bridge) {
        this.plugin = plugin;
        this.rooms = rooms;
        this.bridge = bridge;
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        // Only fire when the player moves to a new block — keeps cost low.
        if (e.getFrom().getBlockX() == e.getTo().getBlockX()
            && e.getFrom().getBlockZ() == e.getTo().getBlockZ()
            && e.getFrom().getBlockY() == e.getTo().getBlockY()) return;

        var player = e.getPlayer();
        Room here = rooms.roomAt(player.getLocation());
        String current = rooms.currentRoom(player);
        String newName = here == null ? null : here.name();

        if (java.util.Objects.equals(current, newName)) return;

        if (current != null) {
            JsonObject leave = new JsonObject();
            leave.addProperty("type", "player_leave_room");
            leave.addProperty("room", current);
            leave.addProperty("playerName", player.getName());
            bridge.send(leave);
        }
        if (newName != null) {
            // No on-screen card. The big "room name" title overlay was removed —
            // it popped up every time you crossed a room boundary, which read as
            // noise; signs + the /omo menu carry orientation instead. Entering a
            // room now just notifies the runtime.
            JsonObject enter = new JsonObject();
            enter.addProperty("type", "player_enter_room");
            enter.addProperty("room", newName);
            enter.addProperty("playerName", player.getName());
            bridge.send(enter);
        }
        rooms.setCurrentRoom(player, newName);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        rooms.setCurrentRoom(e.getPlayer(), null);
    }
}

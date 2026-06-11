package com.agentcraft.listeners;

import com.agentcraft.AgentCraftPlugin;
import com.agentcraft.classroom.CalculatorGui;
import com.agentcraft.rooms.Room;
import com.agentcraft.rooms.RoomManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.InventoryHolder;

/**
 * The classroom calculator. Right-click a lodestone "console" (the block on
 * each desk) inside any {@code classroom*} room to open a chest-GUI calculator
 * backed by {@link CalculatorGui} + {@link com.agentcraft.classroom.AlgebraEval}.
 *
 * <p>The running expression / answer is shown live in the inventory <b>title</b>
 * (vanilla GUIs can't render text on slots, so the title is the screen). We
 * update it on the next tick after each click to avoid resending the window
 * mid-event.
 */
public final class CalculatorListener implements Listener {

    private final AgentCraftPlugin plugin;
    private final RoomManager rooms;

    public CalculatorListener(AgentCraftPlugin plugin, RoomManager rooms) {
        this.plugin = plugin;
        this.rooms = rooms;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent ev) {
        if (ev.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = ev.getClickedBlock();
        if (b == null || b.getType() != Material.LODESTONE) return;
        Room here = rooms.roomAt(b.getLocation());
        if (here == null || !here.name().toLowerCase().startsWith("classroom")) return;
        ev.setCancelled(true);
        ev.getPlayer().openInventory(new CalculatorGui().getInventory());
    }

    @EventHandler
    public void onClick(InventoryClickEvent ev) {
        InventoryHolder holder = ev.getInventory().getHolder();
        if (!(holder instanceof CalculatorGui gui)) return;
        ev.setCancelled(true); // never let items move in/out of the calculator
        if (ev.getClickedInventory() != ev.getView().getTopInventory()) return;
        if (gui.handleClick(ev.getRawSlot()) && ev.getWhoClicked() instanceof Player p) {
            // Push the live readout into the title on the next tick.
            Bukkit.getScheduler().runTask(plugin, () -> {
                var view = p.getOpenInventory();
                if (view.getTopInventory().getHolder() instanceof CalculatorGui g) {
                    try { view.setTitle(g.displayText()); } catch (Throwable ignored) {}
                }
            });
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent ev) {
        if (ev.getInventory().getHolder() instanceof CalculatorGui) ev.setCancelled(true);
    }
}

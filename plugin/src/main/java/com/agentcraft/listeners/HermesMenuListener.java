package com.agentcraft.listeners;

import com.agentcraft.commands.HermesMenu;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;

/**
 * Click router for the {@code /hermes} chest-GUI menus. Any inventory whose
 * holder is a {@link HermesMenu} is one of ours: cancel the click (buttons are
 * never draggable items) and run the {@link HermesMenu.Action} bound to the
 * clicked slot. Sub-menus open themselves from inside those actions, so this
 * one listener covers the whole menu tree.
 */
public final class HermesMenuListener implements Listener {

    @EventHandler
    public void onClick(InventoryClickEvent ev) {
        InventoryHolder holder = ev.getInventory().getHolder();
        if (!(holder instanceof HermesMenu menu)) return;
        ev.setCancelled(true); // buttons are fixed — never let items move
        if (ev.getClickedInventory() != ev.getView().getTopInventory()) return;
        if (!(ev.getWhoClicked() instanceof Player p)) return;
        HermesMenu.Action a = menu.action(ev.getRawSlot());
        if (a != null) a.run(p);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent ev) {
        if (ev.getInventory().getHolder() instanceof HermesMenu) ev.setCancelled(true);
    }
}

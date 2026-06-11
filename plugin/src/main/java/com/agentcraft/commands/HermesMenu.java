package com.agentcraft.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A generic clickable chest-GUI menu for {@code /hermes}. One
 * {@link InventoryHolder} per open menu; each filled slot carries an
 * {@link Action} that {@link com.agentcraft.listeners.HermesMenuListener} runs on
 * click. Sub-menus (confirm dialogs, the agent picker, …) are just more
 * {@code HermesMenu} instances opened from an {@code Action}.
 *
 * <p>Mirrors the {@link com.agentcraft.classroom.CalculatorGui} pattern: vanilla
 * GUIs render item names/lore on hover, so each button explains itself in its
 * display name + lore, and the listener cancels every click so nothing moves.
 */
public final class HermesMenu implements InventoryHolder {

    /** What a button does when its slot is clicked. */
    @FunctionalInterface
    public interface Action { void run(Player player); }

    private final Inventory inv;
    private final Map<Integer, Action> actions = new HashMap<>();

    public HermesMenu(Component title, int rows) {
        this.inv = Bukkit.createInventory(this, rows * 9, title);
        ItemStack filler = icon(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), null);
        for (int i = 0; i < rows * 9; i++) inv.setItem(i, filler);
    }

    /** Place a button. A null {@code action} makes it a non-clickable label. */
    public HermesMenu button(int slot, ItemStack item, Action action) {
        inv.setItem(slot, item);
        if (action != null) actions.put(slot, action);
        return this;
    }

    /** The action bound to {@code slot}, or null if the slot is empty/a label. */
    public Action action(int slot) { return actions.get(slot); }

    @Override
    public Inventory getInventory() { return inv; }

    // ── item helpers ─────────────────────────────────────────────────────────

    /** An item with a (non-italic) display name and optional lore lines. */
    public static ItemStack icon(Material m, Component name, List<Component> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        if (lore != null && !lore.isEmpty()) {
            List<Component> clean = new ArrayList<>(lore.size());
            for (Component c : lore) clean.add(c.decoration(TextDecoration.ITALIC, false));
            meta.lore(clean);
        }
        it.setItemMeta(meta);
        return it;
    }
}

package com.agentcraft.onboarding;

import com.agentcraft.AgentCraftPlugin;
import com.agentcraft.ui.Ui;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/**
 * The "startup screen": a beat after join, show the {@link RoomGuide} welcome
 * title card + two calm orientation lines in chat, and put the agent wand in
 * the player's hand. Runs alongside
 * {@link com.agentcraft.listeners.VoidWorldListener}, which owns the
 * creative-flight / void-catch side of joining.
 *
 * <p>The wand is a plain stick: Minecraft only delivers a right-click to the
 * server when the player is holding <em>something</em>, so an empty hand can't
 * open an agent's terminal from across the room (see
 * {@link com.agentcraft.listeners.AgentClickListener}). Handing every player a
 * stick means "right-click the villager" just works, near or far. The book
 * sits in the last hotbar slot as the secondary item.
 *
 * <p>The short delay lets the client finish loading the world (and lets the
 * void listener teleport the player to a safe spawn first) so the title lands
 * once they can actually see the plaza.
 */
public final class WelcomeListener implements Listener {

    private final AgentCraftPlugin plugin;
    /** Marks a stick as the agent wand so we hand it out exactly once per player. */
    private final NamespacedKey wandKey;

    public WelcomeListener(AgentCraftPlugin plugin) {
        this.plugin = plugin;
        this.wandKey = new NamespacedKey(plugin, "agent_wand");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();

        // The startup screen is the card + calm lines + the agent wand, a beat
        // after the client settles (and after the void listener seats the
        // player). Signs and /omo carry the rest, each fact in one place.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            // No big title card on join — just two quiet chat lines + the wand.
            RoomGuide.sendWelcomeChat(p);
            giveWandIfMissing(p);
        }, 25L);
    }

    /**
     * Put the agent wand in the held slot and a book at the end of the hotbar —
     * but only if the player doesn't already carry the wand, so a join never
     * stomps an inventory they've rearranged.
     */
    private void giveWandIfMissing(Player p) {
        PlayerInventory inv = p.getInventory();
        for (ItemStack it : inv.getContents()) {
            if (isWand(it)) return; // already has it — leave their inventory alone
        }
        inv.setItem(0, makeWand());
        inv.setItem(8, new ItemStack(Material.BOOK)); // "the book on the end"
        inv.setHeldItemSlot(0);
    }

    private boolean isWand(ItemStack it) {
        if (it == null || it.getType() != Material.STICK) return false;
        ItemMeta m = it.getItemMeta();
        return m != null && m.getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
    }

    private ItemStack makeWand() {
        ItemStack wand = new ItemStack(Material.STICK);
        ItemMeta m = wand.getItemMeta();
        m.displayName(Component.text("Agent Wand", Ui.AGENT).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(
                Component.text("Right-click an agent to open its terminal.", Ui.BODY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Works from across the room.", Ui.FAINT)
                        .decoration(TextDecoration.ITALIC, false)));
        m.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        wand.setItemMeta(m);
        return wand;
    }
}

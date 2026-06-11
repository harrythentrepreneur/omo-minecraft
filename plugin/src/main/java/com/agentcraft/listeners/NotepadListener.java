package com.agentcraft.listeners;

import com.agentcraft.AgentCraftPlugin;
import com.agentcraft.rooms.Room;
import com.agentcraft.rooms.RoomManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * The classroom notepad. Right-click the barrel ("notebook") at your desk
 * inside any {@code classroom*} room and a real book &amp; quill — your
 * personal "Algebra Notebook" — drops into your inventory. Hold it and
 * right-click to write; it re-opens with your pages preserved, exactly like a
 * real notepad. We hand out one per player (tagged so re-clicking doesn't
 * duplicate it).
 */
public final class NotepadListener implements Listener {

    private final RoomManager rooms;
    private final NamespacedKey key;

    public NotepadListener(AgentCraftPlugin plugin, RoomManager rooms) {
        this.rooms = rooms;
        this.key = new NamespacedKey(plugin, "algebra_notebook");
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent ev) {
        if (ev.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = ev.getClickedBlock();
        if (b == null || b.getType() != Material.BARREL) return;
        Room here = rooms.roomAt(b.getLocation());
        if (here == null || !here.name().toLowerCase().startsWith("classroom")) return;

        ev.setCancelled(true); // the barrel is a notebook dispenser, not storage
        Player p = ev.getPlayer();
        if (hasNotebook(p)) {
            p.sendMessage(Component.text("✎ You already have your notebook — hold it and right-click to write. It saves as you go.",
                    NamedTextColor.AQUA));
            return;
        }
        ItemStack book = makeNotebook();
        var leftover = p.getInventory().addItem(book);
        if (!leftover.isEmpty()) p.getWorld().dropItemNaturally(p.getLocation(), book);
        p.sendMessage(Component.text("✎ Your Omo Studio notebook is in your inventory — flip through the guide pages, then hold it and right-click to write.",
                NamedTextColor.GREEN));
    }

    private boolean hasNotebook(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (it == null) continue;
            var meta = it.getItemMeta();
            if (meta != null && meta.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) return true;
        }
        return false;
    }

    private ItemStack makeNotebook() {
        ItemStack book = new ItemStack(Material.WRITABLE_BOOK);
        BookMeta m = (BookMeta) book.getItemMeta();
        m.displayName(Component.text("✎ Omo Studio — Guide & Notebook", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        // Guide pages first (a quick reference to every feature + its commands),
        // then a blank page to actually write on. Lines are kept short so they
        // don't wrap in the default book font.
        m.addPages(
                guidePage("OMO STUDIO",
                        "Your team of AI\n"
                        + "workers, live in\n"
                        + "the world.\n\n"
                        + "Walk up to anyone\n"
                        + "and type to give\n"
                        + "a task.\n\n"
                        + "Press V for Omo.\n"
                        + "/omo = menu.\n\n"
                        + "→ flip for commands"),
                guidePage("BUILD",
                        "/omo build\n"
                        + " the whole Studio\n\n"
                        + "/omo buildstudio\n"
                        + " a live-build box\n\n"
                        + "/omo island\n"
                        + " island world\n\n"
                        + "/omo clear\n"
                        + " wipe to void"),
                guidePage("SCHOOL",
                        "/omo school\n"
                        + " build a classroom\n\n"
                        + "/omo classroom\n"
                        + "  <subject>\n"
                        + " re-theme it\n\n"
                        + "Sit at a desk and\n"
                        + "type — ada teaches\n"
                        + "and writes on the\n"
                        + "board."),
                guidePage("AGENTS",
                        "/omo spawn\n"
                        + "  <id> <role>\n"
                        + " a Hermes worker\n\n"
                        + "/omo spawn-code\n"
                        + "  <id> <dir> <task>\n"
                        + " a Claude coder\n\n"
                        + "/omo list\n"
                        + "/omo despawn <id>"),
                guidePage("TEAMS & SCREENS",
                        "/omo team-up\n"
                        + " seat the coders\n\n"
                        + "/omo village-up\n"
                        + " seat the workers\n\n"
                        + "/omo cinema\n"
                        + "  <url>\n"
                        + " change a screen"),
                guidePage("CODE & CONTROL",
                        "/omo cwd <path>\n"
                        + " set code folder\n\n"
                        + "/omo approve <id>\n"
                        + "/omo deny <id>\n"
                        + " okay a request\n\n"
                        + "/omo reconnect\n"
                        + " re-link the bridge"),
                guidePage("WATCH THEM WORK",
                        "Right-click an\n"
                        + "agent to watch it\n"
                        + "think.\n\n"
                        + "Every agent keeps\n"
                        + "a lectern book —\n"
                        + "its live log.\n\n"
                        + "Screens show live\n"
                        + "web + standups."),
                Component.text("My notes\n\n"));
        m.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        book.setItemMeta(m);
        return book;
    }

    /** A reference page: a bold dark-aqua heading over plain dark-grey body copy. */
    private static Component guidePage(String heading, String body) {
        return Component.text(heading + "\n\n", NamedTextColor.DARK_AQUA)
                .decoration(TextDecoration.BOLD, true)
                .append(Component.text(body, NamedTextColor.DARK_GRAY).decoration(TextDecoration.BOLD, false));
    }
}

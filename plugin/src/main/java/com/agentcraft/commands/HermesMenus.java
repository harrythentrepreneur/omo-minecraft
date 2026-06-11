package com.agentcraft.commands;

import com.agentcraft.AgentCraftPlugin;
import com.agentcraft.agents.AgentManager;
import com.agentcraft.agents.AgentNpc;
import com.agentcraft.rooms.Room;
import com.agentcraft.rooms.RoomManager;
import com.agentcraft.ui.Ui;
import com.agentcraft.village.MvpWorldBuilder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds and opens the {@code /omo} chest-GUI menu tree — the friendly,
 * clickable face over the (many) {@code /omo} subcommands. The main menu fans
 * out to sub-menus ({@link #openAgents}, {@link #openCinema}, {@link #openCwd},
 * {@link #openMore}) and confirm dialogs; leaf buttons either run an existing
 * subcommand via {@link Player#performCommand} or, for commands that need typed
 * arguments, drop a click-to-fill command into the player's chat box.
 *
 * <p>All real behaviour still lives in {@link HermesCommand}; this is purely a
 * discoverability layer, so adding/altering commands here never duplicates logic.
 */
public final class HermesMenus {

    private enum Mode { SAY, WATCH, DESPAWN }

    private final AgentCraftPlugin plugin;
    private final AgentManager agents;
    private final RoomManager rooms;

    public HermesMenus(AgentCraftPlugin plugin, AgentManager agents, RoomManager rooms) {
        this.plugin = plugin;
        this.agents = agents;
        this.rooms = rooms;
    }

    // ── Main menu ─────────────────────────────────────────────────────────────

    public void openMain(Player p) {
        HermesMenu m = new HermesMenu(Component.text("Omo Studio", Ui.PLACE), 3);
        m.button(4, btn(Material.NETHER_STAR, Ui.PLACE, "Omo Studio",
                "Hover any item to learn what it does."), null);

        // World
        m.button(10, btn(Material.IRON_PICKAXE, Ui.PLACE, "Build the Studio",
                "!Wipes the world first — asks to confirm."),
                p2 -> openConfirm(p2, "Rebuild the Studio?", "omo build",
                        "Wipes the world, then rebuilds the plaza,", "coder booths, screens, mason and school."));
        m.button(11, btn(Material.TNT, Ui.ERR, "Clear to void",
                "!Erases everything to void — asks to confirm."),
                p2 -> openConfirm(p2, "Clear the world to void?", "omo clear",
                        "Removes all agents, rooms, entities", "and blocks around you."));

        // Agents
        m.button(13, btn(Material.VILLAGER_SPAWN_EGG, Ui.OK, "New coder",
                "A Claude agent that writes code."),
                p2 -> suggest(p2, "/omo spawn-code <id> <folder> <task>",
                        "New coder — replace <id> <folder> <task>, then press enter:"));
        m.button(14, btn(Material.ENCHANTED_BOOK, Ui.OK, "New worker",
                "A Hermes agent: email, ads, notes."),
                p2 -> suggest(p2, "/omo spawn <id> <role>",
                        "New worker — replace <id> <role>, then press enter:"));
        m.button(16, btn(Material.PAINTING, Ui.PLACE, "Change a screen",
                "The standup screen, or a booth's wall."),
                this::openCinema);

        // Manage
        m.button(19, btn(Material.BOOK, Ui.BODY, "List agents",
                "Print everyone working to chat."),
                p2 -> { p2.closeInventory(); p2.performCommand("omo list"); });
        m.button(20, btn(Material.OAK_SIGN, Ui.AGENT, "Talk to an agent",
                "Pick an agent and send a message."),
                p2 -> openAgents(p2, Mode.SAY));
        m.button(21, btn(Material.SPYGLASS, Ui.AGENT, "Watch an agent",
                "Open its terminal right here", "and watch it work from your seat."),
                p2 -> openAgents(p2, Mode.WATCH));
        m.button(22, btn(Material.BONE, Ui.ERR, "Remove an agent",
                "Take an agent off the floor."),
                p2 -> openAgents(p2, Mode.DESPAWN));
        m.button(23, btn(Material.CHEST, Ui.PLACE, "Code repo / folder",
                "Point your coders at your code.", "Set the repo for all booths, or one."),
                this::openCwd);
        m.button(25, btn(Material.COMPARATOR, Ui.BODY, "More",
                "Build studio, school, island,", "approvals, reconnect."),
                this::openMore);

        m.button(26, btn(Material.BARRIER, Ui.BODY, "Close", "Close this menu."),
                Player::closeInventory);
        open(p, m);
    }

    // ── Confirm dialog ─────────────────────────────────────────────────────────

    /** Two-button yes/no. {@code command} is performed (no leading slash) on confirm. */
    private void openConfirm(Player p, String title, String command, String... summary) {
        HermesMenu m = new HermesMenu(Component.text(title, NamedTextColor.DARK_RED), 3);
        List<String> lore = new ArrayList<>(List.of(summary));
        lore.add("");
        lore.add("Runs: /" + command);
        m.button(13, btn(Material.PAPER, NamedTextColor.WHITE, title, lore.toArray(new String[0])), null);
        m.button(11, btn(Material.LIME_WOOL, Ui.OK, "Confirm", "Do it now."),
                p2 -> { p2.closeInventory(); p2.performCommand(command); });
        m.button(15, btn(Material.RED_WOOL, Ui.ERR, "Cancel", "Back to the menu."),
                this::openMain);
        open(p, m);
    }

    // ── Agent picker (Talk / Despawn) ───────────────────────────────────────────

    private void openAgents(Player p, Mode mode) {
        String title = switch (mode) {
            case SAY -> "Talk to an agent";
            case WATCH -> "Watch an agent";
            case DESPAWN -> "Despawn an agent";
        };
        NamedTextColor accent = switch (mode) {
            case SAY -> Ui.AGENT;
            case WATCH -> Ui.AGENT;
            case DESPAWN -> Ui.ERR;
        };
        String hint = switch (mode) {
            case SAY -> "Click to message this agent.";
            case WATCH -> "Click to open its terminal here.";
            case DESPAWN -> "Click to despawn this agent.";
        };
        HermesMenu m = new HermesMenu(Component.text(title, NamedTextColor.DARK_AQUA), 3);
        int slot = 0;
        boolean any = false;
        for (AgentNpc n : agents.all()) {
            if (slot > 25) break;
            any = true;
            final String id = n.agentId();
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text(n.role(), NamedTextColor.GRAY));
            lore.add(Component.text("room: " + n.room(), NamedTextColor.DARK_GRAY));
            lore.add(Component.text(hint, accent));
            ItemStack it = HermesMenu.icon(mode == Mode.WATCH ? Material.SPYGLASS : Material.VILLAGER_SPAWN_EGG,
                    Component.text(id, accent, TextDecoration.BOLD), lore);
            // A WATCH click sends the same §§ACT-TERMINAL§§ sentinel the
            // right-click-villager path uses, so the client mod opens that
            // agent's terminal — letting you watch any agent from your seat.
            m.button(slot++, it,
                    mode == Mode.SAY ? p2 -> suggest(p2, "/omo say " + id + " ", "Message " + id + " — type your text, then press enter:")
                  : mode == Mode.WATCH ? p2 -> { p2.closeInventory(); p2.sendMessage(Component.text("§§ACT-TERMINAL§§ " + id)); }
                  : p2 -> openConfirm(p2, "Despawn " + id + "?", "omo despawn " + id,
                            "Removes " + id + " from the world", "and stops its terminal / PTY."));
        }
        if (!any) {
            m.button(13, btn(Material.BARRIER, NamedTextColor.GRAY, "No active agents",
                    "Spawn one from the main menu first."), null);
        }
        m.button(26, back(), this::openMain);
        open(p, m);
    }

    // ── Cinema channels ─────────────────────────────────────────────────────────

    private void openCinema(Player p) {
        HermesMenu m = new HermesMenu(Component.text("Change a screen", Ui.PLACE), 3);
        m.button(4, btn(Material.PAINTING, Ui.PLACE, "Pick a screen",
                "Pick a screen, then type a URL.", "Standup = the big east screen;", "Wall N = each coder's wall."), null);
        int slot = 10;
        m.button(slot++, btn(Material.FILLED_MAP, Ui.PLACE, "Standup",
                "The big east standup screen."),
                p2 -> suggest(p2, "/omo cinema main ", "Standup screen — type a URL, then press enter:"));
        for (int i = 1; i <= MvpWorldBuilder.STATIONS && slot <= 16; i++) {
            final String id = MvpWorldBuilder.stationCinema(i); // dev-N
            final int booth = i;
            m.button(slot++, btn(Material.MAP, Ui.AGENT, "Wall " + booth, "Coder " + booth + "'s wall."),
                    p2 -> suggest(p2, "/omo cinema " + id + " ", "Wall " + booth + " — type a URL, then press enter:"));
        }
        m.button(26, back(), this::openMain);
        open(p, m);
    }

    // ── Working dir (cwd) ─────────────────────────────────────────────────────

    private void openCwd(Player p) {
        HermesMenu m = new HermesMenu(Component.text("Code repo / folder", Ui.PLACE), 3);
        String home = System.getProperty("user.home");
        String def = plugin.getConfig().getString("terminal.code_cwd");
        String effective = (def != null && !def.isBlank()) ? def
                : (new File(home + "/Fern").isDirectory() ? home + "/Fern" : home);
        m.button(4, btn(Material.BOOK, Ui.BODY, "Where your booths code", effective,
                "Every coder opens here unless a", "booth sets its own below."), null);
        m.button(10, btn(Material.NAME_TAG, Ui.OK, "Set repo for ALL booths",
                "Every coder opens in this folder.", "Click to type a folder path."),
                p2 -> suggest(p2, "/omo cwd ", "Repo for ALL booths — type a folder path, then press enter:"));

        int slot = 12;
        for (Room r : rooms.all()) {
            if (slot > 16) break;
            if (!MvpWorldBuilder.isCodeRoom(r.name())) continue;
            final String room = r.name();
            String override = plugin.getConfig().getString("terminal.room_cwd." + room);
            boolean set = override != null && !override.isBlank();
            m.button(slot++, btn(Material.CHEST, Ui.AGENT, room,
                    set ? override : "(uses the default)",
                    set ? "Click to change just this booth." : "Click to set just this booth."),
                    p2 -> suggest(p2, "/omo cwd " + room + " ",
                            "Folder for '" + room + "' — type a path (or 'reset'), then press enter:"));
        }
        m.button(26, back(), this::openMain);
        open(p, m);
    }

    // ── More / advanced ─────────────────────────────────────────────────────────

    private void openMore(Player p) {
        HermesMenu m = new HermesMenu(Component.text("More · advanced", NamedTextColor.DARK_AQUA), 3);
        m.button(10, btn(Material.BRICKS, NamedTextColor.GOLD, "Build Studio",
                "Add the live-build mason + plot", "at your location."),
                p2 -> { p2.closeInventory(); p2.performCommand("omo buildstudio"); });
        m.button(11, btn(Material.WRITABLE_BOOK, NamedTextColor.GOLD, "School",
                "Build Algebra 101 + seat the tutor."),
                p2 -> { p2.closeInventory(); p2.performCommand("omo school"); });
        m.button(12, btn(Material.WATER_BUCKET, NamedTextColor.AQUA, "Island world",
                "Rebuild as the island reskin.", "!Wipes the current world."),
                p2 -> openConfirm(p2, "Build the island world?", "omo island",
                        "Wipes everything and rebuilds", "the island-style world."));
        m.button(14, btn(Material.COMPASS, NamedTextColor.GRAY, "Where am I?",
                "Print your current room to chat."),
                p2 -> { p2.closeInventory(); p2.performCommand("omo room here"); });
        m.button(15, btn(Material.LIME_DYE, NamedTextColor.GREEN, "Approve a request",
                "Approve a pending tool request.", "Click to type the id."),
                p2 -> suggest(p2, "/omo approve ", "Approve a pending request — type its id (from chat / list):"));
        m.button(16, btn(Material.RED_DYE, NamedTextColor.RED, "Deny a request",
                "Deny a pending tool request."),
                p2 -> suggest(p2, "/omo deny ", "Deny a pending request — type its id:"));
        m.button(20, btn(Material.REDSTONE_TORCH, NamedTextColor.GOLD, "Reconnect bridge",
                "Reconnect to the runtime.", "Use if agents stop responding."),
                p2 -> { p2.closeInventory(); p2.performCommand("omo reconnect"); });
        m.button(26, back(), this::openMain);
        open(p, m);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    /** Defer the actual open one tick — safe to call from inside an InventoryClickEvent. */
    private void open(Player p, HermesMenu menu) {
        Bukkit.getScheduler().runTask(plugin, () -> p.openInventory(menu.getInventory()));
    }

    /**
     * Close the menu and drop a click-to-fill command into the player's chat box
     * (it is NOT run — they finish typing the arguments and press enter). This is
     * how arg-taking commands stay usable from a click-only GUI.
     */
    private void suggest(Player p, String command, String hint) {
        p.closeInventory();
        p.sendMessage(Component.text(hint, Ui.BODY));
        p.sendMessage(Component.text("  ", Ui.BODY).append(Ui.cmdLine(command.trim())));
    }

    private static ItemStack back() {
        return btn(Material.ARROW, NamedTextColor.GRAY, "← Back", "Return to the main menu.");
    }

    /** Button factory. A lore line prefixed with '!' renders red (a warning). */
    private static ItemStack btn(Material m, NamedTextColor color, String name, String... loreLines) {
        List<Component> lore = new ArrayList<>(loreLines.length);
        for (String s : loreLines) {
            if (s.isEmpty()) { lore.add(Component.empty()); continue; }
            boolean warn = s.startsWith("!");
            lore.add(Component.text(warn ? s.substring(1) : s, warn ? NamedTextColor.RED : NamedTextColor.GRAY));
        }
        return HermesMenu.icon(m, Component.text(name, color, TextDecoration.BOLD), lore);
    }
}

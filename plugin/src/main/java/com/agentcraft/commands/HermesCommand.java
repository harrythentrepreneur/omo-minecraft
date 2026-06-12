package com.agentcraft.commands;

import com.agentcraft.AgentCraftPlugin;
import com.agentcraft.agents.AgentManager;
import com.agentcraft.agents.AgentNpc;
import com.agentcraft.bridge.BridgeClient;
import com.agentcraft.build.BuildStudioBuilder;
import com.agentcraft.cinema.CinemaManager;
import com.agentcraft.cinema.CinemaScreen;
import com.agentcraft.cinema.CinemaFrameStore;
import com.agentcraft.rooms.Room;
import com.agentcraft.rooms.RoomManager;
import com.agentcraft.ui.Ui;
import com.agentcraft.village.HqIslandBuilder;
import com.agentcraft.village.IslandWorldBuilder;
import com.agentcraft.village.MvpWorldBuilder;
import com.agentcraft.village.SchoolBuilder;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The only command. MVP surface:
 *   /omo build                build the Studio (plaza + 4 code workstations + Hermes desk + standup screen + build studio + school)
 *   /omo studio               fly up to the floating Studio (the sky plaza)
 *   /omo ground               drop down to the real world (terrain below the Studio)
 *   /omo clear [radius]       wipe the canvas to pure void — agents, rooms, entities, blocks
 *   /omo school [subject]     build the school (classroom + Dean office) SW of the plaza + seat both
 *   /omo classroom [subject]  re-theme the classroom for a subject + re-seat tutor ada (the Dean's path)
 *   /omo buildstudio          build a glass viewing box + 16×16 plot, seat the live-build mason
 *   /omo spawn <id> <role>    spawn a Hermes villager in your current room
 *   /omo spawn-code <id> <cwd> <task>   spawn a Claude Code villager
 *   /omo team-up [cwd]        seat the Claude PTY team in the Code box
 *   /omo village-up           seat the four Hermes pods
 *   /omo cinema <url>         change what the cinema screen shows
 *   /omo cwd [room] <path>    set the default working dir for code terminals
 *   /omo say|despawn|list|room|approve|deny|reconnect
 */
public class HermesCommand implements CommandExecutor, TabCompleter {

    private final AgentCraftPlugin plugin;
    private final AgentManager agents;
    private final RoomManager rooms;
    private final BridgeClient bridge;
    /** The clickable chest-GUI face over these subcommands (see {@link HermesMenus}). */
    private final HermesMenus menus;

    public HermesCommand(AgentCraftPlugin plugin, AgentManager agents, RoomManager rooms, BridgeClient bridge) {
        this.plugin = plugin;
        this.agents = agents;
        this.rooms = rooms;
        this.bridge = bridge;
        this.menus = new HermesMenus(plugin, agents, rooms);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
        // Bare /omo opens the clickable menu (players); console gets the text list.
        if (args.length == 0) {
            if (sender instanceof Player pl) menus.openMain(pl);
            else sender.sendMessage(usageText());
            return true;
        }
        boolean consoleOk = switch (args[0].toLowerCase()) {
            case "list", "despawn", "cwd", "menu", "help", "?" -> true;
            default -> false;
        };
        if (!(sender instanceof Player) && !consoleOk) {
            sender.sendMessage("must be a player.");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "build" -> handleBuild((Player) sender);
            case "clear", "wipe" -> handleClear((Player) sender, args);
            case "island", "island-build" -> handleIslandBuild((Player) sender);
            case "school" -> handleSchool((Player) sender, args);
            case "classroom" -> handleClassroom((Player) sender, args);
            case "buildstudio" -> handleBuildStudio((Player) sender);
            case "hq" -> handleHq((Player) sender);
            case "studio", "tp-studio", "up" -> handleStudio((Player) sender);
            case "ground", "down" -> handleGround((Player) sender);
            case "spawn" -> handleSpawn((Player) sender, args);
            case "spawn-code", "spawncode" -> handleSpawnCode((Player) sender, args);
            case "despawn" -> handleDespawn(sender, args);
            case "list" -> handleList(sender);
            case "room" -> handleRoom((Player) sender, args);
            case "say" -> handleSay((Player) sender, args);
            case "revise" -> handleRevise((Player) sender, args);
            case "cinema" -> handleCinema((Player) sender, args);
            case "capture", "_start_app" -> handleCapture((Player) sender, args);
            case "cwd" -> handleCwd(sender, args);
            case "menu", "help", "?" -> {
                if (sender instanceof Player pl) menus.openMain(pl);
                else sender.sendMessage(usageText());
            }
            case "approve" -> handleApproval((Player) sender, args, true);
            case "deny" -> handleApproval((Player) sender, args, false);
            case "reconnect" -> {
                bridge.shutdown();
                bridge.connect();
                sender.sendMessage("reconnecting…");
            }
            default -> sender.sendMessage("unknown subcommand: " + args[0]);
        }
        return true;
    }

    /** Plain-text command list — the console fallback for the GUI menu. */
    private static Component usageText() {
        return Component.text(
            "Commands: build · studio · ground · clear · spawn · spawn-code · cinema · cwd · say · list · despawn · room · approve · deny · reconnect. "
            + "Players: type /omo for the clickable menu.",
            NamedTextColor.YELLOW);
    }

    // ── World build ──────────────────────────────────────────────────────

    private void handleBuild(Player p) {
        // The studio floats high in the sky (STUDIO_Y) so the real terrain below
        // stays a walkable world. `ground` is where the player is standing on
        // that terrain — we keep the world's join/respawn point there. `at` is
        // the same spot lifted to the studio altitude, where everything builds.
        Location ground = p.getLocation().clone();
        Location at = ground.clone();
        at.setY(STUDIO_Y);
        // Clean slate: tear down every prior agent, room, villager and the
        // structures they left behind so a fresh build never collides with an
        // old one (duplicate cinema rooms, a lost teacher, stale plates, …).
        wipeWorld(p.getWorld());
        p.sendMessage(Component.text("Building Omo Studio…", Ui.WAIT));
        MvpWorldBuilder builder = new MvpWorldBuilder(
                plugin, rooms, plugin.cinema().ensure(CinemaManager.DEFAULT_ID));
        MvpWorldBuilder.Result r = builder.build(at);

        // Point every wall (the per-station dev-site walls + the standup screen)
        // at its default URL so face/'s headless capture starts streaming each
        // one. setUrl() is a blocking HTTP call — push them off the main thread.
        final List<MvpWorldBuilder.CinemaChannel> channels = new ArrayList<>(r.cinemas());
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            for (MvpWorldBuilder.CinemaChannel c : channels) plugin.cinema().setUrl(c.id(), c.url());
        });

        // Live-build studio (the showcase of a villager physically doing the
        // work), WEST of the plaza: step inside the glass box, type a request,
        // and watch the mason build it live on the plot beyond the glass.
        int bx = at.getBlockX() - 46, bz = at.getBlockZ();
        BuildStudioBuilder studio = new BuildStudioBuilder(plugin, rooms, plugin.buildPlots());
        BuildStudioBuilder.Result bs =
                studio.build(new Location(at.getWorld(), bx + 0.5, at.getBlockY(), bz + 0.5));
        buildStudioPath(at.getWorld(), bx, at.getBlockY() - 1, at.getBlockZ(), bz + 2);
        agents.spawn(bs.villagerId(), bs.villagerRole(), bs.room(), bs.villagerHome(), true);
        sendSpawn(bs.villagerId(), bs.villagerRole(), bs.room(), p.getName(),
                bs.villagerHome(), plugin.getDataFolder().getAbsolutePath());

        // On-demand classroom — the school wing, with its tutor "ada" + the Dean
        // who re-themes it on request. It used to sit due west of the plaza, but
        // the build studio now owns the west-centre, so the school drops into the
        // free SOUTH-WEST quadrant (buildInStudio). Re-added to every /omo build
        // so the teacher + Dean are always present, not a separate step. Default
        // subject "Algebra" so the first build looks exactly like before.
        SchoolBuilder school = new SchoolBuilder(plugin, rooms);
        SchoolBuilder.Result s = school.buildInStudio(at, SchoolBuilder.DEFAULT_SUBJECT);
        agents.spawn(s.tutorId(), s.tutorRole(), s.room(), s.tutorHome());
        sendSpawn(s.tutorId(), s.tutorRole(), s.room(), p.getName(), s.tutorHome(), null);
        // Seat the Dean (stationary, Hermes brain → cwd null) between the
        // classroom and the plaza: talk to it, it re-themes the classroom.
        agents.spawn(s.deanId(), s.deanRole(), s.deanRoom(), s.deanHome(), true);
        sendSpawn(s.deanId(), s.deanRole(), s.deanRoom(), p.getName(), s.deanHome(), null);

        // Pre-seat each Hermes worker in the south row — the operational chat-
        // driven agents. Same pattern as the mason: chat one a task, watch it
        // execute on the board, right-click to open its full reasoning terminal.
        // Stationary so each stands at its desk facing whoever walks in; no cwd
        // (Hermes brains, not PTYs).
        for (MvpWorldBuilder.AgentSeat hw : r.hermesWorkers()) {
            agents.spawn(hw.id(), hw.role(), hw.room(), hw.home(), true);
            sendSpawn(hw.id(), hw.role(), hw.room(), p.getName(), hw.home(), null);
        }

        // The MvpWorldBuilder points the world's join/respawn at the sky studio.
        // Override it back to the GROUND directly below, so joining (and /omo
        // ground) lands you in the real world, not on the floating platform.
        World w = at.getWorld();
        int gy = groundSurfaceY(w, ground.getBlockX(), ground.getBlockZ());
        w.setSpawnLocation(ground.getBlockX(), gy, ground.getBlockZ());

        plugin.refreshCanonicalRoomAliases();
        p.setAllowFlight(true);
        p.teleport(r.spawn());
        p.setFlying(true);

        p.sendMessage(Component.text("Built Omo Studio — " + MvpWorldBuilder.STATIONS
                + " code workstations, " + MvpWorldBuilder.HERMES_WORKERS
                + " Hermes workers, standup screen, build studio and school.", Ui.OK));
        p.sendMessage(Component.text("Walk into the plaza" + Ui.SEP
                + "every spot has a sign telling you what to do.", Ui.BODY));
        p.sendMessage(Component.text("Drop to the real world with ", Ui.BODY)
                .append(Ui.cmdLine("/omo ground"))
                .append(Component.text(Ui.SEP + "fly back up with ", Ui.BODY))
                .append(Ui.cmdLine("/omo studio")));
    }

    /**
     * Lay a short 3-wide plaza-style path from the plaza's north edge to the
     * build-studio box doorway, so the studio reads as a place you walk to.
     */
    private void buildStudioPath(World w, int centerX, int fy, int zPlazaEdge, int zBoxDoor) {
        int lo = Math.min(zPlazaEdge, zBoxDoor), hi = Math.max(zPlazaEdge, zBoxDoor);
        for (int z = lo; z <= hi; z++) {
            for (int dx = -1; dx <= 1; dx++) {
                w.getBlockAt(centerX + dx, fy, z)
                        .setType(dx == 0 ? Material.POLISHED_DIORITE : Material.SMOOTH_QUARTZ, false);
            }
        }
    }

    /**
     * Demolish everything a previous build left behind so the next one starts
     * clean. Despawns every agent (and tells the runtime to drop its brain),
     * forgets every registered room (the builders re-create the canonical ones),
     * and removes every villager in the world (agent bodies + classroom
     * students, including orphans whose AgentManager entry was lost across a
     * runtime restart). Blocks are left to be overwritten in place by the
     * builder. Returns the number of villagers removed.
     */
    private int wipeWorld(World world) {
        for (AgentNpc n : new ArrayList<>(agents.all())) {
            JsonObject m = new JsonObject();
            m.addProperty("type", "despawn_agent");
            m.addProperty("agentId", n.agentId());
            bridge.send(m);
        }
        agents.removeAll();
        plugin.buildPlots().clearAll();

        for (Room r : new ArrayList<>(rooms.all())) {
            rooms.remove(r.name());
        }

        int removed = 0;
        if (world != null) {
            for (Villager v : world.getEntitiesByClass(Villager.class)) {
                v.remove();
                removed++;
            }
        }
        return removed;
    }

    // ── Clear / blank canvas ─────────────────────────────────────────────────

    /**
     * Wipe a square region around the player back to a blank canvas so a new
     * map can be built from scratch. In order: despawn every agent + forget
     * every room (the runtime drops its brains, old rooms can't collide),
     * delete every non-player <em>entity</em> in range (villagers, agent
     * holograms, cinema item frames, dropped items, paintings…), then clear
     * every <em>block</em> in range to air — ground included. This is a flat
     * void world, so what's left is pure empty space to build into. You're
     * put into flight so removing the floor under you doesn't drop you.
     *
     * <p>The block pass is spread across ticks (a per-tick budget) so the
     * server never freezes even for a large radius.
     *
     * <pre>
     *   /omo clear         wipe radius 80 to pure void
     *   /omo clear 120     custom radius (clamped 8–256)
     * </pre>
     */
    private void handleClear(Player p, String[] args) {
        int radius = 80;
        if (args.length >= 2) {
            try {
                radius = Integer.parseInt(args[1]);
            } catch (NumberFormatException ignored) {
                p.sendMessage(Component.text("usage: /omo clear [radius]", NamedTextColor.YELLOW));
                return;
            }
        }
        radius = Math.max(8, Math.min(256, radius));

        final World w = p.getWorld();
        final Location center = p.getLocation();
        final int cx = center.getBlockX();
        final int cz = center.getBlockZ();
        final int feetY = center.getBlockY();

        // Hover the player so removing the ground under them doesn't drop them
        // into the void (effectively a no-op if they're already flying).
        p.setAllowFlight(true);
        p.setFlying(true);

        // 1) Agents + rooms + villagers (runtime drops every brain), build plots.
        int villagers = wipeWorld(w);
        plugin.buildPlots().clearAll();

        // 2) Every other object in range: agent holograms, cinema item frames,
        //    dropped items, paintings — everything except the players standing here.
        int objects = 0;
        for (Entity e : w.getNearbyEntities(center, radius + 2, 96, radius + 2)) {
            if (e instanceof Player) continue;
            e.remove();
            objects++;
        }

        // 3) Every block → air, batched across ticks. Full world height (floor to
        //    sky), so any underlying flat-world grass plane below the build — not
        //    just the build itself — gets cleared too.
        final int r = radius;
        final int topY = w.getMaxHeight() - 1;
        final int botY = w.getMinHeight();
        final int yspan = topY - botY + 1;
        // Cap work per tick (~150k block checks; air checks are cheap) so even a
        // full-height clear at a large radius stays smooth.
        final long zspan = (2L * r + 1) * yspan;
        final int colsPerTick = Math.max(1, (int) (150_000L / Math.max(1L, zspan)));

        p.sendMessage(Component.text("cleared " + villagers + " villagers + " + objects
                + " objects + all rooms. wiping blocks (radius " + r + ")…", NamedTextColor.GRAY));

        new BukkitRunnable() {
            int x = cx - r;

            @Override
            public void run() {
                int cols = 0;
                while (x <= cx + r && cols < colsPerTick) {
                    for (int z = cz - r; z <= cz + r; z++) {
                        for (int y = botY; y <= topY; y++) {
                            Block b = w.getBlockAt(x, y, z);
                            if (b.getType() != Material.AIR) b.setType(Material.AIR, false);
                        }
                    }
                    x++;
                    cols++;
                }
                if (x > cx + r) {
                    cancel();
                    w.setSpawnLocation(cx, feetY, cz);
                    plugin.refreshCanonicalRoomAliases();
                    p.sendMessage(Component.text("Cleared to empty void.", Ui.OK));
                    p.sendMessage(Component.text("Build it back: ", Ui.BODY).append(Ui.cmdLine("/omo build")));
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    // ── Island build ───────────────────────────────────────────────────────

    /** Same four features as {@link #handleBuild}, but the Minecraft beach-island re-skin. */
    private void handleIslandBuild(Player p) {
        Location at = p.getLocation();
        wipeWorld(p.getWorld());
        p.sendMessage(Component.text("Building the Omo island…", Ui.WAIT));
        IslandWorldBuilder builder = new IslandWorldBuilder(
                plugin, rooms, plugin.cinema().ensure(CinemaManager.DEFAULT_ID));
        IslandWorldBuilder.Result r = builder.build(at);

        // Algebra 101 classroom — built into the WEST sector of the same island.
        SchoolBuilder school = new SchoolBuilder(plugin, rooms);
        SchoolBuilder.Result s = school.buildOnIsland(r.spawn());
        agents.spawn(s.tutorId(), s.tutorRole(), s.room(), s.tutorHome());
        sendSpawn(s.tutorId(), s.tutorRole(), s.room(), p.getName(), s.tutorHome(), null);

        plugin.refreshCanonicalRoomAliases();
        p.teleport(r.spawn());

        p.sendMessage(Component.text("Built the Omo island.", Ui.OK));
        p.sendMessage(Component.text("Step a plate to open a terminal" + Ui.SEP
                + "every spot has a sign.", Ui.BODY));
    }

    // ── School ───────────────────────────────────────────────────────────

    /**
     * Build the on-demand school (classroom + Dean office) and seat both the Dean
     * and the tutor. Accepts an optional {@code <subject...>} (defaults to
     * "Algebra"). Uses the same {@code buildInStudio} placement as
     * {@code /omo build} (anchored on the world spawn / plaza centre) so the
     * classroom lands deterministically and matches the re-theme path exactly.
     * Re-running rebuilds the rooms in place and re-uses the existing villagers.
     */
    private void handleSchool(Player p, String[] args) {
        String subject = args.length > 1
                ? String.join(" ", Arrays.copyOfRange(args, 1, args.length))
                : SchoolBuilder.DEFAULT_SUBJECT;
        Room spawnRoom = rooms.get("spawn");
        Location anchor = spawnRoom != null ? spawnRoom.center(p.getWorld()) : p.getLocation();
        p.sendMessage(Component.text("building the " + subject + " school SW of the plaza…", NamedTextColor.GRAY));

        SchoolBuilder builder = new SchoolBuilder(plugin, rooms);
        SchoolBuilder.Result r = builder.buildInStudio(anchor, subject);

        // Seat the tutor: render the NPC body + ask the runtime for its Hermes brain.
        agents.spawn(r.tutorId(), r.tutorRole(), r.room(), r.tutorHome());
        sendSpawn(r.tutorId(), r.tutorRole(), r.room(), p.getName(), r.tutorHome(), null);
        // Seat the Dean (stationary Hermes brain, no cwd) between classroom + plaza.
        agents.spawn(r.deanId(), r.deanRole(), r.deanRoom(), r.deanHome(), true);
        sendSpawn(r.deanId(), r.deanRole(), r.deanRoom(), p.getName(), r.deanHome(), null);

        p.teleport(r.deanHome());
        p.sendMessage(Component.text("Built the " + r.subject() + " school.", Ui.OK));
        p.sendMessage(Component.text("Tell the ", Ui.BODY)
                .append(Component.text(r.deanId(), Ui.AGENT))
                .append(Component.text(" what to learn, then sit in class — ", Ui.BODY))
                .append(Component.text(r.tutorId(), Ui.AGENT))
                .append(Component.text(" teaches you.", Ui.BODY)));
    }

    // ── Classroom re-theme (the Dean's open_classroom path) ──────────────────

    /**
     * Re-theme the single classroom for a new subject and re-seat the tutor "ada"
     * to teach it. Triggered by the Dean's {@code open_classroom} tool (which the
     * runtime relays as an {@code open_classroom_request} → {@code /omo
     * classroom <subject>}), or run directly.
     *
     * <p>Spawn is idempotent on agentId on BOTH sides, so simply re-sending a
     * spawn with a new role is a no-op — to actually re-theme ada's persona we
     * must DESPAWN it first (drop the local body + tell the runtime to drop its
     * brain), THEN rebuild + re-seat. The Dean is left untouched.
     */
    private void handleClassroom(Player p, String[] args) {
        String subject = args.length > 1
                ? String.join(" ", Arrays.copyOfRange(args, 1, args.length))
                : SchoolBuilder.DEFAULT_SUBJECT;
        p.sendMessage(Component.text("re-theming the classroom for " + subject + "…", NamedTextColor.GRAY));

        // 1) Despawn ada FIRST so the re-seat below re-themes the brain. Order
        //    matters: despawn (both sides), then spawn with the new role.
        agents.despawn(SchoolBuilder.TUTOR_ID);
        JsonObject despawn = new JsonObject();
        despawn.addProperty("type", "despawn_agent");
        despawn.addProperty("agentId", SchoolBuilder.TUTOR_ID);
        bridge.send(despawn);

        // 2) Rebuild the classroom themed for the subject, OVERRIDING the existing
        //    one IN PLACE. Anchor on the EXISTING classroom's own registered
        //    location rather than recomputing from the plaza — this can never
        //    drift or land beside the old classroom, even if the plaza "spawn"
        //    room was renamed by other map builders. Only the first-ever themed
        //    build (no classroom yet) falls back to the plaza anchor.
        SchoolBuilder builder = new SchoolBuilder(plugin, rooms);
        Room existing = rooms.get(SchoolBuilder.ROOM);
        SchoolBuilder.Result r;
        if (existing != null) {
            r = builder.rebuildAt(existing.center(p.getWorld()), subject);
        } else {
            Room spawnRoom = rooms.get("spawn");
            Location anchor = spawnRoom != null ? spawnRoom.center(p.getWorld()) : p.getLocation();
            r = builder.buildInStudio(anchor, subject);
        }

        // 3) Re-seat the tutor with the subject-derived role in room "classroom".
        agents.spawn(r.tutorId(), r.tutorRole(), r.room(), r.tutorHome());
        sendSpawn(r.tutorId(), r.tutorRole(), r.room(), p.getName(), r.tutorHome(), null);

        // 4) Confirm + face the player toward the classroom door.
        p.teleport(r.playerSpawn());
        p.sendMessage(Component.text("The classroom is now " + r.subject() + ".", Ui.OK));
        p.sendMessage(Component.text("Take a seat and type" + Ui.SEP, Ui.BODY)
                .append(Component.text(r.tutorId(), Ui.AGENT))
                .append(Component.text(" will teach you.", Ui.BODY)));
    }

    // ── Build studio ───────────────────────────────────────────────────────

    /**
     * Build the live-build studio at the player's feet: a glass viewing box,
     * a flat 16×16 clearable plot beyond its window, a registered {@code buildstudio}
     * room, and a seated "mason" villager (the live-build architect). The mason
     * runs on Claude (the {@code buildstudio} room maps to a CodeAgent in the
     * runtime); when the player types a request in chat, the brain's {@code build}
     * tool streams build-DSL ops back as build_ops frames that fill the plot.
     *
     * <p>We give the mason a valid cwd ({@code plugin.getDataFolder()}) because
     * the runtime requires a non-null cwd to construct the Claude brain. The
     * mason is spawned stationary so it always stands at the plot edge facing
     * the build.
     */
    private void handleBuildStudio(Player p) {
        p.sendMessage(Component.text("Building the Build Studio…", Ui.WAIT));

        BuildStudioBuilder.Result r =
                new BuildStudioBuilder(plugin, rooms, plugin.buildPlots()).build(p.getLocation());

        // Seat the mason: render the NPC body (pinned at the plot edge) + ask the
        // runtime for its Claude brain. cwd is the plugin data folder — the
        // runtime needs a non-null cwd to build the CodeAgent.
        String cwd = plugin.getDataFolder().getAbsolutePath();
        agents.spawn(r.villagerId(), r.villagerRole(), r.room(), r.villagerHome(), true);
        sendSpawn(r.villagerId(), r.villagerRole(), r.room(), p.getName(), r.villagerHome(), cwd);

        p.teleport(r.deckStand());
        p.sendMessage(Component.text("Build Studio ready.", Ui.OK));
        p.sendMessage(Component.text("Type what to build" + Ui.SEP + "the mason builds it live.", Ui.BODY));
    }

    // ── Omo HQ (the ADK + Gemini Chief of Staff) ────────────────────────────

    /**
     * Stand up Omo HQ at the player's position and seat the Chief of Staff.
     * The room is named "hq" so the runtime's roomKindFromName routes it to the
     * net-new ADK + Gemini brain (AdkAgent), not Hermes. This is the seed of the
     * futuristic HQ — the parametric build is layered on next.
     */
    private void handleHq(Player p) {
        String room = "hq";
        // Lift the build to the studio altitude; the player's x/z anchors it.
        Location loc = p.getLocation().clone();
        loc.setY(STUDIO_Y);

        // Raise the island + circular command building; the builder returns the
        // HQ centre (interior floor, facing the front door) where cos sits.
        HqIslandBuilder.Result hq = new HqIslandBuilder(plugin, rooms).build(loc);
        Location centre = hq.center();

        rooms.define(room, centre);
        rooms.setCurrentRoom(p, room);
        // The Chief of Staff sits at the heart of the command row (room "hq").
        String id = "cos";
        String role = "Chief of Staff";
        agents.spawn(id, role, room, centre, true);
        sendSpawn(id, role, room, p.getName(), centre, null);

        // The founding crew flanking cos: Comms (−x) and Growth (+x). Their
        // "fn-" room prefix routes them to the specialist Gemini brain in the
        // runtime — same spawn path as cos, just different rooms + seats.
        Location left  = hq.leftSeat();
        Location right = hq.rightSeat();
        rooms.define("fn-comms", left);
        agents.spawn("comms", "Comms", "fn-comms", left, true);
        sendSpawn("comms", "Comms", "fn-comms", p.getName(), left, null);
        rooms.define("fn-growth", right);
        agents.spawn("growth", "Growth", "fn-growth", right, true);
        sendSpawn("growth", "Growth", "fn-growth", p.getName(), right, null);

        buildHqDashboard(centre);
        startHqAtmosphere(centre);

        // Drop the player onto the island just outside the entrance (the door
        // faces −z), looking in toward the Chief of Staff.
        Location entrance = new Location(centre.getWorld(),
                centre.getX(), centre.getBlockY(), centre.getZ() - 14, 0f, 0f);
        p.setAllowFlight(true);
        p.teleport(entrance);

        p.sendMessage(Component.text("Omo HQ ready" + Ui.SEP + "Chief of Staff + Comms + Growth seated.", Ui.OK));
        p.sendMessage(Component.text("Three live screens behind the crew" + Ui.SEP + "org overview, ad performance, outreach — streaming.", Ui.BODY));
        p.sendMessage(Component.text("Talk to them" + Ui.SEP + "give the crew a business goal and watch it work.", Ui.BODY));
    }

    /** The single live HQ atmosphere task — tracked so re-running /omo hq never stacks. */
    private BukkitRunnable hqAtmosphere;

    /**
     * Start (or restart) the gentle magical atmosphere at the HQ: soft glow motes
     * drifting up from the central emblem and the desks, with a faint pulse. Keeps
     * particle counts low and the radius bounded so it never lags, and cancels any
     * prior task first so only one ever runs.
     */
    private void startHqAtmosphere(Location centre) {
        if (hqAtmosphere != null) {
            hqAtmosphere.cancel();
            hqAtmosphere = null;
        }
        final World w = centre.getWorld();
        if (w == null) return;
        final double cx = centre.getX(), cz = centre.getZ();
        final double fy = centre.getY();                  // interior floor (feet) level
        hqAtmosphere = new BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                // Stop if HQ is gone (room cleared) or the world unloaded.
                if (rooms.get("hq") == null || !w.isChunkLoaded((int) cx >> 4, (int) cz >> 4)) {
                    cancel();
                    hqAtmosphere = null;
                    return;
                }
                // Soft glow motes drifting up from the three desks (centre + flanks).
                for (int dx : new int[] { -4, 0, 4 }) {
                    w.spawnParticle(Particle.END_ROD, cx + dx, fy + 1.4, cz, 2,
                            0.25, 0.35, 0.25, 0.004);
                }
                // A wisp of soul-fire glow at the central emblem.
                w.spawnParticle(Particle.SOUL_FIRE_FLAME, cx, fy + 2.2, cz, 1,
                        0.3, 0.3, 0.3, 0.0);
                // A faint, slow pulse ring every ~2s: a low arc of glow over the desks.
                if (tick % 40 == 0) {
                    for (int a = 0; a < 360; a += 30) {
                        double rad = Math.toRadians(a);
                        double px = cx + Math.cos(rad) * 5.0;
                        double pz = cz + Math.sin(rad) * 5.0;
                        w.spawnParticle(Particle.END_ROD, px, fy + 0.8, pz, 1,
                                0.0, 0.05, 0.0, 0.0);
                    }
                }
                tick++;
            }
        };
        // Every 15 ticks (~0.75s): alive but cheap.
        hqAtmosphere.runTaskTimer(plugin, 20L, 15L);
    }

    /**
     * Raise THREE live dashboard screens as a console triptych across the back of
     * the command room, behind the seated crew — one per member, each a DIFFERENT
     * live board, the command-center wall the player sees walking in:
     *   • Comms seat (−x)   → /dash/comms    (outreach + lifecycle)
     *   • Chief of Staff    → /dash/society  (the whole-org overview graph)
     *   • Growth seat (+x)  → /dash/growth   (live Meta Ads performance)
     * NORTH-facing (toward the −z entrance) so they render right-side-up; the
     * CinemaScreen renderer mirror-compensates per column, same as the wings. The
     * panels are spaced 7 apart (cols+2) so their frame-clear boxes — which extend
     * ±1.5 past each screen plus the item-frame width — never delete a neighbour's
     * tiles, so all three stay full 5-wide. backZ=cz+3 keeps the 18-wide chord
     * inside the drum and its frames clear of the central soul-lantern strip.
     */
    private void buildHqDashboard(Location base) {
        if (plugin.cinema() == null) return;
        World w = base.getWorld();
        if (w == null) return;
        final int cols = 5, rows = 4;
        final int backZ = base.getBlockZ() + 3;          // across the back of the command room
        final int topY  = base.getBlockY() + 6;          // floats above the crew's heads, 4 tall
        hqScreen(w, base.getBlockX() - 7, backZ, topY, cols, rows, "hq-comms",  "comms");
        hqScreen(w, base.getBlockX(),     backZ, topY, cols, rows, "hq",        "society");
        hqScreen(w, base.getBlockX() + 7, backZ, topY, cols, rows, "hq-growth", "growth");
    }

    /** One NORTH-facing HQ screen centred on {@code centreX}, pointed at /dash/{board}. */
    private void hqScreen(World w, int centreX, int backZ, int topY, int cols, int rows,
                          String id, String board) {
        Location topLeftWall = new Location(w, centreX - (cols / 2), topY, backZ);
        CinemaFrameStore store = plugin.cinema().ensure(id);
        CinemaScreen.Result r = CinemaScreen.build(
                topLeftWall, BlockFace.NORTH, cols, rows, Material.POLISHED_BLACKSTONE, store);
        plugin.cinema().registerScreen(r.geometry());
        final String url = "http://127.0.0.1:8088/dash/" + board;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> plugin.cinema().setUrl(id, url));
    }

    // ── Studio ↕ ground teleports ────────────────────────────────────────────

    /** Altitude the floating Omo Studio is built at, well above the terrain. */
    private static final int STUDIO_Y = 200;

    /**
     * Fly up to the floating studio — the "spawn" plaza room the builder
     * registers up in the sky. If nothing's been built yet, point the player at
     * {@code /omo build}.
     */
    private void handleStudio(Player p) {
        Room studio = rooms.get("spawn");
        if (studio == null) {
            p.sendMessage(Component.text("No studio up there yet — build it first: ", Ui.BODY)
                    .append(Ui.cmdLine("/omo build")));
            return;
        }
        Location dst = studio.center(p.getWorld());
        dst.setYaw(p.getLocation().getYaw());
        dst.setPitch(p.getLocation().getPitch());
        p.setAllowFlight(true);
        p.teleport(dst);
        p.setFlying(true);
        p.sendMessage(Component.text("Up at the studio.", Ui.OK)
                .append(Component.text(Ui.SEP + "drop back with ", Ui.BODY))
                .append(Ui.cmdLine("/omo ground")));
    }

    /**
     * Drop down to the real world — the ground spawn beneath the studio. Lands
     * the player on the terrain surface (not inside a block) and stops flight.
     */
    private void handleGround(Player p) {
        World w = p.getWorld();
        Location g = w.getSpawnLocation();
        int gy = groundSurfaceY(w, g.getBlockX(), g.getBlockZ());
        Location dst = new Location(w, g.getBlockX() + 0.5, gy, g.getBlockZ() + 0.5,
                p.getLocation().getYaw(), p.getLocation().getPitch());
        p.teleport(dst);
        p.setFlying(false);
        p.sendMessage(Component.text("Back on the ground.", Ui.OK)
                .append(Component.text(Ui.SEP + "fly up with ", Ui.BODY))
                .append(Ui.cmdLine("/omo studio")));
    }

    /**
     * The y to stand on at (x,z): one above the highest solid terrain block
     * below the studio altitude. Scans down from well under {@link #STUDIO_Y}
     * so the floating platform itself is never mistaken for the ground.
     */
    private static int groundSurfaceY(World w, int x, int z) {
        int top = Math.min(STUDIO_Y - 20, w.getMaxHeight() - 1);
        for (int y = top; y > w.getMinHeight(); y--) {
            if (w.getBlockAt(x, y, z).getType().isSolid()) return y + 1;
        }
        return 64;
    }

    // ── Spawning ───────────────────────────────────────────────────────────

    private void handleSpawn(Player p, String[] args) {
        if (args.length < 3) { p.sendMessage("usage: /omo spawn <id> <role...>"); return; }
        String id = args[1];
        String role = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        String currentRoom = rooms.currentRoom(p);
        if (currentRoom == null) {
            Room r = rooms.define("home-" + id, p.getLocation());
            currentRoom = r.name();
            p.sendMessage(Component.text("created room '" + r.name() + "' at your position", NamedTextColor.GREEN));
        }
        Location home = p.getLocation();
        agents.spawn(id, role, currentRoom, home);
        sendSpawn(id, role, currentRoom, p.getName(), home, null);
        p.sendMessage(Component.text("spawned " + id + " (" + role + ") in " + currentRoom, NamedTextColor.AQUA));
    }

    private void handleSpawnCode(Player p, String[] args) {
        if (args.length < 4) {
            p.sendMessage(Component.text("usage: /omo spawn-code <id> <cwd> <task...>", NamedTextColor.YELLOW));
            return;
        }
        String id = args[1];
        String cwd = args[2];
        String role = String.join(" ", Arrays.copyOfRange(args, 3, args.length));

        File cwdFile = new File(cwd);
        if (!cwdFile.isAbsolute()) {
            p.sendMessage(Component.text("cwd must be an absolute path", NamedTextColor.RED));
            return;
        }
        if (!cwdFile.isDirectory()) {
            p.sendMessage(Component.text("cwd does not exist or is not a directory: " + cwd, NamedTextColor.RED));
            return;
        }

        String currentRoom = rooms.currentRoom(p);
        if (currentRoom == null) {
            Room r = rooms.define("workshop-" + id, p.getLocation());
            currentRoom = r.name();
            p.sendMessage(Component.text("created room '" + r.name() + "' at your position", NamedTextColor.GREEN));
        }
        Location home = p.getLocation();
        agents.spawn(id, role, currentRoom, home);
        sendSpawn(id, role, currentRoom, p.getName(), home, cwd);
        p.sendMessage(Component.text("spawned code villager " + id + " in " + currentRoom + " (cwd " + cwd + ")",
                NamedTextColor.AQUA));
    }

    /** Build + send a spawn_agent frame to the runtime. cwd null for Hermes agents. */
    private void sendSpawn(String id, String role, String room, String playerName, Location home, String cwd) {
        JsonObject m = new JsonObject();
        m.addProperty("type", "spawn_agent");
        m.addProperty("agentId", id);
        m.addProperty("role", role);
        m.addProperty("room", room);
        m.addProperty("playerName", playerName);
        if (cwd != null) m.addProperty("cwd", cwd);
        JsonObject vec = new JsonObject();
        vec.addProperty("x", home.getX());
        vec.addProperty("y", home.getY());
        vec.addProperty("z", home.getZ());
        m.add("home", vec);
        bridge.send(m);
    }

    // ── Cinema ───────────────────────────────────────────────────────────

    private void handleCinema(Player p, String[] args) {
        if (plugin.cinema() == null) {
            p.sendMessage(Component.text("The screen service isn't ready yet.", Ui.ERR));
            return;
        }
        if (args.length < 2) {
            p.sendMessage(Component.text("Type a URL to put on the standup screen:", Ui.BODY));
            p.sendMessage(Component.text("  ", Ui.BODY).append(Ui.cmdLine("/omo cinema")));
            return;
        }
        String id;
        String url;
        if (args.length == 2) {
            id = CinemaManager.DEFAULT_ID;
            url = args[1];
        } else {
            id = args[1];
            url = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        }
        final String finalId = id;
        final String finalUrl = normalizeUrl(url);
        final String screenName = screenName(finalId);
        final String shownUrl = finalUrl.replaceFirst("^https?://", "");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean ok = plugin.cinema().setUrl(finalId, finalUrl);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (ok) {
                    p.sendMessage(Component.text(screenName + " now shows ", Ui.OK)
                            .append(Component.text(shownUrl, Ui.PATH))
                            .append(Component.text(".", Ui.OK)));
                    p.sendMessage(Component.text("The wall repaints in a few seconds.", Ui.BODY));
                } else {
                    p.sendMessage(Component.text("Couldn't reach the screen service.", Ui.ERR));
                    p.sendMessage(Component.text("Is ./agentcraft running?" + Ui.SEP + "then try again.", Ui.BODY));
                }
            });
        });
    }

    /** Friendly name for a cinema channel id: main → Standup Screen, dev-N → Dev Wall N. */
    private static String screenName(String id) {
        if (id.equalsIgnoreCase(CinemaManager.DEFAULT_ID)) return "Standup Screen";
        if (id.toLowerCase().startsWith("dev-")) return "Dev Wall " + id.substring(4);
        return "Screen " + id;
    }

    /**
     * Coerce loose user input into a navigable URL. "localhost:3000" and
     * ":3000" both become "http://localhost:3000"; anything with a scheme is
     * left alone. Without this the headless capture navigates to garbage and
     * the screen stays blank.
     */
    private static String normalizeUrl(String raw) {
        String u = raw.trim();
        if (u.startsWith("http://") || u.startsWith("https://") || u.startsWith("about:")) return u;
        if (u.startsWith(":")) return "http://localhost" + u;          // :3000
        return "http://" + u;                                          // localhost:3000, example.com
    }

    // ── Window capture ───────────────────────────────────────────────────────

    /**
     * Mirror any macOS window into a cinema channel at up to 60 fps via
     * ScreenCaptureKit.
     *
     *   /omo capture            — list open windows; click one to start capturing it
     *   /omo capture stop       — stop all active captures
     *   /omo capture stop <id>  — stop one capture by cinema id
     */
    private void handleCapture(Player p, String[] args) {
        // /omo capture stop [cinema-id]
        if (args.length >= 2 && args[1].equalsIgnoreCase("stop")) {
            if (args.length >= 3) {
                String cid = args[2];
                callRuntimeAsync(p, "POST", "/api/window-capture/stop",
                        "{\"cinemaId\":\"" + cid + "\"}",
                        (body, ok) -> {
                            if (ok) p.sendMessage(Component.text("Stopped capture for \"" + cid + "\".", Ui.OK));
                            else    p.sendMessage(Component.text("Could not stop (runtime unreachable?).", Ui.ERR));
                        });
            } else {
                // Stop all — fetch active list then stop each.
                callRuntimeAsync(p, "GET", "/api/window-capture/list", null, (body, ok) -> {
                    if (!ok) { p.sendMessage(Component.text("Runtime unreachable.", Ui.ERR)); return; }
                    try {
                        var arr = new com.google.gson.JsonParser().parse(body)
                                .getAsJsonObject().getAsJsonArray("captures");
                        if (arr.isEmpty()) { p.sendMessage(Component.text("No active captures.", Ui.BODY)); return; }
                        for (var el : arr) {
                            String cid = el.getAsJsonObject().get("cinemaId").getAsString();
                            callRuntimeAsync(p, "POST", "/api/window-capture/stop",
                                    "{\"cinemaId\":\"" + cid + "\"}", (b2, ok2) -> {});
                        }
                        p.sendMessage(Component.text("Stopped " + arr.size() + " capture(s).", Ui.OK));
                    } catch (Exception ignored) {}
                });
            }
            return;
        }

        // Internal sub-route used by the clickable window list.
        // /omo capture _start_app <appName>
        if (args.length >= 3 && args[1].equals("_start_app")) {
            String appName  = args[2];
            String cinemaId = args.length >= 4 ? args[3] : CinemaManager.DEFAULT_ID;
            String payload  = "{\"cinemaId\":\"" + cinemaId + "\",\"appName\":\"" + appName + "\",\"fps\":60}";
            callRuntimeAsync(p, "POST", "/api/window-capture/start", payload, (body, ok) -> {
                if (ok) p.sendMessage(Component.text(
                        "Capturing \"" + appName + "\" in cinema \"" + cinemaId + "\" at 60 fps. Right-click the wall to go fullscreen.", Ui.OK));
                else    p.sendMessage(Component.text("Runtime unreachable.", Ui.ERR));
            });
            return;
        }

        // /omo capture  — fetch window list and render a clickable picker.
        callRuntimeAsync(p, "GET", "/api/window-capture/windows", null, (body, ok) -> {
            if (!ok) {
                p.sendMessage(Component.text("Could not reach runtime, or binary not built yet.", Ui.ERR));
                p.sendMessage(Component.text("Run:  face/capture/build.sh   then restart ./agentcraft", Ui.BODY));
                return;
            }
            try {
                var arr = new com.google.gson.JsonParser().parse(body)
                        .getAsJsonObject().getAsJsonArray("windows");

                // Show active captures first.
                callRuntimeAsync(p, "GET", "/api/window-capture/list", null, (lb, lok) -> {
                    java.util.Set<String> activeApps = new java.util.HashSet<>();
                    if (lok) {
                        try {
                            var active = new com.google.gson.JsonParser().parse(lb)
                                    .getAsJsonObject().getAsJsonArray("captures");
                            for (var el : active) {
                                var f = el.getAsJsonObject().getAsJsonObject("filter");
                                if (f != null && f.has("appName"))
                                    activeApps.add(f.get("appName").getAsString());
                            }
                        } catch (Exception ignored) {}
                    }

                    p.sendMessage(Component.text("── Open windows ─────────────────", Ui.BODY));
                    int shown = 0;
                    for (var el : arr) {
                        var w = el.getAsJsonObject();
                        if (!"window".equals(w.get("type").getAsString())) continue;
                        String app   = w.has("appName") ? w.get("appName").getAsString() : "";
                        String title = w.has("title")   ? w.get("title").getAsString()   : "";
                        if (app.isEmpty()) continue;
                        // Skip Minecraft itself to avoid capturing MC inside MC.
                        if (app.toLowerCase().contains("minecraft") && title.toLowerCase().contains("minecraft")) continue;

                        String label = title.isEmpty() || title.equals(app) ? app : app + " — " + title;
                        if (label.length() > 48) label = label.substring(0, 45) + "…";
                        boolean active = activeApps.contains(app);

                        net.kyori.adventure.text.Component line = active
                                ? Component.text("  ● " + label + "  [live]", Ui.OK)
                                : Component.text("  ○ " + label, Ui.BODY)
                                        .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                                                Component.text("Click to capture in cinema \"" + CinemaManager.DEFAULT_ID + "\"")))
                                        .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(
                                                "/omo capture _start_app " + app));
                        p.sendMessage(line);
                        if (++shown >= 12) {
                            p.sendMessage(Component.text("  … and more. Use: /omo capture _start_app <Name>", Ui.BODY));
                            break;
                        }
                    }
                    if (shown == 0)
                        p.sendMessage(Component.text("No windows found. Is the binary built?  face/capture/build.sh", Ui.ERR));
                    p.sendMessage(Component.text("  /omo capture stop   — stop all", Ui.BODY));
                });
            } catch (Exception e) {
                p.sendMessage(Component.text("Parse error: " + e.getMessage(), Ui.ERR));
            }
        });
    }

    @FunctionalInterface
    private interface RuntimeCallback { void accept(String body, boolean ok); }

    /** Fire-and-forget async call to the runtime HTTP API; callback runs on the main thread. */
    private void callRuntimeAsync(Player p, String method, String path, String jsonBody, RuntimeCallback cb) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            String body = "";
            boolean ok = false;
            try {
                String base = "http://127.0.0.1:" + (System.getenv("AGENTCRAFT_HTTP_PORT") != null
                        ? System.getenv("AGENTCRAFT_HTTP_PORT") : "8766");
                HttpURLConnection conn = (HttpURLConnection)
                        URI.create(base + path).toURL().openConnection();
                conn.setRequestMethod(method);
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(5000);
                String tok = plugin.getConfig().getString("runtime-token", "");
                if (!tok.isBlank()) conn.setRequestProperty("Authorization", "Bearer " + tok);
                if (jsonBody != null) {
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json");
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                    }
                }
                int code = conn.getResponseCode();
                try (var in = code < 400 ? conn.getInputStream() : conn.getErrorStream()) {
                    if (in != null) body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                ok = code >= 200 && code < 300;
            } catch (Exception e) {
                body = e.getMessage();
            }
            final String finalBody = body;
            final boolean finalOk = ok;
            plugin.getServer().getScheduler().runTask(plugin, () -> cb.accept(finalBody, finalOk));
        });
    }

    private static int parseInt(String s, int fallback) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return fallback; }
    }

    // ── Working dir (terminal cwd) ───────────────────────────────────────────

    /**
     * Set the working directory a coding terminal opens in — server-wide or for
     * one room. Persists to config.yml so it survives restarts; resolution lives
     * in {@link MvpWorldBuilder#terminalCwd}. Takes effect the next time a station
     * spawns fresh (despawn the running PTY to restart it now).
     *
     *   /omo cwd                       show the current default + overrides
     *   /omo cwd <path>                set the server-wide default (code rooms)
     *   /omo cwd reset                 clear it → falls back to ~/Fern (or ~)
     *   /omo cwd <room> <path>         set one room's override
     *   /omo cwd <room> reset          clear that room's override
     */
    private void handleCwd(CommandSender sender, String[] args) {
        var cfg = plugin.getConfig();
        if (args.length == 1) { showCwd(sender, cfg); return; }

        String a1 = args[1];
        boolean serverWide = a1.startsWith("/") || a1.startsWith("~")
                || a1.equalsIgnoreCase("reset") || a1.equalsIgnoreCase("default");

        if (serverWide) {
            if (a1.equalsIgnoreCase("reset") || a1.equalsIgnoreCase("default")) {
                cfg.set("terminal.code_cwd", "");
                plugin.saveConfig();
                sender.sendMessage(Component.text("Booths reset to the default folder.", Ui.OK));
                sender.sendMessage(Component.text("Takes effect next time a booth opens.", Ui.BODY));
                return;
            }
            String path = expandHome(String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
            if (!validDir(sender, path)) return;
            cfg.set("terminal.code_cwd", path);
            plugin.saveConfig();
            sender.sendMessage(Component.text("All booths now code in ", Ui.OK)
                    .append(Component.text(path, Ui.PATH))
                    .append(Component.text(".", Ui.OK)));
            sender.sendMessage(Component.text("Takes effect next time a booth opens" + Ui.SEP
                    + "step any plate to start one now.", Ui.BODY));
            return;
        }

        // Per-room override: /omo cwd <room> <path|reset>
        String room = a1;
        if (args.length < 3) {
            sender.sendMessage(Component.text("Add a folder after the booth:", Ui.BODY));
            sender.sendMessage(Component.text("  ", Ui.BODY).append(Ui.cmdLine("/omo cwd " + room)));
            return;
        }
        String rest = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        String id = MvpWorldBuilder.terminalAgentId(room);
        if (rest.equalsIgnoreCase("reset") || rest.equalsIgnoreCase("default") || rest.equals("-")) {
            cfg.set("terminal.room_cwd." + room, null);
            plugin.saveConfig();
            sender.sendMessage(Component.text(room, Ui.AGENT)
                    .append(Component.text(" is back on the default folder.", Ui.OK)));
            sender.sendMessage(Component.text("Restart it now: ", Ui.BODY)
                    .append(Ui.runButton("/omo despawn " + id, "/omo despawn " + id)));
            return;
        }
        String path = expandHome(rest);
        if (!validDir(sender, path)) return;
        cfg.set("terminal.room_cwd." + room, path);
        plugin.saveConfig();
        sender.sendMessage(Component.text(room, Ui.AGENT)
                .append(Component.text(" now codes in ", Ui.OK))
                .append(Component.text(path, Ui.PATH))
                .append(Component.text(".", Ui.OK)));
        sender.sendMessage(Component.text("Other booths keep the default" + Ui.SEP + "restart it now: ", Ui.BODY)
                .append(Ui.runButton("/omo despawn " + id, "/omo despawn " + id)));
    }

    /** Print the effective server default + any per-room overrides. */
    private void showCwd(CommandSender sender, org.bukkit.configuration.file.FileConfiguration cfg) {
        String home = System.getProperty("user.home");
        String def = cfg.getString("terminal.code_cwd");
        String effective = (def != null && !def.isBlank()) ? def
                : (new File(home + "/Fern").isDirectory() ? home + "/Fern" : home);
        sender.sendMessage(Component.text("Code repo / folder", Ui.PLACE));
        sender.sendMessage(Component.text("All booths: ", Ui.BODY).append(Component.text(effective, Ui.PATH)));
        var section = cfg.getConfigurationSection("terminal.room_cwd");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                String v = section.getString(key);
                if (v == null || v.isBlank()) continue;
                sender.sendMessage(Component.text("  ", Ui.BODY).append(Component.text(key, Ui.AGENT))
                        .append(Component.text(" → ", Ui.FAINT)).append(Component.text(v, Ui.PATH)));
            }
        }
        sender.sendMessage(Component.text("Set for all booths: ", Ui.BODY).append(Ui.cmdLine("/omo cwd")));
    }

    /** Expand a leading ~ to $HOME so users can type "~/repo". */
    private static String expandHome(String path) {
        String home = System.getProperty("user.home");
        if (path.equals("~")) return home;
        if (path.startsWith("~/")) return home + path.substring(1);
        return path;
    }

    /** True if {@code path} is an absolute, existing directory; else messages the sender. */
    private boolean validDir(CommandSender sender, String path) {
        File f = new File(path);
        if (!f.isAbsolute()) {
            sender.sendMessage(Component.text("That path must be absolute — start with / or ~.", Ui.ERR));
            return false;
        }
        if (!f.isDirectory()) {
            sender.sendMessage(Component.text("No folder there: ", Ui.ERR).append(Component.text(path, Ui.PATH)));
            return false;
        }
        return true;
    }

    // ── Misc ───────────────────────────────────────────────────────────────

    private void handleDespawn(CommandSender s, String[] args) {
        if (args.length < 2) { s.sendMessage("usage: /omo despawn <id>"); return; }
        String id = args[1];
        agents.despawn(id);
        JsonObject m = new JsonObject();
        m.addProperty("type", "despawn_agent");
        m.addProperty("agentId", id);
        bridge.send(m);
        s.sendMessage("despawned " + id);
    }

    private void handleList(CommandSender s) {
        if (agents.all().isEmpty()) { s.sendMessage("no active agents."); return; }
        for (AgentNpc n : agents.all()) {
            s.sendMessage(Component.text("- " + n.agentId() + " — " + n.role() + " @ " + n.room(), NamedTextColor.AQUA));
        }
    }

    private void handleRoom(Player p, String[] args) {
        if (args.length < 2) {
            String cur = rooms.currentRoom(p);
            p.sendMessage("current room: " + (cur == null ? "(none)" : cur));
            return;
        }
        switch (args[1].toLowerCase()) {
            case "define" -> {
                if (args.length < 3) { p.sendMessage("usage: /omo room define <name>"); return; }
                Room r = rooms.define(args[2], p.getLocation());
                p.sendMessage(Component.text("defined room '" + r.name() + "' (radius " + r.radius() + ")",
                        NamedTextColor.GREEN));
            }
            case "here" -> {
                Room here = rooms.roomAt(p.getLocation());
                p.sendMessage(here == null ? "you are not in a defined room." : "in room: " + here.name());
            }
            default -> p.sendMessage("usage: /omo room define <name> | here");
        }
    }

    private void handleSay(Player p, String[] args) {
        if (args.length < 3) { p.sendMessage("usage: /omo say <id> <text...>"); return; }
        String id = args[1];
        String text = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        JsonObject m = new JsonObject();
        m.addProperty("type", "player_message");
        m.addProperty("agentId", id);
        m.addProperty("playerName", p.getName());
        m.addProperty("text", text);
        bridge.send(m);
    }

    /**
     * Revise the work / live dashboard of the agent you're standing next to.
     * Routes the prompt to the nearest villager as a "Revise:" message; the agent
     * (Gemini) re-does its work and updates its room's dashboard via dashboard_update.
     */
    private void handleRevise(Player p, String[] args) {
        if (args.length < 2) {
            p.sendMessage(Component.text("usage: /omo revise <what to change...>", Ui.BODY));
            return;
        }
        String text = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        AgentNpc target = agents.nearest(p.getLocation(), 28);
        if (target == null) {
            p.sendMessage(Component.text("Stand near an agent" + Ui.SEP + "then /omo revise to change its work or its screen.", Ui.BODY));
            return;
        }
        JsonObject m = new JsonObject();
        m.addProperty("type", "player_message");
        m.addProperty("agentId", target.agentId());
        m.addProperty("playerName", p.getName());
        m.addProperty("text", "Revise: " + text
                + ". Then update the live dashboard on the screen in your room by calling dashboard_update with your function_id so it reflects this.");
        bridge.send(m);
        p.sendMessage(Component.text("Revising with " + target.agentId() + Ui.SEP + text, Ui.OK));
    }

    private void handleApproval(Player p, String[] args, boolean approved) {
        String callId = args.length >= 2 ? args[1] : null;
        AgentManager.PendingApproval pa = agents.popApproval(p, callId);
        if (pa == null) { p.sendMessage("no pending approvals."); return; }
        JsonObject m = new JsonObject();
        m.addProperty("type", "tool_approval");
        m.addProperty("agentId", pa.agentId());
        m.addProperty("callId", pa.callId());
        m.addProperty("approved", approved);
        bridge.send(m);
        p.sendMessage((approved ? "approved " : "denied ") + pa.tool() + " for " + pa.agentId());
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("build", "studio", "ground", "clear", "island", "school", "classroom", "buildstudio",
                    "spawn", "spawn-code", "cinema", "capture", "cwd", "say", "despawn", "list", "room",
                    "approve", "deny", "reconnect");
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("clear") || args[0].equalsIgnoreCase("wipe"))) {
            return List.of("80", "120", "200");
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("despawn") || args[0].equalsIgnoreCase("say"))) {
            List<String> ids = new ArrayList<>();
            for (AgentNpc n : agents.all()) ids.add(n.agentId());
            return ids;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("cwd")) {
            // First token can be a terminal room name (per-room override) or
            // "reset" to clear the server-wide default.
            List<String> opts = new ArrayList<>();
            for (Room r : rooms.all()) {
                if (MvpWorldBuilder.isCodeRoom(r.name())
                        || r.name().equalsIgnoreCase(MvpWorldBuilder.HERMES_ROOM)) {
                    opts.add(r.name());
                }
            }
            opts.add("reset");
            return opts;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("cwd")) return List.of("reset");
        if (args.length == 2 && args[0].equalsIgnoreCase("room")) return List.of("define", "here");
        if (args.length == 2 && args[0].equalsIgnoreCase("capture"))
            return List.of("app", "screen", "stop");
        return List.of();
    }
}

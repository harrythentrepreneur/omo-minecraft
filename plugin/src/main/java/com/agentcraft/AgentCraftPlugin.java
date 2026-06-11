package com.agentcraft;

import com.agentcraft.agents.AgentManager;
import com.agentcraft.bridge.BridgeClient;
import com.agentcraft.bridge.IncomingHandler;
import com.agentcraft.build.BuildPlotManager;
import com.agentcraft.cinema.CinemaManager;
import com.agentcraft.commands.HermesCommand;
import com.agentcraft.listeners.AgentClickListener;
import com.agentcraft.listeners.CalculatorListener;
import com.agentcraft.listeners.ChatListener;
import com.agentcraft.listeners.CinemaInteractListener;
import com.agentcraft.listeners.CinemaSeatListener;
import com.agentcraft.listeners.HermesMenuListener;
import com.agentcraft.listeners.ListeningRoomListener;
import com.agentcraft.listeners.MovementListener;
import com.agentcraft.listeners.NotepadListener;
import com.agentcraft.listeners.TerminalPlateListener;
import com.agentcraft.listeners.VoidWorldListener;
import com.agentcraft.onboarding.WelcomeListener;
import com.agentcraft.rooms.RoomManager;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * AgentCraft MVP. Four features, one flat world:
 *   1. Hermes glass box   — chat "agent spawn <task>" to seat a Hermes
 *                           villager in an empty pod; step on the plate to
 *                           open its live Hermes terminal.
 *   2. Code glass box      — /hermes team-up seats the Claude PTY team; step
 *                           on the plate (or chat nearby) to open the terminal.
 *   3. Cinema              — a map-wall screen mirroring /hermes cinema <url>
 *                           (defaults to localhost:3000).
 *   4. Voice               — press V in-game; Omo (Gemini Live) drives spawn /
 *                           teleport / terminal over the bridge.
 *
 * The world is built by {@link com.agentcraft.village.MvpWorldBuilder} via
 * {@code /hermes build}. Everything else (HQ dashboards, hologram metrics,
 * sky islands, portals) was removed for the MVP.
 */
public final class AgentCraftPlugin extends JavaPlugin {

    private BridgeClient bridge;
    private AgentManager agents;
    private RoomManager rooms;
    private CinemaManager cinema;
    private BuildPlotManager buildPlots;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.rooms = new RoomManager(this);
        backfillCanonicalRoomAliases();
        this.agents = new AgentManager(this);
        this.buildPlots = new BuildPlotManager();
        this.bridge = new BridgeClient(this);

        this.cinema = new CinemaManager(this);
        // Pre-create the default "main" store so HTTP polling starts now — the
        // wall paints whenever /hermes build places it. Seed the runtime with
        // the default channel so face's headless capture warms up early.
        this.cinema.ensure(CinemaManager.DEFAULT_ID);
        String defaultUrl = getConfig().getString("cinema.default_url", "http://localhost:3000");
        getServer().getScheduler().runTaskAsynchronously(this,
                () -> this.cinema.setUrl(CinemaManager.DEFAULT_ID, defaultUrl));

        this.bridge.setHandler(new IncomingHandler(this, agents, rooms));
        this.bridge.connect();

        HermesCommand cmd = new HermesCommand(this, agents, rooms, bridge);
        // Command renamed /hermes -> /omo; `hermes` stays as a plugin.yml alias
        // so old muscle-memory and every internal `hermes …` dispatch still
        // resolve. getCommand() matches the primary NAME only, so this must move
        // to "omo" in lockstep with the plugin.yml rename or it NPEs on enable.
        getCommand("omo").setExecutor(cmd);
        getCommand("omo").setTabCompleter(cmd);
        // Clicks in the /omo chest-GUI menu tree (bare /omo opens it).
        getServer().getPluginManager().registerEvents(new HermesMenuListener(), this);

        getServer().getPluginManager().registerEvents(new ChatListener(this, agents, bridge, rooms), this);
        getServer().getPluginManager().registerEvents(new MovementListener(this, rooms, bridge), this);
        getServer().getPluginManager().registerEvents(new VoidWorldListener(), this);
        // Startup screen: welcome title + plaza orientation + the re-readable Guide book.
        getServer().getPluginManager().registerEvents(new WelcomeListener(this), this);
        getServer().getPluginManager().registerEvents(new CinemaSeatListener(this, rooms), this);
        // Interactive cinema: look + left-click to click the page, scroll to
        // scroll, F to type. The same controller force-pushes fresh map frames
        // to nearby viewers each tick so the wall plays at video rates.
        getServer().getPluginManager().registerEvents(
                new CinemaInteractListener(this, cinema.input()), this);
        getServer().getScheduler().runTaskTimer(this, () -> {
            if (cinema != null) cinema.input().tick(getServer().getOnlinePlayers());
        }, 20L, 1L);
        // Right-click a lodestone calculator console in a classroom → open the GUI.
        getServer().getPluginManager().registerEvents(new CalculatorListener(this, rooms), this);
        // Right-click the classroom barrel → hand out a real book-and-quill notepad.
        getServer().getPluginManager().registerEvents(new NotepadListener(this, rooms), this);
        // Pressure plate at a glass box → spawn that box's PTY agent (if none)
        // and open its terminal.
        getServer().getPluginManager().registerEvents(
                new TerminalPlateListener(this, agents, bridge, rooms), this);
        // Right-click any agent villager → open that agent's terminal directly.
        getServer().getPluginManager().registerEvents(
                new AgentClickListener(this, agents), this);
        // Listening Room: RECORD lever arms whisper capture; DISTILL button turns
        // the transcript into paste-ready prompts (copied to the host clipboard).
        getServer().getPluginManager().registerEvents(
                new ListeningRoomListener(this, rooms), this);

        lockToNoon();
        getServer().getScheduler().runTaskTimer(this, this::lockToNoon, 20L * 5, 20L * 5);

        getLogger().info("AgentCraft (MVP) enabled. Bridge target: " + getConfig().getString("bridge.url"));
    }

    /**
     * Map the bare canonical ids the voice layer uses ("code", "cinema",
     * "agents", "hq") onto whatever room {@link com.agentcraft.village.MvpWorldBuilder}
     * registered. Safe to call repeatedly; {@code /hermes build} re-invokes it
     * via {@link #refreshCanonicalRoomAliases()} after a fresh build.
     */
    private void backfillCanonicalRoomAliases() {
        // alias -> source room (rooms "code"/"hermes"/"cinema"/"spawn" are
        // registered directly by the builder; these just map the extra
        // voice phrases onto them).
        String[][] pairs = {
            { "agents",   "hermes" },
            { "task",     "hermes" },
            { "hq",       "spawn" },
            { "lobby",    "spawn" },
        };
        for (String[] row : pairs) {
            if (rooms.defineAlias(row[0], row[1], true)) {
                getLogger().info("[room-alias] " + row[0] + " -> " + row[1]);
            }
        }
    }

    /** Public hook so {@code /hermes build} can re-align aliases post-build. */
    public void refreshCanonicalRoomAliases() {
        backfillCanonicalRoomAliases();
    }

    private void lockToNoon() {
        for (World world : getServer().getWorlds()) {
            if (world.getEnvironment() != World.Environment.NORMAL) continue;
            world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
            world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
            world.setTime(6000L);
            world.setStorm(false);
            world.setThundering(false);
            world.setClearWeatherDuration(Integer.MAX_VALUE);
        }
    }

    @Override
    public void onDisable() {
        if (cinema != null) cinema.shutdown();
        if (bridge != null) bridge.shutdown();
        if (agents != null) agents.removeAll();
        if (buildPlots != null) buildPlots.clearAll();
    }

    public BridgeClient bridge() { return bridge; }
    public AgentManager agents() { return agents; }
    public RoomManager rooms() { return rooms; }
    public CinemaManager cinema() { return cinema; }
    public BuildPlotManager buildPlots() { return buildPlots; }
}

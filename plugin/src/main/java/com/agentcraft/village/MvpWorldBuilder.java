package com.agentcraft.village;

import com.agentcraft.AgentCraftPlugin;
import com.agentcraft.cinema.CinemaFrameStore;
import com.agentcraft.cinema.CinemaManager;
import com.agentcraft.cinema.CinemaScreen;
import com.agentcraft.rooms.RoomManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.FaceAttachable;
import org.bukkit.block.data.type.Switch;
import org.bukkit.block.sign.Side;

import java.util.ArrayList;
import java.util.List;

/**
 * "The Studio" — a multiplayer coding + agent-launching campus, built flat at
 * the player's feet by {@code /omo build}. One open plaza (the team commons)
 * ringed by the working surfaces a team of four needs to give agents tasks and
 * <em>watch</em> them work three ways: the live thinking/tool-call stream, the
 * live screen of what the agent is producing, and the villager physically doing
 * the work.
 *
 * <pre>
 *     ┌code-1┐ ┌code-2┐ ┌code-3┐ ┌code-4┐     NORTH: a row of 4 coding workstations.
 *     │ ▒dev │ │ ▒dev │ │ ▒dev │ │ ▒dev │      Each booth is one coder's station:
 *     │ NPC  │ │ NPC  │ │ NPC  │ │ NPC  │        • a plate → that station's OWN claude
 *     │[plate]│ │[plate]│ │[plate]│ │[plate]│      terminal (its own PTY, so 4 people
 *     └──────┘ └──────┘ └──────┘ └──────┘          never collide on one session)
 *  ┌─────┐                              ┌──────┐    • a live dev-site wall (cinema dev-N,
 *  │BUILD│····  plaza · Omo hub (V) ····│STANDUP│      localhost:300{N-1}) on the back wall
 *  │STUDIO│           (commons)          │SCREEN │    • the working villager + reasoning board
 *  └─────┘            ┌─HERMES/OMO─┐     └──────┘
 *   (west)            │  ops desk  │      (east, "main" channel)
 *                     └────────────┘
 * </pre>
 *
 * Each station registers one room ({@code code-1}..{@code code-N}) + one
 * pressure plate; {@link com.agentcraft.listeners.TerminalPlateListener} turns a
 * plate step into "spawn this station's claude PTY if absent, then open its
 * terminal", so the booths sit empty until a coder walks in. The Hermes/Omo
 * booth ({@link #HERMES_ROOM}) is the operational/voice desk — its "screen" is
 * the Omo face HUD, so it has no dev wall. The Build Studio (live-build mason)
 * and standup cinema are added by {@code HermesCommand#handleBuild}.
 */
public final class MvpWorldBuilder {

    public record Result(Location spawn, List<String> roomsCreated,
                         List<CinemaChannel> cinemas, List<AgentSeat> hermesWorkers, int blocksPlaced) {}

    /** A cinema wall the command should point at a URL (async) after the build. */
    public record CinemaChannel(String id, String url) {}

    /** A villager the command should pre-seat after the build (id, role, room, home). */
    public record AgentSeat(String id, String role, String room, Location home) {}

    // Legacy single-box room names. Kept for the island reskin (IslandWorldBuilder)
    // and the no-arg terminal fallback (IncomingHandler). The Studio uses the
    // numbered station rooms below for code; HERMES_ROOM is the one Hermes booth.
    public static final String CODE_ROOM = "code";
    public static final String HERMES_ROOM = "hermes";

    // The Studio's operational Hermes worker (south booth): a chat-tasked
    // HermesAgent you watch via the 6-line board + right-click reasoning
    // terminal — the same interaction as the build mason, on the Hermes brain.
    // Room "hermes-worker" → agent_home kind (full toolset, sensitive gated).
    public static final String HERMES_WORKER_ROOM = "hermes-worker";
    public static final String HERMES_WORKER_ID = "hermes";
    public static final String HERMES_WORKER_ROLE = "Hermes operations agent — tasks, email, ads & notes";

    /** How many Hermes worker booths sit in the south row (the original + flankers). */
    public static final int HERMES_WORKERS = 3;

    /** Room name for Hermes worker {@code i} (0-based): 0 = the original "hermes-worker". */
    public static String hermesWorkerRoom(int i) { return i == 0 ? HERMES_WORKER_ROOM : "hermes-" + (i + 1); }

    /** Agent id for Hermes worker {@code i} (0-based): 0 = the original "hermes". */
    public static String hermesWorkerId(int i) { return i == 0 ? HERMES_WORKER_ID : "hermes-" + (i + 1); }

    /** How many coding workstations the campus seats (the "team of N"). */
    public static final int STATIONS = 4;

    /** Room name for coding workstation {@code i} (1-based): {@code code-1}.. */
    public static String stationRoom(int i) { return "code-" + i; }

    /** Cinema id for workstation {@code i}'s dev-site wall: {@code dev-1}.. */
    public static String stationCinema(int i) { return "dev-" + i; }

    /** Default dev-server URL a station's wall starts on: localhost:300{i-1}. */
    public static String stationUrl(int i) { return "http://localhost:" + (3000 + i - 1); }

    /** True if {@code room} is a coding station ("code" or "code-N"). */
    public static boolean isCodeRoom(String room) {
        if (room == null) return false;
        String r = room.toLowerCase();
        return r.equals(CODE_ROOM) || r.startsWith("code-");
    }

    /**
     * The PTY agent id a terminal room maps to. The legacy single boxes keep
     * their well-known ids ("claude"/"hermes", which the runtime's no-arg
     * terminal fallback special-cases); every numbered workstation uses its own
     * room name as its id ("code-2"), so four stations are four PTYs.
     */
    public static String terminalAgentId(String room) {
        if (room == null) return null;
        String r = room.toLowerCase();
        if (r.equals(CODE_ROOM)) return "claude";
        if (r.equals(HERMES_ROOM)) return "hermes";
        return room;
    }

    /**
     * Resolve the working directory a terminal room's PTY should open in.
     * Precedence (first non-blank wins):
     *   1. per-room override — {@code terminal.room_cwd.<room>} (set live by
     *      {@code /omo cwd <room> <path>});
     *   2. type-wide default — {@code terminal.code_cwd} for coding stations,
     *      {@code terminal.hermes_cwd} for the Hermes booth;
     *   3. built-in fallback — {@code $HOME/Fern} for coding stations if it
     *      exists, otherwise {@code $HOME}.
     * A blank config value means "fall through to the next level".
     */
    public static String terminalCwd(org.bukkit.configuration.file.FileConfiguration cfg, String room) {
        String home = System.getProperty("user.home");
        if (room != null) {
            String perRoom = cfg.getString("terminal.room_cwd." + room);
            if (perRoom != null && !perRoom.isBlank()) return perRoom;
        }
        if (isCodeRoom(room)) {
            String def = cfg.getString("terminal.code_cwd");
            if (def != null && !def.isBlank()) return def;
            String fern = home + "/Fern";
            return new java.io.File(fern).isDirectory() ? fern : home;
        }
        String def = cfg.getString("terminal.hermes_cwd");
        if (def != null && !def.isBlank()) return def;
        return home;
    }

    private final AgentCraftPlugin plugin;
    private final RoomManager rooms;
    private final CinemaFrameStore mainStore;

    public MvpWorldBuilder(AgentCraftPlugin plugin, RoomManager rooms, CinemaFrameStore mainStore) {
        this.plugin = plugin;
        this.rooms = rooms;
        this.mainStore = mainStore;
    }

    public Result build(Location center) {
        World w = center.getWorld();
        int cx = center.getBlockX();
        int cz = center.getBlockZ();
        int wy = center.getBlockY();   // walk level (player's feet)
        int fy = wy - 1;               // floor blocks sit one below
        int placed = 0;
        List<String> created = new ArrayList<>();
        List<CinemaChannel> cinemas = new ArrayList<>();

        placed += buildPlaza(w, cx, fy, cz);

        // CENTRE: the Command Dome — one big glass dome you walk into, with a
        // throne at its centre and all the agents ringed around the inner walls
        // FACING INWARD, each with its screen on the wall behind it. Right-click
        // the throne to sit, then turn 360° to watch every agent work; step a code
        // plate (a few paces away) or use the /omo menu to command any of them.
        // This replaces the old spread-out north/south agent rows — no more
        // trekking from room to room.
        List<AgentSeat> hermesWorkers = new ArrayList<>();
        placed += buildCommandDome(w, cx, fy, cz, created, cinemas, hermesWorkers);

        // EAST: the shared standup screen — the "main" cinema channel, the one
        // /omo cinema <url> targets by default. The whole team gathers in the
        // plaza and watches it together (demos, the live app, a deploy).
        placed += buildCinema(w, cx + STANDUP_DX, fy, cz, created);
        cinemas.add(new CinemaChannel(CinemaManager.DEFAULT_ID, "http://localhost:3000"));

        // NORTH-EAST: the Listening Room — a calm transcription booth. Its west
        // door opens straight onto the plaza's NE corner; inside, the live
        // whisper transcript fills the east wall, a RECORD lever arms the mic,
        // and a DISTILL button turns what was said into paste-ready prompts.
        placed += buildListeningRoom(w, cx + LISTEN_DX, fy, cz + LISTEN_DZ, created, cinemas);

        // Omo hub + signage at the centre of the commons.
        placed += buildSpawnHub(w, cx, fy, cz);

        // Spawn inside the dome, a few paces south of the throne, facing it.
        Location spawn = new Location(w, cx + 0.5, wy, cz + 4.5, 180f, 0f);
        w.setSpawnLocation(cx, wy, cz + 4);
        rooms.define("spawn", spawn, 6);
        created.add("spawn");

        return new Result(spawn, created, cinemas, hermesWorkers, placed);
    }

    // ── Layout constants (offsets from the build centre) ─────────────────────

    private static final int STANDUP_DX = 40;              // standup cinema, east of centre
    private static final int LISTEN_DX = 33, LISTEN_DZ = -16; // Listening Room, north-east corner

    // ── Plaza ───────────────────────────────────────────────────────────

    /**
     * The team commons: a polished-diorite grid on smooth quartz. Stretches far
     * enough north to sit under the workstation row (whose booths overwrite their
     * own footprints) so the gaps between booths read as plaza, not bare ground.
     */
    private int buildPlaza(World w, int cx, int fy, int cz) {
        int placed = 0;
        for (int dx = -30; dx <= 30; dx++) {
            for (int dz = -31; dz <= 27; dz++) {
                boolean grid = (dx % 4 == 0) || (dz % 4 == 0);
                w.getBlockAt(cx + dx, fy, cz + dz)
                        .setType(grid ? Material.POLISHED_DIORITE : Material.SMOOTH_QUARTZ, false);
                placed++;
            }
        }
        // A 3-wide approach east to the standup cinema and west to the build studio,
        // so both annexes read as places you walk to from the commons.
        for (int dx = 30; dx <= STANDUP_DX - 8; dx++) placed += pathTile(w, cx + dx, fy, cz);
        for (int dx = -44; dx <= -30; dx++) placed += pathTile(w, cx + dx, fy, cz);
        return placed;
    }

    private int pathTile(World w, int x, int fy, int cz) {
        for (int dz = -1; dz <= 1; dz++) {
            w.getBlockAt(x, fy, cz + dz)
                    .setType(dz == 0 ? Material.POLISHED_DIORITE : Material.SMOOTH_QUARTZ, false);
        }
        return 3;
    }

    // ── Command Dome (the Observatory: 360° agents around a central throne) ───

    private static final int DOME_HX = 11, DOME_HZ = 11, DOME_H = 8; // 23×23×8 glass dome
    private static final int DEV_COLS = 7, DEV_ROWS = 4;             // per-agent dev-wall tiles
    private static final int CODE_OFF = 5;                            // code stations sit ±5 along the N/S walls

    /**
     * The Observatory: one big glass dome you walk into, a raised throne at its
     * centre, and every agent ringed around the inner walls FACING INWARD with its
     * screen on the wall behind it. Right-click the throne to sit, then turn 360°
     * to watch them all; step a code plate (a few paces away) or use the /hermes
     * menu to command any of them.
     *
     *   N wall: code-1, code-2   ·   S wall: code-3, code-4 (+ the entrance)
     *   E wall: hermes           ·   W wall: hermes-2, hermes-3
     *
     * Code stations are lazy — a plate spawns that station's own claude PTY. The
     * Hermes workers are pre-seated by the command (returned via {@code hermesWorkers}).
     */
    private int buildCommandDome(World w, int cx, int fy, int cz,
                                 List<String> created, List<CinemaChannel> cinemas,
                                 List<AgentSeat> hermesWorkers) {
        int placed = 0;
        int wy = fy + 1;

        // Shell: a polished-diorite-ringed quartz floor, light-blue glass walls
        // with quartz-pillar corners, and a glass roof.
        for (int dx = -DOME_HX; dx <= DOME_HX; dx++) {
            for (int dz = -DOME_HZ; dz <= DOME_HZ; dz++) {
                boolean ring = Math.abs(dx) == DOME_HX || Math.abs(dz) == DOME_HZ;
                w.getBlockAt(cx + dx, fy, cz + dz)
                        .setType(ring ? Material.POLISHED_DIORITE : Material.SMOOTH_QUARTZ, false);
                w.getBlockAt(cx + dx, wy + DOME_H, cz + dz)
                        .setType(Material.LIGHT_BLUE_STAINED_GLASS, false);
                placed += 2;
                if (!ring) continue;
                boolean corner = Math.abs(dx) == DOME_HX && Math.abs(dz) == DOME_HZ;
                for (int dy = 0; dy < DOME_H; dy++) {
                    w.getBlockAt(cx + dx, wy + dy, cz + dz)
                            .setType(corner ? Material.QUARTZ_PILLAR : Material.LIGHT_BLUE_STAINED_GLASS, false);
                    placed++;
                }
            }
        }
        // Entrance: a 3-wide doorway on the SOUTH wall (between the two south
        // screens), plus a short welcome strip out onto the plaza + a marquee.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = 0; dy < 3; dy++) {
                w.getBlockAt(cx + dx, wy + dy, cz + DOME_HZ).setType(Material.AIR, false);
            }
            w.getBlockAt(cx + dx, fy, cz + DOME_HZ + 1).setType(Material.POLISHED_DIORITE, false);
            placed++;
        }
        placed += wallSign(w, cx, wy + 3, cz + DOME_HZ, BlockFace.SOUTH,
                "Omo Studio", "Take the throne", "watch them all", "Press V to talk");

        // Central throne dais: a 3×3 quartz pedestal (1 up) with a sea-lantern
        // glow and a quartz-stairs throne — right-click it to sit, then look around.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                w.getBlockAt(cx + dx, wy, cz + dz).setType(Material.SMOOTH_QUARTZ, false);
                placed++;
            }
        }
        w.getBlockAt(cx, fy, cz).setType(Material.SEA_LANTERN, false);
        Block throne = w.getBlockAt(cx, wy + 1, cz);
        throne.setType(Material.QUARTZ_STAIRS, false);
        BlockData td = throne.getBlockData();
        if (td instanceof Directional dir) { dir.setFacing(BlockFace.NORTH); throne.setBlockData(dir, false); }
        placed++;

        // Light spires framing the throne + a radial floor "compass" out to each
        // wall, so the command deck reads as the centre and you can orient at a
        // glance (N/S walls = coders, E/W walls = Hermes workers).
        for (int dx = -1; dx <= 1; dx += 2) {
            for (int dz = -1; dz <= 1; dz += 2) {
                w.getBlockAt(cx + dx, wy + 1, cz + dz).setType(Material.END_ROD, false);
                placed++;
            }
        }
        int[][] spokes = { {0, -1}, {0, 1}, {-1, 0}, {1, 0} };
        for (int[] s : spokes) {
            for (int rr = 3; rr <= DOME_HX - 3; rr++) {
                w.getBlockAt(cx + s[0] * rr, fy, cz + s[1] * rr).setType(Material.LIGHT_BLUE_CONCRETE, false);
                placed++;
            }
        }

        // Code stations — two on the north wall, two on the south wall. Each
        // mounts its dev-site screen on the wall facing the throne, with a plate +
        // glow pad just inward; the villager (room centre) faces the throne.
        int devTopY = wy + DOME_H - 2; // rows wy+6..wy+3 — clear above the agents' heads
        placed += codeStation(w, cx - CODE_OFF, cz - DOME_HZ, BlockFace.SOUTH, fy, wy, devTopY,
                stationRoom(1), stationCinema(1));
        placed += codeStation(w, cx + CODE_OFF, cz - DOME_HZ, BlockFace.SOUTH, fy, wy, devTopY,
                stationRoom(2), stationCinema(2));
        placed += codeStation(w, cx - CODE_OFF, cz + DOME_HZ, BlockFace.NORTH, fy, wy, devTopY,
                stationRoom(3), stationCinema(3));
        placed += codeStation(w, cx + CODE_OFF, cz + DOME_HZ, BlockFace.NORTH, fy, wy, devTopY,
                stationRoom(4), stationCinema(4));
        for (int i = 1; i <= STATIONS; i++) {
            created.add(stationRoom(i));
            cinemas.add(new CinemaChannel(stationCinema(i), stationUrl(i)));
        }

        // Hermes workers — one on the east wall, two on the west — facing the
        // throne. Pre-seated by the command (it loops hermesWorkers).
        placed += hermesDomeStation(w, cx + DOME_HX - 2, cz,     90f,  fy, wy,
                hermesWorkerRoom(0), hermesWorkerId(0), created, hermesWorkers);
        placed += hermesDomeStation(w, cx - DOME_HX + 2, cz - 4, 270f, fy, wy,
                hermesWorkerRoom(1), hermesWorkerId(1), created, hermesWorkers);
        placed += hermesDomeStation(w, cx - DOME_HX + 2, cz + 4, 270f, fy, wy,
                hermesWorkerRoom(2), hermesWorkerId(2), created, hermesWorkers);
        return placed;
    }

    /**
     * One code station inside the dome: a dev-site screen mounted on {@code wallZ}
     * facing the throne ({@code audienceDir} = SOUTH for the north wall, NORTH for
     * the south wall), a plate + glow pad just inward, and the {@code code-i} room
     * registered with the villager facing the throne. Lazy — the plate spawns the
     * station's own claude PTY when stepped.
     */
    private int codeStation(World w, int sx, int wallZ, BlockFace audienceDir, int fy, int wy,
                            int devTopY, String room, String cinemaId) {
        int placed = 0;
        int inward = audienceDir == BlockFace.SOUTH ? 1 : -1;   // wall → centre (toward the throne)
        float yaw  = audienceDir == BlockFace.SOUTH ? 0f : 180f; // face the throne
        int villagerZ = wallZ + inward * 2;
        int plateZ    = wallZ + inward * 3;
        // col 0 sits at the anti-colStep end so columns march along the wall:
        // audience SOUTH → colStep WEST → start east; NORTH → colStep EAST → start west.
        int half = (DEV_COLS - 1) / 2;
        int colStartX = audienceDir == BlockFace.SOUTH ? sx + half : sx - half;
        CinemaFrameStore store = plugin.cinema().ensure(cinemaId);
        CinemaScreen.Result screen = CinemaScreen.build(
                new Location(w, colStartX, devTopY, wallZ), audienceDir, DEV_COLS, DEV_ROWS,
                Material.WHITE_CONCRETE, store);
        placed += screen.blocksPlaced();
        plugin.cinema().registerScreen(screen.geometry());
        w.getBlockAt(sx, fy, villagerZ).setType(Material.SEA_LANTERN, false);
        w.getBlockAt(sx, wy, plateZ).setType(Material.LIGHT_WEIGHTED_PRESSURE_PLATE, false);
        placed += 2;
        rooms.define(room, new Location(w, sx + 0.5, wy, villagerZ + 0.5, yaw, 0f), 7);
        return placed;
    }

    /**
     * One Hermes worker station inside the dome: a glow pad + the room registered
     * with the villager facing the throne, added to {@code hermesWorkers} so the
     * command pre-seats the HermesAgent here (chat to task it, right-click it for
     * its reasoning terminal).
     */
    private int hermesDomeStation(World w, int sx, int sz, float yaw, int fy, int wy,
                                  String room, String id, List<String> created,
                                  List<AgentSeat> hermesWorkers) {
        w.getBlockAt(sx, fy, sz).setType(Material.SEA_LANTERN, false);
        Location home = new Location(w, sx + 0.5, wy, sz + 0.5, yaw, 0f);
        rooms.define(room, home, 8);
        created.add(room);
        hermesWorkers.add(new AgentSeat(id, HERMES_WORKER_ROLE, room, home));
        return 1;
    }

    // ── Standup cinema (the shared "main" screen) ─────────────────────────────

    private static final int CIN_HX = 9, CIN_HZ = 6, CIN_H = 7;
    private static final int SCREEN_COLS = 8, SCREEN_ROWS = 5;

    private int buildCinema(World w, int cx, int fy, int cz, List<String> created) {
        int placed = 0;
        int wy = fy + 1;
        for (int dx = -CIN_HX; dx <= CIN_HX; dx++) {
            for (int dz = -CIN_HZ; dz <= CIN_HZ; dz++) {
                w.getBlockAt(cx + dx, fy, cz + dz).setType(Material.POLISHED_DIORITE, false);
                w.getBlockAt(cx + dx, wy + CIN_H, cz + dz).setType(Material.LIGHT_BLUE_STAINED_GLASS, false);
                placed += 2;
                boolean edge = Math.abs(dx) == CIN_HX || Math.abs(dz) == CIN_HZ;
                boolean westArch = dx == -CIN_HX && Math.abs(dz) <= 1; // entry from plaza
                if (!edge || westArch) continue;
                for (int dy = 0; dy < CIN_H; dy++) {
                    w.getBlockAt(cx + dx, wy + dy, cz + dz)
                            .setType(Material.LIGHT_BLUE_STAINED_GLASS, false);
                    placed++;
                }
            }
        }
        int screenWallX = cx + CIN_HX - 1;
        int screenTopY = wy + CIN_H - 2;
        int southmostZ = cz + (SCREEN_COLS / 2) - 1;
        placed += screenBezel(w, screenWallX, screenTopY, southmostZ);
        Location topLeftWall = new Location(w, screenWallX, screenTopY, southmostZ);
        CinemaScreen.Result screen = CinemaScreen.build(
                topLeftWall, BlockFace.WEST, SCREEN_COLS, SCREEN_ROWS,
                Material.WHITE_CONCRETE, mainStore);
        placed += screen.blocksPlaced();
        plugin.cinema().registerScreen(screen.geometry());

        rooms.define("cinema", new Location(w, cx - CIN_HX + 2 + 0.5, wy, cz + 0.5, -90f, 0f), 16);
        created.add("cinema");

        placed += wallSign(w, cx - CIN_HX + 1, wy + 1, cz - 2, BlockFace.EAST,
                "Standup Screen", "Aim, then click", "F types on it", "/omo cinema");
        return placed;
    }

    private int screenBezel(World w, int screenWallX, int screenTopY, int southmostZ) {
        int placed = 0;
        int northmostZ = southmostZ - (SCREEN_COLS - 1);
        int bottomY = screenTopY - (SCREEN_ROWS - 1);
        for (int z = northmostZ - 1; z <= southmostZ + 1; z++) {
            w.getBlockAt(screenWallX, screenTopY + 1, z).setType(Material.WHITE_CONCRETE, false);
            w.getBlockAt(screenWallX, bottomY - 1, z).setType(Material.WHITE_CONCRETE, false);
            placed += 2;
        }
        for (int y = bottomY - 1; y <= screenTopY + 1; y++) {
            w.getBlockAt(screenWallX, y, northmostZ - 1).setType(Material.WHITE_CONCRETE, false);
            w.getBlockAt(screenWallX, y, southmostZ + 1).setType(Material.WHITE_CONCRETE, false);
            placed += 2;
        }
        return placed;
    }

    // ── Listening Room (live transcript wall + record/distill controls) ───────

    private static final int LR_HX = 5, LR_HZ = 5, LR_H = 5; // 11×11×5 booth
    private static final int LR_COLS = 7, LR_ROWS = 4;        // transcript wall tiles

    /**
     * A calm transcription booth: a light-blue glass shell (white-concrete floor,
     * quartz corners) with a 3-wide doorway on the WEST (plaza) wall, the live
     * whisper transcript filling the EAST back wall (cinema id {@code listening}),
     * and two control kiosks just inside the door — a RECORD <em>lever</em> that
     * arms the mic and a DISTILL <em>button</em> that turns the transcript into
     * paste-ready prompts. Registers the {@code listening} room and returns its
     * cinema channel so the command points the wall at {@code /listening}.
     */
    private int buildListeningRoom(World w, int lx, int fy, int lz,
                                   List<String> created, List<CinemaChannel> cinemas) {
        int placed = 0;
        int wy = fy + 1;
        Material glass = Material.LIGHT_BLUE_STAINED_GLASS;
        for (int dx = -LR_HX; dx <= LR_HX; dx++) {
            for (int dz = -LR_HZ; dz <= LR_HZ; dz++) {
                w.getBlockAt(lx + dx, fy, lz + dz).setType(Material.WHITE_CONCRETE, false);
                w.getBlockAt(lx + dx, wy + LR_H, lz + dz).setType(glass, false);
                placed += 2;
                boolean edge = Math.abs(dx) == LR_HX || Math.abs(dz) == LR_HZ;
                if (!edge) continue;
                boolean corner = Math.abs(dx) == LR_HX && Math.abs(dz) == LR_HZ;
                for (int dy = 0; dy < LR_H; dy++) {
                    w.getBlockAt(lx + dx, wy + dy, lz + dz)
                            .setType(corner ? Material.QUARTZ_PILLAR : glass, false);
                    placed++;
                }
            }
        }
        // Doorway (3 wide × 3 tall) on the WEST (plaza-facing) wall.
        for (int dz = -1; dz <= 1; dz++) {
            for (int dy = 0; dy < 3; dy++) {
                w.getBlockAt(lx - LR_HX, wy + dy, lz + dz).setType(Material.AIR, false);
            }
        }
        // Sea-lantern lighting: centre + corners.
        w.getBlockAt(lx, fy, lz).setType(Material.SEA_LANTERN, false);
        for (int sx = -3; sx <= 3; sx += 6) for (int sz = -3; sz <= 3; sz += 6) {
            w.getBlockAt(lx + sx, fy, lz + sz).setType(Material.SEA_LANTERN, false);
            placed++;
        }
        placed++;

        rooms.define("listening", new Location(w, lx + 0.5, wy, lz + 0.5, 90f, 0f), 8);
        created.add("listening");

        // Live transcript wall on the EAST back wall (audience to the west looks
        // east). Mirrors the standup cinema's WEST-audience geometry.
        CinemaFrameStore store = plugin.cinema().ensure("listening");
        int screenWallX = lx + LR_HX - 1;        // interior east column
        int screenTopY  = wy + LR_H - 1;          // top row, just below the ceiling
        int southmostZ  = lz + (LR_COLS / 2);     // centre the 7-col screen (lz-3..lz+3)
        Location topLeftWall = new Location(w, screenWallX, screenTopY, southmostZ);
        CinemaScreen.Result screen = CinemaScreen.build(
                topLeftWall, BlockFace.WEST, LR_COLS, LR_ROWS,
                Material.WHITE_CONCRETE, store);
        placed += screen.blocksPlaced();
        plugin.cinema().registerScreen(screen.geometry());
        cinemas.add(new CinemaChannel("listening", "http://127.0.0.1:8766/listening"));

        // Two control kiosks just inside the west door, facing the entering
        // player. North kiosk = RECORD lever; south kiosk = DISTILL button.
        placed += controlKiosk(w, lx - 3, wy, lz - 2, Material.LEVER, BlockFace.WEST,
                "Record", "flip to start", "or stop", "listening");
        placed += controlKiosk(w, lx - 3, wy, lz + 2, Material.STONE_BUTTON, BlockFace.WEST,
                "Distill", "press to turn", "talk into", "paste prompts");

        // Marquee over the west door, facing the approaching player.
        placed += wallSign(w, lx - LR_HX, wy + LR_H + 1, lz, BlockFace.WEST,
                "Listening Room", "Flip the lever", "and speak", "press Distill");
        return placed;
    }

    /** A 1-tall podium with a lever/button on top and a labelled sign above it. */
    private int controlKiosk(World w, int x, int wy, int z, Material sw, BlockFace facing,
                             String l0, String l1, String l2, String l3) {
        int placed = 0;
        w.getBlockAt(x, wy, z).setType(Material.QUARTZ_PILLAR, false);
        placed++;
        placed += placeFloorSwitch(w, x, wy + 1, z, sw, facing);
        placed += wallSign(w, x, wy + 2, z, facing, l0, l1, l2, l3);
        return placed;
    }

    /** Place a floor-mounted lever/button (always attaches; faces {@code facing}). */
    private int placeFloorSwitch(World w, int x, int y, int z, Material mat, BlockFace facing) {
        Block b = w.getBlockAt(x, y, z);
        b.setType(mat, false);
        BlockData bd = b.getBlockData();
        if (bd instanceof Switch sw) {
            sw.setAttachedFace(FaceAttachable.AttachedFace.FLOOR);
            sw.setFacing(facing);
            sw.setPowered(false);
            b.setBlockData(sw, false);
        }
        return 1;
    }

    // ── Omo hub ──────────────────────────────────────────────────────────────

    private int buildSpawnHub(World w, int cx, int fy, int cz) {
        int placed = 0;
        w.getBlockAt(cx, fy, cz).setType(Material.SEA_LANTERN, false);
        placed++;
        placed += wallSign(w, cx, fy + 2, cz, BlockFace.NORTH,
                "Controls", "Walk up, type", "to task anyone", "Type /omo");
        return placed;
    }

    // ── Sign helper ──────────────────────────────────────────────────────────

    /** Quartz post + glowing wall sign reading four lines, facing {@code facing}. */
    private int wallSign(World w, int x, int y, int z, BlockFace facing,
                         String l0, String l1, String l2, String l3) {
        w.getBlockAt(x, y, z).setType(Material.QUARTZ_PILLAR, false);
        Block s = w.getBlockAt(x, y, z).getRelative(facing);
        s.setType(Material.OAK_WALL_SIGN, false);
        BlockData bd = s.getBlockData();
        if (bd instanceof Directional d) {
            d.setFacing(facing);
            s.setBlockData(d, false);
        }
        if (s.getState() instanceof Sign sign) {
            var front = sign.getSide(Side.FRONT);
            front.line(0, Component.text(l0));
            front.line(1, Component.text(l1));
            front.line(2, Component.text(l2));
            front.line(3, Component.text(l3));
            sign.setGlowingText(true);
            sign.update();
        }
        return 2;
    }
}

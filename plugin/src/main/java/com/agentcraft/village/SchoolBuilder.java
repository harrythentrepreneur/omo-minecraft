package com.agentcraft.village;

import com.agentcraft.AgentCraftPlugin;
import com.agentcraft.cinema.CinemaFrameStore;
import com.agentcraft.cinema.CinemaScreen;
import com.agentcraft.rooms.RoomManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Lectern;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

/**
 * Builds a single re-themeable classroom (default subject "Algebra") west of
 * the plaza — the school wing — plus a Dean's office reception booth on the
 * door-side path that leads back to the plaza.
 *
 * <pre>
 *   whiteboard (west)        door (east) → [DEAN'S OFFICE] → plaza
 *   ┌───────────────────────────────────┐
 *   │ ▓▓▓▓▓   [ada=tutor]                │  ▓ = live cinema whiteboard
 *   │ ▓▓▓▓▓                ▦s  ▦       ▦s │  ▦ = desk + calculator console
 *   │ ▓▓▓▓▓     ▦s   ▦(you)  ▦s          │  s = seated student villager
 *   │                                ════╪═══[dean]═══ path to plaza
 *   └───────────────────────────────────┘
 * </pre>
 *
 * The classroom registers one room ({@code classroom}, {@link
 * com.agentcraft.rooms registry} → {@code classroom} kind → a Hermes tutor
 * with control + notes tools only). The tutor villager ("ada") is seated by
 * {@code /hermes school}; walk in, right-click a desk chair to sit (handled by
 * {@link com.agentcraft.listeners.CinemaSeatListener}), then just type — chat
 * routes to whichever agent shares your room, so the tutor hears you and
 * teaches. The Dean ({@code dean} room, id {@code dean}) sits between the
 * classroom and the plaza: a learner talks to it ("teach me Spanish"), the Dean
 * calls {@code open_classroom}, and the classroom is re-themed for that subject.
 *
 * The classroom is fully parameterised by {@code subject}: the signage and the
 * desk notebook interpolate it, and the tutor's role becomes
 * {@code subject + " tutor"} (e.g. "Spanish tutor"). The default subject is
 * "Algebra" so the first {@code /hermes build} looks exactly like before.
 *
 * Coordinates are anchored on the plaza centre (the world spawn that {@link
 * MvpWorldBuilder} sets), so the school always lands just west of the plaza.
 */
public final class SchoolBuilder {

    public static final String ROOM = "classroom";
    public static final String TUTOR_ID = "ada";
    /** Default subject when a caller doesn't specify one. */
    public static final String DEFAULT_SUBJECT = "Algebra";
    public static final String DEAN_ROOM = "dean";
    public static final String DEAN_ID = "dean";
    public static final String DEAN_ROLE = "Dean of the on-demand school";
    private static final String STUDENT_TAG = "act-student";

    /**
     * What got built: the classroom room + its tutor (themed to {@code subject}),
     * the door-side player drop, and the Dean office room + its stationary Dean.
     */
    public record Result(String room, String tutorId, String tutorRole,
                         Location tutorHome, Location playerSpawn,
                         int blocksPlaced, int students,
                         String subject,
                         String deanId, String deanRole,
                         Location deanHome, String deanRoom) {}

    // Interior half-extents (block radius from the classroom centre).
    private static final int HX = 7;   // east-west (depth: whiteboard ↔ door)
    private static final int HZ = 6;   // north-south (bench rows)
    private static final int H  = 5;   // interior height
    private static final int WEST_OFFSET   = 48; // flat world: standalone wing, west of the plaza
    private static final int ISLAND_OFFSET = 18; // island: on the grass plateau, west of the heart
    private static final int STUDIO_SOUTH_SHIFT = 28; // Studio: drop the wing SOUTH of due-west. Must clear the
                                                       // build studio's room sphere (centre cx-46, radius 18): the
                                                       // classroom centre (cx-48, cz+SHIFT, radius 8) needs distance
                                                       // >= 26, so SHIFT >= 26; 28 leaves a margin so chat at the
                                                       // whiteboard never mis-routes to the build mason.

    // ── Dean office geometry (offsets from the classroom centre) ─────────────
    // The Dean booth straddles the door-side path between the classroom (whose
    // east door is at scx+HX) and the plaza. Centred 14 east of the classroom
    // centre, it's a 7-wide × 5-deep × 4-tall reception with a west doorway
    // (toward the classroom) and an east doorway (toward the plaza).
    private static final int DEAN_DX = 14;  // Dean centre east of the classroom centre
    private static final int DEAN_HX = 3;   // 7 wide (x): deanCx-3 .. deanCx+3
    private static final int DEAN_HZ = 2;   // 5 deep (z): scz-2 .. scz+2
    private static final int DEAN_H  = 4;   // interior height
    // Containment radii. The classroom room reaches scx+8 (centre + 8); the Dean
    // room reaches deanCx-4 = scx+10. 8 + 4 = 12 < the 14-block centre spacing,
    // so the two spheres never overlap — there's a clean ~2-block corridor
    // between them for the door-side path (see RoomManager#roomAt containment).
    private static final int ROOM_RADIUS      = 8; // classroom (was HX+4 = 11)
    private static final int DEAN_ROOM_RADIUS = 4;

    private final AgentCraftPlugin plugin;
    private final RoomManager rooms;

    public SchoolBuilder(AgentCraftPlugin plugin, RoomManager rooms) {
        this.plugin = plugin;
        this.rooms = rooms;
    }

    /** Flat-world placement (default subject): a standalone wing 48 blocks west of the plaza. */
    public Result build(Location anchor) {
        return build(anchor, DEFAULT_SUBJECT);
    }

    /** Flat-world placement themed to {@code subject} (with a Dean office). */
    public Result build(Location anchor, String subject) {
        return build(anchor, WEST_OFFSET, anchor.getBlockX() - 30, subject, true);
    }

    /**
     * Island placement (default subject): on the grass plateau in the free WEST
     * sector (Hermes is north, Code south, Cinema east), with the path running
     * back toward the village heart. Same classroom, just nearer the anchor and
     * aimed inward. No Dean office on the island.
     */
    public Result buildOnIsland(Location anchor) {
        return buildOnIsland(anchor, DEFAULT_SUBJECT);
    }

    /** Island placement themed to {@code subject} — no Dean office on the island. */
    public Result buildOnIsland(Location anchor, String subject) {
        return build(anchor, ISLAND_OFFSET, anchor.getBlockX() - 4, subject, false);
    }

    /**
     * Studio placement ({@code /hermes build}, default subject): the same
     * classroom that {@link #build} drops due west of the plaza, but shifted one
     * quadrant SOUTH so it clears the build studio that now occupies the plaza's
     * west-centre. The door-side path still runs east, landing in the south-west
     * of the commons.
     */
    public Result buildInStudio(Location anchor) {
        return buildInStudio(anchor, DEFAULT_SUBJECT);
    }

    /** Studio placement themed to {@code subject} — the deterministic re-theme anchor (with a Dean office). */
    public Result buildInStudio(Location anchor, String subject) {
        Location sw = new Location(anchor.getWorld(), anchor.getBlockX(),
                anchor.getBlockY(), anchor.getBlockZ() + STUDIO_SOUTH_SHIFT);
        return build(sw, WEST_OFFSET, anchor.getBlockX() - 30, subject, true);
    }

    /**
     * @param westOffset  blocks west of the anchor to centre the classroom.
     * @param pathTargetX world X the door-side path should reach (the plaza
     *                    edge on flat ground, or the village heart on the island).
     * @param subject     the lesson subject — themed into signage + the notebook,
     *                    and the tutor role becomes {@code subject + " tutor"}.
     * @param withDean    build the Dean's office + register room "dean". True for
     *                    the flat / studio campus; false on the island (which has
     *                    no Dean — its sectors leave no room for the booth).
     */
    private Result build(Location anchor, int westOffset, int pathTargetX, String subject, boolean withDean) {
        int scx = anchor.getBlockX() - westOffset;  // classroom centre X
        int scz = anchor.getBlockZ();               // classroom centre Z
        return buildCore(anchor.getWorld(), scx, scz, anchor.getBlockY(), pathTargetX, subject, withDean);
    }

    /**
     * Re-theme an EXISTING classroom IN PLACE: build it centred exactly at
     * {@code classroomCentre} (no plaza-offset transform) so a Dean re-theme
     * OVERRIDES the current classroom instead of landing beside it. The door-side
     * path still runs east toward the plaza (mirrors buildInStudio's pathTarget,
     * where the plaza anchor is the classroom centre + WEST_OFFSET).
     */
    public Result rebuildAt(Location classroomCentre, String subject) {
        int scx = classroomCentre.getBlockX();
        int scz = classroomCentre.getBlockZ();
        return buildCore(classroomCentre.getWorld(), scx, scz, classroomCentre.getBlockY(),
                scx + WEST_OFFSET - 30, subject, true);
    }

    private Result buildCore(World w, int scx, int scz, int wy, int pathTargetX, String subject, boolean withDean) {
        int fy = wy - 1;               // floor sits one below
        final String subj = normalizeSubject(subject);
        final String tutorRole = subj + " tutor";

        // Re-running the build is idempotent: blocks overwrite in place, but old
        // student villagers would pile up, so clear ours first.
        clearStudents(w);

        int placed = 0;
        placed += buildShell(w, scx, fy, scz, wy);
        placed += carveDoor(w, scx, scz, wy);
        placed += buildAcousticTreatment(w, scx, scz, wy);
        placed += buildWhiteboard(w, scx, scz, wy);
        int students = buildDesks(w, scx, scz, wy, subj);
        placed += buildPathTo(w, scx, scz, fy, pathTargetX);
        placed += buildEntrance(w, scx, scz, fy, wy, subj);

        // Classroom containment: radius 8 (was HX+4=11) so it can't reach the
        // Dean office room 14 blocks east — see DEAN_ROOM_RADIUS / RoomManager.
        rooms.define(ROOM, new Location(w, scx + 0.5, wy, scz + 0.5), ROOM_RADIUS);

        // Tutor stands a few blocks east of the whiteboard, facing the class (east).
        Location tutorHome = new Location(w, scx - HX + 3 + 0.5, wy, scz + 0.5, -90f, 0f);
        // Drop the player just inside the door, looking west toward the board.
        Location playerSpawn = new Location(w, scx + HX - 1 + 0.5, wy, scz + 0.5, 90f, 0f);

        // Dean's office: a reception booth straddling the door-side path between
        // the classroom and the plaza. Registers room "dean" and returns the
        // Dean's standing spot. Skipped on the island (no room for the booth);
        // the placeholder deanHome there is never used (handleIslandBuild seats
        // only the tutor).
        Location deanHome = withDean
                ? buildDeanOffice(w, scx, scz, fy, wy)
                : new Location(w, scx + HX + 0.5, wy, scz + 0.5, -90f, 0f);

        return new Result(ROOM, TUTOR_ID, tutorRole, tutorHome, playerSpawn, placed, students,
                subj, DEAN_ID, DEAN_ROLE, deanHome, DEAN_ROOM);
    }

    /** Trim + default the subject; keep capitalisation as the caller typed it. */
    private static String normalizeSubject(String subject) {
        if (subject == null) return DEFAULT_SUBJECT;
        String s = subject.trim();
        return s.isEmpty() ? DEFAULT_SUBJECT : s;
    }

    // ── Shell: floor, ceiling, walls, corners, windows, lights ──────────────

    private int buildShell(World w, int scx, int fy, int scz, int wy) {
        int placed = 0;
        for (int dx = -HX; dx <= HX; dx++) {
            for (int dz = -HZ; dz <= HZ; dz++) {
                int x = scx + dx, z = scz + dz;
                // Floor.
                w.getBlockAt(x, fy, z).setType(Material.SMOOTH_QUARTZ, false);
                // Ceiling — sea-lantern grid for light, quartz elsewhere.
                boolean lamp = (dx % 3 == 0) && (dz % 3 == 0);
                w.getBlockAt(x, wy + H, z).setType(lamp ? Material.SEA_LANTERN : Material.SMOOTH_QUARTZ, false);
                placed += 2;

                boolean edge = Math.abs(dx) == HX || Math.abs(dz) == HZ;
                if (!edge) continue;
                boolean corner = Math.abs(dx) == HX && Math.abs(dz) == HZ;
                boolean sideWall = Math.abs(dz) == HZ && Math.abs(dx) < HX; // north/south
                for (int dy = 0; dy < H; dy++) {
                    Material m = Material.WHITE_CONCRETE;
                    if (corner) m = Material.QUARTZ_PILLAR;
                    else if (sideWall && (dy == 2 || dy == 3) && Math.abs(dx) <= HX - 2)
                        m = Material.LIGHT_BLUE_STAINED_GLASS; // window band on the long walls
                    w.getBlockAt(x, wy + dy, z).setType(m, false);
                    placed++;
                }
                // Parapet slab cap so the roofline reads as a finished building.
                w.getBlockAt(x, wy + H + 1, z).setType(Material.QUARTZ_SLAB, false);
                placed++;
            }
        }
        // Warm aisle carpet down the centre (overwritten later where desks land).
        for (int dx = -HX + 2; dx <= HX - 1; dx++) {
            w.getBlockAt(scx + dx, wy, scz).setType(Material.LIGHT_GRAY_CARPET, false);
            placed++;
        }
        return placed;
    }

    private int carveDoor(World w, int scx, int scz, int wy) {
        int placed = 0;
        int x = scx + HX; // east wall
        for (int dz = -1; dz <= 1; dz++) {
            for (int dy = 0; dy < 3; dy++) {
                w.getBlockAt(x, wy + dy, scz + dz).setType(Material.AIR, false);
                placed++;
            }
        }
        // Quartz door jambs.
        for (int dy = 0; dy < 4; dy++) {
            w.getBlockAt(x, wy + dy, scz - 2).setType(Material.QUARTZ_PILLAR, false);
            w.getBlockAt(x, wy + dy, scz + 2).setType(Material.QUARTZ_PILLAR, false);
            placed += 2;
        }
        return placed;
    }

    /**
     * Soft surfaces make the school read as a quiet, noise-cancelling room:
     * wool wall panels absorb echo, ceiling baffles break up slap-back, and the
     * thicker carpet strip keeps the lesson space visually hushed.
     */
    private int buildAcousticTreatment(World w, int scx, int scz, int wy) {
        int placed = 0;

        // North/south acoustic panels under the window bands.
        for (int dx = -HX + 2; dx <= HX - 2; dx += 3) {
            placed += acousticPanel(w, scx + dx, wy + 1, scz - HZ, Material.LIGHT_GRAY_WOOL);
            placed += acousticPanel(w, scx + dx, wy + 1, scz + HZ, Material.LIGHT_GRAY_WOOL);
        }

        // Back wall panels around the live whiteboard bezel.
        for (int dz = -HZ + 1; dz <= HZ - 1; dz += 4) {
            placed += acousticPanel(w, scx - HX, wy + 1, scz + dz, Material.GRAY_WOOL);
        }

        // Door-side panels, leaving the 3-wide doorway open.
        for (int dz : new int[] { -5, 5 }) {
            placed += acousticPanel(w, scx + HX, wy + 1, scz + dz, Material.GRAY_WOOL);
        }

        // Hanging ceiling baffles between the light grid.
        for (int z = scz - 4; z <= scz + 4; z += 4) {
            for (int x = scx - 4; x <= scx + 4; x += 4) {
                w.getBlockAt(x, wy + H - 1, z).setType(Material.GRAY_CARPET, false);
                placed++;
            }
        }

        // Extra soft central runner: visual cue that this is the quiet zone.
        for (int dx = -HX + 2; dx <= HX - 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                w.getBlockAt(scx + dx, wy, scz + dz).setType(Material.LIGHT_GRAY_CARPET, false);
                placed++;
            }
        }

        postSign(w, scx + HX + 1, wy + 2, scz + 3, BlockFace.EAST,
                "Quiet Classroom", "noise-cancelling", "wool panels", "soft carpet");
        return placed + 1;
    }

    private int acousticPanel(World w, int x, int y, int z, Material material) {
        int placed = 0;
        for (int dy = 0; dy < 2; dy++) {
            w.getBlockAt(x, y + dy, z).setType(material, false);
            placed++;
        }
        return placed;
    }

    // ── Whiteboard: a live cinema map-wall behind the tutor ─────────────────

    /** Cinema channel id + default page for the classroom whiteboard. */
    public static final String WHITEBOARD_ID = "whiteboard";
    public static final String WHITEBOARD_URL = "http://127.0.0.1:8766/whiteboard";
    private static final int WB_COLS = 7;   // tiles wide (z axis)
    private static final int WB_ROWS = 3;   // tiles tall (y axis)

    /**
     * Build the whiteboard on the west wall behind the tutor by reusing the
     * cinema map-wall: a {@code whiteboard} channel pointed at the runtime's
     * /whiteboard lesson page. face/ captures that page and the plugin paints
     * it onto the item-frame maps. The audience (students) sits to the EAST,
     * so the frames face east.
     */
    private int buildWhiteboard(World w, int scx, int scz, int wy) {
        if (plugin.cinema() == null) return 0;
        CinemaFrameStore store = plugin.cinema().ensure(WHITEBOARD_ID);
        int bezelX = scx - HX + 1;             // one block inside the west wall
        int topY = wy + 3;                     // rows fill wy+3 .. wy+1
        int northZ = scz - (WB_COLS - 1) / 2;  // columns fill north → south
        Location topLeft = new Location(w, bezelX, topY, northZ);
        CinemaScreen.Result r = CinemaScreen.build(
                topLeft, BlockFace.EAST, WB_COLS, WB_ROWS, Material.POLISHED_BLACKSTONE, store);
        plugin.cinema().registerScreen(r.geometry());
        // Point the channel at the lesson page so face/ starts capturing it.
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin,
                () -> plugin.cinema().setUrl(WHITEBOARD_ID, WHITEBOARD_URL));
        return r.blocksPlaced();
    }

    // ── Dean's office: reception booth between the classroom and the plaza ───

    /**
     * Build a small reception booth straddling the door-side path, centred {@code
     * DEAN_DX} blocks east of the classroom centre. A learner enters from the
     * plaza (east), tells the Dean what they want to learn; the Dean re-themes the
     * classroom and points them through the WEST doorway toward the board.
     *
     * <p>The booth is {@code 2*DEAN_HX+1} wide × {@code 2*DEAN_HZ+1} deep ×
     * {@code DEAN_H} tall, with a 3-wide doorway on both the WEST (classroom) and
     * EAST (plaza) walls so the path runs straight through it. Registers room
     * {@code "dean"} at radius {@code DEAN_ROOM_RADIUS} (small enough never to
     * overlap the classroom room) and returns the Dean's standing spot — centre,
     * facing EAST to greet whoever walks in from the plaza.
     */
    private Location buildDeanOffice(World w, int scx, int scz, int fy, int wy) {
        int dcx = scx + DEAN_DX;   // Dean office centre X
        int dcz = scz;             // shares the classroom's Z (sits on the path)
        for (int dx = -DEAN_HX; dx <= DEAN_HX; dx++) {
            for (int dz = -DEAN_HZ; dz <= DEAN_HZ; dz++) {
                int x = dcx + dx, z = dcz + dz;
                // Floor + lit ceiling.
                w.getBlockAt(x, fy, z).setType(Material.SMOOTH_QUARTZ, false);
                boolean lamp = dx == 0 && dz == 0;
                w.getBlockAt(x, wy + DEAN_H, z).setType(lamp ? Material.SEA_LANTERN : Material.SMOOTH_QUARTZ, false);

                boolean edge = Math.abs(dx) == DEAN_HX || Math.abs(dz) == DEAN_HZ;
                if (!edge) continue;
                boolean corner = Math.abs(dx) == DEAN_HX && Math.abs(dz) == DEAN_HZ;
                for (int dy = 0; dy < DEAN_H; dy++) {
                    Material m = corner ? Material.QUARTZ_PILLAR
                            : (dy == 2 && Math.abs(dz) < DEAN_HZ
                                ? Material.LIGHT_BLUE_STAINED_GLASS  // window band on the long walls
                                : Material.WHITE_CONCRETE);
                    w.getBlockAt(x, wy + dy, z).setType(m, false);
                }
            }
        }
        // Doorways (3 wide × 3 tall) on the WEST (toward the classroom) and EAST
        // (toward the plaza) walls, so the path runs straight through the booth.
        for (int dz = -1; dz <= 1; dz++) {
            for (int dy = 0; dy < 3; dy++) {
                w.getBlockAt(dcx - DEAN_HX, wy + dy, dcz + dz).setType(Material.AIR, false);
                w.getBlockAt(dcx + DEAN_HX, wy + dy, dcz + dz).setType(Material.AIR, false);
            }
        }
        // Glowing signage just east of the plaza door so an approaching learner
        // sees it first.
        postSign(w, dcx + DEAN_HX + 1, wy + 2, dcz - 1, BlockFace.EAST,
                "Dean's Office", "Tell the Dean", "what to learn", "then head in");

        // Containment radius 4: reaches west to dcx-4 = scx+10; the classroom
        // room reaches east to scx+8 (radius 8). 8+4 = 12 < the 14-block centre
        // spacing, so the spheres never overlap — clean corridor (RoomManager).
        rooms.define(DEAN_ROOM, new Location(w, dcx + 0.5, wy, dcz + 0.5), DEAN_ROOM_RADIUS);

        // Dean stands at centre, facing EAST (-90°) toward the plaza door.
        return new Location(w, dcx + 0.5, wy, dcz + 0.5, -90f, 0f);
    }

    // ── Desk study station: a notes lectern + a writable notebook ───────────

    private void equipStudyStation(World w, int x, int wy, int z, String subject) {
        // READ: a lectern holding the subject notebook, just north of the desk.
        Block lec = w.getBlockAt(x, wy, z - 1);
        lec.setType(Material.LECTERN, false);
        if (lec.getState() instanceof Lectern lectern) {
            lectern.getInventory().setItem(0, notesBook(subject));
            lectern.update();
        }
        // WRITE: a barrel just south of the desk. Right-clicking it hands the
        // player a real book & quill (see NotepadListener) — a working notepad.
        w.getBlockAt(x, wy, z + 1).setType(Material.BARREL, false);
    }

    /**
     * A short, generic per-subject notebook. The rich lesson content now lives on
     * the LIVE whiteboard up front (ada writes it as the lesson unfolds), so the
     * desk book is just an orientation card — what's on your desk and how to learn.
     */
    private ItemStack notesBook(String subject) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta m = (BookMeta) book.getItemMeta();
        m.title(Component.text(subject + " Notes"));
        m.author(Component.text(TUTOR_ID));
        m.addPages(
                Component.text(subject.toUpperCase() + "\n\nWelcome to class.\n\nYour desk has:\n• a calculator (right-click the block on top)\n• a notebook in the barrel\n\nThe whiteboard up front shows the live lesson."),
                Component.text("HOW TO LEARN\n\nJust type in chat — ada (the tutor) hears you and teaches.\n\nWatch the whiteboard: ada writes each new idea on the board as it goes.\n\nAsk ada anything about " + subject + ".")
        );
        book.setItemMeta(m);
        return book;
    }

    // ── Desks, chairs, and the ambient class of students ────────────────────

    /** 6 desks in a 2×3 grid; 5 seated students; front-centre desk left for you. */
    private int buildDesks(World w, int scx, int scz, int wy, String subject) {
        int[] rowX = { scx - 1, scx + 2 };      // front (near board), back
        int[] colZ = { scz - 3, scz, scz + 3 }; // left, centre, right
        int students = 0;
        for (int rx : rowX) {
            for (int cz : colZ) {
                placeDesk(w, rx, wy, cz);
                // Every desk gets a calculator console — a lodestone on its top.
                w.getBlockAt(rx, wy + 1, cz).setType(Material.LODESTONE, false);
                boolean playerSeat = (rx == rowX[0] && cz == scz); // front-centre = yours
                if (playerSeat) {
                    equipStudyStation(w, rx, wy, cz, subject); // notes lectern + notebook barrel
                } else {
                    spawnStudent(w, rx + 1 + 0.5, wy, cz + 0.5);
                    students++;
                }
            }
        }
        return students;
    }

    /** Cartography-table desk + an oak-stair chair on its east (door) side. */
    private void placeDesk(World w, int x, int wy, int z) {
        w.getBlockAt(x, wy, z).setType(Material.CARTOGRAPHY_TABLE, false);
        Block chair = w.getBlockAt(x + 1, wy, z);
        chair.setType(Material.OAK_STAIRS, false);
        BlockData bd = chair.getBlockData();
        if (bd instanceof Stairs st) {
            st.setFacing(BlockFace.WEST); // seat faces the chalkboard
            chair.setBlockData(st, false);
        }
    }

    private void spawnStudent(World w, double x, double y, double z) {
        Location loc = new Location(w, x, y, z, 90f, 0f); // facing west toward the board
        Villager v = (Villager) w.spawnEntity(loc, EntityType.VILLAGER);
        v.setAI(false);
        v.setInvulnerable(true);
        v.setSilent(true);
        v.setCollidable(false);
        v.setPersistent(true);
        v.setRemoveWhenFarAway(false);
        v.setCustomNameVisible(false);
        v.addScoreboardTag(STUDENT_TAG);
        v.setRotation(90f, 0f);
    }

    private void clearStudents(World w) {
        for (Villager v : w.getEntitiesByClass(Villager.class)) {
            if (v.getScoreboardTags().contains(STUDENT_TAG)) v.remove();
        }
    }

    // ── Walkway from the door back to the plaza, plus an entrance arch ───────

    private int buildPathTo(World w, int scx, int scz, int fy, int targetX) {
        int placed = 0;
        int fromX = scx + HX;       // door threshold
        int toX = targetX;          // plaza edge (flat) / village heart (island)
        for (int x = fromX; x <= toX; x++) {
            for (int dz = -2; dz <= 2; dz++) {
                Material m = Math.abs(dz) == 2 ? Material.SMOOTH_QUARTZ : Material.POLISHED_DIORITE;
                w.getBlockAt(x, fy, scz + dz).setType(m, false);
                placed++;
            }
        }
        return placed;
    }

    private int buildEntrance(World w, int scx, int scz, int fy, int wy, String subject) {
        int placed = 0;
        int x = scx + HX + 1; // one block east of the door
        // Two quartz pillars + a lintel forming a little gateway.
        for (int dy = 0; dy < 4; dy++) {
            w.getBlockAt(x, wy + dy, scz - 2).setType(Material.QUARTZ_PILLAR, false);
            w.getBlockAt(x, wy + dy, scz + 2).setType(Material.QUARTZ_PILLAR, false);
            placed += 2;
        }
        for (int dz = -2; dz <= 2; dz++) {
            w.getBlockAt(x, wy + 4, scz + dz).setType(Material.SMOOTH_QUARTZ, false);
            placed++;
        }
        // Lanterns flanking the gate.
        w.getBlockAt(x, wy + 3, scz - 2).setType(Material.SEA_LANTERN, false);
        w.getBlockAt(x, wy + 3, scz + 2).setType(Material.SEA_LANTERN, false);
        placed += 2;
        // Marquee signs facing the plaza (east): welcome + how the desk works.
        placed += postSign(w, x, wy + 2, scz - 3, BlockFace.EAST,
                subject, "Sit at a desk,", "then type", "ada teaches you");
        placed += postSign(w, x, wy + 2, scz + 3, BlockFace.EAST,
                "At your desk", "calculator block", "notes, notebook", "board up front");
        return placed;
    }

    // ── Sign helpers ────────────────────────────────────────────────────────

    /** Quartz post + glowing wall sign reading four lines, facing {@code facing}. */
    private int postSign(World w, int x, int y, int z, BlockFace facing,
                         String l0, String l1, String l2, String l3) {
        w.getBlockAt(x, y, z).setType(Material.QUARTZ_PILLAR, false);
        Block s = w.getBlockAt(x, y, z).getRelative(facing);
        s.setType(Material.OAK_WALL_SIGN, false);
        BlockData bd = s.getBlockData();
        if (bd instanceof Directional d) {
            d.setFacing(facing);
            s.setBlockData(d, false);
        }
        writeSign(s, l0, l1, l2, l3);
        return 2;
    }

    private void writeSign(Block s, String l0, String l1, String l2, String l3) {
        if (s.getState() instanceof Sign sign) {
            var front = sign.getSide(Side.FRONT);
            front.line(0, Component.text(l0));
            front.line(1, Component.text(l1));
            front.line(2, Component.text(l2));
            front.line(3, Component.text(l3));
            sign.setGlowingText(true);
            sign.update();
        }
    }
}

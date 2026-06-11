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
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.sign.Side;

import java.util.ArrayList;
import java.util.List;

/**
 * A second, hand-built map in the classic Minecraft idiom: a grassy beach
 * island ringed by a contained water lagoon, floating on the flat world.
 * Built at the player's feet by {@code /omo island}.
 *
 * <p>It is a <b>re-skin</b>, not a new system. It mirrors {@link MvpWorldBuilder}
 * one-for-one — same plaza-relative layout, same four features — only the
 * materials change from sci-fi glass/quartz to timber, grass, sand and stone.
 * Crucially it registers the <i>exact same room names</i> the MVP does
 * ({@code hermes}, {@code code}, {@code cinema}, {@code spawn}) and drops the
 * same gold pressure plates, so the runtime, the voice aliases and every
 * listener (the gold-plate terminal opener {@code TerminalPlateListener}, the
 * cinema stair-seat mount, voice teleport) keep working with zero changes:
 *
 * <pre>
 *   ~~~~~~~~~~~~~~  lagoon  ~~~~~~~~~~~~~~
 *   ~        ┌──────────────┐          ~
 *   ~        │ HERMES LODGE │  (north) ~   plate → hermes terminal
 *   ~        └──────────────┘          ~
 *   ~   beach   ╔════════╗   ┌────────┐~
 *   ~  ~dock~   ║  WELL  ║   │ CINEMA │~   (east) localhost:3000
 *   ~           ╚════════╝   └────────┘~
 *   ~        ┌──────────────┐          ~
 *   ~        │ CODE WORKSHOP│  (south) ~   plate → claude terminal
 *   ~        └──────────────┘          ~
 *   ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 * </pre>
 */
public final class IslandWorldBuilder {

    public record Result(Location spawn, List<String> roomsCreated, int blocksPlaced) {}

    // ── Island silhouette (XZ distance² thresholds, cheap integer compare) ──
    private static final int R_GRASS  = 29;             // grassy plateau
    private static final int R_BEACH  = 32;             // sand ring sloping to water
    private static final int R_WATER  = 37;             // lagoon
    private static final int R_OUTER  = 38;             // containment rim
    private static final int R_GRASS2 = R_GRASS * R_GRASS;
    private static final int R_BEACH2 = R_BEACH * R_BEACH;
    private static final int R_WATER2 = R_WATER * R_WATER;
    private static final int R_OUTER2 = R_OUTER * R_OUTER;
    private static final int MAX_DEPTH = 9;             // how deep the rounded underbelly dips at centre

    // ── Cinema screen geometry (mirrors MvpWorldBuilder so the page reads right) ──
    private static final int CIN_HX = 9, CIN_HZ = 6, CIN_H = 7;
    private static final int SCREEN_COLS = 8, SCREEN_ROWS = 5;

    private final AgentCraftPlugin plugin;
    private final RoomManager rooms;
    private final CinemaFrameStore frameStore;

    public IslandWorldBuilder(AgentCraftPlugin plugin, RoomManager rooms, CinemaFrameStore frameStore) {
        this.plugin = plugin;
        this.rooms = rooms;
        this.frameStore = frameStore;
    }

    public Result build(Location center) {
        World w = center.getWorld();
        int cx = center.getBlockX();
        int cz = center.getBlockZ();
        int wy = center.getBlockY();   // walk level (player's feet)
        int fy = wy - 1;               // grass surface sits one below
        int placed = 0;
        List<String> created = new ArrayList<>();

        // 1. The island landmass: grass plateau, beach, lagoon, rounded underbelly.
        placed += buildIsland(w, cx, fy, cz);

        // 2. Hermes lodge — NORTH (timber cabin, doorway facing south to the plaza).
        placed += buildLodge(w, cx, fy, cz - 16,
                Material.SPRUCE_PLANKS, Material.STRIPPED_SPRUCE_LOG, Material.SPRUCE_STAIRS,
                BlockFace.SOUTH, MvpWorldBuilder.HERMES_ROOM,
                "HERMES LODGE", "step the plate", "to open a", "hermes terminal", created);

        // 3. Code workshop — SOUTH (barn, doorway facing north to the plaza).
        placed += buildLodge(w, cx, fy, cz + 16,
                Material.OAK_PLANKS, Material.STRIPPED_OAK_LOG, Material.DARK_OAK_STAIRS,
                BlockFace.NORTH, MvpWorldBuilder.CODE_ROOM,
                "CODE WORKSHOP", "step the plate", "to open a", "claude terminal", created);

        // 4. Cinema — EAST (stone amphitheatre + map-wall screen, audience faces east).
        placed += buildCinema(w, cx + 17, fy, cz, created);

        // 5. Village heart: a stone well, paths to each building, the Omo sign.
        placed += buildVillageHeart(w, cx, fy, cz);

        // 6. Nature pass: trees, flowers, lanterns, lily pads, a little dock.
        placed += decorate(w, cx, fy, cz);

        Location spawn = new Location(w, cx + 0.5, wy, cz + 0.5, 0f, 0f);
        w.setSpawnLocation(cx, wy, cz);
        rooms.define("spawn", spawn, 6);
        created.add("spawn");

        return new Result(spawn, created, placed);
    }

    // ── Landmass ────────────────────────────────────────────────────────────

    /** Lay the whole island column-by-column from the silhouette thresholds. */
    private int buildIsland(World w, int cx, int fy, int cz) {
        int placed = 0;
        for (int dx = -R_OUTER; dx <= R_OUTER; dx++) {
            for (int dz = -R_OUTER; dz <= R_OUTER; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 > R_OUTER2) continue;        // beyond the rim → open void
                placed += column(w, cx + dx, fy, cz + dz, d2);
            }
        }
        return placed;
    }

    /** One vertical stack: rounded stone core + a zone-specific cap. */
    private int column(World w, int x, int fy, int z, int d2) {
        int placed = 0;

        // Rounded underbelly: deepest at the centre, feathering to the rim.
        double t = 1.0 - (double) d2 / R_OUTER2;        // 1 at centre, 0 at rim
        int depth = (int) Math.round(MAX_DEPTH * Math.sqrt(Math.max(0, t)));
        int bottomY = fy - 4 - depth;
        for (int y = fy - 4; y >= bottomY; y--) {
            w.getBlockAt(x, y, z).setType(y <= bottomY + 1 ? Material.DEEPSLATE : Material.STONE, false);
            placed++;
        }

        // Zone cap (fy-4 .. fy).
        if (d2 <= R_GRASS2) {                            // grassy plateau
            w.getBlockAt(x, fy, z).setType(Material.GRASS_BLOCK, false);
            w.getBlockAt(x, fy - 1, z).setType(Material.DIRT, false);
            w.getBlockAt(x, fy - 2, z).setType(Material.DIRT, false);
            w.getBlockAt(x, fy - 3, z).setType(Material.STONE, false);
            placed += 4;
        } else if (d2 <= R_BEACH2) {                     // beach, stepping down to the water
            int by = beachY(fy, d2);
            w.getBlockAt(x, by, z).setType(Material.SAND, false);
            placed++;
            for (int y = by - 1; y >= fy - 3; y--) {
                w.getBlockAt(x, y, z).setType(Material.SANDSTONE, false);
                placed++;
            }
        } else if (d2 <= R_WATER2) {                     // lagoon (source blocks → stable)
            w.getBlockAt(x, fy - 2, z).setType(Material.WATER, false);
            w.getBlockAt(x, fy - 3, z).setType(Material.WATER, false);
            w.getBlockAt(x, fy - 4, z).setType(Material.SAND, false);   // lagoon bed
            placed += 3;
        } else {                                         // containment rim (lip above waterline)
            for (int y = fy - 1; y >= fy - 4; y--) {
                w.getBlockAt(x, y, z).setType(Material.STONE_BRICKS, false);
                placed++;
            }
        }
        return placed;
    }

    /** Beach surface height: fy at the inner edge, stepping down to fy-2 at the water. */
    private int beachY(int fy, int d2) {
        if (d2 <= 30 * 30) return fy;
        if (d2 <= 31 * 31) return fy - 1;
        return fy - 2;
    }

    // ── Lodge / workshop (one room + one gold plate, like MvpWorldBuilder#buildBox) ──

    /**
     * A timber cabin that plays the role of the MVP "box": it registers exactly
     * one room (named {@code room}, e.g. "hermes" or "code") at its centre and
     * drops one gold pressure plate just inside the doorway. Stepping the plate
     * spawns/open that box's PTY terminal via {@code TerminalPlateListener}.
     */
    private int buildLodge(World w, int bx, int fy, int bz,
                           Material wall, Material post, Material roofStair, BlockFace door,
                           String room, String l0, String l1, String l2, String l3, List<String> created) {
        int placed = cabin(w, bx, fy, bz, 5, 4, 4, wall, post, roofStair, wall, door);
        int wy = fy + 1;

        // Glowing pad at the centre where the agent stands.
        w.getBlockAt(bx, fy, bz).setType(Material.GLOWSTONE, false);
        placed++;

        // Gold plate just inside the doorway (TerminalPlateListener uses a 9-block
        // radius from the plate to the nearest box room anchor).
        int plateZ = door == BlockFace.SOUTH ? bz + 3 : bz - 3;
        w.getBlockAt(bx, wy, plateZ).setType(Material.LIGHT_WEIGHTED_PRESSURE_PLATE, false);
        placed++;

        rooms.define(room, new Location(w, bx + 0.5, wy, bz + 0.5, door == BlockFace.SOUTH ? 0f : 180f, 0f), 7);
        created.add(room);

        placed += wallSign(w, bx - 1, wy + 1, door == BlockFace.SOUTH ? bz - 4 : bz + 4, door,
                l0, l1, l2, l3);
        return placed;
    }

    /**
     * A timber cabin: plank floor, plank walls with stripped-log corner posts,
     * glass windows, a gabled stair roof (ridge running north–south) with closed
     * gable ends, and a 3-wide×3-tall doorway carved on {@code door}.
     */
    private int cabin(World w, int bx, int fy, int bz, int hx, int hz, int wallH,
                      Material wall, Material post, Material roofStair, Material floor, BlockFace door) {
        int placed = 0;
        int wy = fy + 1;

        // Floor + walls.
        for (int dx = -hx; dx <= hx; dx++) {
            for (int dz = -hz; dz <= hz; dz++) {
                w.getBlockAt(bx + dx, fy, bz + dz).setType(floor, false);
                placed++;
                boolean edge = Math.abs(dx) == hx || Math.abs(dz) == hz;
                if (!edge) continue;
                boolean corner = Math.abs(dx) == hx && Math.abs(dz) == hz;
                for (int dy = 0; dy < wallH; dy++) {
                    Material m = corner ? post : wall;
                    // Punch a band of glass windows on the middle wall rows.
                    if (!corner && (dy == 1 || dy == 2) && ((dx + dz) & 1) == 0) m = Material.GLASS;
                    w.getBlockAt(bx + dx, wy + dy, bz + dz).setType(m, false);
                    placed++;
                }
            }
        }

        // Gabled roof: slope in ±x, ridge along z. Each level steps inward.
        int roofBase = wy + wallH;
        for (int L = 0; L <= hx; L++) {
            int y = roofBase + L;
            int leftX = bx - hx + L;     // west slope rises toward the ridge
            int rightX = bx + hx - L;    // east slope
            for (int dz = -hz - 1; dz <= hz + 1; dz++) {
                placed += stair(w, leftX, y, bz + dz, roofStair, BlockFace.EAST);
                if (rightX != leftX) placed += stair(w, rightX, y, bz + dz, roofStair, BlockFace.WEST);
            }
            // Close the two triangular gable ends with wall planks under the slope.
            for (int gz : new int[] { bz - hz, bz + hz }) {
                for (int yy = roofBase; yy < y; yy++) {
                    w.getBlockAt(leftX, yy, gz).setType(wall, false);
                    if (rightX != leftX) w.getBlockAt(rightX, yy, gz).setType(wall, false);
                    placed += rightX != leftX ? 2 : 1;
                }
            }
        }

        // Carve the doorway (3 wide × 3 tall) on the requested face + a lantern over it.
        int doorZ = door == BlockFace.SOUTH ? bz + hz : bz - hz;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = 0; dy < 3; dy++) {
                w.getBlockAt(bx + dx, wy + dy, doorZ).setType(Material.AIR, false);
            }
        }
        w.getBlockAt(bx, wy + 3, doorZ).setType(Material.LANTERN, false);
        placed++;
        return placed;
    }

    // ── Cinema (east) ──────────────────────────────────────────────────────────

    private int buildCinema(World w, int cx, int fy, int cz, List<String> created) {
        int placed = 0;
        int wy = fy + 1;

        // Stone floor, low side/back walls (open west entrance), solid east back wall.
        for (int dx = -CIN_HX; dx <= CIN_HX; dx++) {
            for (int dz = -CIN_HZ; dz <= CIN_HZ; dz++) {
                w.getBlockAt(cx + dx, fy, cz + dz).setType(Material.POLISHED_ANDESITE, false);
                placed++;
                boolean edge = Math.abs(dx) == CIN_HX || Math.abs(dz) == CIN_HZ;
                if (!edge) continue;
                boolean westArch = dx == -CIN_HX && Math.abs(dz) <= 1;  // entry from the plaza
                if (westArch) continue;
                boolean backWall = dx == CIN_HX;                        // tall east screen backdrop
                int top = backWall ? CIN_H : 2;
                for (int dy = 0; dy < top; dy++) {
                    boolean moss = ((cx + dx + cz + dz) & 3) == 0;
                    w.getBlockAt(cx + dx, wy + dy, cz + dz)
                            .setType(moss ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS, false);
                    placed++;
                }
            }
        }

        // Map-wall screen on the far (east) wall — audience faces EAST. Identical
        // orientation to MvpWorldBuilder so the captured page reads the right way.
        int screenWallX = cx + CIN_HX - 1;
        int screenTopY = wy + CIN_H - 2;
        int southmostZ = cz + (SCREEN_COLS / 2) - 1;
        placed += screenBezel(w, screenWallX, screenTopY, southmostZ);
        Location topLeftWall = new Location(w, screenWallX, screenTopY, southmostZ);
        CinemaScreen.Result screen = CinemaScreen.build(
                topLeftWall, BlockFace.WEST, SCREEN_COLS, SCREEN_ROWS,
                Material.POLISHED_BLACKSTONE, frameStore);
        placed += screen.blocksPlaced();
        plugin.cinema().registerScreen(screen.geometry());

        // Three tiered rows of oak-stair seats facing the screen. Right-clicking
        // any stair inside the cinema room mounts the player (CinemaSeatListener).
        for (int row = 0; row < 3; row++) {
            int sx = cx - 2 - row * 2;        // each row further west
            int riserY = wy + row;            // and one block higher
            for (int dz = -4; dz <= 4; dz++) {
                if (row > 0) w.getBlockAt(sx, riserY - 1, cz + dz).setType(Material.STONE_BRICKS, false);
                placed += stair(w, sx, riserY, cz + dz, Material.OAK_STAIRS, BlockFace.WEST);
            }
        }

        rooms.define("cinema", new Location(w, cx - CIN_HX + 2 + 0.5, wy, cz + 0.5, -90f, 0f), 16);
        created.add("cinema");

        placed += wallSign(w, cx - CIN_HX + 1, wy + 1, cz - 2, BlockFace.EAST,
                "CINEMA", "/omo cinema", "<url>", "default :3000");
        return placed;
    }

    private int screenBezel(World w, int screenWallX, int screenTopY, int southmostZ) {
        int placed = 0;
        int northmostZ = southmostZ - (SCREEN_COLS - 1);
        int bottomY = screenTopY - (SCREEN_ROWS - 1);
        for (int z = northmostZ - 1; z <= southmostZ + 1; z++) {
            w.getBlockAt(screenWallX, screenTopY + 1, z).setType(Material.POLISHED_BLACKSTONE, false);
            w.getBlockAt(screenWallX, bottomY - 1, z).setType(Material.POLISHED_BLACKSTONE, false);
            placed += 2;
        }
        for (int y = bottomY - 1; y <= screenTopY + 1; y++) {
            w.getBlockAt(screenWallX, y, northmostZ - 1).setType(Material.POLISHED_BLACKSTONE, false);
            w.getBlockAt(screenWallX, y, southmostZ + 1).setType(Material.POLISHED_BLACKSTONE, false);
            placed += 2;
        }
        return placed;
    }

    // ── Village heart ──────────────────────────────────────────────────────────

    private int buildVillageHeart(World w, int cx, int fy, int cz) {
        int placed = 0;
        int wy = fy + 1;

        // Cobblestone plaza disc.
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                if (dx * dx + dz * dz > 16) continue;
                w.getBlockAt(cx + dx, fy, cz + dz)
                        .setType(((dx + dz) & 1) == 0 ? Material.COBBLESTONE : Material.STONE_BRICKS, false);
                placed++;
            }
        }

        // Classic village well: 3×3 cobble ring, water in the middle, fence
        // posts and a slab roof. A lantern hangs under the roof.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                boolean rim = dx != 0 || dz != 0;
                w.getBlockAt(cx + dx, fy, cz + dz).setType(rim ? Material.COBBLESTONE : Material.WATER, false);
                if (rim) w.getBlockAt(cx + dx, wy, cz + dz).setType(Material.COBBLESTONE_WALL, false);
                placed++;
            }
        }
        // Four corner fence posts up to a slab canopy.
        for (int dx = -1; dx <= 1; dx += 2) {
            for (int dz = -1; dz <= 1; dz += 2) {
                w.getBlockAt(cx + dx, wy + 1, cz + dz).setType(Material.OAK_FENCE, false);
                w.getBlockAt(cx + dx, wy + 2, cz + dz).setType(Material.OAK_FENCE, false);
                placed += 2;
            }
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                w.getBlockAt(cx + dx, wy + 3, cz + dz).setType(Material.OAK_SLAB, false);
                placed++;
            }
        }
        w.getBlockAt(cx, wy + 2, cz).setType(Material.LANTERN, false);
        placed++;

        // Dirt-path spokes to the three buildings.
        placed += path(w, fy, cx, cz - 5, cx, cz - 11);   // → Hermes lodge
        placed += path(w, fy, cx, cz + 5, cx, cz + 11);   // → Code workshop
        placed += path(w, fy, cx + 5, cz, cx + 7, cz);    // → Cinema

        // The Omo voice sign on the plaza edge.
        placed += wallSign(w, cx - 3, wy, cz, BlockFace.EAST,
                "OMO", "press V to talk", "N: Hermes S: Code", "E: Cinema");
        return placed;
    }

    /** Carve a straight 3-wide dirt path between two points (one axis at a time). */
    private int path(World w, int fy, int x1, int z1, int x2, int z2) {
        int placed = 0;
        int dx = Integer.signum(x2 - x1), dz = Integer.signum(z2 - z1);
        int x = x1, z = z1;
        while (true) {
            for (int o = -1; o <= 1; o++) {
                int px = x + (dx != 0 ? 0 : o);
                int pz = z + (dz != 0 ? 0 : o);
                Block b = w.getBlockAt(px, fy, pz);
                if (b.getType() == Material.GRASS_BLOCK) { b.setType(Material.DIRT_PATH, false); placed++; }
            }
            if (x == x2 && z == z2) break;
            if (x != x2) x += dx;
            if (z != z2) z += dz;
        }
        return placed;
    }

    // ── Nature ───────────────────────────────────────────────────────────────

    private int decorate(World w, int cx, int fy, int cz) {
        int placed = 0;

        // Trees curated to the quadrant gaps so none land on a building or path.
        int[][] trees = {
            { -22, -8, 5 }, { -18, -18, 6 }, { -10, -23, 5 }, { 8, -22, 6 },
            { 19, -16, 5 }, { 24, 7, 6 }, { 18, 16, 5 }, { 8, 22, 6 },
            { -9, 23, 6 }, { -19, 17, 5 }, { -24, 6, 6 }, { -25, -6, 5 },
        };
        for (int[] t : trees) {
            boolean spruce = ((t[0] + t[1]) & 1) == 0;
            placed += tree(w, cx + t[0], fy, cz + t[1],
                    spruce ? Material.SPRUCE_LOG : Material.OAK_LOG,
                    spruce ? Material.SPRUCE_LEAVES : Material.OAK_LEAVES, t[2]);
        }

        // Flower + grass clusters dotted over the plateau.
        Material[] flowers = { Material.POPPY, Material.DANDELION, Material.CORNFLOWER,
                Material.OXEYE_DAISY, Material.BLUE_ORCHID, Material.AZURE_BLUET };
        int[][] patches = {
            { -14, -10 }, { 13, -9 }, { -12, 11 }, { 14, 10 }, { -20, 2 },
            { 21, -3 }, { 4, -14 }, { -5, 14 }, { -7, -16 }, { 9, 13 },
        };
        int fi = 0;
        for (int[] p : patches) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int x = cx + p[0] + dx, z = cz + p[1] + dz;
                    Block ground = w.getBlockAt(x, fy, z);
                    Block above = w.getBlockAt(x, fy + 1, z);
                    if (ground.getType() != Material.GRASS_BLOCK || above.getType() != Material.AIR) continue;
                    boolean grass = ((dx + dz) & 1) == 0;
                    above.setType(grass ? Material.SHORT_GRASS : flowers[fi % flowers.length], false);
                    placed++;
                }
            }
            fi++;
        }

        // Lanterns on fence posts lining the north/south paths.
        for (int dz : new int[] { -8, -11, 8, 11 }) {
            for (int dx : new int[] { -2, 2 }) {
                w.getBlockAt(cx + dx, fy + 1, cz + dz).setType(Material.OAK_FENCE, false);
                w.getBlockAt(cx + dx, fy + 2, cz + dz).setType(Material.LANTERN, false);
                placed += 2;
            }
        }

        // A few lily pads on the lagoon (water surface is at fy-2).
        int[][] pads = { { 30, 6 }, { 28, -10 }, { -30, -4 }, { -28, 11 }, { 6, 30 }, { -8, -30 } };
        for (int[] lp : pads) {
            Block water = w.getBlockAt(cx + lp[0], fy - 2, cz + lp[1]);
            if (water.getType() == Material.WATER) {
                w.getBlockAt(cx + lp[0], fy - 1, cz + lp[1]).setType(Material.LILY_PAD, false);
                placed++;
            }
        }

        // A little oak dock reaching west off the beach into the lagoon.
        for (int dx = -28; dx >= -36; dx--) {
            w.getBlockAt(cx + dx, fy - 1, cz).setType(Material.OAK_PLANKS, false);
            w.getBlockAt(cx + dx, fy - 1, cz - 1).setType(Material.OAK_PLANKS, false);
            placed += 2;
        }
        w.getBlockAt(cx - 36, fy - 1, cz).setType(Material.OAK_FENCE, false);
        w.getBlockAt(cx - 36, fy, cz).setType(Material.LANTERN, false);
        placed += 2;

        return placed;
    }

    /** A small log+leaves tree. Skips if the ground isn't solid grass/sand. */
    private int tree(World w, int x, int fy, int z, Material log, Material leaf, int h) {
        Material ground = w.getBlockAt(x, fy, z).getType();
        if (ground != Material.GRASS_BLOCK && ground != Material.SAND) return 0;
        int placed = 0;
        int wy = fy + 1;
        for (int i = 0; i < h; i++) {
            w.getBlockAt(x, wy + i, z).setType(log, false);
            placed++;
        }
        int topY = wy + h;
        for (int dy = -2; dy <= 0; dy++) {
            int r = dy <= -1 ? 2 : 1;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx == 0 && dz == 0 && dy < 0) continue;            // keep the trunk clear
                    if (Math.abs(dx) == 2 && Math.abs(dz) == 2) continue;  // trim the far corners
                    Block bl = w.getBlockAt(x + dx, topY + dy, z + dz);
                    if (bl.getType() == Material.AIR) { bl.setType(leaf, false); placed++; }
                }
            }
        }
        w.getBlockAt(x, topY + 1, z).setType(leaf, false);
        placed++;
        return placed;
    }

    // ── Shared helpers ─────────────────────────────────────────────────────────

    /** Place a stair block with the given facing. Returns 1. */
    private int stair(World w, int x, int y, int z, Material mat, BlockFace facing) {
        Block b = w.getBlockAt(x, y, z);
        b.setType(mat, false);
        BlockData bd = b.getBlockData();
        if (bd instanceof Stairs s) {
            s.setFacing(facing);
            b.setBlockData(s, false);
        }
        return 1;
    }

    /** Free-standing oak post + glowing wall sign reading four lines. */
    private int wallSign(World w, int x, int y, int z, BlockFace facing,
                         String l0, String l1, String l2, String l3) {
        w.getBlockAt(x, y, z).setType(Material.STRIPPED_OAK_LOG, false);
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

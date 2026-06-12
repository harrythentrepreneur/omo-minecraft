package com.agentcraft.village;

import com.agentcraft.AgentCraftPlugin;
import com.agentcraft.rooms.RoomManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.sign.Side;
import net.kyori.adventure.text.Component;

/**
 * The futuristic Omo HQ — a sleek circular command-center building on a
 * floating sky island, where the Chief of Staff ({@code cos}) sits.
 *
 * <p>Built by {@code /omo hq} at sky level (STUDIO_Y ≈ 200) so the real terrain
 * far below is never touched — this is the non-destructive guarantee. The
 * silhouette borrows from {@link IslandWorldBuilder}: a circular grass plateau
 * over a rounded, tapered stone/deepslate underbelly so the whole thing reads
 * as floating, with a glowing perimeter rim.
 *
 * <p>The aesthetic is "ancient temple meets alien research lab": a pale
 * smooth-quartz / white-concrete drum with a big cyan-glass front, a glowing
 * cyan circular emblem on the facade, horizontal sea-lantern light-strips,
 * copper trim, glow-lichen and vines creeping the stone, wide steps up to a
 * clear doorway flanked by lanterns, and an open glowing command room inside
 * with a lectern workstation at its heart.
 *
 * <pre>
 *                    ╭───────────────╮
 *                    │   ◉  EMBLEM    │   ← glowing cyan ring on the facade
 *                    │ ▓▓▓ glass ▓▓▓  │   ← big cyan-glass front (faces −z / approach)
 *                    │  ▔▔ door ▔▔    │
 *                    ╰──┐ steps  ┌────╯
 *                       │ ║ ║ ║  │       ← wide quartz steps + flanking lanterns
 *                 ~ grass · flowers · tree ~
 *            ╲___ tapered stone underbelly ___╱   (floats)
 * </pre>
 */
public final class HqIslandBuilder {

    /**
     * The seated command row: the HQ centre plus the two flanking crew desks.
     * <p>{@code center} is where {@code cos} (Chief of Staff) sits at the heart
     * of the row; {@code leftSeat} / {@code rightSeat} are the glowing consoles
     * {@link #CREW_OFFSET} blocks to the −x / +x where {@code comms} and
     * {@code growth} sit. All three share the interior-floor Y so they read as
     * one row of three scientists at glowing holographic desks.
     */
    public record Result(Location center, Location leftSeat, Location rightSeat) {}

    /** X offset of the two flanking crew desks from the HQ centre. */
    private static final int CREW_OFFSET = 4;

    // ── Island silhouette (XZ distance² thresholds, cheap integer compare) ──
    private static final int R_ISLAND   = 20;                 // grassy plateau radius
    private static final int R_ISLAND2  = R_ISLAND * R_ISLAND;
    private static final int MAX_DEPTH  = 12;                 // how deep the underbelly dips at centre

    // ── Command building (centred on the island) ──
    private static final int R_DRUM     = 11;                 // outer wall radius
    private static final int R_DRUM2    = R_DRUM * R_DRUM;
    private static final int R_INNER2   = (R_DRUM - 1) * (R_DRUM - 1);
    private static final int WALL_H     = 7;                  // wall height (floor → eaves)

    private final AgentCraftPlugin plugin;
    private final RoomManager rooms;

    public HqIslandBuilder(AgentCraftPlugin plugin, RoomManager rooms) {
        this.plugin = plugin;
        this.rooms = rooms;
    }

    /**
     * Raise the HQ island + command building at {@code center} (sky level).
     * Returns the HQ centre {@link Location} (where {@code cos} should be seated
     * and the {@code hq} room defined), one block above the interior floor.
     */
    public Result build(Location center) {
        World w = center.getWorld();
        int cx = center.getBlockX();
        int cz = center.getBlockZ();
        int wy = center.getBlockY();    // walk level (player's feet / interior floor surface)
        int fy = wy - 1;                // grass / floor block sits one below

        buildIsland(w, cx, fy, cz);
        buildCommandBuilding(w, cx, fy, cz);
        decorateIsland(w, cx, fy, cz);

        // Flanking crew consoles: a glowing desk + lectern to the −x and +x of
        // centre, so the founding crew reads as one command row of three.
        buildCrewDesk(w, cx - CREW_OFFSET, fy, cz, wy);
        buildCrewDesk(w, cx + CREW_OFFSET, fy, cz, wy);

        // HQ centre: stand on the interior floor at the heart of the drum.
        Location centre = new Location(w, cx + 0.5, wy, cz + 0.5, 180f, 0f);
        Location left   = new Location(w, cx - CREW_OFFSET + 0.5, wy, cz + 0.5, 180f, 0f);
        Location right  = new Location(w, cx + CREW_OFFSET + 0.5, wy, cz + 0.5, 180f, 0f);
        return new Result(centre, left, right);
    }

    // ── Floating island ─────────────────────────────────────────────────────

    /** Lay the circular grass plateau over a rounded, tapered stone underbelly. */
    private void buildIsland(World w, int cx, int fy, int cz) {
        for (int dx = -R_ISLAND; dx <= R_ISLAND; dx++) {
            for (int dz = -R_ISLAND; dz <= R_ISLAND; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 > R_ISLAND2) continue;
                islandColumn(w, cx + dx, fy, cz + dz, d2);
            }
        }
        // Glowing perimeter rim: a smooth-quartz lip studded with sea-lanterns.
        for (int a = 0; a < 360; a += 2) {
            double rad = Math.toRadians(a);
            int x = cx + (int) Math.round(Math.cos(rad) * (R_ISLAND - 1));
            int z = cz + (int) Math.round(Math.sin(rad) * (R_ISLAND - 1));
            w.getBlockAt(x, fy + 1, z).setType(Material.SMOOTH_QUARTZ, false);
            w.getBlockAt(x, fy + 2, z).setType(
                    a % 24 == 0 ? Material.SEA_LANTERN : Material.LIGHT_BLUE_STAINED_GLASS, false);
        }
    }

    /** One island column: rounded stone/deepslate core + a grass cap. */
    private void islandColumn(World w, int x, int fy, int z, int d2) {
        // Grass plateau cap.
        w.getBlockAt(x, fy, z).setType(Material.GRASS_BLOCK, false);
        w.getBlockAt(x, fy - 1, z).setType(Material.DIRT, false);
        w.getBlockAt(x, fy - 2, z).setType(Material.DIRT, false);

        // Rounded underbelly: deepest at the centre, feathering to the rim.
        double t = 1.0 - (double) d2 / R_ISLAND2;        // 1 at centre, 0 at rim
        int depth = (int) Math.round(MAX_DEPTH * Math.sqrt(Math.max(0, t)));
        int bottomY = fy - 3 - depth;
        for (int y = fy - 3; y >= bottomY; y--) {
            w.getBlockAt(x, y, z).setType(y <= bottomY + 1 ? Material.DEEPSLATE : Material.STONE, false);
        }
    }

    // ── Circular futuristic command building ─────────────────────────────────

    private void buildCommandBuilding(World w, int cx, int fy, int cz) {
        int wy = fy + 1;                          // interior floor surface level

        // 1. Smooth interior floor disc (quartz with a glowing inlay ring).
        for (int dx = -R_DRUM; dx <= R_DRUM; dx++) {
            for (int dz = -R_DRUM; dz <= R_DRUM; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 > R_DRUM2) continue;
                Material floor = Material.SMOOTH_QUARTZ;
                int ringLo = (R_DRUM - 3) * (R_DRUM - 3);
                int ringHi = (R_DRUM - 2) * (R_DRUM - 2);
                if (d2 >= ringLo && d2 <= ringHi) floor = Material.LIGHT_BLUE_CONCRETE;  // inlay accent
                w.getBlockAt(cx + dx, fy, cz + dz).setType(floor, false);
            }
        }

        // 2. The drum wall: a circular shell, WALL_H tall, with a cyan-glass
        //    front, horizontal sea-lantern light-strips, copper trim + glow.
        //    The doorway faces −z (toward the player's approach / the steps).
        for (int dx = -R_DRUM; dx <= R_DRUM; dx++) {
            for (int dz = -R_DRUM; dz <= R_DRUM; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 > R_DRUM2 || d2 < R_INNER2) continue;       // the wall ring only
                int x = cx + dx, z = cz + dz;
                boolean front = dz < 0 && Math.abs(dx) <= 4;        // −z facade quadrant

                for (int dy = 0; dy < WALL_H; dy++) {
                    Material m = wallBlock(x, z, dy, front);
                    w.getBlockAt(x, wy + dy, z).setType(m, false);
                }
            }
        }

        // 3. Carve the doorway: a 3-wide × 3-tall air opening on the front (−z)
        //    face, then frame it with copper + lanterns.
        carveDoorway(w, cx, cz, wy);

        // 4. Big cyan-glass front window above/around the door.
        buildFrontWindow(w, cx, cz, wy);

        // 5. The glowing cyan circular emblem on the facade, above the window.
        buildEmblem(w, cx, cz, wy);

        // 6. Glowing perimeter light-ring at the eaves (sea-lantern crown).
        for (int a = 0; a < 360; a += 2) {
            double rad = Math.toRadians(a);
            int x = cx + (int) Math.round(Math.cos(rad) * R_DRUM);
            int z = cz + (int) Math.round(Math.sin(rad) * R_DRUM);
            w.getBlockAt(x, wy + WALL_H, z).setType(
                    a % 18 == 0 ? Material.SEA_LANTERN : Material.SMOOTH_QUARTZ_SLAB, false);
        }

        // 7. A low glowing dome roof: quartz slabs ringing inward, glass cap.
        buildDome(w, cx, cz, wy);

        // 8. Wide stone steps up to the doorway + flanking lanterns + entry path.
        buildSteps(w, cx, fy, cz);

        // 9. Creeping vines + glow-lichen on the stone, for the "ancient" texture.
        creep(w, cx, cz, wy);

        // 10. The interior: the Chief of Staff's glowing lectern workstation.
        buildWorkstation(w, cx, fy, cz, wy);
    }

    /** Pick a wall block for a drum position: glass front, light-strips, copper trim. */
    private Material wallBlock(int x, int z, int dy, boolean front) {
        // Copper trim band at the base and just under the eaves.
        if (dy == 0 || dy == WALL_H - 1) return Material.COPPER_BLOCK;
        // Horizontal glowing light-strip at mid-height.
        if (dy == 3) return Material.SEA_LANTERN;
        // The big glass front: cyan / light-blue panes on the −z facade.
        if (front && dy >= 1 && dy <= WALL_H - 2) {
            return ((x + z) & 1) == 0 ? Material.CYAN_STAINED_GLASS : Material.LIGHT_BLUE_STAINED_GLASS;
        }
        // Default pale wall, with an occasional white-concrete fleck for texture.
        return ((x * 31 + z) & 7) == 0 ? Material.WHITE_CONCRETE : Material.SMOOTH_QUARTZ;
    }

    /** Carve the front doorway (3-wide × 3-tall) and frame it with copper + lanterns. */
    private void carveDoorway(World w, int cx, int cz, int wy) {
        int doorZ = cz - (R_DRUM - 1);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = 0; dy < 3; dy++) {
                // Clear through the outer ring (doorZ-1), the front face (doorZ) and
                // one ring inside (doorZ+1) so the opening reads cleanly. The front
                // drum is 2 blocks thick on the centreline, so doorZ-1 must be cleared.
                for (int back = -1; back <= 1; back++) {
                    Block b = w.getBlockAt(cx + dx, wy + dy, doorZ + back);
                    if (b.getType() != Material.AIR) b.setType(Material.AIR, false);
                }
            }
        }
        // The outer ring is proud by one block on the door centreline (dx=0,
        // z=doorZ-1, full WALL_H tall). Clear its whole height so paving the
        // avenue beneath it never strands a floating soffit over the doorway.
        for (int dy = 3; dy < WALL_H; dy++) {
            Block b = w.getBlockAt(cx, wy + dy, doorZ - 1);
            if (b.getType() != Material.AIR) b.setType(Material.AIR, false);
        }
        // Copper-trimmed doorframe.
        for (int dy = 0; dy < 4; dy++) {
            w.getBlockAt(cx - 2, wy + dy, doorZ).setType(Material.COPPER_BLOCK, false);
            w.getBlockAt(cx + 2, wy + dy, doorZ).setType(Material.COPPER_BLOCK, false);
        }
        for (int dx = -2; dx <= 2; dx++) {
            w.getBlockAt(cx + dx, wy + 3, doorZ).setType(Material.SMOOTH_QUARTZ, false);
        }
        // A glowing threshold strip on the floor just inside the door.
        for (int dx = -1; dx <= 1; dx++) {
            w.getBlockAt(cx + dx, wy - 1, doorZ + 1).setType(Material.LIGHT_BLUE_CONCRETE, false);
        }
    }

    /** A band of cyan-glass either side of the door — the building's "front window". */
    private void buildFrontWindow(World w, int cx, int cz, int wy) {
        int z = cz - (R_DRUM - 1);
        for (int dx = -4; dx <= 4; dx++) {
            if (Math.abs(dx) <= 2) continue;                 // leave the doorframe alone
            for (int dy = 1; dy <= 4; dy++) {
                w.getBlockAt(cx + dx, wy + dy, z).setType(
                        (dy & 1) == 0 ? Material.CYAN_STAINED_GLASS : Material.LIGHT_BLUE_STAINED_GLASS, false);
            }
        }
    }

    /** A glowing cyan circular emblem set into the facade, above the window band. */
    private void buildEmblem(World w, int cx, int cz, int wy) {
        int z = cz - (R_DRUM - 1);
        int ey = wy + 5;                                     // emblem centre height
        // A 2-radius ring of light-blue glass around a sea-lantern core.
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                int rr = dx * dx + dy * dy;
                if (rr == 0) {
                    w.getBlockAt(cx + dx, ey + dy, z).setType(Material.SEA_LANTERN, false);
                } else if (rr >= 3 && rr <= 5) {
                    w.getBlockAt(cx + dx, ey + dy, z).setType(Material.LIGHT_BLUE_STAINED_GLASS, false);
                }
            }
        }
    }

    /** A low glowing dome: concentric quartz-slab rings stepping in, a glass cap. */
    private void buildDome(World w, int cx, int cz, int wy) {
        int domeBase = wy + WALL_H;
        int rings = 4;
        for (int ring = 1; ring <= rings; ring++) {
            int r = R_DRUM - 1 - (ring * (R_DRUM - 2) / (rings + 1));
            int y = domeBase + ring;
            int r2 = r * r;
            int inner2 = (r - 1) * (r - 1);
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    int d2 = dx * dx + dz * dz;
                    if (d2 > r2 || d2 < inner2) continue;
                    Material m = (ring == rings) ? Material.LIGHT_BLUE_STAINED_GLASS : Material.SMOOTH_QUARTZ;
                    w.getBlockAt(cx + dx, y, cz + dz).setType(m, false);
                }
            }
        }
        // Glass oculus cap + a glowing apex.
        int capY = domeBase + rings + 1;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                w.getBlockAt(cx + dx, capY, cz + dz).setType(Material.LIGHT_BLUE_STAINED_GLASS, false);
            }
        }
        w.getBlockAt(cx, capY + 1, cz).setType(Material.SEA_LANTERN, false);
        w.getBlockAt(cx, capY + 2, cz).setType(Material.END_ROD, false);
    }

    /**
     * A clean, flush processional leading −z from the front door across the island
     * to its rim, where it meets the village street. The HQ floor, the island grass
     * and the street are ALL one sky-level, so there is NO descending trench (the
     * old buried-steps bug) — just a wide lit avenue, flush with the grass:
     * dark-prismarine kerbs, smooth-quartz paving, a light-blue centre strip,
     * lantern posts, and a sign on the path naming the building.
     */
    private void buildSteps(World w, int cx, int fy, int cz) {
        int doorZ = cz - (R_DRUM - 1);          // the −z doorway threshold (at the wall)
        int rimZ  = cz - (R_ISLAND - 1);         // island rim — where the street begins
        // Pave from just OUTSIDE the door (doorZ-1) out to the rim, flush with grass.
        for (int z = doorZ - 1; z >= rimZ; z--) {
            for (int dx = -2; dx <= 2; dx++) {
                int x = cx + dx;
                Material paving = (Math.abs(dx) == 2) ? Material.DARK_PRISMARINE
                        : (dx == 0 ? Material.LIGHT_BLUE_CONCRETE : Material.SMOOTH_QUARTZ);
                w.getBlockAt(x, fy, z).setType(paving, false);                 // paving replaces grass top
                w.getBlockAt(x, fy - 1, z).setType(Material.SMOOTH_QUARTZ, false); // solid underfill, no dirt walls
                for (int up = 1; up <= 3; up++) {                              // clear headroom — fully walkable
                    Block a = w.getBlockAt(x, fy + up, z);
                    if (a.getType() != Material.AIR) a.setType(Material.AIR, false);
                }
            }
            // Lantern posts flanking the avenue every few blocks.
            if ((doorZ - z) % 4 == 0) {
                for (int dx : new int[] { -3, 3 }) {
                    w.getBlockAt(cx + dx, fy, z).setType(Material.SMOOTH_QUARTZ, false);
                    w.getBlockAt(cx + dx, fy + 1, z).setType(Material.SMOOTH_QUARTZ, false);
                    w.getBlockAt(cx + dx, fy + 2, z).setType(Material.SEA_LANTERN, false);
                }
            }
        }
        // Lanterns either side of the doorway itself.
        for (int dx : new int[] { -3, 3 }) {
            w.getBlockAt(cx + dx, fy, doorZ).setType(Material.SMOOTH_QUARTZ, false);
            w.getBlockAt(cx + dx, fy + 1, doorZ).setType(Material.LANTERN, false);
        }
        // A sign on the path naming the building (the door faces this path).
        placeHqSign(w, cx + 3, fy, cz - (R_ISLAND - 3));
    }

    /** A standing sign on a quartz post on the HQ processional, naming the building. */
    private void placeHqSign(World w, int sx, int fy, int signZ) {
        w.getBlockAt(sx, fy, signZ).setType(Material.SMOOTH_QUARTZ, false);       // base
        w.getBlockAt(sx, fy + 1, signZ).setType(Material.SMOOTH_QUARTZ, false);   // post
        Block sb = w.getBlockAt(sx, fy + 2, signZ);
        sb.setType(Material.OAK_SIGN, false);
        BlockData bd = sb.getBlockData();
        if (bd instanceof Rotatable rot) {
            rot.setRotation(BlockFace.NORTH);    // face down the street (−z), readable on approach
            sb.setBlockData(bd, false);
        }
        if (sb.getState() instanceof Sign sign) {
            var front = sign.getSide(Side.FRONT);
            front.line(1, Component.text("Omo HQ"));
            front.line(2, Component.text("Mission Control"));
            sign.update();
        }
    }

    /** Creep vines + glow-lichen on the outer drum wall for the "ancient" texture. */
    private void creep(World w, int cx, int cz, int wy) {
        for (int a = 0; a < 360; a += 11) {
            double rad = Math.toRadians(a);
            int x = cx + (int) Math.round(Math.cos(rad) * (R_DRUM + 1));
            int z = cz + (int) Math.round(Math.sin(rad) * (R_DRUM + 1));
            boolean front = z < cz && Math.abs(x - cx) <= 5;
            if (front) continue;                              // keep the glass front clean
            BlockFace toward = faceToward(x - cx, z - cz);
            for (int dy = 1; dy <= 4; dy++) {
                Block b = w.getBlockAt(x, wy + dy, z);
                if (b.getType() != Material.AIR) continue;
                Material m = (a % 22 == 0) ? Material.GLOW_LICHEN : Material.VINE;
                placeClinging(b, m, toward);
            }
        }
    }

    /** The interior: a glowing lectern workstation for the Chief of Staff at the centre. */
    private void buildWorkstation(World w, int cx, int fy, int cz, int wy) {
        // A small glowing desk dais of light-blue concrete + sea-lantern under-glow.
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx * dx + dz * dz > 5) continue;
                w.getBlockAt(cx + dx, fy, cz + dz).setType(
                        (dx == 0 && dz == 0) ? Material.SEA_LANTERN : Material.LIGHT_BLUE_CONCRETE, false);
            }
        }
        // Glowing desk blocks framing the workstation (room for holographic screens).
        w.getBlockAt(cx - 2, wy, cz).setType(Material.SEA_LANTERN, false);
        w.getBlockAt(cx + 2, wy, cz).setType(Material.SEA_LANTERN, false);
        w.getBlockAt(cx, wy, cz + 2).setType(Material.SMOOTH_QUARTZ, false);
        // The lectern: the Chief of Staff's workstation, facing the front door (−z).
        Block lectern = w.getBlockAt(cx, wy, cz);
        lectern.setType(Material.LECTERN, false);
        BlockData bd = lectern.getBlockData();
        if (bd instanceof Directional d) {
            d.setFacing(BlockFace.NORTH);                     // face the front entrance
            lectern.setBlockData(d, false);
        }
        // A glowing strip of soul-lanterns hovering as "holographic" desk light.
        for (int dz = -3; dz <= 3; dz += 2) {
            w.getBlockAt(cx, wy + 3, cz + dz).setType(Material.SOUL_LANTERN, false);
        }
    }

    /**
     * A flanking crew console at {@code (dx,dz=cz)}: a small glowing
     * light-blue-concrete + sea-lantern dais, a lectern facing the front door
     * (−z), and a soul-lantern "hologram" mote above it — matching the centre
     * workstation so the founding crew reads as one row of glowing consoles.
     */
    private void buildCrewDesk(World w, int deskX, int fy, int cz, int wy) {
        // Glowing 3×3 desk dais (sea-lantern core, light-blue-concrete surround).
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                w.getBlockAt(deskX + dx, fy, cz + dz).setType(
                        (dx == 0 && dz == 0) ? Material.SEA_LANTERN : Material.LIGHT_BLUE_CONCRETE, false);
            }
        }
        // Glowing console blocks framing the desk (room for holographic screens).
        w.getBlockAt(deskX - 1, wy, cz).setType(Material.SEA_LANTERN, false);
        w.getBlockAt(deskX + 1, wy, cz).setType(Material.SEA_LANTERN, false);
        // The lectern: the crew member's workstation, facing the front door (−z).
        Block lectern = w.getBlockAt(deskX, wy, cz);
        lectern.setType(Material.LECTERN, false);
        BlockData bd = lectern.getBlockData();
        if (bd instanceof Directional d) {
            d.setFacing(BlockFace.NORTH);
            lectern.setBlockData(d, false);
        }
        // A floating "hologram" mote above the console.
        w.getBlockAt(deskX, wy + 3, cz).setType(Material.SOUL_LANTERN, false);
    }

    // ── Island nature pass ───────────────────────────────────────────────────

    private void decorateIsland(World w, int cx, int fy, int cz) {
        // A couple of trees in the quadrants behind the building (clear of the steps).
        int[][] trees = { { 13, 13, 6 }, { -13, 13, 6 }, { -15, -3, 5 }, { 15, -3, 5 } };
        for (int[] t : trees) {
            tree(w, cx + t[0], fy, cz + t[1], Material.OAK_LOG, Material.OAK_LEAVES, t[2]);
        }

        // Flower + grass clusters scattered on the open grass beyond the drum.
        Material[] flowers = { Material.POPPY, Material.CORNFLOWER, Material.OXEYE_DAISY,
                Material.BLUE_ORCHID, Material.AZURE_BLUET, Material.LILY_OF_THE_VALLEY };
        int[][] patches = {
            { 14, -7 }, { -14, -7 }, { 16, 6 }, { -16, 6 }, { 9, 16 }, { -9, 16 },
            { 17, -1 }, { -17, -1 }, { 6, -16 }, { -6, -16 },
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
                }
            }
            fi++;
        }

        // Lanterns on quartz posts ringing the island, between the rim beacons.
        for (int a = 12; a < 360; a += 60) {
            double rad = Math.toRadians(a);
            int x = cx + (int) Math.round(Math.cos(rad) * (R_ISLAND - 3));
            int z = cz + (int) Math.round(Math.sin(rad) * (R_ISLAND - 3));
            Block ground = w.getBlockAt(x, fy, z);
            if (ground.getType() != Material.GRASS_BLOCK) continue;
            w.getBlockAt(x, fy + 1, z).setType(Material.SMOOTH_QUARTZ, false);
            w.getBlockAt(x, fy + 2, z).setType(Material.LANTERN, false);
        }
    }

    /** A small log+leaves tree. Skips if the ground isn't solid grass. */
    private void tree(World w, int x, int fy, int z, Material log, Material leaf, int h) {
        if (w.getBlockAt(x, fy, z).getType() != Material.GRASS_BLOCK) return;
        int wy = fy + 1;
        for (int i = 0; i < h; i++) w.getBlockAt(x, wy + i, z).setType(log, false);
        int topY = wy + h;
        for (int dy = -2; dy <= 0; dy++) {
            int r = dy <= -1 ? 2 : 1;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx == 0 && dz == 0 && dy < 0) continue;
                    if (Math.abs(dx) == 2 && Math.abs(dz) == 2) continue;
                    Block bl = w.getBlockAt(x + dx, topY + dy, z + dz);
                    if (bl.getType() == Material.AIR) bl.setType(leaf, false);
                }
            }
        }
        w.getBlockAt(x, topY + 1, z).setType(leaf, false);
    }

    // ── Shared helpers ───────────────────────────────────────────────────────

    /** Place a stair block with the given facing. */
    private void stair(World w, int x, int y, int z, Material mat, BlockFace facing) {
        Block b = w.getBlockAt(x, y, z);
        b.setType(mat, false);
        BlockData bd = b.getBlockData();
        if (bd instanceof Stairs s) {
            s.setFacing(facing);
            b.setBlockData(s, false);
        }
    }

    /** The cardinal face pointing from the drum centre toward (ox,oz). */
    private BlockFace faceToward(int ox, int oz) {
        if (Math.abs(ox) >= Math.abs(oz)) return ox >= 0 ? BlockFace.EAST : BlockFace.WEST;
        return oz >= 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    /** Place vine / glow-lichen clinging to the wall face behind it. */
    private void placeClinging(Block b, Material m, BlockFace toward) {
        b.setType(m, false);
        BlockData bd = b.getBlockData();
        // The clinging plant must attach to the opposite face (the wall it grows on).
        if (bd instanceof MultipleFacing mf) {
            BlockFace attach = toward.getOppositeFace();
            if (mf.getAllowedFaces().contains(attach)) {
                mf.setFace(attach, true);
                b.setBlockData(mf, false);
            }
        }
    }
}

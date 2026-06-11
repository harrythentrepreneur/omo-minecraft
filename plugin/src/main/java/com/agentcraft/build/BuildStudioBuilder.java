package com.agentcraft.build;

import com.agentcraft.AgentCraftPlugin;
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
import org.bukkit.block.sign.Side;

/**
 * Builds the live-build studio: a small glass VIEWING BOX you stand inside and a
 * flat 16×16 clearable plot just beyond its far (north) glass wall. You walk into
 * the box, type a request in chat, and the "mason" villager builds it live on the
 * plot in front of you — visible straight through the glass.
 *
 * <pre>
 *   plot (mason fills it on request)        viewing box (you stand here)
 *   ┌──────────────────────┐   curb  gap   ┌───────────┐
 *   │                      │   ════  ←3→    │ ░░░glass░░ │
 *   │   16 × 16 clearing    │  (north)      │ ░░ you ░░ │  ← doorway on the
 *   │                      │               │ ░░░░░░░░░ │     SOUTH (approach) side
 *   └──────────────────────┘               └───────────┘
 *        (−Z, north)                              (anchor)
 * </pre>
 *
 * The plot is axis-aligned: local (x,y,z) maps to world by a pure translation from
 * its origin (see {@link BuildPlot}). It sits to the NORTH (−Z) of the box, centred
 * on the box's X. local y=0 is the grass floor. The box faces north over it.
 */
public final class BuildStudioBuilder {

    public static final String ROOM = "buildstudio";
    public static final String MASON_ID = "mason";
    public static final String MASON_ROLE = "live build architect";

    // Plot footprint: 16 wide (x) × 20 tall (y) × 16 deep (z).
    public static final int PLOT_W = 16;
    public static final int PLOT_H = 20;
    public static final int PLOT_D = 16;
    public static final Material FLOOR_MATERIAL = Material.GRASS_BLOCK;

    private static final int BOX_HX = 3;   // viewing box half-extent across (x): 7 wide
    private static final int BOX_HZ = 2;   // viewing box half-extent deep (z): 5 deep
    private static final int BOX_H = 5;    // wall height
    private static final int PLOT_GAP = 3; // blocks between the box's far wall and the plot
    private static final Material GLASS = Material.ORANGE_STAINED_GLASS;

    /** What the studio build produced — for the command to seat the mason + teleport. */
    public record Result(String room, String villagerId, String villagerRole,
                         Location villagerHome, Location deckStand, int blocksPlaced) {}

    private final AgentCraftPlugin plugin;
    private final RoomManager rooms;
    private final BuildPlotManager plots;

    public BuildStudioBuilder(AgentCraftPlugin plugin, RoomManager rooms, BuildPlotManager plots) {
        this.plugin = plugin;
        this.rooms = rooms;
        this.plots = plots;
    }

    /** Build the box + plot anchored on {@code at} (the box's centre/floor). */
    public Result build(Location at) {
        World w = at.getWorld();
        int bx = at.getBlockX();
        int bz = at.getBlockZ();
        int wy = at.getBlockY();   // walk level (player's feet)
        int fy = wy - 1;           // floor blocks sit one below
        int placed = 0;

        placed += buildViewingBox(w, bx, fy, bz);

        // Plot sits NORTH (−Z) of the box, centred on the box's X. local y=0 == floor.
        int ox = bx - PLOT_W / 2;                 // west edge of the plot
        int nearEdgeZ = bz - BOX_HZ - PLOT_GAP;   // the plot's south (near) edge
        int oz = nearEdgeZ - (PLOT_D - 1);        // local z=0 is the far (north) edge
        int oy = fy;

        placed += buildPlotFloor(w, ox, oy, oz);

        // Register the live plot so build_ops frames addressed to "mason" land here.
        plots.register(MASON_ID, new BuildPlot(w, ox, oy, oz, PLOT_W, PLOT_H, PLOT_D, FLOOR_MATERIAL, plugin, MASON_ID));

        // Room covers the box + the plot so chat typed inside the box routes to the mason.
        Location boxCenter = new Location(w, bx + 0.5, wy, bz + 0.5, 180f, 0f); // face −Z (north, over the plot)
        rooms.define(ROOM, boxCenter, 18);

        // Mason rests at the plot's near (south) edge, centred, facing into the plot
        // (north) — right in front of the box's window. deckStand is the box centre,
        // also facing the plot.
        Location villagerHome = new Location(w, bx + 0.5, wy, nearEdgeZ + 0.5, 180f, 0f);
        Location deckStand = new Location(w, bx + 0.5, wy, bz + 0.5, 180f, 0f);

        return new Result(ROOM, MASON_ID, MASON_ROLE, villagerHome, deckStand, placed);
    }

    // ── Viewing box ───────────────────────────────────────────────────────────

    /**
     * A 7×5×5 glass box (white-concrete floor, stained-glass walls, quartz-pillar
     * corners, glass ceiling) — the same basic palette as the Hermes/Code boxes.
     * The doorway is on the SOUTH wall (the approach side); the plot is visible
     * straight through the (transparent) NORTH glass wall.
     */
    private int buildViewingBox(World w, int bx, int fy, int bz) {
        int placed = 0;
        int wy = fy + 1;
        for (int dx = -BOX_HX; dx <= BOX_HX; dx++) {
            for (int dz = -BOX_HZ; dz <= BOX_HZ; dz++) {
                w.getBlockAt(bx + dx, fy, bz + dz).setType(Material.WHITE_CONCRETE, false);  // floor
                w.getBlockAt(bx + dx, wy + BOX_H, bz + dz).setType(GLASS, false);            // ceiling
                placed += 2;
                boolean edge = Math.abs(dx) == BOX_HX || Math.abs(dz) == BOX_HZ;
                if (!edge) continue;
                boolean corner = Math.abs(dx) == BOX_HX && Math.abs(dz) == BOX_HZ;
                for (int dy = 0; dy < BOX_H; dy++) {
                    w.getBlockAt(bx + dx, wy + dy, bz + dz)
                            .setType(corner ? Material.QUARTZ_PILLAR : GLASS, false);
                    placed++;
                }
            }
        }
        // Doorway (3 wide × 3 tall) on the SOUTH wall, facing the approach.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = 0; dy < 3; dy++) {
                w.getBlockAt(bx + dx, wy + dy, bz + BOX_HZ).setType(Material.AIR, false);
            }
        }
        // Glow pad at centre where the player stands to watch.
        w.getBlockAt(bx, fy, bz).setType(Material.SEA_LANTERN, false);
        placed++;
        // Marquee sign over the doorway, facing the approaching player.
        placed += wallSign(w, bx, wy + BOX_H + 1, bz + BOX_HZ, BlockFace.SOUTH,
                "Build Studio", "Type a request", "mason builds it", "Right-click it");
        return placed;
    }

    // ── Plot floor + curb ─────────────────────────────────────────────────────

    /**
     * Flat plot: grass floor at {@code oy} and AIR cleared {@code PLOT_H} blocks
     * above it (so the local y=0 floor matches and nothing obstructs a fresh
     * build). A 1-block sea-lantern/smooth-stone curb rings the footprint so the
     * buildable area is visible from the box.
     */
    private int buildPlotFloor(World w, int ox, int oy, int oz) {
        int placed = 0;
        for (int lx = 0; lx < PLOT_W; lx++) {
            for (int lz = 0; lz < PLOT_D; lz++) {
                w.getBlockAt(ox + lx, oy, oz + lz).setType(FLOOR_MATERIAL, false);
                placed++;
                for (int ly = 1; ly < PLOT_H; ly++) {
                    w.getBlockAt(ox + lx, oy + ly, oz + lz).setType(Material.AIR, false);
                    placed++;
                }
            }
        }
        // Curb ring just outside the footprint, at floor level.
        for (int lx = -1; lx <= PLOT_W; lx++) {
            placed += curbBlock(w, ox + lx, oy, oz - 1);
            placed += curbBlock(w, ox + lx, oy, oz + PLOT_D);
        }
        for (int lz = 0; lz < PLOT_D; lz++) {
            placed += curbBlock(w, ox - 1, oy, oz + lz);
            placed += curbBlock(w, ox + PLOT_W, oy, oz + lz);
        }
        return placed;
    }

    private int curbBlock(World w, int x, int y, int z) {
        boolean lamp = (x % 8 == 0) || (z % 8 == 0);
        w.getBlockAt(x, y, z).setType(lamp ? Material.SEA_LANTERN : Material.SMOOTH_STONE, false);
        return 1;
    }

    // ── Sign helper ───────────────────────────────────────────────────────────

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

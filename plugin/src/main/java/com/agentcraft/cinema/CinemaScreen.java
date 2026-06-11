package com.agentcraft.cinema;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.GlowItemFrame;
import org.bukkit.entity.ItemFrame;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A W×H grid of map-on-itemframe tiles forming a giant cinema screen.
 * Pulls frames from a {@link CinemaFrameStore} via {@link CinemaTileRenderer}.
 *
 * <p>Geometry: caller supplies the back-of-wall top-left block and the
 * direction the audience sits (the frames face this way). The screen
 * lays out columns along the wall's horizontal axis and rows downward.
 *
 *   audienceDir = SOUTH means the wall runs east–west; frames point south;
 *   columns increase in +X, rows increase in -Y (top-down visual order).
 *
 * The wall blocks themselves are painted polished blackstone for a
 * cinema-bezel look; the user can swap the material before calling if a
 * different aesthetic is wanted.
 */
public final class CinemaScreen {

    public record Result(List<ItemFrame> frames, List<MapView> maps, int blocksPlaced,
                         CinemaScreenGeometry geometry) {}

    /**
     * @param topLeftWall  the block that will be the TOP-LEFT bezel of the screen
     *                     (looking AT the screen from the audience side).
     * @param audienceDir  cardinal BlockFace the audience is on. Frames face this way.
     * @param cols         number of tile columns (each = 128 px wide).
     * @param rows         number of tile rows (each = 128 px tall).
     * @param bezelMaterial wall block material behind the frames.
     * @param store        frame source for every tile.
     */
    public static Result build(
            Location topLeftWall,
            BlockFace audienceDir,
            int cols,
            int rows,
            Material bezelMaterial,
            CinemaFrameStore store
    ) {
        World world = topLeftWall.getWorld();
        if (world == null) throw new IllegalStateException("world unloaded");
        if (audienceDir != BlockFace.NORTH && audienceDir != BlockFace.SOUTH
                && audienceDir != BlockFace.EAST && audienceDir != BlockFace.WEST) {
            throw new IllegalArgumentException("audienceDir must be cardinal, got " + audienceDir);
        }

        // The wall extends horizontally perpendicular to audienceDir.
        // Pick a +column-step BlockFace by rotating audienceDir 90° clockwise (looking from above).
        BlockFace colStep = rotateClockwise(audienceDir);
        // Frames live one block IN FRONT of the wall (toward the audience).
        BlockFace toAudience = audienceDir;

        List<ItemFrame> frames = new ArrayList<>(cols * rows);
        List<MapView> maps = new ArrayList<>(cols * rows);
        Map<UUID, CinemaScreenGeometry.Tile> tiles = new HashMap<>(cols * rows);
        int placed = 0;

        int wallPxW = cols * 128;
        int wallPxH = rows * 128;
        // The store scales + quantises every frame to the full wall once
        // (off the main thread); tile renderers then just copy their slice.
        store.setWallSize(wallPxW, wallPxH);

        // Image axes in world space. Columns are laid out along colStep, but
        // the renderer mirrors them (imageCol = cols-1-col), so the image's
        // left→right axis runs OPPOSITE colStep. Top→bottom is always down.
        Vector imgX = colStep.getDirection().multiply(-1);
        Vector imgY = new Vector(0, -1, 0);
        double sumX = 0, sumY = 0, sumZ = 0;

        // Pre-clear any old frames in the bounding box so a rebuild doesn't
        // stack duplicates from a previous cinema.
        clearOldFrames(world, topLeftWall, colStep, cols, rows);

        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                Block wall = topLeftWall.getBlock()
                        .getRelative(colStep, col)
                        .getRelative(0, -row, 0);
                wall.setType(bezelMaterial, false);
                placed++;

                Block frameSpot = wall.getRelative(toAudience, 1);
                if (frameSpot.getType().isSolid()) {
                    // Clear whatever was in front of the wall so the frame can attach.
                    frameSpot.setType(Material.AIR, false);
                }

                ItemFrame frame;
                Location frameLoc = frameSpot.getLocation().add(0.5, 0.5, 0.5);
                try {
                    // Use GlowItemFrame so the screen reads as "lit" even in
                    // dim rooms — a real cinema screen self-illuminates.
                    frame = world.spawn(frameLoc, GlowItemFrame.class, fr -> {
                        fr.setFacingDirection(toAudience, true);
                        fr.setInvulnerable(true);
                        fr.setVisible(false); // hide the wooden border for a seamless wall
                        fr.setFixed(true);
                    });
                } catch (Exception ex) {
                    // Spawn refused — skip this tile; partial wall is still
                    // better than aborting the whole build.
                    continue;
                }

                MapView mv = Bukkit.createMap(world);
                for (MapRenderer r : new ArrayList<>(mv.getRenderers())) mv.removeRenderer(r);
                // colStep rotates audienceDir CLOCKWISE, which lays physical
                // columns out right-to-left from the audience's point of view
                // (col 0 sits at the viewer's right). The renderer treats
                // tileCol 0 as the image's LEFT edge, so feed it the mirrored
                // index — otherwise the whole screen renders left-right flipped.
                int imageCol = cols - 1 - col;
                mv.addRenderer(new CinemaTileRenderer(store, imageCol, row, wallPxW, wallPxH));
                mv.setUnlimitedTracking(true);

                ItemStack item = new ItemStack(Material.FILLED_MAP);
                MapMeta meta = (MapMeta) item.getItemMeta();
                meta.setMapView(mv);
                item.setItemMeta(meta);
                frame.setItem(item, false);

                frames.add(frame);
                maps.add(mv);
                Vector c = frameLoc.toVector();
                tiles.put(frame.getUniqueId(),
                        new CinemaScreenGeometry.Tile(imageCol, row, c));
                sumX += c.getX(); sumY += c.getY(); sumZ += c.getZ();
            }
        }

        Location center = frames.isEmpty()
                ? topLeftWall.clone()
                : new Location(world, sumX / frames.size(), sumY / frames.size(), sumZ / frames.size());
        // Generous view range: the screen is large and meant to be watched
        // from across the room, so push fresh frames to anyone in the hall.
        double viewRange = Math.max(cols, rows) * 8.0 + 32.0;
        CinemaScreenGeometry geometry = new CinemaScreenGeometry(
                store.id(), cols, rows, imgX, imgY, tiles, maps, center, viewRange);

        return new Result(frames, maps, placed, geometry);
    }

    /** Remove any pre-existing item frames in the screen footprint so rebuilds don't duplicate. */
    private static void clearOldFrames(World world, Location topLeftWall, BlockFace colStep, int cols, int rows) {
        Block tl = topLeftWall.getBlock();
        Block br = tl.getRelative(colStep, cols - 1).getRelative(0, -(rows - 1), 0);
        // Add a 2-block margin on the audience side so we also pick up the
        // currently-attached frames (they live one block in front of the wall).
        double minX = Math.min(tl.getX(), br.getX()) - 1.5;
        double maxX = Math.max(tl.getX(), br.getX()) + 1.5;
        double minY = Math.min(tl.getY(), br.getY()) - 0.5;
        double maxY = Math.max(tl.getY(), br.getY()) + 1.5;
        double minZ = Math.min(tl.getZ(), br.getZ()) - 1.5;
        double maxZ = Math.max(tl.getZ(), br.getZ()) + 1.5;
        for (var entity : world.getNearbyEntities(
                new org.bukkit.util.BoundingBox(minX, minY, minZ, maxX, maxY, maxZ),
                e -> e instanceof ItemFrame)) {
            entity.remove();
        }
    }

    private static BlockFace rotateClockwise(BlockFace dir) {
        return switch (dir) {
            case NORTH -> BlockFace.EAST;
            case EAST  -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            case WEST  -> BlockFace.NORTH;
            default    -> throw new IllegalArgumentException("not cardinal: " + dir);
        };
    }

    private CinemaScreen() {}
}

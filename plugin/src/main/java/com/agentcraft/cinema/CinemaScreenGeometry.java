package com.agentcraft.cinema;

import org.bukkit.Location;
import org.bukkit.map.MapView;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything the interactive layer needs to treat a {@link CinemaScreen}'s
 * map-wall as a clickable surface:
 *
 * <ul>
 *   <li>the in-world geometry to turn a player's ray-trace hit on a tile into
 *       a normalised [0,1]×[0,1] point on the whole screen (which the face
 *       then scales to the capture browser's viewport), and</li>
 *   <li>the live {@link MapView} tiles + a centre/range so the push loop can
 *       force-send fresh frames to nearby viewers faster than the vanilla
 *       map tracker would.</li>
 * </ul>
 *
 * <p>Coordinate model: each tile is exactly one block on the wall. Within a
 * tile, the local offset along {@code imgX} (image left→right) and
 * {@code imgY} (image top→bottom) gives a fraction [0,1]; combined with the
 * tile's image column/row it yields the screen-wide normalised point. Because
 * {@code imgX}/{@code imgY} are perpendicular to the screen normal, the
 * tile's stored centre can be the block centre — the depth component drops
 * out of both dot products.
 */
public final class CinemaScreenGeometry {

    /** Per-tile: where it sits in the image grid and its world-space centre. */
    public record Tile(int imageCol, int imageRow, Vector center) {}

    private final String cinemaId;
    private final int cols;
    private final int rows;
    private final Vector imgX; // unit world vector: image +x (left → right)
    private final Vector imgY; // unit world vector: image +y (top → bottom)
    private final Map<UUID, Tile> tiles;
    private final List<MapView> maps;
    private final Location center;
    private final double viewRange;

    public CinemaScreenGeometry(
            String cinemaId,
            int cols,
            int rows,
            Vector imgX,
            Vector imgY,
            Map<UUID, Tile> tiles,
            List<MapView> maps,
            Location center,
            double viewRange) {
        this.cinemaId = cinemaId;
        this.cols = cols;
        this.rows = rows;
        this.imgX = imgX.clone().normalize();
        this.imgY = imgY.clone().normalize();
        this.tiles = tiles;
        this.maps = maps;
        this.center = center;
        this.viewRange = viewRange;
    }

    public String cinemaId() { return cinemaId; }
    public int cols() { return cols; }
    public int rows() { return rows; }
    public Map<UUID, Tile> tiles() { return tiles; }
    public List<MapView> maps() { return maps; }
    public Location center() { return center; }
    public double viewRange() { return viewRange; }

    public boolean owns(UUID frameId) { return tiles.containsKey(frameId); }

    /**
     * Project a ray-trace hit on one of this screen's tiles to a normalised
     * point on the whole screen, or {@code null} if the entity isn't a tile
     * of this screen.
     *
     * @param frameId the hit item-frame's UUID
     * @param hitPos  the world point the ray struck
     * @return {@code [nx, ny]} in [0,1], or {@code null}
     */
    public double[] toNormalized(UUID frameId, Vector hitPos) {
        Tile t = tiles.get(frameId);
        if (t == null) return null;
        Vector delta = hitPos.clone().subtract(t.center());
        double u = clamp01(0.5 + delta.dot(imgX));
        double v = clamp01(0.5 + delta.dot(imgY));
        double nx = (t.imageCol() + u) / cols;
        double ny = (t.imageRow() + v) / rows;
        return new double[] { clamp01(nx), clamp01(ny) };
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}

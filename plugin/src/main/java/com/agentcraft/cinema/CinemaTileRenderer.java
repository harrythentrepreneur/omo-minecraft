package com.agentcraft.cinema;

import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapPalette;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

/**
 * One 128×128 map tile inside a cinema's W×H wall. Each render() copies this
 * tile's slice out of the store's pre-quantised full-wall buffer (cheap — the
 * scaling + palette match already happened off-thread in
 * {@link CinemaFrameStore}), then paints the shared cursor reticle if it falls
 * inside this tile.
 *
 * <p>Bukkit calls render() ~once per tick per viewer when the map is "dirty".
 * We repaint whenever the store's frame-id OR cursor-version advances. Because
 * the {@link MapCanvas} persists between renders, skipping an unchanged frame
 * leaves the last image intact — which also makes {@link Player#sendMap}
 * (used by the push loop to beat the vanilla tracker's slow cadence) safe.
 */
public final class CinemaTileRenderer extends MapRenderer {

    private final CinemaFrameStore store;
    private final int tileCol;
    private final int tileRow;

    private long lastVersion = -1L;
    private boolean dirty = true;

    @SuppressWarnings("deprecation")
    private static final byte BG = MapPalette.matchColor(8, 10, 14);
    @SuppressWarnings("deprecation")
    private static final byte CURSOR = MapPalette.matchColor(255, 255, 255);
    @SuppressWarnings("deprecation")
    private static final byte CURSOR_HALO = MapPalette.matchColor(20, 20, 20);

    public CinemaTileRenderer(CinemaFrameStore store, int tileCol, int tileRow, int wallPxW, int wallPxH) {
        super(false); // non-contextual: same paint for all viewers.
        this.store = store;
        this.tileCol = tileCol;
        this.tileRow = tileRow;
    }

    @Override
    public void render(MapView map, MapCanvas canvas, Player player) {
        long version = store.tileVersion(tileCol, tileRow);
        if (version == lastVersion && !dirty) return;

        byte[] buf = store.quantized();
        if (buf == null) {
            for (int x = 0; x < 128; x++)
                for (int y = 0; y < 128; y++)
                    canvas.setPixel(x, y, BG);
            lastVersion = version;
            dirty = false;
            return;
        }

        int wallW = store.wallW();
        int wallH = store.wallH();
        int x0 = tileCol * 128;
        int y0 = tileRow * 128;
        for (int px = 0; px < 128; px++) {
            int sx = x0 + px;
            for (int py = 0; py < 128; py++) {
                int sy = y0 + py;
                if (sx >= wallW || sy >= wallH) {
                    canvas.setPixel(px, py, BG);
                    continue;
                }
                int idx = sy * wallW + sx;
                canvas.setPixel(px, py, idx < buf.length ? buf[idx] : BG);
            }
        }

        if (store.cursorActive()) {
            int cwx = (int) Math.round(store.cursorNx() * wallW);
            int cwy = (int) Math.round(store.cursorNy() * wallH);
            // Only bother if the reticle could touch this tile.
            if (cwx >= x0 - 8 && cwx < x0 + 128 + 8 && cwy >= y0 - 8 && cwy < y0 + 128 + 8) {
                drawCursor(canvas, x0, y0, cwx, cwy);
            }
        }

        lastVersion = version;
        dirty = false;
    }

    /** White crosshair with a hollow centre and a dark halo, drawn at wall px (cwx,cwy). */
    private void drawCursor(MapCanvas canvas, int x0, int y0, int cwx, int cwy) {
        // Pass 1: dark halo around every arm pixel for contrast on any page.
        for (int t = 2; t <= 7; t++) {
            halo(canvas, x0, y0, cwx + t, cwy);
            halo(canvas, x0, y0, cwx - t, cwy);
            halo(canvas, x0, y0, cwx, cwy + t);
            halo(canvas, x0, y0, cwx, cwy - t);
        }
        halo(canvas, x0, y0, cwx, cwy);
        // Pass 2: white arms + centre dot on top.
        for (int t = 2; t <= 7; t++) {
            put(canvas, x0, y0, cwx + t, cwy, CURSOR);
            put(canvas, x0, y0, cwx - t, cwy, CURSOR);
            put(canvas, x0, y0, cwx, cwy + t, CURSOR);
            put(canvas, x0, y0, cwx, cwy - t, CURSOR);
        }
        put(canvas, x0, y0, cwx, cwy, CURSOR);
    }

    private void halo(MapCanvas canvas, int x0, int y0, int wx, int wy) {
        put(canvas, x0, y0, wx, wy, CURSOR_HALO);
        put(canvas, x0, y0, wx + 1, wy, CURSOR_HALO);
        put(canvas, x0, y0, wx - 1, wy, CURSOR_HALO);
        put(canvas, x0, y0, wx, wy + 1, CURSOR_HALO);
        put(canvas, x0, y0, wx, wy - 1, CURSOR_HALO);
    }

    private void put(MapCanvas canvas, int x0, int y0, int wx, int wy, byte color) {
        int cx = wx - x0;
        int cy = wy - y0;
        if (cx >= 0 && cx < 128 && cy >= 0 && cy < 128) canvas.setPixel(cx, cy, color);
    }
}

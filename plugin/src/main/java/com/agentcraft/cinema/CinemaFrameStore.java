package com.agentcraft.cinema;

import com.agentcraft.AgentCraftPlugin;
import org.bukkit.map.MapPalette;
import org.bukkit.scheduler.BukkitTask;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Polls {@code GET http://127.0.0.1:8766/api/cinema/<id>/frame} several times
 * a second and keeps a ready-to-blit, full-wall buffer of Minecraft map-colour
 * bytes around for tile renderers to copy. One store per cinema id.
 *
 * <p>Two things make video-rate playback affordable:
 * <ol>
 *   <li>The expensive work — scaling the screenshot to the wall and quantising
 *       every pixel to the map palette — happens ONCE per frame on the async
 *       poll thread, not once per tile per render. Tile renderers just copy
 *       their 128×128 slice out of {@link #quantized()}.</li>
 *   <li>Quantisation goes through a 15-bit RGB→palette lookup table built once
 *       at startup, turning {@link MapPalette#matchColor}'s palette search into
 *       a single array read per pixel.</li>
 * </ol>
 *
 * <p>The frame-id counter increments every time we decode a fresh frame; the
 * cursor-version counter increments when the in-game cursor moves. Tile
 * renderers repaint whenever either advances.
 *
 * <p>The runtime answers 204 when the face's headless capture isn't pushing
 * frames; we treat that as "hold the previous frame" rather than blanking.
 */
public final class CinemaFrameStore {

    private static final Duration TIMEOUT = Duration.ofMillis(1500);
    // 1 tick = 20 Hz, the server tick rate and the ceiling for the
    // main-thread sendMap push. The face uploads ~30 fps so there's always a
    // fresh frame waiting each pull. (Bukkit async-timer periods are in ticks,
    // so 20 Hz is the fastest the scheduler can drive this loop.)
    private static final long POLL_PERIOD_TICKS = 1L;

    private final AgentCraftPlugin plugin;
    private final String id;
    private final URI endpoint;
    private final HttpClient http;

    // Quantised full-wall buffer (one map-colour byte per wall pixel,
    // row-major) plus the size it was built at. Replaced atomically per frame.
    private final AtomicReference<byte[]> quantized = new AtomicReference<>(null);
    private volatile int wallW = 0;
    private volatile int wallH = 0;
    private final AtomicLong frameId = new AtomicLong(0L);
    private byte[] lastBody; // raw bytes of the last frame, for identity skip (poll thread only)
    private BukkitTask pollTask;

    // Per-tile dirty tracking — the heart of "send only what changed". Each
    // tile (one 128×128 map) carries a version that bumps when its pixels OR
    // the cursor-over-it state change. Renderers and the push loop compare
    // versions so a static page costs ~nothing and the main thread never
    // re-sends 40 tiles for a one-tile change. Image-space index = row*tcols+col.
    private volatile int tcols = 0;
    private volatile int trows = 0;
    private volatile AtomicLongArray tileVersion; // concurrent: poll thread (pixels) + main (cursor)
    private long[] tileChecksum;                  // poll thread only

    // Reused scratch (poll thread only) so the 20 fps hot path doesn't churn
    // ~50 MB/s of garbage — GC pauses are what turn "fast" into "janky".
    private int[] rgbScratch;
    private BufferedImage scaledScratch;

    // Shared cursor (one per cinema — the wall is non-contextual, so all
    // viewers see the same reticle). Updated by the input controller.
    private volatile boolean cursorActive = false;
    private volatile double cursorNx = 0.0;
    private volatile double cursorNy = 0.0;
    private final AtomicLong cursorVersion = new AtomicLong(0L);

    public CinemaFrameStore(AgentCraftPlugin plugin, String id, String runtimeBase) {
        this.plugin = plugin;
        this.id = id;
        this.endpoint = URI.create(runtimeBase + "/api/cinema/" + id + "/frame");
        this.http = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public String id() { return id; }

    /** Set by {@link CinemaScreen#build} so the store knows the wall pixel size. */
    public void setWallSize(int w, int h) {
        this.wallW = Math.max(0, w);
        this.wallH = Math.max(0, h);
        int tc = Math.max(1, wallW / 128);
        int tr = Math.max(1, wallH / 128);
        this.tcols = tc;
        this.trows = tr;
        this.tileVersion = new AtomicLongArray(tc * tr);
        this.tileChecksum = new long[tc * tr];
        this.lastBody = null; // force a fresh decode + dirty pass on the new wall
    }

    public int wallW() { return wallW; }
    public int wallH() { return wallH; }
    public int tileCols() { return tcols; }
    public int tileRows() { return trows; }

    /** Per-tile change version (pixels or cursor). Image-space (col,row). */
    public long tileVersion(int tcol, int trow) {
        AtomicLongArray tv = tileVersion;
        if (tv == null || tcol < 0 || trow < 0 || tcol >= tcols || trow >= trows) return 0L;
        return tv.get(trow * tcols + tcol);
    }

    /** Latest quantised wall buffer (row-major map-colour bytes), or null. */
    public byte[] quantized() { return quantized.get(); }

    public long currentFrameId() { return frameId.get(); }

    public long cursorVersion() { return cursorVersion.get(); }
    public boolean cursorActive() { return cursorActive; }
    public double cursorNx() { return cursorNx; }
    public double cursorNy() { return cursorNy; }

    public void setCursor(double nx, double ny) {
        double oldNx = cursorNx, oldNy = cursorNy;
        boolean wasActive = cursorActive;
        this.cursorNx = nx;
        this.cursorNy = ny;
        this.cursorActive = true;
        cursorVersion.incrementAndGet();
        // Dirty the tiles the reticle just left AND the ones it now covers, so
        // only those repaint/re-send — not the whole wall.
        if (wasActive) bumpCursorTiles(oldNx, oldNy);
        bumpCursorTiles(nx, ny);
    }

    public void clearCursor() {
        if (cursorActive) {
            double nx = cursorNx, ny = cursorNy;
            cursorActive = false;
            cursorVersion.incrementAndGet();
            bumpCursorTiles(nx, ny); // erase the reticle from the tiles it was on
        }
    }

    /** Bump the version of every tile the reticle at (nx,ny) overlaps. */
    private void bumpCursorTiles(double nx, double ny) {
        AtomicLongArray tv = tileVersion;
        if (tv == null || wallW <= 0 || wallH <= 0) return;
        int cwx = (int) Math.round(nx * wallW);
        int cwy = (int) Math.round(ny * wallH);
        final int reticle = 8; // matches CinemaTileRenderer's crosshair reach
        int c0 = Math.max(0, (cwx - reticle) / 128), c1 = Math.min(tcols - 1, (cwx + reticle) / 128);
        int r0 = Math.max(0, (cwy - reticle) / 128), r1 = Math.min(trows - 1, (cwy + reticle) / 128);
        for (int r = r0; r <= r1; r++)
            for (int c = c0; c <= c1; c++)
                tv.incrementAndGet(r * tcols + c);
    }

    /** FNV-1a per tile; bump versions of tiles whose pixels changed. Poll thread. */
    private void markDirtyTiles(byte[] buf) {
        AtomicLongArray tv = tileVersion;
        long[] sums = tileChecksum;
        if (tv == null || sums == null) return;
        int tc = tcols, tr = trows, ww = wallW;
        for (int trow = 0; trow < tr; trow++) {
            int y0 = trow * 128;
            for (int tcol = 0; tcol < tc; tcol++) {
                int x0 = tcol * 128;
                long h = 0xcbf29ce484222325L;
                for (int py = 0; py < 128; py++) {
                    int base = (y0 + py) * ww + x0;
                    for (int px = 0; px < 128; px++) {
                        h ^= (buf[base + px] & 0xff);
                        h *= 0x100000001b3L;
                    }
                }
                int idx = trow * tc + tcol;
                if (sums[idx] != h) {
                    sums[idx] = h;
                    tv.incrementAndGet(idx);
                }
            }
        }
    }

    public void start() {
        if (pollTask != null) return;
        // Async timer: the HTTP fetch, PNG/JPEG decode, scale and quantise all
        // happen off the main thread. The result is published via the atomic
        // buffer the renderers read on whichever thread Bukkit calls render().
        pollTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
                plugin, this::tick, POLL_PERIOD_TICKS, POLL_PERIOD_TICKS);
    }

    public void stop() {
        if (pollTask != null) { pollTask.cancel(); pollTask = null; }
    }

    private void tick() {
        try {
            HttpRequest req = HttpRequest.newBuilder(endpoint)
                    .GET()
                    .timeout(TIMEOUT)
                    .build();
            HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() != 200) return; // 204 = no fresh frame; keep previous.
            byte[] body = res.body();
            if (body == null || body.length == 0) return;
            // Identical frame (static page, or the runtime replaying its last
            // PNG until a new one is posted) → skip decode + don't bump the
            // frame-id, so the push loop stays idle when nothing's moving.
            if (lastBody != null && java.util.Arrays.equals(body, lastBody)) return;
            lastBody = body;
            BufferedImage img;
            try (ByteArrayInputStream in = new ByteArrayInputStream(body)) {
                img = ImageIO.read(in);
            }
            if (img == null) return;
            int w = wallW, h = wallH;
            if (w <= 0 || h <= 0) return; // wall not built yet
            byte[] q = quantizeToWall(img, w, h);
            quantized.set(q);
            markDirtyTiles(q);
            frameId.incrementAndGet();
        } catch (Throwable ignored) {
            // Runtime down / network burp / decode error — silent. Renderers
            // hold the previous buffer.
        }
    }

    /**
     * Scale the screenshot to the wall and quantise every pixel to the palette.
     * Runs only on the single poll thread, so the scale image + RGB array are
     * reused across frames; only the published {@code out} buffer is fresh.
     */
    private byte[] quantizeToWall(BufferedImage src, int w, int h) {
        BufferedImage scaled;
        if (src.getWidth() == w && src.getHeight() == h) {
            // Capture already matches the wall — no resample needed.
            scaled = src;
        } else {
            if (scaledScratch == null || scaledScratch.getWidth() != w || scaledScratch.getHeight() != h) {
                scaledScratch = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            }
            scaled = scaledScratch;
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            // drawImage scales src to fill the whole w×h, so no fill needed.
            g.drawImage(src, 0, 0, w, h, null);
            g.dispose();
        }
        if (rgbScratch == null || rgbScratch.length != w * h) rgbScratch = new int[w * h];
        int[] rgb = scaled.getRGB(0, 0, w, h, rgbScratch, 0, w);
        byte[] out = new byte[w * h];
        byte[] lut = lut();
        for (int i = 0; i < rgb.length; i++) {
            int c = rgb[i];
            int r = (c >> 16) & 0xff, gg = (c >> 8) & 0xff, b = c & 0xff;
            out[i] = lut[((r >> 3) << 10) | ((gg >> 3) << 5) | (b >> 3)];
        }
        return out;
    }

    // ── 15-bit RGB → map-palette lookup table (built once) ──────────────────
    private static volatile byte[] LUT;

    @SuppressWarnings("deprecation")
    private static byte[] lut() {
        byte[] l = LUT;
        if (l != null) return l;
        synchronized (CinemaFrameStore.class) {
            if (LUT != null) return LUT;
            byte[] t = new byte[32768];
            for (int r = 0; r < 32; r++) {
                int rr = (r << 3) | (r >> 2);
                for (int g = 0; g < 32; g++) {
                    int gg = (g << 3) | (g >> 2);
                    for (int b = 0; b < 32; b++) {
                        int bb = (b << 3) | (b >> 2);
                        t[(r << 10) | (g << 5) | b] = MapPalette.matchColor(rr, gg, bb);
                    }
                }
            }
            LUT = t;
            return t;
        }
    }
}

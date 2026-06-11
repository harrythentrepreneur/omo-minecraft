package com.agentcraft.terminal.cinema;

import com.agentcraft.terminal.RuntimeHost;
import net.minecraft.client.texture.NativeImage;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Polls the runtime's {@code GET /api/cinema/<id>/frame} endpoint at ~30 Hz and
 * decodes each frame (JPEG from the CDP screencast, PNG on the screenshot
 * fallback — {@link NativeImage#read} sniffs the format either way) into a
 * {@link NativeImage}. {@link CinemaScreen} reads the latest decoded frame each
 * render tick and blits it fullscreen.
 *
 * <p>This is the same shape as {@code omo.OmoFramePoller} — the proven path that
 * already streams the live Omo head into the HUD — but per cinema id and at the
 * higher cadence a fullscreen "real computer" screen wants. The map-wall caps at
 * ~1 Hz; this client texture path is bounded only by how fast the face pushes
 * frames into the runtime (~30 fps) and the client's own framerate.
 *
 * <p>The runtime answers 204 when the face isn't currently pushing frames for
 * this cinema (page mid-navigation, runtime just started). We treat that as
 * "no fresh frame" and the screen shows its connecting placeholder until one
 * arrives. Decoded NativeImages are owned here: we close the previous one AFTER
 * swapping in the new one so the renderer never reads a freed pointer.
 */
public final class CinemaFramePoller {

    private static final Duration TIMEOUT = Duration.ofMillis(1500);
    // ~60 Hz. Window-capture pushes at up to 60 fps; match that so the
    // fullscreen CinemaScreen gets every frame without adding a poll-cycle
    // of lag. CDP screencast tops out at ~30 fps anyway, so the extra polls
    // cost nothing for web-page cinemas (just a 204 + no-op).
    private static final long PERIOD_MS = 16L;
    // Slightly larger than the runtime's own stale window (8000ms) is overkill;
    // 3000ms keeps the placeholder from flashing on brief packet loss while
    // still surfacing a genuinely dead stream within a few seconds.
    private static final long STALE_MS = 3000L;
    private static final long WARN_INTERVAL_MS = 60_000L;

    private final String cinemaId;
    private final URI endpoint;
    private final HttpClient http;
    private final ScheduledExecutorService exec;

    private final AtomicReference<NativeImage> latest = new AtomicReference<>(null);
    private final AtomicLong frameId = new AtomicLong(0L);
    private final AtomicLong lastUpdatedMs = new AtomicLong(0L);

    private volatile long lastWarnMs = 0L;
    private volatile int lastWidth = 0;
    private volatile int lastHeight = 0;

    public CinemaFramePoller(String cinemaId) {
        this.cinemaId = cinemaId;
        this.endpoint = URI.create(RuntimeHost.httpUrl("/api/cinema/" + cinemaId + "/frame"));
        this.http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
        this.exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "agentcraft-cinema-frame-poller-" + cinemaId);
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        exec.scheduleWithFixedDelay(this::tick, 0L, PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    /** Latest decoded image, or null if none yet / stale. Borrow-only — do not close. */
    public NativeImage current() {
        if (isStale()) return null;
        return latest.get();
    }

    /** Monotonic counter; the screen re-uploads its texture only when this changes. */
    public long currentFrameId() {
        return frameId.get();
    }

    public boolean isStale() {
        if (latest.get() == null) return true;
        return System.currentTimeMillis() - lastUpdatedMs.get() > STALE_MS;
    }

    public int width() { return lastWidth; }
    public int height() { return lastHeight; }

    public void shutdown() {
        exec.shutdownNow();
        NativeImage img = latest.getAndSet(null);
        if (img != null) {
            try { img.close(); } catch (Throwable ignored) {}
        }
    }

    private void tick() {
        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder(endpoint)
                .GET()
                .timeout(TIMEOUT);
            String tok = RuntimeHost.token();
            if (!tok.isBlank()) rb.header("Authorization", "Bearer " + tok);
            HttpResponse<byte[]> res = http.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray());
            int code = res.statusCode();
            if (code == 204) {
                // Face isn't pushing frames for this cinema right now — let the
                // current frame go stale and the screen shows its placeholder.
                return;
            }
            if (code != 200) {
                maybeWarn("non-200 from /api/cinema/" + cinemaId + "/frame: " + code);
                return;
            }
            byte[] body = res.body();
            if (body == null || body.length == 0) return;

            NativeImage img;
            try (ByteArrayInputStream in = new ByteArrayInputStream(body)) {
                img = NativeImage.read(in);
            }
            lastWidth = headerInt(res, "X-Cinema-Width", img.getWidth());
            lastHeight = headerInt(res, "X-Cinema-Height", img.getHeight());
            lastUpdatedMs.set(System.currentTimeMillis());
            // Swap, then close the old one. The render thread can call current()
            // at any moment; closing first could hand it a freed pointer.
            NativeImage prev = latest.getAndSet(img);
            frameId.incrementAndGet();
            if (prev != null) {
                try { prev.close(); } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            maybeWarn("frame poll failed: " + t.getClass().getSimpleName()
                + (t.getMessage() != null ? " " + t.getMessage() : ""));
        }
    }

    private static int headerInt(HttpResponse<?> res, String name, int fallback) {
        return res.headers().firstValue(name)
            .map(s -> {
                try { return Integer.parseInt(s); } catch (NumberFormatException e) { return fallback; }
            })
            .orElse(fallback);
    }

    private void maybeWarn(String msg) {
        long now = System.currentTimeMillis();
        if (now - lastWarnMs > WARN_INTERVAL_MS) {
            lastWarnMs = now;
            System.out.println("[cinema-screen] " + msg);
        }
    }
}

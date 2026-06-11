package com.agentcraft.terminal.omo;

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
 * Polls the runtime's {@code GET /api/omo-frame} endpoint at ~12 Hz and
 * decodes each PNG it gets back into a {@link NativeImage}. The HUD layer
 * reads the latest decoded frame each render tick and blits it as the
 * live Omo head.
 *
 * Failure mode is silent on purpose: if the runtime is down, or the
 * overlay browser tab isn't open, or the network burped, we just mark
 * {@code live = false} and the HUD layer falls back to its bundled
 * pre-rendered sprite frames. One log line per minute documents the
 * outage so debugging "why is Omo not animating" doesn't require devtools.
 *
 * Decoded NativeImages are owned by this class. The HUD layer borrows a
 * reference each frame; when a new image arrives we close the old one
 * AFTER the swap so the renderer never sees a freed pointer.
 */
public class OmoFramePoller {

    private static final URI ENDPOINT = URI.create(RuntimeHost.httpUrl("/api/omo-frame"));
    private static final Duration TIMEOUT = Duration.ofMillis(500);
    // Matches overlay-app.js's POST_INTERVAL_MS. Polling faster than the
    // browser pushes is pure waste; slower introduces lag in the in-game
    // head's animations.
    private static final long PERIOD_MS = 40L;
    // If we haven't seen a fresh frame in this window, the HUD layer
    // treats `live` as false and reverts to sprites. Slightly larger than
    // the runtime's own stale window (2000ms) so we don't blink between
    // sprites and live during transient packet loss.
    private static final long STALE_MS = 2500L;
    private static final long WARN_INTERVAL_MS = 60_000L;

    private final HttpClient http;
    private final ScheduledExecutorService exec;

    // Latest decoded frame + bookkeeping. `frameId` increments on every
    // successful decode — the HUD layer uses it to decide whether to
    // re-upload the texture.
    private final AtomicReference<NativeImage> latest = new AtomicReference<>(null);
    private final AtomicLong frameId = new AtomicLong(0L);
    private final AtomicLong lastUpdatedMs = new AtomicLong(0L);

    private volatile long lastWarnMs = 0L;
    private volatile int lastWidth = 0;
    private volatile int lastHeight = 0;

    public OmoFramePoller() {
        this.http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
        this.exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "agentcraft-omo-frame-poller");
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

    /** Monotonic counter; HUD layer re-uploads texture only when this changes. */
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
            HttpRequest.Builder rb = HttpRequest.newBuilder(ENDPOINT)
                .GET()
                .timeout(TIMEOUT);
            String tok = RuntimeHost.token();
            if (!tok.isBlank()) rb.header("Authorization", "Bearer " + tok);
            HttpRequest req = rb.build();
            HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            int code = res.statusCode();
            if (code == 204) {
                // Overlay tab not pushing — drop to sprite fallback.
                disposeLatest();
                return;
            }
            if (code != 200) {
                // Anything else: warn occasionally, keep last frame around
                // briefly (it'll go stale on its own and we'll fall back).
                maybeWarn("non-200 from /api/omo-frame: " + code);
                return;
            }
            byte[] body = res.body();
            if (body == null || body.length == 0) return;

            // Decode off-thread. NativeImage.read uses STB internally and
            // allocates native memory we own — must close eventually.
            NativeImage img;
            try (ByteArrayInputStream in = new ByteArrayInputStream(body)) {
                img = NativeImage.read(in);
            }
            // Read dimensions from headers when present (cheap source of
            // truth); fall back to the decoded image if missing.
            int w = headerInt(res, "X-Omo-Width", img.getWidth());
            int h = headerInt(res, "X-Omo-Height", img.getHeight());
            lastWidth = w;
            lastHeight = h;
            lastUpdatedMs.set(System.currentTimeMillis());
            // Swap, then close the old one. Order matters — the HUD layer
            // can call current() at any moment, and if we closed first it
            // could read a freed pointer.
            NativeImage prev = latest.getAndSet(img);
            frameId.incrementAndGet();
            if (prev != null) {
                try { prev.close(); } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            // ConnectException, timeout, decode error — all silent in the
            // common case (just gives sprite fallback). Throttled log
            // surfaces persistent issues.
            maybeWarn("frame poll failed: " + t.getClass().getSimpleName()
                + (t.getMessage() != null ? " " + t.getMessage() : ""));
        }
    }

    private void disposeLatest() {
        NativeImage prev = latest.getAndSet(null);
        if (prev != null) {
            try { prev.close(); } catch (Throwable ignored) {}
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
            System.out.println("[omo-overlay] " + msg);
        }
    }
}

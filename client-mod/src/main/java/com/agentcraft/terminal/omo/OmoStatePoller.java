package com.agentcraft.terminal.omo;

import com.agentcraft.terminal.RuntimeHost;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Polls the omo-mc runtime's HTTP bridge at ~4Hz for the current Omo face
 * state and caches it in an {@link AtomicReference} that the HUD layer reads
 * each frame.
 *
 * Stays cheap: a single-threaded scheduled executor, a 500ms HTTP timeout,
 * silent on failure (runtime not up → we just sit at IDLE). No exceptions
 * propagate out to the client thread.
 *
 * Uses JDK 21's {@link HttpClient} — no third-party HTTP lib so we don't
 * bloat the jar-in-jar.
 */
public class OmoStatePoller {

    private static final URI ENDPOINT = URI.create(RuntimeHost.httpUrl("/api/face-state"));
    private static final Duration TIMEOUT = Duration.ofMillis(500);
    private static final long PERIOD_MS = 250L;          // 4 Hz
    // Throttle warn-level logs to once per N seconds so a missing runtime
    // never spams the client console.
    private static final long WARN_INTERVAL_MS = 60_000L;

    private final HttpClient http;
    private final ScheduledExecutorService exec;
    private final AtomicReference<OmoFaceState> current = new AtomicReference<>(OmoFaceState.IDLE);
    private volatile long lastWarnMs = 0L;
    private volatile boolean reachable = false;

    private static final Pattern MODE_RE =
        Pattern.compile("\"mode\"\\s*:\\s*\"([a-zA-Z_]+)\"");

    public OmoStatePoller() {
        this.http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
        this.exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "agentcraft-omo-poller");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        exec.scheduleWithFixedDelay(this::tick, 0L, PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    public OmoFaceState get() {
        return current.get();
    }

    public boolean isReachable() {
        return reachable;
    }

    private void tick() {
        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder(ENDPOINT)
                .GET()
                .timeout(TIMEOUT)
                .header("Accept", "application/json");
            String tok = RuntimeHost.token();
            if (!tok.isBlank()) rb.header("Authorization", "Bearer " + tok);
            HttpRequest req = rb.build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                fallToIdle();
                return;
            }
            Matcher m = MODE_RE.matcher(res.body());
            if (m.find()) {
                current.set(OmoFaceState.parse(m.group(1)));
                reachable = true;
            }
        } catch (Throwable t) {
            // Connection refused, timeout, etc. — runtime is just not up yet.
            fallToIdle();
            long now = System.currentTimeMillis();
            if (now - lastWarnMs > WARN_INTERVAL_MS) {
                lastWarnMs = now;
                // One line every minute is enough to debug "the overlay
                // never animated" — not a spam concern.
                System.out.println("[omo-overlay] runtime not reachable (" + t.getClass().getSimpleName() + ")");
            }
        }
    }

    private void fallToIdle() {
        reachable = false;
        current.set(OmoFaceState.IDLE);
    }
}

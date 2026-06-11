package com.agentcraft.terminal.omo;

import com.agentcraft.terminal.RuntimeHost;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/**
 * Tiny fire-and-forget HTTP client that POSTs to the omo-mc runtime's
 * {@code /api/voice-trigger} endpoint when the user presses the in-game
 * "V" keybind.
 *
 * <p>The runtime fans the request out to the face/ server's SSE stream,
 * which the browser face tab is subscribed to. The browser then runs the
 * exact same voice-loop start/stop code path the mic button click runs.
 *
 * <p>Design rules mirrored from {@link OmoStatePoller} /
 * {@link OmoFramePoller}: JDK 21 {@link HttpClient}, short timeout,
 * silent on failure (so a missing runtime never breaks the client tick).
 * Throttled warning to log so a misconfig is debuggable but never spammy.
 */
public final class OmoVoiceTrigger {

    private static final URI ENDPOINT = URI.create(RuntimeHost.httpUrl("/api/voice-trigger"));
    private static final Duration TIMEOUT = Duration.ofMillis(800);
    private static final long WARN_INTERVAL_MS = 60_000L;

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(TIMEOUT)
        .version(HttpClient.Version.HTTP_1_1)
        .build();

    // Daemon thread pool so the JVM can still exit cleanly on quit.
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "agentcraft-omo-voice");
        t.setDaemon(true);
        return t;
    });

    private static volatile long lastWarnMs = 0L;

    private OmoVoiceTrigger() {}

    /**
     * Callback invoked on the JVM async thread after the runtime replies.
     * {@code listeners} is the number of browser tabs currently subscribed
     * to the face's SSE /events stream — 0 means the user pressed V but no
     * tab is open to host the actual Gemini Live voice loop, which is the
     * most common "I pressed V and nothing happened" failure mode.
     *
     * The caller is responsible for hopping back to the client thread if
     * it wants to touch Minecraft UI — see TerminalMod#talkToOmo for the
     * MinecraftClient.execute() pattern.
     */
    @FunctionalInterface
    public interface ResultCallback {
        void onResult(boolean ok, int listeners, String error);
    }

    /**
     * POST {@code {"action":"toggle"}} to the runtime, off the client tick
     * thread. Returns immediately. Failures are logged at most once a
     * minute; the keybind tick path is never blocked.
     */
    public static void toggle() {
        send("toggle", null);
    }

    public static void toggle(ResultCallback cb) {
        send("toggle", cb);
    }

    public static void start() { send("start", null); }

    public static void stop() { send("stop", null); }

    // Retry budget for the "headless voice tab not subscribed yet" case.
    // If the user presses V right after ./agentcraft, the puppeteer voice
    // page may still be navigating — POST returns ok with listeners=0.
    // We retry the toggle a few times with 600 ms gaps so the press still
    // takes effect once the tab connects, instead of silently failing.
    // For "stop" actions we don't retry (idempotent and never blocks).
    private static final int  RETRY_ON_ZERO_LISTENERS = 4;     // ~2.4s total
    private static final long RETRY_GAP_MS            = 600L;

    private static CompletableFuture<Void> send(String action, ResultCallback cb) {
        byte[] body = ("{\"action\":\"" + action + "\"}").getBytes(StandardCharsets.UTF_8);
        HttpRequest.Builder rb = HttpRequest.newBuilder(ENDPOINT)
            .timeout(TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        String tok = RuntimeHost.token();
        if (!tok.isBlank()) rb.header("Authorization", "Bearer " + tok);
        HttpRequest req = rb.build();
        return CompletableFuture.runAsync(() -> {
            try {
                HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() >= 400) {
                    maybeWarn("voice-trigger " + action + " → HTTP " + res.statusCode());
                    if (cb != null) cb.onResult(false, 0, "HTTP " + res.statusCode());
                    return;
                }
                // Cheap regex parse — avoids dragging Gson into the client mod.
                // Response shape from runtime: {"ok":true,"action":"toggle","listeners":N,...}
                int listeners = parseListeners(res.body());
                // If the headless voice tab isn't subscribed yet (common
                // race in the first ~2s after ./agentcraft), retry a few
                // times before reporting back. We only retry start/toggle,
                // because resending "stop" repeatedly is harmless but
                // pointless.
                if (listeners == 0 && !"stop".equals(action)) {
                    for (int i = 0; i < RETRY_ON_ZERO_LISTENERS; i++) {
                        try { Thread.sleep(RETRY_GAP_MS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                        HttpResponse<String> retry = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                        if (retry.statusCode() >= 400) {
                            // Don't keep banging on an erroring face — bail.
                            maybeWarn("voice-trigger retry → HTTP " + retry.statusCode());
                            if (cb != null) cb.onResult(false, 0, "HTTP " + retry.statusCode());
                            return;
                        }
                        listeners = parseListeners(retry.body());
                        if (listeners > 0) break;
                    }
                }
                if (cb != null) cb.onResult(true, listeners, null);
            } catch (Throwable t) {
                // ConnectException = runtime not up; that's expected when
                // the user fires the keybind before ./agentcraft is running.
                maybeWarn("voice-trigger " + action + " failed: "
                    + t.getClass().getSimpleName()
                    + (t.getMessage() != null ? " " + t.getMessage() : ""));
                if (cb != null) cb.onResult(false, 0,
                    t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : ""));
            }
        }, EXEC);
    }

    private static int parseListeners(String body) {
        if (body == null) return -1;
        // Match "listeners":N (with optional whitespace, signed integer).
        java.util.regex.Matcher m = LISTENERS_RE.matcher(body);
        if (!m.find()) return -1;
        try { return Integer.parseInt(m.group(1)); }
        catch (NumberFormatException e) { return -1; }
    }

    private static final java.util.regex.Pattern LISTENERS_RE =
        java.util.regex.Pattern.compile("\"listeners\"\\s*:\\s*(\\d+)");

    private static void maybeWarn(String msg) {
        long now = System.currentTimeMillis();
        if (now - lastWarnMs > WARN_INTERVAL_MS) {
            lastWarnMs = now;
            System.out.println("[omo-voice] " + msg);
        }
    }
}

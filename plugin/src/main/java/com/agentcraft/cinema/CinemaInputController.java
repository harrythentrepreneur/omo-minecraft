package com.agentcraft.cinema;

import com.agentcraft.AgentCraftPlugin;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.map.MapView;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns in-game player gestures into live browser input on a cinema wall, and
 * keeps the wall responsive while doing it.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li><b>Aim → page point.</b> Ray-traces a player's look vector against the
 *       registered screens' item-frame tiles and projects the hit to a
 *       normalised [0,1] point (see {@link CinemaScreenGeometry}).</li>
 *   <li><b>Cursor + hover.</b> Each tick, the player aiming at a screen owns
 *       its reticle (drawn by {@link CinemaTileRenderer}) and a throttled
 *       {@code move} event keeps the page's hover state live.</li>
 *   <li><b>Gesture push.</b> {@code click}/{@code scroll}/{@code text}/{@code key}
 *       are POSTed to the runtime, which the face replays into Chrome via CDP.</li>
 *   <li><b>Frame push.</b> When a screen's frame or cursor changes, force-send
 *       its map tiles to nearby viewers via {@link Player#sendMap} — beating
 *       the vanilla map tracker's slow cadence so video looks like video.</li>
 * </ul>
 *
 * <p>All ray-tracing and {@code sendMap} happens on the main thread (callers
 * must ensure this); the gesture HTTP POSTs are fired async so the tick never
 * blocks on the network.
 */
public final class CinemaInputController {

    /** A projected aim: which cinema, and where on its surface (normalised). */
    public record Aim(String cinemaId, double nx, double ny) {}

    private static final double MAX_AIM_DIST = 64.0;
    private static final double MOVE_EPSILON = 0.003; // ~3 wall px before re-sending hover

    private final AgentCraftPlugin plugin;
    private final CinemaManager manager;
    private final String runtimeBase;
    private final HttpClient http;

    private final Map<String, CinemaScreenGeometry> screens = new HashMap<>();
    private final Map<UUID, CinemaScreenGeometry> frameIndex = new HashMap<>();

    // Type-mode players: their chat is captured into the focused page field.
    // Mapped to the cinema id their keystrokes are routed to. Concurrent —
    // read on the async chat thread, written on the main thread.
    private final Map<UUID, String> typing = new ConcurrentHashMap<>();

    // Last screen each player was aiming at, cached by the main-thread tick so
    // the async chat handler can ask "am I pointing at a screen?" without calling
    // aimAt (which must run on the main thread).
    private final Map<UUID, String> aimedByPlayer = new ConcurrentHashMap<>();

    // Tick state.
    private Set<String> lastAimed = new HashSet<>();
    private final Map<String, double[]> lastMoveSent = new HashMap<>();
    private final Map<String, long[]> lastPushTiles = new HashMap<>(); // id → per-tile version last sent

    public CinemaInputController(AgentCraftPlugin plugin, CinemaManager manager, String runtimeBase) {
        this.plugin = plugin;
        this.manager = manager;
        this.runtimeBase = runtimeBase;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /** Register (or replace) a built screen's geometry so it becomes clickable. */
    public void registerScreen(CinemaScreenGeometry geo) {
        if (geo == null) return;
        screens.put(geo.cinemaId(), geo);
        // Rebuild the frame index from scratch — a rebuild may have spawned
        // fresh frame entities with new UUIDs.
        frameIndex.clear();
        for (CinemaScreenGeometry g : screens.values()) {
            for (UUID id : g.tiles().keySet()) frameIndex.put(id, g);
        }
        lastPushTiles.remove(geo.cinemaId());
        plugin.getLogger().info("[cinema] screen '" + geo.cinemaId() + "' is now interactive ("
                + geo.tiles().size() + " tiles)");
    }

    public boolean hasScreens() { return !screens.isEmpty(); }

    /**
     * Ray-trace a player's aim onto a screen. Returns null if they aren't
     * looking at any registered cinema wall. Must run on the main thread.
     */
    public Aim aimAt(Player p) {
        if (frameIndex.isEmpty()) return null;
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection();
        RayTraceResult r = p.getWorld().rayTraceEntities(
                eye, dir, MAX_AIM_DIST, e -> frameIndex.containsKey(e.getUniqueId()));
        if (r == null) return null;
        Entity hit = r.getHitEntity();
        if (hit == null) return null;
        CinemaScreenGeometry geo = frameIndex.get(hit.getUniqueId());
        if (geo == null) return null;
        double[] nn = geo.toNormalized(hit.getUniqueId(), r.getHitPosition());
        if (nn == null) return null;
        return new Aim(geo.cinemaId(), nn[0], nn[1]);
    }

    // ── Per-tick work (main thread): cursor tracking + frame push ───────────

    public void tick(Collection<? extends Player> players) {
        if (screens.isEmpty()) return;

        Set<String> aimed = new HashSet<>();
        for (Player p : players) {
            Aim a = aimAt(p);
            if (a == null) { aimedByPlayer.remove(p.getUniqueId()); continue; }
            aimedByPlayer.put(p.getUniqueId(), a.cinemaId());
            aimed.add(a.cinemaId());
            CinemaFrameStore store = manager.getStore(a.cinemaId());
            // Only move the reticle (and tell the page) when the aim actually
            // shifts — otherwise holding still would churn the cursor version
            // and force needless frame pushes.
            double[] prev = lastMoveSent.get(a.cinemaId());
            boolean moved = prev == null
                    || Math.abs(prev[0] - a.nx()) >= MOVE_EPSILON
                    || Math.abs(prev[1] - a.ny()) >= MOVE_EPSILON;
            boolean inactive = store != null && !store.cursorActive();
            if (moved || inactive) {
                if (store != null) store.setCursor(a.nx(), a.ny());
                lastMoveSent.put(a.cinemaId(), new double[] { a.nx(), a.ny() });
                post(a.cinemaId(),
                        "{\"type\":\"move\",\"nx\":" + num(a.nx()) + ",\"ny\":" + num(a.ny()) + "}");
            }
        }
        // Clear the reticle on screens nobody is aiming at any more.
        for (String id : new ArrayList<>(lastAimed)) {
            if (!aimed.contains(id)) {
                CinemaFrameStore s = manager.getStore(id);
                if (s != null) s.clearCursor();
                lastMoveSent.remove(id);
            }
        }
        lastAimed = aimed;

        for (CinemaScreenGeometry geo : screens.values()) pushScreen(geo, players);
    }

    private void pushScreen(CinemaScreenGeometry geo, Collection<? extends Player> players) {
        CinemaFrameStore store = manager.getStore(geo.cinemaId());
        if (store == null) return;
        Location c = geo.center();
        if (c == null || c.getWorld() == null) return;

        // Gather in-range viewers once.
        double r2 = geo.viewRange() * geo.viewRange();
        List<Player> nearby = null;
        for (Player p : players) {
            if (!c.getWorld().equals(p.getWorld())) continue;
            if (p.getLocation().distanceSquared(c) > r2) continue;
            if (nearby == null) nearby = new ArrayList<>(2);
            nearby.add(p);
        }
        if (nearby == null) return; // nobody watching → don't touch the wall

        // Per-tile diff: only re-send tiles whose pixels (or cursor overlay)
        // changed since our last push. A static page or a small animated
        // region costs only the tiles that moved, not all 40.
        List<MapView> maps = geo.maps();
        int cols = geo.cols(), n = maps.size();
        long[] lastV = lastPushTiles.get(geo.cinemaId());
        if (lastV == null || lastV.length != n) {
            lastV = new long[n];
            java.util.Arrays.fill(lastV, -1L);
            lastPushTiles.put(geo.cinemaId(), lastV);
        }
        for (int k = 0; k < n; k++) {
            int row = k / cols, col = k % cols;
            int imageCol = cols - 1 - col, imageRow = row;
            long v = store.tileVersion(imageCol, imageRow);
            if (v == lastV[k]) continue;
            MapView mv = maps.get(k);
            for (Player p : nearby) p.sendMap(mv);
            lastV[k] = v;
        }
    }

    // ── Gestures (called from the listener) ─────────────────────────────────

    public void sendClick(String id, double nx, double ny, boolean rightButton) {
        post(id, "{\"type\":\"click\",\"nx\":" + num(nx) + ",\"ny\":" + num(ny)
                + ",\"button\":\"" + (rightButton ? "right" : "left") + "\"}");
    }

    public void sendScroll(String id, double nx, double ny, double dy) {
        post(id, "{\"type\":\"scroll\",\"nx\":" + num(nx) + ",\"ny\":" + num(ny)
                + ",\"dy\":" + num(dy) + "}");
    }

    public void sendText(String id, String text) {
        post(id, "{\"type\":\"text\",\"text\":" + jsonString(text) + "}");
    }

    public void sendKey(String id, String key) {
        post(id, "{\"type\":\"key\",\"key\":" + jsonString(key) + "}");
    }

    // ── Type mode ────────────────────────────────────────────────────────────

    /** Toggle keyboard capture for a player, routing to {@code cinemaId}.
     *  Returns the new state (true = now typing). */
    public boolean toggleTyping(UUID playerId, String cinemaId) {
        if (typing.containsKey(playerId)) { typing.remove(playerId); return false; }
        typing.put(playerId, cinemaId);
        return true;
    }

    public boolean isTyping(UUID playerId) { return typing.containsKey(playerId); }

    public String typingTarget(UUID playerId) { return typing.get(playerId); }

    public void stopTyping(UUID playerId) { typing.remove(playerId); }

    /** The screen the player was last aiming at (cached by {@link #tick}), or null. */
    public String aimedCinema(UUID playerId) { return aimedByPlayer.get(playerId); }

    /** Forget a player's cached aim (e.g. on quit). */
    public void clearAim(UUID playerId) { aimedByPlayer.remove(playerId); }

    // ── HTTP + JSON helpers ───────────────────────────────────────────────────

    private void post(String id, String body) {
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create(runtimeBase + "/api/cinema/" + id + "/input"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(3))
                .build();
        // Fire-and-forget: a lost gesture is no worse than a dropped frame, and
        // we must never block the main thread on the bridge.
        http.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                .exceptionally(t -> null);
    }

    /** Locale-independent number formatting (always a '.' decimal). */
    private static String num(double v) {
        if (!Double.isFinite(v)) return "0";
        return Double.toString(v);
    }

    private static String jsonString(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch));
                    else b.append(ch);
            }
        }
        b.append('"');
        return b.toString();
    }
}

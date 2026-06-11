package com.agentcraft.cinema;

import com.agentcraft.AgentCraftPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single owner of every {@link CinemaFrameStore} in the world. Lazily
 * creates a store per cinema id and exposes a helper to push URL changes
 * to the runtime, which face/ picks up via its registry poll and points
 * the headless Chrome at.
 */
public final class CinemaManager {

    public static final String DEFAULT_ID = "main";

    /** Scoreboard tag on the floating "URL header" armor stands above each screen. */
    private static final String LABEL_TAG = "act-cinema-label";

    private final AgentCraftPlugin plugin;
    private final String runtimeBase;
    private final Map<String, CinemaFrameStore> stores = new HashMap<>();
    private final HttpClient http;
    private final CinemaInputController input;

    /** Current URL per cinema id — read async (chat/hint), written in {@link #setUrl}. */
    private final Map<String, String> urls = new ConcurrentHashMap<>();
    /** The floating header label above each screen (main-thread only). */
    private final Map<String, ArmorStand> labels = new HashMap<>();

    public CinemaManager(AgentCraftPlugin plugin) {
        this.plugin = plugin;
        this.runtimeBase = plugin.getConfig().getString("runtime.http", "http://127.0.0.1:8766");
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.input = new CinemaInputController(plugin, this, runtimeBase);
    }

    /** Get-or-create the store and start its poll loop. Idempotent. */
    public CinemaFrameStore ensure(String id) {
        return stores.computeIfAbsent(id, key -> {
            CinemaFrameStore s = new CinemaFrameStore(plugin, key, runtimeBase);
            s.start();
            return s;
        });
    }

    /** The store for an id if it already exists, else null (no creation). */
    public CinemaFrameStore getStore(String id) {
        return stores.get(id);
    }

    /** The interactive-input controller (clicks, scroll, cursor, frame push). */
    public CinemaInputController input() { return input; }

    /** Register a freshly-built screen so its wall becomes clickable + labelled. */
    public void registerScreen(CinemaScreenGeometry geometry) {
        input.registerScreen(geometry);
        placeLabel(geometry);
    }

    /** The URL a cinema is currently pointed at (null if never set). */
    public String currentUrl(String id) { return urls.get(id); }

    public void shutdown() {
        for (CinemaFrameStore s : stores.values()) s.stop();
        stores.clear();
        for (ArmorStand st : labels.values()) if (st != null && !st.isDead()) st.remove();
        labels.clear();
    }

    // ── Floating "URL header" label above each screen ─────────────────────────

    /**
     * Spawn (or replace) the floating header label centred just above a screen,
     * showing its cinema id + current URL so you can see what each wall is
     * pointed at from across the room. Billboarded armor-stand name, so it reads
     * from any angle (e.g. from the dome throne). Main thread only.
     */
    private void placeLabel(CinemaScreenGeometry geo) {
        Location c = geo.center();
        if (c == null || c.getWorld() == null) return;
        World w = c.getWorld();
        double topY = c.getY() + (geo.rows() - 1) / 2.0; // screen's top row
        Location at = new Location(w, c.getX(), topY + 0.9, c.getZ());
        String id = geo.cinemaId();
        // Drop the previous label for this id + sweep stale tagged orphans (a
        // rebuild keeps the old armor stands since wipeWorld only removes villagers).
        ArmorStand old = labels.remove(id);
        if (old != null && !old.isDead()) old.remove();
        for (Entity e : w.getNearbyEntities(at, 2.5, 2.5, 2.5)) {
            if (e.getType() == EntityType.ARMOR_STAND && e.getScoreboardTags().contains(LABEL_TAG)) e.remove();
        }
        ArmorStand st = w.spawn(at, ArmorStand.class, as -> {
            as.setInvisible(true);
            as.setMarker(true);
            as.setGravity(false);
            as.setSmall(true);
            as.setBasePlate(false);
            as.setInvulnerable(true);
            as.setCustomNameVisible(true);
            as.setPersistent(true);
            as.addScoreboardTag(LABEL_TAG);
        });
        st.customName(labelText(id, urls.get(id)));
        labels.put(id, st);
    }

    /** Refresh an existing label's text after its URL changed. Main thread only. */
    private void updateLabel(String id) {
        ArmorStand st = labels.get(id);
        if (st != null && !st.isDead()) st.customName(labelText(id, urls.get(id)));
    }

    /** "» dev-1 · localhost:3000" — id + the URL with the scheme stripped for width. */
    private static Component labelText(String id, String url) {
        String shown = url == null ? "(no channel — look here & type a URL)"
                : url.replaceFirst("^https?://", "");
        return Component.text()
                .append(Component.text("» ", NamedTextColor.DARK_GRAY))
                .append(Component.text(id + " ", NamedTextColor.AQUA))
                .append(Component.text("· " + shown, NamedTextColor.WHITE))
                .build();
    }

    /**
     * Tell the runtime (and therefore face/) to point the given cinema's
     * headless capture at the new URL. Returns true on HTTP 200.
     * Runs the HTTP call on the calling thread — callers should invoke
     * asynchronously when convenient.
     */
    public boolean setUrl(String id, String url) {
        // Remember the channel + refresh the floating header label (on the main
        // thread — setUrl is usually called async). Do this regardless of the
        // HTTP result so the label tracks intent even if the face is down.
        urls.put(id, url);
        plugin.getServer().getScheduler().runTask(plugin, () -> updateLabel(id));
        try {
            String body = "{\"url\":" + jsonString(url) + "}";
            HttpRequest req = HttpRequest.newBuilder(URI.create(runtimeBase + "/api/cinema/" + id + "/url"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(3))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return res.statusCode() == 200;
        } catch (Throwable t) {
            plugin.getLogger().warning("[cinema] failed to push url for " + id + ": " + t.getMessage());
            return false;
        }
    }

    private static String jsonString(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append('"');
        return b.toString();
    }
}

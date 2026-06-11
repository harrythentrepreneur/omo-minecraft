package com.agentcraft.build;

import com.agentcraft.AgentCraftPlugin;
import com.agentcraft.agents.AgentNpc;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * One live-build plot: a fixed W×H×D box anchored at a world origin that an
 * agent's {@code build} tool fills incrementally over the WebSocket.
 *
 * <p>The runtime's Claude brain emits a {@code build_ops} frame carrying a list
 * of build-DSL ops (set / box / cuboid_frame / cylinder / sphere / pyramid /
 * line / clear) in LOCAL plot coordinates (x:0..w-1, y:0..h-1, z:0..d-1; y=0 is
 * the floor). {@link #enqueueOps} expands each op into individual
 * {@link Placement}s — material-validated against the whitelist, bounds-clamped
 * and dropped if outside the plot, then translated local→world — and queues
 * them. A 1-tick {@link #drain} timer lays down ~{@code build.blocks_per_tick}
 * blocks per tick on the main thread so a large build streams in rather than
 * freezing the server.
 *
 * <p>{@link #clearNow} is the one synchronous operation: a plot reset (~w*h*d
 * setType calls) that wipes the volume back to AIR over a fresh floor layer,
 * mirroring the plaza-builder's bulk-place style. It is called before enqueuing
 * when the frame sets {@code clearFirst}.
 *
 * <p>All world mutation here runs on the main thread: {@code enqueueOps} is
 * invoked from {@code IncomingHandler.onMessage} (already main-thread, see
 * {@code BridgeClient.onText}), and the drain timer is a main-thread
 * {@code runTaskTimer}.
 */
public final class BuildPlot {

    /** One resolved block placement in absolute world coordinates. */
    private record Placement(int x, int y, int z, Material mat) {}

    /** Once the queue has been dry this many ticks, the mason returns to its post.
     *  Long enough to bridge the gap between a turn's multiple {@code build} calls
     *  so the architect doesn't yo-yo home and back between them. */
    private static final int FINISH_GRACE_TICKS = 50; // ~2.5s

    private final World world;
    private final int ox, oy, oz;
    private final int w, h, d;
    private final Material floorMaterial;
    private final AgentCraftPlugin plugin;
    /** The agent whose villager body animates this plot's construction. */
    private final String agentId;

    private final Deque<Placement> queue = new ArrayDeque<>();
    /** Per-message scratch: ops expand into here, get sorted into a natural
     *  build order (bottom-up, circling), then flush to {@link #queue}. */
    private final List<Placement> pending = new ArrayList<>();
    private BukkitTask drainTask;
    /** True while blocks are streaming in and the mason is in live-build mode. */
    private boolean building = false;
    /** Consecutive ticks the queue has been empty (drives the finish grace). */
    private int idleTicks = 0;

    public BuildPlot(World world, int ox, int oy, int oz, int w, int h, int d,
                     Material floorMaterial, AgentCraftPlugin plugin, String agentId) {
        this.world = world;
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.w = w;
        this.h = h;
        this.d = d;
        this.floorMaterial = floorMaterial;
        this.plugin = plugin;
        this.agentId = agentId;
    }

    // ── Material whitelist (the security boundary) ──────────────────────────

    /**
     * Denied material substrings: any block whose enum name contains one of
     * these tokens is rejected. Catches all bed colours, all shulker boxes,
     * all structure/jigsaw blocks, both portals, etc. without enumerating them.
     */
    private static final String[] DENY_TOKENS = {
        "BEDROCK", "LAVA", "WATER", "TNT", "COMMAND_BLOCK", "SPAWNER", "BARRIER",
        "STRUCTURE_", "JIGSAW", "END_PORTAL", "NETHER_PORTAL", "FIRE", "_BED",
        "PISTON", "SHULKER_BOX", "DRAGON_EGG"
    };

    /**
     * Resolve a Minecraft material name to a safe, placeable block, or null if
     * unknown / non-block / denylisted. A null result skips just the op that
     * referenced it — it never aborts the whole build.
     */
    private Material resolveMaterial(String name) {
        if (name == null) return null;
        Material mat = Material.matchMaterial(name);
        if (mat == null || !mat.isBlock()) {
            plugin.getLogger().fine("build: skipping unknown/non-block material '" + name + "'");
            return null;
        }
        String upper = mat.name().toUpperCase(Locale.ROOT);
        for (String token : DENY_TOKENS) {
            if (upper.contains(token)) {
                plugin.getLogger().fine("build: skipping denylisted material '" + mat.name() + "'");
                return null;
            }
        }
        return mat;
    }

    // ── Op expansion ────────────────────────────────────────────────────────

    /**
     * Expand every op in {@code ops} into queued placements, then start the
     * drain timer. Unknown ops and unknown/denied materials are skipped
     * (logged at FINE); they never abort the rest of the build.
     */
    public void enqueueOps(JsonArray ops, AgentCraftPlugin plugin) {
        if (ops != null) {
            for (JsonElement el : ops) {
                if (!el.isJsonObject()) continue;
                try {
                    expandOp(el.getAsJsonObject());
                } catch (Exception e) {
                    plugin.getLogger().fine("build: skipping malformed op: " + e.getMessage());
                }
            }
        }
        // Lay blocks in a natural construction order: bottom-up (so the structure
        // rises floor-by-floor), and within a layer sweeping by angle around the
        // plot centre (so the build front — and the mason chasing it — circles the
        // structure rather than jumping around).
        if (!pending.isEmpty()) {
            final double ccx = ox + w / 2.0, ccz = oz + d / 2.0;
            pending.sort((a, b) -> {
                if (a.y() != b.y()) return Integer.compare(a.y(), b.y());
                double aa = Math.atan2(a.z() - ccz, a.x() - ccx);
                double ab = Math.atan2(b.z() - ccz, b.x() - ccx);
                return Double.compare(aa, ab);
            });
            queue.addAll(pending);
            pending.clear();
        }
        ensureDrain(plugin);
    }

    private void expandOp(JsonObject o) {
        String op = str(o, "op", "");
        switch (op) {
            case "set" -> {
                Material m = resolveMaterial(str(o, "material", null));
                if (m == null) return;
                place(i(o, "x"), i(o, "y"), i(o, "z"), m);
            }
            case "box" -> expandBox(o);
            case "cuboid_frame" -> expandFrame(o);
            case "cylinder" -> expandCylinder(o);
            case "sphere" -> expandSphere(o);
            case "pyramid" -> expandPyramid(o);
            case "line" -> expandLine(o);
            case "clear" -> clearNow();
            default -> plugin.getLogger().fine("build: skipping unknown op '" + op + "'");
        }
    }

    private void expandBox(JsonObject o) {
        Material m = resolveMaterial(str(o, "material", null));
        if (m == null) return;
        int x1 = i(o, "x1"), y1 = i(o, "y1"), z1 = i(o, "z1");
        int x2 = i(o, "x2"), y2 = i(o, "y2"), z2 = i(o, "z2");
        int lox = Math.min(x1, x2), hix = Math.max(x1, x2);
        int loy = Math.min(y1, y2), hiy = Math.max(y1, y2);
        int loz = Math.min(z1, z2), hiz = Math.max(z1, z2);
        boolean hollow = bool(o, "hollow", false);
        for (int x = lox; x <= hix; x++) {
            for (int y = loy; y <= hiy; y++) {
                for (int z = loz; z <= hiz; z++) {
                    if (hollow) {
                        boolean shell = x == lox || x == hix || y == loy || y == hiy || z == loz || z == hiz;
                        if (!shell) continue;
                    }
                    place(x, y, z, m);
                }
            }
        }
    }

    private void expandFrame(JsonObject o) {
        Material m = resolveMaterial(str(o, "material", null));
        if (m == null) return;
        int x1 = i(o, "x1"), y1 = i(o, "y1"), z1 = i(o, "z1");
        int x2 = i(o, "x2"), y2 = i(o, "y2"), z2 = i(o, "z2");
        int lox = Math.min(x1, x2), hix = Math.max(x1, x2);
        int loy = Math.min(y1, y2), hiy = Math.max(y1, y2);
        int loz = Math.min(z1, z2), hiz = Math.max(z1, z2);
        for (int x = lox; x <= hix; x++) {
            for (int y = loy; y <= hiy; y++) {
                for (int z = loz; z <= hiz; z++) {
                    int extremes = 0;
                    if (x == lox || x == hix) extremes++;
                    if (y == loy || y == hiy) extremes++;
                    if (z == loz || z == hiz) extremes++;
                    if (extremes >= 2) place(x, y, z, m); // only the 12 edges
                }
            }
        }
    }

    private void expandCylinder(JsonObject o) {
        Material m = resolveMaterial(str(o, "material", null));
        if (m == null) return;
        int cx = i(o, "cx"), baseY = i(o, "y"), cz = i(o, "cz");
        int radius = i(o, "radius"), height = i(o, "height");
        boolean hollow = bool(o, "hollow", false);
        int r2 = radius * radius;
        int inner2 = (radius - 1) * (radius - 1);
        for (int lx = cx - radius; lx <= cx + radius; lx++) {
            for (int lz = cz - radius; lz <= cz + radius; lz++) {
                int dx = lx - cx, dz = lz - cz;
                int dist2 = dx * dx + dz * dz;
                if (dist2 > r2) continue;
                if (hollow && dist2 < inner2) continue;
                for (int dy = 0; dy < height; dy++) {
                    place(lx, baseY + dy, lz, m);
                }
            }
        }
    }

    private void expandSphere(JsonObject o) {
        Material m = resolveMaterial(str(o, "material", null));
        if (m == null) return;
        int cx = i(o, "cx"), cy = i(o, "cy"), cz = i(o, "cz");
        int radius = i(o, "radius");
        boolean hollow = bool(o, "hollow", false);
        boolean dome = bool(o, "dome", false);
        int r2 = radius * radius;
        int inner2 = (radius - 1) * (radius - 1);
        for (int lx = cx - radius; lx <= cx + radius; lx++) {
            for (int ly = cy - radius; ly <= cy + radius; ly++) {
                if (dome && ly < cy) continue; // upper hemisphere only
                for (int lz = cz - radius; lz <= cz + radius; lz++) {
                    int dx = lx - cx, dy = ly - cy, dz = lz - cz;
                    int dist2 = dx * dx + dy * dy + dz * dz;
                    if (dist2 > r2) continue;
                    if (hollow && dist2 <= inner2) continue;
                    place(lx, ly, lz, m);
                }
            }
        }
    }

    private void expandPyramid(JsonObject o) {
        Material m = resolveMaterial(str(o, "material", null));
        if (m == null) return;
        int cx = i(o, "cx"), baseY = i(o, "baseY"), cz = i(o, "cz");
        int baseRadius = i(o, "baseRadius");
        boolean solid = bool(o, "solid", false);
        for (int layer = 0; layer <= baseRadius; layer++) {
            int half = baseRadius - layer;
            int y = baseY + layer;
            for (int dx = -half; dx <= half; dx++) {
                for (int dz = -half; dz <= half; dz++) {
                    boolean ring = Math.abs(dx) == half || Math.abs(dz) == half;
                    if (solid || ring) place(cx + dx, y, cz + dz, m);
                }
            }
        }
    }

    private void expandLine(JsonObject o) {
        Material m = resolveMaterial(str(o, "material", null));
        if (m == null) return;
        int x1 = i(o, "x1"), y1 = i(o, "y1"), z1 = i(o, "z1");
        int x2 = i(o, "x2"), y2 = i(o, "y2"), z2 = i(o, "z2");
        // 3D Bresenham.
        int dx = Math.abs(x2 - x1), dy = Math.abs(y2 - y1), dz = Math.abs(z2 - z1);
        int sx = x1 < x2 ? 1 : -1;
        int sy = y1 < y2 ? 1 : -1;
        int sz = z1 < z2 ? 1 : -1;
        int x = x1, y = y1, z = z1;
        if (dx >= dy && dx >= dz) {
            int p1 = 2 * dy - dx, p2 = 2 * dz - dx;
            while (true) {
                place(x, y, z, m);
                if (x == x2) break;
                if (p1 >= 0) { y += sy; p1 -= 2 * dx; }
                if (p2 >= 0) { z += sz; p2 -= 2 * dx; }
                p1 += 2 * dy; p2 += 2 * dz; x += sx;
            }
        } else if (dy >= dx && dy >= dz) {
            int p1 = 2 * dx - dy, p2 = 2 * dz - dy;
            while (true) {
                place(x, y, z, m);
                if (y == y2) break;
                if (p1 >= 0) { x += sx; p1 -= 2 * dy; }
                if (p2 >= 0) { z += sz; p2 -= 2 * dy; }
                p1 += 2 * dx; p2 += 2 * dz; y += sy;
            }
        } else {
            int p1 = 2 * dy - dz, p2 = 2 * dx - dz;
            while (true) {
                place(x, y, z, m);
                if (z == z2) break;
                if (p1 >= 0) { y += sy; p1 -= 2 * dz; }
                if (p2 >= 0) { x += sx; p2 -= 2 * dz; }
                p1 += 2 * dy; p2 += 2 * dx; z += sz;
            }
        }
    }

    /**
     * Validate a LOCAL coordinate against the plot bounds; if inside, translate
     * local→world and queue the placement. Out-of-bounds coords are dropped.
     */
    private void place(int lx, int ly, int lz, Material mat) {
        if (lx < 0 || lx >= w || ly < 0 || ly >= h || lz < 0 || lz >= d) return;
        pending.add(new Placement(ox + lx, oy + ly, oz + lz, mat));
    }

    // ── Synchronous clear ───────────────────────────────────────────────────

    /**
     * Reset the plot to a flat clearing: AIR for every layer above the floor,
     * {@code floorMaterial} at local y=0. Synchronous — like the plaza builder,
     * a few thousand setType calls is acceptable for a one-shot reset. Call
     * this BEFORE {@link #enqueueOps} when {@code clearFirst} is set.
     */
    public void clearNow() {
        for (int lx = 0; lx < w; lx++) {
            for (int lz = 0; lz < d; lz++) {
                world.getBlockAt(ox + lx, oy, oz + lz).setType(floorMaterial, false);
                for (int ly = 1; ly < h; ly++) {
                    world.getBlockAt(ox + lx, oy + ly, oz + lz).setType(Material.AIR, false);
                }
            }
        }
    }

    // ── Incremental drain ─────────────────────────────────────────────────────

    /** Start the per-tick drain timer if it isn't already running. */
    public void ensureDrain(AgentCraftPlugin plugin) {
        if (drainTask != null && !drainTask.isCancelled()) return;
        drainTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::drain, 1L, 1L);
    }

    /**
     * Lay down up to {@code build.blocks_per_tick} queued placements this tick.
     * Cancels the timer once the queue empties. Runs on the main thread.
     */
    private void drain() {
        int perTick = Math.max(1, plugin.getConfig().getInt("build.blocks_per_tick", 30));
        boolean sound = plugin.getConfig().getBoolean("build.place_sound", true);
        int placed = 0;
        int lastX = 0, lastY = 0, lastZ = 0; // leading edge — the newest block this tick
        Material lastMat = null;             // the block the mason "holds" while placing
        while (placed < perTick) {
            Placement p = queue.pollFirst();
            if (p == null) break;
            world.getBlockAt(p.x(), p.y(), p.z()).setType(p.mat(), false);
            lastX = p.x(); lastY = p.y(); lastZ = p.z(); lastMat = p.mat();
            if (sound && (placed % 12 == 0)) {
                world.playSound(new Location(world, p.x() + 0.5, p.y() + 0.5, p.z() + 0.5),
                        Sound.BLOCK_STONE_PLACE, 0.4f, 1.0f);
            }
            placed++;
        }

        AgentNpc mason = plugin.agents().get(agentId);
        if (placed > 0) {
            idleTicks = 0;
            if (!building) {
                building = true;
                if (mason != null) mason.beginBuild(new Location(world, ox + w / 2.0, oy, oz + d / 2.0));
            }
            if (mason != null) {
                // Chase the LEADING edge (the newest block), not the smoothed
                // centroid, so the mason actually travels along the build path
                // and darts to each new spot rather than hovering near the middle.
                Location focus = new Location(world, lastX + 0.5, lastY, lastZ + 0.5);
                mason.buildStep(focus, lastMat);
            }
        } else if (building) {
            // Queue ran dry. Keep the (cheap) timer alive through the grace window
            // so a follow-up build_ops call resumes without sending the mason home.
            if (++idleTicks >= FINISH_GRACE_TICKS) {
                building = false;
                if (mason != null) mason.endBuild();
                if (drainTask != null) { drainTask.cancel(); drainTask = null; }
            }
        } else if (drainTask != null) {
            drainTask.cancel(); drainTask = null;
        }
    }

    /** Cancel the drain timer (called when the plot is removed / on shutdown). */
    public void cancel() {
        if (building) {
            building = false;
            AgentNpc mason = plugin.agents().get(agentId);
            if (mason != null) mason.endBuild();
        }
        if (drainTask != null) { drainTask.cancel(); drainTask = null; }
    }

    // ── JSON helpers ──────────────────────────────────────────────────────────

    private static int i(JsonObject o, String key) {
        return (o.has(key) && !o.get(key).isJsonNull()) ? o.get(key).getAsInt() : 0;
    }

    private static String str(JsonObject o, String key, String fallback) {
        return (o.has(key) && !o.get(key).isJsonNull()) ? o.get(key).getAsString() : fallback;
    }

    private static boolean bool(JsonObject o, String key, boolean fallback) {
        return (o.has(key) && !o.get(key).isJsonNull()) ? o.get(key).getAsBoolean() : fallback;
    }
}

package com.agentcraft.listeners;

import com.agentcraft.AgentCraftPlugin;
import com.agentcraft.rooms.Room;
import com.agentcraft.rooms.RoomManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Switch;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Listening Room controls. Right-click the RECORD <b>lever</b> to arm/disarm
 * the mic (the runtime starts/stops whisper capture); right-click the DISTILL
 * <b>button</b> to turn everything heard into tasks + paste-ready prompts — the
 * runtime copies the best prompt to the host clipboard and we open the result as
 * an in-game book. Both controls talk to the runtime over the same localhost
 * HTTP bridge the cinema uses ({@code runtime.http}).
 */
public final class ListeningRoomListener implements Listener {

    private static final String ROOM = "listening";
    private static final long DISTILL_COOLDOWN_MS = 4000;

    private final AgentCraftPlugin plugin;
    private final RoomManager rooms;
    private final HttpClient http;
    private final String base;
    private final Map<UUID, Long> lastDistill = new ConcurrentHashMap<>();

    public ListeningRoomListener(AgentCraftPlugin plugin, RoomManager rooms) {
        this.plugin = plugin;
        this.rooms = rooms;
        this.base = plugin.getConfig().getString("runtime.http", "http://127.0.0.1:8766");
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent ev) {
        if (ev.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (ev.getHand() != EquipmentSlot.HAND) return; // ignore the off-hand echo
        Block b = ev.getClickedBlock();
        if (b == null) return;
        Material m = b.getType();
        if (m != Material.LEVER && m != Material.STONE_BUTTON) return;
        // Test the listening room directly (not roomAt): the standup cinema's
        // generous radius overlaps this corner, and roomAt returns the first
        // match — so a name check on roomAt could miss. contains() on our own
        // room is exact and overlap-proof.
        Room lr = rooms.get(ROOM);
        if (lr == null || !lr.contains(b.getLocation())) return;

        Player p = ev.getPlayer();
        if (m == Material.LEVER) {
            // We drive the lever's visual + the recording state from the runtime's
            // authoritative response, so cancel vanilla's own toggle.
            ev.setCancelled(true);
            toggleRecording(p, b);
        } else {
            long now = System.currentTimeMillis();
            Long last = lastDistill.get(p.getUniqueId());
            if (last != null && now - last < DISTILL_COOLDOWN_MS) return;
            lastDistill.put(p.getUniqueId(), now);
            p.sendActionBar(Component.text("✦ distilling what you said into prompts…", NamedTextColor.AQUA));
            distill(p);
        }
    }

    // ── RECORD lever ──────────────────────────────────────────────────────

    private void toggleRecording(Player p, Block lever) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            JsonObject res = postJson("/api/listening/arm", "{}");
            boolean armed = res != null && res.has("armed") && res.get("armed").getAsBoolean();
            boolean recording = res != null && res.has("recording") && res.get("recording").getAsBoolean();
            String error = res != null && res.has("error") && !res.get("error").isJsonNull()
                    ? res.get("error").getAsString() : null;
            Bukkit.getScheduler().runTask(plugin, () -> {
                // Reflect the authoritative state on the physical lever.
                if (lever.getBlockData() instanceof Switch sw) {
                    sw.setPowered(armed);
                    lever.setBlockData(sw, false);
                }
                if (res == null) {
                    p.sendActionBar(Component.text("⚠ runtime offline — is ./agentcraft running?", NamedTextColor.RED));
                } else if (armed && error != null) {
                    p.sendActionBar(Component.text("⚠ " + error, NamedTextColor.GOLD));
                } else if (armed) {
                    p.sendActionBar(Component.text("● recording — speak; watch the wall" + (recording ? "" : " (warming up…)"),
                            NamedTextColor.GREEN));
                } else {
                    p.sendActionBar(Component.text("⏸ recording paused — press ✦ DISTILL to make prompts", NamedTextColor.YELLOW));
                }
            });
        });
    }

    // ── DISTILL button ─────────────────────────────────────────────────────

    private void distill(Player p) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            JsonObject res = postJson("/api/listening/distill", "{}");
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (res == null) {
                    p.sendActionBar(Component.text("⚠ runtime offline — couldn't distill", NamedTextColor.RED));
                    return;
                }
                int n = res.has("items") && res.get("items").isJsonArray() ? res.getAsJsonArray("items").size() : 0;
                boolean copied = res.has("copied") && res.get("copied").getAsBoolean();
                if (n > 0) {
                    p.openBook(buildBook(res));
                    p.sendMessage(Component.text("✦ Distilled " + n + " work item" + (n == 1 ? "" : "s")
                            + (copied
                                ? " — full plan copied to your clipboard. Click a card on the wall to copy just one prompt, or read the book."
                                : " — read the book, then click a card on the wall to copy a prompt."),
                            NamedTextColor.GREEN));
                } else {
                    // No actionable request — don't pop an empty book; the wall
                    // shows what was heard. Tell them how to get a real result.
                    p.sendMessage(Component.text("✦ I didn't find an actionable request in what I heard. "
                            + "The wall shows what I caught — flip RECORD, describe what you want built, then press DISTILL again.",
                            NamedTextColor.YELLOW));
                }
            });
        });
    }

    private ItemStack buildBook(JsonObject res) {
        String title = str(res, "title", "Distilled plan");
        String summary = str(res, "summary", "");
        List<JsonObject> items = objList(res.has("items") ? res.getAsJsonArray("items") : null);
        boolean copied = res.has("copied") && res.get("copied").getAsBoolean();

        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.title(Component.text(title.isEmpty() ? "Distilled plan" : title));
        meta.author(Component.text("The Listening Room"));
        List<Component> pages = new ArrayList<>();

        // Cover: title + summary + an index of the work items.
        var cover = Component.text()
                .append(Component.text((title.isEmpty() ? "DISTILLED PLAN" : title) + "\n", NamedTextColor.DARK_AQUA, TextDecoration.BOLD))
                .append(Component.text((summary.isEmpty() ? "Nothing actionable was found." : summary) + "\n\n", NamedTextColor.DARK_GRAY));
        if (items.isEmpty()) {
            cover.append(Component.text("Flip the RECORD lever, talk, then press DISTILL.", NamedTextColor.GRAY, TextDecoration.ITALIC));
        } else {
            cover.append(Component.text(items.size() + (items.size() == 1 ? " work item\n\n" : " work items\n\n"), NamedTextColor.GRAY));
            int i = 1;
            for (JsonObject it : items) {
                cover.append(Component.text(i + ". " + str(it, "title", "Item " + i) + "\n", NamedTextColor.BLACK));
                i++;
            }
        }
        pages.add(cover.build());

        // One section per work item: a header page (title + category/priority +
        // checklist), then the agent-ready prompt chunked across pages.
        int n = 1;
        for (JsonObject it : items) {
            String itTitle = str(it, "title", "Item " + n);
            String category = str(it, "category", "");
            String priority = str(it, "priority", "");
            List<String> tasks = strList(it.has("tasks") ? it.getAsJsonArray("tasks") : null);
            String prompt = str(it, "prompt", "");

            var head = Component.text()
                    .append(Component.text(n + ". " + itTitle + "\n", NamedTextColor.DARK_AQUA, TextDecoration.BOLD))
                    .append(Component.text(metaLine(category, priority) + "\n\n", NamedTextColor.DARK_GRAY));
            if (!tasks.isEmpty()) {
                head.append(Component.text("Checklist\n", NamedTextColor.GRAY, TextDecoration.BOLD));
                for (String t : tasks) head.append(Component.text("▢ " + t + "\n", NamedTextColor.BLACK));
                head.append(Component.text("\n", NamedTextColor.GRAY));
            }
            head.append(Component.text("→ prompt follows", NamedTextColor.GRAY, TextDecoration.ITALIC));
            pages.add(head.build());

            for (String chunk : chunk(prompt, 460)) pages.add(Component.text(chunk, NamedTextColor.BLACK));
            n++;
        }

        // Closing page: the handoff.
        if (!items.isEmpty()) {
            pages.add(Component.text()
                    .append(Component.text(copied ? "✔ COPIED\n\n" : "PLAN READY\n\n", NamedTextColor.DARK_GREEN, TextDecoration.BOLD))
                    .append(Component.text(copied
                            ? "The full plan is on your clipboard. To grab just ONE prompt, click its card on the wall — then ⌘V into that code workstation's terminal."
                            : "Click a card on the wall to copy its prompt, then ⌘V into a code workstation terminal.", NamedTextColor.DARK_GRAY))
                    .build());
        }

        while (pages.size() > 100) pages.remove(pages.size() - 1); // book hard cap
        meta.pages(pages);
        book.setItemMeta(meta);
        return book;
    }

    /** "feature · high priority" — omits blanks gracefully. */
    private static String metaLine(String category, String priority) {
        StringBuilder b = new StringBuilder();
        if (!category.isEmpty()) b.append(category);
        if (!priority.isEmpty()) { if (b.length() > 0) b.append(" · "); b.append(priority).append(" priority"); }
        return b.toString();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** Split text into <=max-char pages at whitespace boundaries (book page budget). */
    private static List<String> chunk(String s, int max) {
        List<String> out = new ArrayList<>();
        s = s == null ? "" : s.trim();
        if (s.isEmpty()) { out.add(""); return out; }
        while (s.length() > max) {
            int cut = s.lastIndexOf(' ', max);
            int nl = s.lastIndexOf('\n', max);
            cut = Math.max(cut, nl);
            if (cut <= 0) cut = max;
            out.add(s.substring(0, cut).trim());
            s = s.substring(cut).trim();
        }
        if (!s.isEmpty()) out.add(s);
        return out;
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }

    private static List<String> strList(JsonArray arr) {
        List<String> out = new ArrayList<>();
        if (arr != null) for (JsonElement e : arr) if (e.isJsonPrimitive()) out.add(e.getAsString());
        return out;
    }

    private static List<JsonObject> objList(JsonArray arr) {
        List<JsonObject> out = new ArrayList<>();
        if (arr != null) for (JsonElement e : arr) if (e.isJsonObject()) out.add(e.getAsJsonObject());
        return out;
    }

    /** POST a JSON body and parse the response object, or null on any failure. */
    private JsonObject postJson(String path, String body) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(60)) // distill calls Claude — give it room
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() != 200) return null;
            return JsonParser.parseString(res.body()).getAsJsonObject();
        } catch (Throwable t) {
            plugin.getLogger().warning("[listening] " + path + " failed: " + t.getMessage());
            return null;
        }
    }
}

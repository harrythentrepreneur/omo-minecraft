package com.agentcraft.listeners;

import com.agentcraft.AgentCraftPlugin;
import com.agentcraft.cinema.CinemaInputController;
import com.agentcraft.cinema.CinemaInputController.Aim;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Makes the cinema wall a usable browser. The control grammar is deliberately
 * tiny so it works from anywhere in the hall (look + gesture, no reach limit):
 *
 * <ul>
 *   <li><b>Left-click (attack swing)</b> while looking at the screen → click
 *       the page at that point. Works from your seat across the room.</li>
 *   <li><b>Scroll wheel</b> while looking at the screen → scroll the page.</li>
 *   <li><b>Swap-hands (F)</b> while looking at the screen → toggle "type mode";
 *       in type mode your chat lines are typed into the focused field + Enter,
 *       instead of broadcasting. F again to exit.</li>
 * </ul>
 *
 * Aim is resolved by {@link CinemaInputController#aimAt} (a ray-trace against
 * the screen's map tiles); all of these handlers run on the main thread except
 * chat, which is async and only forwards strings (safe off-thread).
 */
public final class CinemaInteractListener implements Listener {

    private static final long CLICK_DEBOUNCE_MS = 150L;
    private static final long OPEN_DEBOUNCE_MS = 400L; // ignore the off-hand / air+block double-fire
    private static final double SCROLL_STEP = 120.0; // px per wheel notch

    /** Hidden sentinel the client-mod swallows to open the fullscreen cinema view. */
    private static final String CINEMA_SENTINEL = "§§ACT-CINEMA§§ ";

    private final AgentCraftPlugin plugin;
    private final CinemaInputController input;
    private final Map<UUID, Long> lastClick = new HashMap<>();
    private final Map<UUID, Long> lastOpen = new HashMap<>();

    public CinemaInteractListener(AgentCraftPlugin plugin, CinemaInputController input) {
        this.plugin = plugin;
        this.input = input;
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwing(PlayerAnimationEvent ev) {
        if (ev.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        if (!input.hasScreens()) return;
        Player p = ev.getPlayer();
        Aim aim = input.aimAt(p);
        if (aim == null) return;

        long now = System.currentTimeMillis();
        Long prev = lastClick.get(p.getUniqueId());
        if (prev != null && now - prev < CLICK_DEBOUNCE_MS) return;
        lastClick.put(p.getUniqueId(), now);

        input.sendClick(aim.cinemaId(), aim.nx(), aim.ny(), false);
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.3f, 1.7f);
    }

    /**
     * Right-click while looking at a screen → open the fullscreen "real computer"
     * view of that cinema on the client. The plugin can't open a client Screen
     * directly (no plugin→client channel), so it emits the hidden
     * {@code §§ACT-CINEMA§§ <id>} sentinel that {@code TerminalMod} swallows and
     * turns into a {@code CinemaScreen}. Left-click stays the in-page click; this
     * is the complementary gesture.
     */
    @EventHandler
    public void onRightClick(PlayerInteractEvent ev) {
        Action a = ev.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        if (ev.getHand() != EquipmentSlot.HAND) return; // ignore the off-hand duplicate event
        if (!input.hasScreens()) return;
        Player p = ev.getPlayer();
        Aim aim = input.aimAt(p);
        if (aim == null) return; // not looking at a screen — leave normal right-click alone

        long now = System.currentTimeMillis();
        Long prev = lastOpen.get(p.getUniqueId());
        if (prev != null && now - prev < OPEN_DEBOUNCE_MS) { ev.setCancelled(true); return; }
        lastOpen.put(p.getUniqueId(), now);

        ev.setCancelled(true); // don't place a block / use the held item
        plugin.getLogger().info("[cinema] " + p.getName() + " right-clicked screen '"
                + aim.cinemaId() + "' → opening fullscreen view");
        p.sendMessage(Component.text(CINEMA_SENTINEL + aim.cinemaId()));
        p.sendActionBar(Component.text("⛶ opening " + aim.cinemaId() + " — full screen · esc to exit",
                NamedTextColor.AQUA));
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 1.6f);
    }

    @EventHandler
    public void onScroll(PlayerItemHeldEvent ev) {
        if (!input.hasScreens()) return;
        Player p = ev.getPlayer();
        Aim aim = input.aimAt(p);
        if (aim == null) return; // not looking at a screen — leave the hotbar alone

        int delta = ev.getNewSlot() - ev.getPreviousSlot();
        if (delta > 4) delta -= 9;        // wrap 8→0
        else if (delta < -4) delta += 9;  // wrap 0→8
        if (delta == 0) return;

        // Wheel-down in MC advances the slot (+1) → scroll the page down (+dy).
        input.sendScroll(aim.cinemaId(), aim.nx(), aim.ny(), delta * SCROLL_STEP);
        ev.setCancelled(true); // keep their held slot put while they browse
    }

    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent ev) {
        if (!input.hasScreens()) return;
        Player p = ev.getPlayer();
        boolean alreadyTyping = input.isTyping(p.getUniqueId());
        Aim aim = input.aimAt(p);
        // Allow turning type-mode OFF from anywhere; only turn it ON while
        // looking at a screen (so F keeps its normal use elsewhere).
        if (!alreadyTyping && aim == null) return;

        ev.setCancelled(true);
        String target = aim != null ? aim.cinemaId() : null;
        boolean nowTyping = input.toggleTyping(p.getUniqueId(), target);
        if (nowTyping) {
            p.sendActionBar(Component.text("⌨ type mode ON — chat goes to the page · F to exit",
                    NamedTextColor.AQUA));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, 1.5f);
        } else {
            p.sendActionBar(Component.text("type mode off", NamedTextColor.GRAY));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, 1.0f);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent ev) {
        Player p = ev.getPlayer();
        UUID id = p.getUniqueId();
        String text = PlainTextComponentSerializer.plainText().serialize(ev.message()).trim();

        // (1) Type-mode (toggled with F): keystrokes go into the focused field.
        if (input.isTyping(id)) {
            String target = input.typingTarget(id);
            if (target == null) { input.stopTyping(id); return; }
            ev.setCancelled(true); // captured for the page, not broadcast
            if (text.isEmpty()) return;
            // Let the player drop out of type mode by typing "exit"/"done".
            if (text.equalsIgnoreCase("exit") || text.equalsIgnoreCase("done")) {
                input.stopTyping(id);
                p.sendActionBar(Component.text("type mode off", NamedTextColor.GRAY));
                return;
            }
            input.sendText(target, text);
            input.sendKey(target, "Enter");
            return;
        }

        // (2) Pointing at a screen + typed a URL → change that screen's channel.
        //     A URL is a single token with a dot/colon/localhost; plain chat
        //     (which has spaces) falls through to the agent router untouched.
        String aimed = input.aimedCinema(id);
        if (aimed == null || !looksLikeUrl(text)) return;
        ev.setCancelled(true);
        final String cine = aimed;
        final String url = normalizeUrl(text);
        p.sendActionBar(Component.text("→ " + cine + " → " + url, NamedTextColor.AQUA));
        // setUrl is a blocking HTTP call — off the (already async) chat thread anyway.
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin,
                () -> plugin.cinema().setUrl(cine, url));
    }

    /**
     * Is this typed line an actual URL/host (vs normal chat)? It must be a single
     * token (no spaces) that is one of: an http(s) URL, a {@code localhost} host,
     * a {@code :port} shorthand, a {@code host:port}, or a dotted domain with a
     * real TLD. Crucially this REJECTS sentence words like "Thanks." or a bare
     * ":" so pointing at a screen never hijacks normal chat.
     */
    private static boolean looksLikeUrl(String s) {
        if (s == null) return false;
        String l = s.trim().toLowerCase();
        if (l.isEmpty() || l.indexOf(' ') >= 0) return false;
        if (l.startsWith("http://") || l.startsWith("https://")) return true;
        if (l.startsWith("localhost")) return true;                       // localhost[:port][/path]
        if (l.startsWith(":") && l.length() > 1 && allDigits(l.substring(1).split("/", 2)[0]))
            return true;                                                  // ":3000" port shorthand
        String host = l.split("[/?#]", 2)[0];                             // strip path/query
        int colon = host.lastIndexOf(':');
        if (colon > 0 && allDigits(host.substring(colon + 1))) return true; // host:port (e.g. 127.0.0.1:3000)
        return host.matches("[a-z0-9-]+(\\.[a-z0-9-]+)*\\.[a-z]{2,}");     // dotted domain w/ a TLD
    }

    private static boolean allDigits(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return false;
        return true;
    }

    /** Coerce loose input into a navigable URL (mirrors HermesCommand.normalizeUrl). */
    private static String normalizeUrl(String raw) {
        String u = raw.trim();
        if (u.startsWith("http://") || u.startsWith("https://") || u.startsWith("about:")) return u;
        if (u.startsWith(":")) return "http://localhost" + u;   // ":3000"
        return "http://" + u;                                   // "localhost:3000", "example.com"
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent ev) {
        UUID id = ev.getPlayer().getUniqueId();
        input.stopTyping(id);
        input.clearAim(id);
        lastClick.remove(id);
        lastOpen.remove(id);
    }
}

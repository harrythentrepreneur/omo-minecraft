package com.agentcraft.onboarding;

import com.agentcraft.ui.Ui;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Locale;

/**
 * The two onboarding overlays Omo Studio shows on the HUD: the join card and
 * the room-entry card. Both follow one rule — name the place, name its one verb,
 * then get out of the way. Everything else a player needs lives where they can
 * reach for it on purpose: the world {@code signs} (what to do where you stand)
 * and the {@code /omo} menu (act from anywhere). There is deliberately no
 * guide book and no per-room chat "tips" — those were a fourth and fifth copy of
 * the same facts, and the noise was the problem.
 *
 * @see com.agentcraft.ui.Ui the design system these strings obey
 */
public final class RoomGuide {

    private RoomGuide() {}

    // Title timings (fade in / stay / fade out).
    private static final Title.Times WELCOME_TIMES =
            Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(3000), Duration.ofMillis(800));
    private static final Title.Times ENTRY_TIMES =
            Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1600), Duration.ofMillis(500));

    // ── Room entry: a single calm card (title + subtitle), nothing else ───────

    /** A room's entry card: its Title-Case name and the one-line verb beneath it. */
    public record RoomInfo(String title, Component subtitle) {}

    /**
     * The entry card for a room. Prefix-based so the numbered Studio rooms
     * (code-1.., hermes-2..), their aliases (hq/lobby), and the single-box island
     * reskin (code/hermes/cinema) all resolve. The title is always the place
     * colour; the subtitle is body copy with at most one highlighted agent id.
     */
    public static RoomInfo infoFor(String room) {
        String r = room == null ? "" : room.toLowerCase(Locale.ROOT);

        if (r.equals("spawn") || r.equals("hq") || r.equals("lobby") || r.equals("commons")) {
            return new RoomInfo("Studio Commons",
                    body("Press V to talk to Omo"));
        }
        if (r.equals("code") || r.startsWith("code-")) {
            String n = r.startsWith("code-") ? r.substring("code-".length()) : "";
            String title = n.isBlank() ? "Code Workshop" : "Code Workstation " + n;
            return new RoomInfo(title, body("Step the plate to open its terminal"));
        }
        if (r.startsWith("hermes")) {
            return new RoomInfo("Hermes Worker",
                    body("Type a task" + Ui.SEP + "right-click to watch"));
        }
        if (r.equals("cinema") || r.startsWith("standup")) {
            return new RoomInfo("Standup Screen",
                    body("Aim" + Ui.SEP + "left-click, scroll, F to type"));
        }
        if (r.startsWith("build")) {
            return new RoomInfo("Build Studio",
                    body("Type what to build" + Ui.SEP + "a mason builds it"));
        }
        if (r.startsWith("classroom") || r.startsWith("school") || r.startsWith("algebra")) {
            String subj = r.startsWith("classroom-") ? r.substring("classroom-".length()) : "";
            String title = subj.isBlank() ? "Classroom" : prettify(subj);
            Component sub = Component.text("Sit at a desk and type" + Ui.SEP, Ui.BODY)
                    .append(Component.text("ada", Ui.AGENT))
                    .append(Component.text(" teaches you", Ui.BODY));
            return new RoomInfo(title, sub);
        }
        if (r.startsWith("listening")) {
            return new RoomInfo("Listening Room", body("Flip the lever and speak"));
        }
        return new RoomInfo(prettify(room), Component.empty());
    }

    /** Show the entry card. Callers gate this to once per room per session. */
    public static void announceEntry(Player p, String room) {
        RoomInfo info = infoFor(room);
        p.showTitle(Title.title(
                Component.text(info.title(), Ui.PLACE, TextDecoration.BOLD),
                info.subtitle(),
                ENTRY_TIMES));
    }

    // ── Join: one card + three calm lines ─────────────────────────────────────

    /** The card shown a beat after a player joins. */
    public static Title welcomeTitle() {
        return Title.title(
                Component.text("Omo Studio", Ui.PLACE, TextDecoration.BOLD),
                Component.text("Press V to talk" + Ui.SEP + "signs guide you", Ui.BODY),
                WELCOME_TIMES);
    }

    /** Two lines: what this is, then the one verb + where the menu lives. */
    public static void sendWelcomeChat(Player p) {
        p.sendMessage(Component.empty());
        p.sendMessage(Ui.wordmark().append(Component.text(" — an AI team you can watch work.", Ui.BODY)));
        p.sendMessage(Component.text("Walk up to anyone and type to give a task. Press ", Ui.BODY)
                .append(Component.text("V", Ui.PLACE))
                .append(Component.text(" to talk, or ", Ui.BODY))
                .append(Ui.suggest("/omo", "/omo"))
                .append(Component.text(" for the menu.", Ui.BODY)));
        p.sendMessage(Component.empty());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static Component body(String s) { return Component.text(s, Ui.BODY); }

    /** "code-1" → "Code 1", "buildstudio" → "Buildstudio" — a readable fallback title. */
    private static String prettify(String room) {
        if (room == null || room.isBlank()) return "Room";
        String s = room.replace('-', ' ').replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}

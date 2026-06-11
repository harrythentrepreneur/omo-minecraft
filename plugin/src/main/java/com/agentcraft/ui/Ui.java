package com.agentcraft.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * The Omo Studio design system, in one place. Six color ROLES, the five allowed
 * glyphs, the wordmark, and the few component builders every player-facing
 * surface shares. The whole UI obeys one rule:
 *
 * <pre>  GOLD where · AQUA who · GREEN go · YELLOW wait · RED stop · GRAY rest.</pre>
 *
 * Glyphs are limited to {@code › • ·  ─} and the compass arrows; everything else
 * ({@code ▸ » ✓ ✗ ⌨ ═ ▓ …}) is banned from player copy. Voice is second-person
 * imperative, sentence case (room titles are the only Title Case), no dev-speak
 * ("repo"/"folder" not "cwd", "right-click" not "R-click"). Keep this class
 * small enough to memorise — it is the single source of truth for look & feel.
 */
public final class Ui {

    private Ui() {}

    // ── Color roles — call sites use the role, never the raw color ────────────
    /** Place / brand: the wordmark, room titles, sign headers, menu titles. */
    public static final NamedTextColor PLACE = NamedTextColor.GOLD;
    /** Agent identity: an agent id wherever it appears (code-1, ada, omo). */
    public static final NamedTextColor AGENT = NamedTextColor.AQUA;
    /** Success / runnable: a finished result, a clickable command. */
    public static final NamedTextColor OK = NamedTextColor.GREEN;
    /** Caution / pending: a confirm warning, a pending approval, "working…". */
    public static final NamedTextColor WAIT = NamedTextColor.YELLOW;
    /** Error / deny: something that failed or was blocked. */
    public static final NamedTextColor ERR = NamedTextColor.RED;
    /** Body / chrome: all explanatory copy, subtitles, lore, dividers. */
    public static final NamedTextColor BODY = NamedTextColor.GRAY;
    /** Faintest chrome: secondary lore, the dimmer half of a divider. */
    public static final NamedTextColor FAINT = NamedTextColor.DARK_GRAY;
    /** An interpolated path / url, shown as a bright run inside BODY copy. */
    public static final NamedTextColor PATH = NamedTextColor.WHITE;

    // ── Glyphs (the only ones allowed) ────────────────────────────────────────
    /** Marks a runnable / fill-able command. */
    public static final String RUN = "›"; // ›
    /** Inline separator within one line: {@code a · b · c}. */
    public static final String SEP = " · "; // ·

    /** A 6-wide chat divider. */
    public static Component rule() { return Component.text("─".repeat(6), FAINT); }

    /** The wordmark — always exactly "Omo Studio", GOLD bold. */
    public static Component wordmark() {
        return Component.text("Omo Studio", PLACE, TextDecoration.BOLD);
    }

    // ── Clickable commands — a command shown to a player is never dead text ────

    /**
     * A clickable command that PRE-FILLS the chat box (so the player learns the
     * syntax and types the argument). {@code display} is what they read;
     * {@code command} is dropped into the box (a trailing space is added so the
     * cursor sits ready for the argument).
     */
    public static Component suggest(String display, String command) {
        String fill = command.endsWith(" ") ? command : command + " ";
        return Component.text(display, OK)
                .clickEvent(ClickEvent.suggestCommand(fill))
                .hoverEvent(HoverEvent.showText(Component.text("Click to fill your chat box", BODY)));
    }

    /** A standalone command line: "{@code › <command>}", clickable to pre-fill. */
    public static Component cmdLine(String command) {
        return Component.text(RUN + " ", OK).append(suggest(command, command));
    }

    /** A clickable button that RUNS a command immediately (no-arg commands only). */
    public static Component runButton(String label, String command) {
        return Component.text(label, OK)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text("Click to run", BODY)));
    }
}

package com.agentcraft.terminal;

import org.lwjgl.glfw.GLFW;

import java.nio.charset.StandardCharsets;

/**
 * Translates GLFW key events from Minecraft's {@code Screen.keyPressed} into
 * the byte sequences a unix terminal expects. Char input (letters, symbols)
 * goes through {@code Screen.charTyped} and is sent as UTF-8 directly.
 *
 * Returns {@code null} when a key has no terminal mapping — the caller should
 * leave it for Minecraft (e.g. ESC closing the screen).
 */
public final class Keymap {

    private Keymap() {}

    public static byte[] translate(int keyCode, int modifiers, boolean appCursorKeys) {
        boolean ctrl  = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
        boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT)   != 0;
        boolean alt   = (modifiers & GLFW.GLFW_MOD_ALT)     != 0;

        // Ctrl + letter → ASCII control code.
        if (ctrl && !alt && keyCode >= GLFW.GLFW_KEY_A && keyCode <= GLFW.GLFW_KEY_Z) {
            return new byte[]{ (byte) (keyCode - GLFW.GLFW_KEY_A + 1) };
        }
        // Ctrl + bracket / backslash / minus / space — classic control bytes.
        if (ctrl && !alt) {
            switch (keyCode) {
                case GLFW.GLFW_KEY_LEFT_BRACKET  -> { return new byte[]{ 0x1B }; }
                case GLFW.GLFW_KEY_BACKSLASH     -> { return new byte[]{ 0x1C }; }
                case GLFW.GLFW_KEY_RIGHT_BRACKET -> { return new byte[]{ 0x1D }; }
                case GLFW.GLFW_KEY_MINUS         -> { return new byte[]{ 0x1F }; }
                case GLFW.GLFW_KEY_SPACE         -> { return new byte[]{ 0x00 }; }
                default -> { /* fall through */ }
            }
        }

        // Plain literal byte sequences — no leading ESC.
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            return prefixAlt(alt, new byte[]{ '\r' });
        }
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            return prefixAlt(alt, new byte[]{ 0x7F });
        }
        if (keyCode == GLFW.GLFW_KEY_TAB && !shift) {
            return prefixAlt(alt, new byte[]{ '\t' });
        }

        // CSI / SS3 sequences — always prefixed with ESC.
        String tail = null;
        switch (keyCode) {
            case GLFW.GLFW_KEY_TAB       -> tail = "[Z"; // shift+tab
            case GLFW.GLFW_KEY_DELETE    -> tail = "[3~";
            case GLFW.GLFW_KEY_INSERT    -> tail = "[2~";
            case GLFW.GLFW_KEY_PAGE_UP   -> tail = "[5~";
            case GLFW.GLFW_KEY_PAGE_DOWN -> tail = "[6~";
            case GLFW.GLFW_KEY_UP        -> tail = appCursorKeys ? "OA" : "[A";
            case GLFW.GLFW_KEY_DOWN      -> tail = appCursorKeys ? "OB" : "[B";
            case GLFW.GLFW_KEY_RIGHT     -> tail = appCursorKeys ? "OC" : "[C";
            case GLFW.GLFW_KEY_LEFT      -> tail = appCursorKeys ? "OD" : "[D";
            case GLFW.GLFW_KEY_HOME      -> tail = appCursorKeys ? "OH" : "[H";
            case GLFW.GLFW_KEY_END       -> tail = appCursorKeys ? "OF" : "[F";
            case GLFW.GLFW_KEY_F1        -> tail = "OP";
            case GLFW.GLFW_KEY_F2        -> tail = "OQ";
            case GLFW.GLFW_KEY_F3        -> tail = "OR";
            case GLFW.GLFW_KEY_F4        -> tail = "OS";
            case GLFW.GLFW_KEY_F5        -> tail = "[15~";
            case GLFW.GLFW_KEY_F6        -> tail = "[17~";
            case GLFW.GLFW_KEY_F7        -> tail = "[18~";
            case GLFW.GLFW_KEY_F8        -> tail = "[19~";
            case GLFW.GLFW_KEY_F9        -> tail = "[20~";
            case GLFW.GLFW_KEY_F10       -> tail = "[21~";
            case GLFW.GLFW_KEY_F11       -> tail = "[23~";
            case GLFW.GLFW_KEY_F12       -> tail = "[24~";
            default -> { /* unmapped */ }
        }
        if (tail == null) return null;
        byte[] body = tail.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[body.length + 1];
        out[0] = 0x1B;
        System.arraycopy(body, 0, out, 1, body.length);
        return prefixAlt(alt, out);
    }

    private static byte[] prefixAlt(boolean alt, byte[] body) {
        if (!alt) return body;
        byte[] out = new byte[body.length + 1];
        out[0] = 0x1B;
        System.arraycopy(body, 0, out, 1, body.length);
        return out;
    }

    /** Wrap pasted text with bracketed-paste markers if the app asked for them. */
    public static byte[] forPaste(String text, boolean bracketed) {
        String normalized = text.replace("\r\n", "\r").replace("\n", "\r");
        if (!bracketed) return normalized.getBytes(StandardCharsets.UTF_8);
        return ("[200~" + normalized + "[201~").getBytes(StandardCharsets.UTF_8);
    }
}

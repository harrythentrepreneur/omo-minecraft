package com.agentcraft.terminal;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Terminal Screen for the Code Lab "team-of-4" workflow. Instead of spawning a
 * local PTY (see {@link TerminalScreen}) it attaches over WebSocket to a
 * {@code claude} subprocess running on the runtime, owned by a specific
 * workshop villager.
 *
 * Shift+Left / Shift+Right cycle through the 4 team agents (alice/bob/carol/dave).
 * The bare arrow keys pass through to claude's TUI for line/history navigation.
 */
public class TeamTerminalScreen extends Screen {

    private static final int BASE_CELL_W = 7;   // Minecraft default font ~6-7 px wide
    private static final int BASE_CELL_H = 9;
    private static final int MARGIN      = 8;
    private static final int HEADER_H    = 16;
    private static final float ZOOM_MIN  = 0.6f;
    private static final float ZOOM_MAX  = 3.0f;
    private static final float ZOOM_STEP = 0.15f;

    private static final String DEFAULT_URL = RuntimeHost.terminalWsUrl();
    // Fallback when the server hasn't returned a team yet (just opened, mid-
    // handshake). The MVP terminal boxes are the real PTY-backed pair:
    // `claude` in the Code box and `hermes` in the Hermes box.
    private static final List<String> FALLBACK_TEAM = List.of("claude", "hermes");

    /** Mutable: replaced when the server delivers the real roster. */
    private List<String> team;
    private int agentIdx;
    /** "workshop" (Code Lab claude PTY) or "hermes" (sanctuary live stream). */
    private volatile String kind = "workshop";
    /** The agent the screen *wants* to be on (may not equal team.get(agentIdx) before first subscribed). */
    private String pendingAgent;

    private TerminalBuffer buffer;
    private TerminalAttachClient client;
    // Default a touch more zoomed-in than before. 1.0 renders the font at native
    // size — the crispest it gets — and reads more comfortably than the old 0.85.
    // Player can still ctrl +/- between ZOOM_MIN..ZOOM_MAX (ctrl+0 resets).
    private float zoom = 1.0f;
    private long lastBlinkMs;
    private boolean blinkOn = true;
    private volatile String status = "";
    /** Scrollback viewport + click-drag text selection (shared with the single-PTY screen). */
    private final TerminalView view = new TerminalView();

    public TeamTerminalScreen() {
        this(null);
    }

    /**
     * The agent this screen is currently showing (or trying to subscribe to).
     * Lets the open trigger ({@code TerminalMod.openTeam}) skip recreating the
     * screen when a duplicate sentinel arrives for the same agent — a re-created
     * screen wipes the buffer + reconnects, which looks like the terminal
     * "refreshing" every couple of seconds.
     */
    public String currentAgent() {
        if (pendingAgent != null) return pendingAgent;
        if (team != null && !team.isEmpty() && agentIdx >= 0 && agentIdx < team.size()) {
            return team.get(agentIdx);
        }
        return null;
    }

    public TeamTerminalScreen(String initialAgent) {
        super(Text.literal("Omo Terminal"));
        this.team = new ArrayList<>(FALLBACK_TEAM);
        if (initialAgent == null) {
            this.agentIdx = 0;
            this.pendingAgent = null;
        } else {
            int idx = team.indexOf(initialAgent);
            if (idx < 0) {
                // Unknown agent — add it as a singleton so we can subscribe
                // immediately; the server will overwrite the team list with
                // the right roster on the first subscribed frame.
                team = new ArrayList<>();
                team.add(initialAgent);
                this.agentIdx = 0;
            } else {
                this.agentIdx = idx;
            }
            this.pendingAgent = initialAgent;
        }
    }

    @Override
    protected void init() {
        super.init();
        Dim d = computeDims();
        if (buffer == null) buffer = new TerminalBuffer(d.cols, d.rows);
        else buffer.resize(d.cols, d.rows);

        if (client == null) {
            client = new TerminalAttachClient(DEFAULT_URL, new TerminalAttachClient.Sink() {
                @Override public void onBytes(byte[] bytes) {
                    MinecraftClient.getInstance().execute(() -> {
                        if (buffer != null) buffer.feed(bytes, bytes.length);
                    });
                }
                @Override public void onAgents(String json) { /* full roster ignored — we rely on per-subscribe team */ }
                @Override public void onSubscribed(String agentId, List<String> serverTeam, String agentKind) {
                    MinecraftClient.getInstance().execute(() -> {
                        kind = (agentKind == null || agentKind.isEmpty()) ? "workshop" : agentKind;
                        if (serverTeam != null && !serverTeam.isEmpty()) {
                            team = new ArrayList<>(serverTeam);
                            int idx = team.indexOf(agentId);
                            if (idx < 0) {
                                // The agent we subscribed to isn't in the
                                // server's team list — fall back to a
                                // single-entry team so ←/→ stays sensible.
                                team = new ArrayList<>();
                                team.add(agentId);
                                idx = 0;
                            }
                            agentIdx = idx;
                        }
                        pendingAgent = agentId;
                        status = "attached: " + agentId + " (" + kind + ")";
                        Dim dd = computeDims();
                        if (client != null) client.sendResize(dd.cols, dd.rows);
                    });
                }
                @Override public void onError(String message) {
                    status = "ws: " + message;
                }
            });
            client.connect().whenComplete((v, err) -> {
                if (err == null) {
                    String agent = (pendingAgent != null) ? pendingAgent : "";
                    client.subscribe(agent);
                    client.sendResize(d.cols, d.rows);
                }
            });
        } else {
            client.sendResize(d.cols, d.rows);
        }
    }

    private record Dim(int cols, int rows, int cellW, int cellH) {}

    private Dim computeDims() {
        int cellW = Math.max(2, Math.round(BASE_CELL_W * zoom));
        int cellH = Math.max(4, Math.round(BASE_CELL_H * zoom));
        int availW = width - 2 * MARGIN;
        int availH = height - 2 * MARGIN - HEADER_H;
        int cols = Math.max(20, availW / cellW);
        int rows = Math.max(5,  availH / cellH);
        return new Dim(cols, rows, cellW, cellH);
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        Dim d = computeDims();
        if (buffer != null && (d.cols != buffer.cols() || d.rows != buffer.rows())) {
            buffer.resize(d.cols, d.rows);
            if (client != null) client.sendResize(d.cols, d.rows);
        }
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, width, height, 0xCC000000);
        if (buffer == null) {
            // Pre-init: just a black pane for one frame, never a "waiting" label.
            return;
        }
        Dim d = computeDims();
        int gridW = d.cols * d.cellW;
        int gridH = d.rows * d.cellH;
        int x0 = (width  - gridW) / 2;
        int y0 = (height - gridH) / 2 + HEADER_H / 2;

        // Header: agent name + status
        String agent = team.get(agentIdx);
        String header = "Agent: " + agent + "   (" + (agentIdx + 1) + "/" + team.size() + ")    " + status;
        ctx.fill(x0 - 2, y0 - HEADER_H, x0 + gridW + 2, y0 - 2, 0xFF1a1f26);
        ctx.drawText(textRenderer, header, x0 + 2, y0 - HEADER_H + 4, 0xFFE0E0E0, false);

        // Terminal body background
        ctx.fill(x0 - 2, y0 - 2, x0 + gridW + 2, y0 + gridH + 2, 0xFF111418);

        // Keep a scrolled-up view pinned as new output streams in, then record
        // the geometry so mouse selection lands on the same cells we draw.
        view.pin(buffer.scrollbackSize());
        view.setGeometry(x0, y0, d.cellW, d.cellH, d.cols, d.rows);

        for (int r = 0; r < d.rows; r++) {
            int y = y0 + r * d.cellH;
            for (int c = 0; c < d.cols; c++) {
                Cell cell = buffer.cellAtView(r, c, view.viewOffset);
                int x = x0 + c * d.cellW;
                int fg = cell.fg();
                int bg = cell.bg();
                if (cell.reverse()) { int t = fg; fg = bg; bg = t; }
                if (view.isSelected(r, c)) {
                    ctx.fill(x, y, x + d.cellW, y + d.cellH, 0xFF335577); // selection
                } else if (bg != Cell.DEFAULT_BG) {
                    ctx.fill(x, y, x + d.cellW, y + d.cellH, 0xFF000000 | bg);
                }
                char ch = cell.ch();
                if (ch != ' ' && ch != 0) {
                    drawGlyph(ctx, cell, ch, x, y, d.cellW, fg);
                }
            }
        }

        long now = Util.getMeasuringTimeMs();
        if (now - lastBlinkMs > 500) { blinkOn = !blinkOn; lastBlinkMs = now; }
        // Cursor only tracks the live view — hide it while scrolled into history.
        if (view.viewOffset == 0 && buffer.cursorVisible() && blinkOn) {
            int cx = x0 + Math.min(buffer.cursorCol(), d.cols - 1) * d.cellW;
            int cy = y0 + Math.min(buffer.cursorRow(), d.rows - 1) * d.cellH;
            ctx.fill(cx, cy, cx + d.cellW, cy + d.cellH, 0x88FFFFFF);
        }

        if (view.scrolledUp()) {
            ctx.drawTextWithShadow(textRenderer,
                "↑ scrollback — scroll down or press a key to return to live", 6, 6, 0xFFFFC107);
        }

        // For "hermes" kind the terminal is read-only (the live agent
        // transcript) so paste/typing isn't relevant. Surface that clearly
        // in the hint line so the player knows to type in chat instead.
        String hint = "hermes".equals(kind)
            ? "shift+esc close • shift+←/→ switch cube • ctrl+= zoom • scroll history • drag to select • cmd/ctrl+shift+c copy"
            : "shift+esc close • shift+←/→ switch agent • ctrl+= zoom • scroll history • drag select • cmd/ctrl+shift+c copy • ctrl+shift+v paste";
        ctx.drawTextWithShadow(textRenderer, hint, 6, height - 12, 0xFF808080);
    }

    /** Draw one glyph centered in its cell, honoring bold/underline attributes. */
    private void drawGlyph(DrawContext ctx, Cell cell, char ch, int x, int y, int cellW, int fg) {
        String s = String.valueOf(ch);
        // Default MC font is proportional; center each glyph in its fixed cell so
        // narrow letters (i, l, t, ') don't clump left with a big gap on the right.
        float gx = x + (cellW - textRenderer.getWidth(s) * zoom) / 2f;
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(gx, (float) y);
        ctx.getMatrices().scale(zoom, zoom);
        if (cell.bold() || cell.underline()) {
            ctx.drawText(textRenderer,
                Text.literal(s).styled(st -> st.withBold(cell.bold()).withUnderline(cell.underline())),
                0, 0, 0xFF000000 | fg, false);
        } else {
            ctx.drawText(textRenderer, s, 0, 0, 0xFF000000 | fg, false);
        }
        ctx.getMatrices().popMatrix();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (buffer == null || verticalAmount == 0) return false;
        view.scrollLines((int) Math.signum(verticalAmount) * 3, buffer.scrollbackSize());
        return true;
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && buffer != null) {
            view.beginSelection(click.x(), click.y());
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        if (click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && view.dragSelection(click.x(), click.y())) {
            return true;
        }
        return super.mouseDragged(click, offsetX, offsetY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        view.endSelection();
        return super.mouseReleased(click);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int keyCode = input.key();
        int modifiers = input.modifiers();
        boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT)   != 0;
        boolean ctrl  = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
        boolean cmd   = (modifiers & GLFW.GLFW_MOD_SUPER)   != 0;

        if (keyCode == GLFW.GLFW_KEY_ESCAPE && shift) {
            close();
            return true;
        }

        // Agent switching — shift+arrows.
        if (shift && (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT)) {
            int step = keyCode == GLFW.GLFW_KEY_LEFT ? -1 : 1;
            int next = (agentIdx + step + team.size()) % team.size();
            switchAgent(next);
            return true;
        }
        // Direct-pick: ctrl/cmd + 1..4
        if ((ctrl || cmd) && keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_9) {
            int n = keyCode - GLFW.GLFW_KEY_1;
            if (n < team.size()) {
                switchAgent(n);
                return true;
            }
        }
        // Zoom — ctrl + = / -
        if ((ctrl || cmd) && (keyCode == GLFW.GLFW_KEY_EQUAL || keyCode == GLFW.GLFW_KEY_KP_ADD)) {
            setZoom(zoom + ZOOM_STEP); return true;
        }
        if ((ctrl || cmd) && (keyCode == GLFW.GLFW_KEY_MINUS || keyCode == GLFW.GLFW_KEY_KP_SUBTRACT)) {
            setZoom(zoom - ZOOM_STEP); return true;
        }
        // Paste: ctrl+shift+v or cmd+v
        if (((ctrl && shift) || cmd) && keyCode == GLFW.GLFW_KEY_V) {
            pasteFromClipboard();
            return true;
        }
        // Copy selection: cmd+C or ctrl+shift+C (must come before the ctrl+letter
        // block so plain ctrl+C still reaches claude as SIGINT).
        if (((ctrl && shift) || cmd) && keyCode == GLFW.GLFW_KEY_C) {
            view.copyToClipboard(buffer);
            return true;
        }

        // Bare ESC: forward to claude.
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            sendInputBytes(new byte[]{ 0x1B });
            return true;
        }

        // Ctrl + letter: control byte.
        if (ctrl && !shift && !cmd && keyCode >= GLFW.GLFW_KEY_A && keyCode <= GLFW.GLFW_KEY_Z) {
            sendInputBytes(new byte[]{ (byte) (keyCode - GLFW.GLFW_KEY_A + 1) });
            return true;
        }

        // Other named keys via Keymap (arrows pass through to claude).
        boolean appCursor = buffer != null && buffer.appCursorKeys();
        byte[] bytes = Keymap.translate(keyCode, modifiers, appCursor);
        if (bytes != null) {
            sendInputBytes(bytes);
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        int modifiers = input.modifiers();
        if ((modifiers & GLFW.GLFW_MOD_CONTROL) != 0) return false;
        if ((modifiers & GLFW.GLFW_MOD_SUPER)   != 0) return false;

        String s = input.asString();
        if (s == null || s.isEmpty()) return false;
        boolean alt = (modifiers & GLFW.GLFW_MOD_ALT) != 0;
        byte[] body = s.getBytes(StandardCharsets.UTF_8);
        if (alt) {
            byte[] out = new byte[body.length + 1];
            out[0] = 0x1B;
            System.arraycopy(body, 0, out, 1, body.length);
            sendInputBytes(out);
        } else {
            sendInputBytes(body);
        }
        return true;
    }

    private void switchAgent(int idx) {
        if (idx == agentIdx) return;
        agentIdx = idx;
        String next = team.get(idx);
        pendingAgent = next;
        // Wipe the buffer so the new agent's source draws fresh. The runtime
        // sends its replay snapshot via the "subscribed" frame.
        if (buffer != null) {
            Dim d = computeDims();
            buffer = new TerminalBuffer(d.cols, d.rows);
        }
        view.reset(); // fresh buffer → drop scrollback offset + selection
        status = "switching to " + next + "…";
        if (client != null) client.subscribe(next);
    }

    private void setZoom(float z) {
        z = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, z));
        if (z == zoom) return;
        zoom = z;
        Dim d = computeDims();
        if (buffer != null) buffer.resize(d.cols, d.rows);
        if (client != null) client.sendResize(d.cols, d.rows);
    }

    private void sendInputBytes(byte[] bytes) {
        view.snapToBottom(); // any input returns to the live view
        if (client != null && client.isAlive()) client.sendInput(bytes);
    }

    private void pasteFromClipboard() {
        String clip = MinecraftClient.getInstance().keyboard.getClipboard();
        if (clip == null || clip.isEmpty()) {
            // No text on the clipboard — most likely an image was copied.
            // Minecraft's (GLFW) clipboard API is text-only, so we can't read
            // the image here. Ask the runtime to grab it off the host OS
            // clipboard, write a temp file, and feed the path to claude (which
            // auto-attaches image paths, just like the real terminal). Only the
            // live claude PTY can take input — skip for read-only hermes.
            if ("workshop".equals(kind) && client != null && client.isAlive()) {
                client.sendPasteImage();
            }
            return;
        }
        boolean bracketed = buffer != null && buffer.bracketedPaste();
        sendInputBytes(Keymap.forPaste(clip, bracketed));
    }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    public boolean shouldCloseOnEsc() { return false; }

    @Override
    public void close() {
        if (client != null) {
            try { client.close(); } catch (Throwable ignored) {}
            client = null;
        }
        super.close();
    }
}

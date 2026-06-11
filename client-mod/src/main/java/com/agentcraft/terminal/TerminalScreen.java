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

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * In-world terminal Screen. Owns a {@link TerminalSession} for its lifetime —
 * opening the screen starts a fresh PTY; closing the screen destroys it.
 *
 * Zoom is a render-time scale factor (Ctrl+= / Ctrl+-). Each zoom change
 * recomputes the column/row count and resizes the PTY accordingly, so the
 * shell still sees a real window-size change.
 *
 * Closing: Shift+Esc. Plain Esc is forwarded to the shell so vim/Claude etc.
 * see it normally.
 */
public class TerminalScreen extends Screen {

    private static final int BASE_CELL_W = 7;   // Minecraft default font ~6-7 px wide
    private static final int BASE_CELL_H = 9;
    private static final int MARGIN      = 8;
    private static final float ZOOM_MIN  = 0.75f;
    private static final float ZOOM_MAX  = 3.0f;
    private static final float ZOOM_STEP = 0.15f;

    private TerminalBuffer buffer;
    private TerminalSession session;
    // A little more zoomed in by default for readability; ctrl +/- still adjusts
    // within ZOOM_MIN..ZOOM_MAX (ctrl+0 resets to 1.5).
    private float zoom = 1.15f;
    private long lastBlinkMs;
    private boolean blinkOn = true;
    private String startupError;
    /** Scrollback viewport + click-drag text selection (shared with the team screen). */
    private final TerminalView view = new TerminalView();

    public TerminalScreen() {
        super(Text.literal("Omo Terminal"));
    }

    @Override
    protected void init() {
        super.init();
        ensureSession();
    }

    private void ensureSession() {
        Dim d = computeDims();
        if (session != null && session.isAlive()) {
            session.resize(d.cols, d.rows);
            return;
        }
        buffer = new TerminalBuffer(d.cols, d.rows);
        String[] cmd = TerminalSession.defaultShellCommand();
        String cwd = System.getProperty("user.home");
        session = new TerminalSession(buffer, cmd, cwd);
        try {
            session.start();
        } catch (IOException | RuntimeException e) {
            startupError = e.getClass().getSimpleName() + ": " + e.getMessage();
            session = null;
        }
    }

    private record Dim(int cols, int rows, int cellW, int cellH) {}

    private Dim computeDims() {
        int cellW = Math.max(2, Math.round(BASE_CELL_W * zoom));
        int cellH = Math.max(4, Math.round(BASE_CELL_H * zoom));
        int availW = width - 2 * MARGIN;
        int availH = height - 2 * MARGIN;
        int cols = Math.max(20, availW / cellW);
        int rows = Math.max(5,  availH / cellH);
        return new Dim(cols, rows, cellW, cellH);
    }

    private void resyncGrid() {
        if (session == null || buffer == null) return;
        Dim d = computeDims();
        if (d.cols != buffer.cols() || d.rows != buffer.rows()) {
            session.resize(d.cols, d.rows);
        }
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);
        resyncGrid();
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // Dim backdrop so we still see a hint of the game behind.
        ctx.fill(0, 0, width, height, 0xCC000000);

        if (startupError != null) {
            ctx.drawCenteredTextWithShadow(textRenderer,
                "failed to start shell: " + startupError, width / 2, height / 2, 0xFFFF6666);
            return;
        }
        if (buffer == null) {
            ctx.drawCenteredTextWithShadow(textRenderer,
                "starting shell…", width / 2, height / 2, 0xFFFFFFFF);
            return;
        }

        Dim d = computeDims();
        int gridW = d.cols * d.cellW;
        int gridH = d.rows * d.cellH;
        int x0 = (width  - gridW) / 2;
        int y0 = (height - gridH) / 2;

        // Keep a scrolled-up view pinned as new output streams in, then record
        // the geometry so mouse selection lands on the same cells we draw.
        view.pin(buffer.scrollbackSize());
        view.setGeometry(x0, y0, d.cellW, d.cellH, d.cols, d.rows);

        ctx.fill(x0 - 2, y0 - 2, x0 + gridW + 2, y0 + gridH + 2, 0xFF111418);

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
                "↑ scrollback — scroll down or press a key to return to live",
                6, 6, 0xFFFFC107);
        }

        ctx.drawTextWithShadow(textRenderer,
            "shift+esc close • ctrl+=/- zoom • scroll history • drag to select • cmd/ctrl+shift+c copy • ctrl+shift+v paste",
            6, height - 12, 0xFF808080);

        if (session != null && !session.isAlive()) {
            String msg = "shell exited" +
                (session.lastError() != null ? " — " + session.lastError() : "");
            ctx.drawCenteredTextWithShadow(textRenderer, msg, width / 2, y0 - 14, 0xFFFF6666);
        }
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

        // Shift+ESC closes the screen.
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && (modifiers & GLFW.GLFW_MOD_SHIFT) != 0) {
            close();
            return true;
        }

        boolean ctrlLike = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0
                        || (modifiers & GLFW.GLFW_MOD_SUPER)   != 0;

        if (ctrlLike) {
            if (keyCode == GLFW.GLFW_KEY_EQUAL || keyCode == GLFW.GLFW_KEY_KP_ADD) {
                setZoom(zoom + ZOOM_STEP); return true;
            }
            if (keyCode == GLFW.GLFW_KEY_MINUS || keyCode == GLFW.GLFW_KEY_KP_SUBTRACT) {
                setZoom(zoom - ZOOM_STEP); return true;
            }
            if (keyCode == GLFW.GLFW_KEY_0 || keyCode == GLFW.GLFW_KEY_KP_0) {
                setZoom(1.5f); return true;
            }
            if (keyCode == GLFW.GLFW_KEY_V) {
                boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
                boolean cmd   = (modifiers & GLFW.GLFW_MOD_SUPER) != 0;
                if (shift || cmd) {
                    pasteFromClipboard();
                    return true;
                }
            }
            // Copy selection: cmd+C (mac) or ctrl+shift+C. Plain ctrl+C falls
            // through to the shell as SIGINT (0x03) — never intercept it.
            if (keyCode == GLFW.GLFW_KEY_C) {
                boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
                boolean cmd   = (modifiers & GLFW.GLFW_MOD_SUPER) != 0;
                if (shift || cmd) {
                    view.copyToClipboard(buffer);
                    return true;
                }
            }
        }

        // Bare ESC: forward to shell.
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (session != null && session.isAlive()) { view.snapToBottom(); session.write(new byte[]{ 0x1B }); }
            return true;
        }

        if (session != null && session.isAlive() && buffer != null) {
            byte[] bytes = Keymap.translate(keyCode, modifiers, buffer.appCursorKeys());
            if (bytes != null) {
                view.snapToBottom();
                session.write(bytes);
                return true;
            }
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (session == null || !session.isAlive()) return false;
        int modifiers = input.modifiers();
        if ((modifiers & GLFW.GLFW_MOD_CONTROL) != 0) return false;
        if ((modifiers & GLFW.GLFW_MOD_SUPER)   != 0) return false;

        String s = input.asString();
        if (s == null || s.isEmpty()) return false;
        view.snapToBottom(); // typing returns to the live view
        boolean alt = (modifiers & GLFW.GLFW_MOD_ALT) != 0;
        byte[] body = s.getBytes(StandardCharsets.UTF_8);
        if (alt) {
            byte[] out = new byte[body.length + 1];
            out[0] = 0x1B;
            System.arraycopy(body, 0, out, 1, body.length);
            session.write(out);
        } else {
            session.write(body);
        }
        return true;
    }

    private void setZoom(float z) {
        z = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, z));
        if (z == zoom) return;
        zoom = z;
        resyncGrid();
    }

    private void pasteFromClipboard() {
        if (session == null) return;
        String clip = MinecraftClient.getInstance().keyboard.getClipboard();
        if (clip == null || clip.isEmpty()) return;
        session.write(Keymap.forPaste(clip, buffer != null && buffer.bracketedPaste()));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void close() {
        if (session != null) {
            try { session.close(); } catch (Throwable ignored) { }
            session = null;
        }
        super.close();
    }
}

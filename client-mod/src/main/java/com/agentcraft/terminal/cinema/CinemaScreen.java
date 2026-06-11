package com.agentcraft.terminal.cinema;

import com.agentcraft.terminal.RuntimeHost;
import com.agentcraft.terminal.TerminalMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * The "real computer inside Minecraft" view of a cinema wall. Opening it
 * (right-click the wall → the plugin emits {@code §§ACT-CINEMA§§ <id>}) trades
 * the shared map-wall — which is palette-crushed and capped at ~1 Hz by the
 * Bukkit map-packet ceiling — for a fullscreen GPU blit of the same live page
 * at full RGB and the client's native framerate.
 *
 * <p>Frame in: {@link CinemaFramePoller} polls {@code GET /api/cinema/<id>/frame}
 * and decodes each JPEG to a {@link NativeImage} off the render thread. Blit:
 * we upload that into a {@link NativeImageBackedTexture} (only when a new frame
 * arrives — re-uploading every render tick would crater fps) and draw it
 * letterboxed to fill the window. Input out: the Screen's own mouse/keyboard
 * overrides normalise to [0,1] over the blit rect and POST straight to
 * {@code /api/cinema/<id>/input}; the face drains that queue and replays each
 * gesture into Chrome via CDP — exactly the path the map-wall already uses, so
 * no runtime or face changes are needed.
 *
 * <p>Esc (or Shift+Esc) closes. Closing shuts the poller and frees the texture.
 */
public final class CinemaScreen extends Screen {

    private static final Identifier LIVE_TEX_ID =
        Identifier.of(TerminalMod.MOD_ID, "cinema/live");

    // How hard one wheel notch scrolls the page. Mirrors the wall's SCROLL_STEP
    // so scrolling feels the same whether you're aiming at the wall or zoomed in.
    private static final double SCROLL_STEP = 120.0;
    // Don't flood the runtime with a hover event every mouse-move; ~30 Hz is
    // plenty for the page's :hover state and keeps the POST rate sane.
    private static final long MOVE_THROTTLE_MS = 33L;

    private final String cinemaId;
    private final HttpClient http;
    private final URI inputEndpoint;

    private CinemaFramePoller poller;

    // Live-frame texture state (same strategy as omo.OmoHudLayer): one texture
    // for the screen's lifetime, inner image replaced per frame, re-uploaded
    // only when the poller's frame id advances.
    private NativeImageBackedTexture liveTex;
    private long uploadedFrameId = -1L;
    private boolean liveTexRegistered = false;
    private int liveW = 0;
    private int liveH = 0;

    // Geometry of the last blit (letterboxed), so mouse handlers can map a
    // window pixel to a [0,1] point on the page. Updated every render.
    private int blitX, blitY, blitW, blitH;

    private long lastMovePostMs = 0L;

    public CinemaScreen(String cinemaId) {
        super(Text.literal("Cinema · " + cinemaId));
        this.cinemaId = cinemaId;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
        this.inputEndpoint = URI.create(RuntimeHost.httpUrl("/api/cinema/" + cinemaId + "/input"));
    }

    public String cinemaId() { return cinemaId; }

    @Override
    protected void init() {
        super.init();
        if (poller == null) {
            poller = new CinemaFramePoller(cinemaId);
            poller.start();
        }
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, width, height, 0xFF0A0C0F);

        if (!blitLiveFrame(ctx)) {
            ctx.drawCenteredTextWithShadow(textRenderer,
                "connecting to " + cinemaId + " …", width / 2, height / 2 - 4, 0xFFBBBBBB);
            ctx.drawCenteredTextWithShadow(textRenderer,
                "is ./agentcraft running and the page reachable?", width / 2, height / 2 + 10, 0xFF707070);
        }

        // Thin hint bar so the controls are discoverable without cluttering the page.
        ctx.fill(0, height - 14, width, height, 0x99000000);
        ctx.drawTextWithShadow(textRenderer,
            "esc close • click = click • scroll = scroll • type to type into the page",
            6, height - 12, 0xFFB0B0B0);
    }

    /** Upload the latest frame (on change) and blit it letterboxed. @return false if no frame yet. */
    private boolean blitLiveFrame(DrawContext ctx) {
        if (poller == null) return false;
        NativeImage img = poller.current();
        if (img == null) return false;

        long fid = poller.currentFrameId();
        if (fid != uploadedFrameId) {
            try {
                int w = img.getWidth();
                int h = img.getHeight();
                if (liveTex == null || liveW != w || liveH != h
                    || liveTex.getImage() == null
                    || liveTex.getImage().getWidth() != w
                    || liveTex.getImage().getHeight() != h) {
                    // First frame or the source dimensions changed — (re)build the
                    // texture. Dispose the old one first so we don't leak native memory.
                    if (liveTex != null) {
                        try { liveTex.close(); } catch (Throwable ignored) {}
                    }
                    NativeImage owned = new NativeImage(w, h, false);
                    owned.copyFrom(img);
                    liveTex = new NativeImageBackedTexture(() -> "cinema-live-" + cinemaId, owned);
                    liveW = w;
                    liveH = h;
                    liveTexRegistered = false;
                } else {
                    // Same dimensions — memcpy pixels into the existing image and re-upload.
                    liveTex.getImage().copyFrom(img);
                }
                liveTex.upload();
                if (!liveTexRegistered) {
                    MinecraftClient mc = MinecraftClient.getInstance();
                    if (mc != null && mc.getTextureManager() != null) {
                        mc.getTextureManager().registerTexture(LIVE_TEX_ID, liveTex);
                        liveTexRegistered = true;
                    }
                }
                uploadedFrameId = fid;
            } catch (Throwable t) {
                return false; // try again next frame
            }
        }
        if (!liveTexRegistered || liveW <= 0 || liveH <= 0) return false;

        // Letterbox: fit the source aspect inside the window, centered. Keeps the
        // page un-stretched and gives mouse mapping a clean rect to normalise over.
        double srcAspect = (double) liveW / liveH;
        double dstAspect = (double) width / height;
        if (dstAspect > srcAspect) {
            blitH = height;
            blitW = (int) Math.round(height * srcAspect);
        } else {
            blitW = width;
            blitH = (int) Math.round(width / srcAspect);
        }
        blitX = (width - blitW) / 2;
        blitY = (height - blitH) / 2;

        ctx.drawTexture(
            RenderPipelines.GUI_TEXTURED,
            LIVE_TEX_ID,
            blitX, blitY,
            0f, 0f,
            blitW, blitH,
            liveW, liveH,
            0xFFFFFFFF
        );
        return true;
    }

    // ── Coordinate mapping ──────────────────────────────────────────────────────

    /** Map a window pixel to a [0,1] page point, or null if it's in the letterbox bars. */
    private double[] normalise(double mx, double my) {
        if (blitW <= 0 || blitH <= 0) return null;
        double nx = (mx - blitX) / blitW;
        double ny = (my - blitY) / blitH;
        if (nx < 0 || nx > 1 || ny < 0 || ny > 1) return null;
        return new double[] { nx, ny };
    }

    // ── Mouse ───────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double[] n = normalise(click.x(), click.y());
        if (n != null) {
            String button = click.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT ? "right" : "left";
            postInput("{\"type\":\"click\",\"nx\":" + num(n[0]) + ",\"ny\":" + num(n[1])
                + ",\"button\":\"" + button + "\"}");
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        // CDP click is an atomic press+release, so true drag-select doesn't carry
        // through; we at least keep hover tracking the cursor while dragging.
        maybePostMove(click.x(), click.y());
        return true;
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        maybePostMove(mouseX, mouseY);
        super.mouseMoved(mouseX, mouseY);
    }

    private void maybePostMove(double mx, double my) {
        long now = System.currentTimeMillis();
        if (now - lastMovePostMs < MOVE_THROTTLE_MS) return;
        double[] n = normalise(mx, my);
        if (n == null) return;
        lastMovePostMs = now;
        postInput("{\"type\":\"move\",\"nx\":" + num(n[0]) + ",\"ny\":" + num(n[1]) + "}");
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (verticalAmount == 0) return false;
        double[] n = normalise(mouseX, mouseY);
        if (n == null) n = new double[] { 0.5, 0.5 }; // scroll the page even if over a bar
        // MC wheel-up is +verticalAmount; a page scrolls DOWN with +deltaY, so invert.
        double dy = -verticalAmount * SCROLL_STEP;
        postInput("{\"type\":\"scroll\",\"nx\":" + num(n[0]) + ",\"ny\":" + num(n[1])
            + ",\"dy\":" + num(dy) + "}");
        return true;
    }

    // ── Keyboard ─────────────────────────────────────────────────────────────────

    @Override
    public boolean keyPressed(KeyInput input) {
        int keyCode = input.key();

        // Esc (with or without shift) leaves the zoom view.
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }

        // Named non-printable keys the face's CDP map understands. Printable
        // characters flow through charTyped() as Input.insertText instead.
        String named = cdpKeyName(keyCode);
        if (named != null) {
            postInput("{\"type\":\"key\",\"key\":\"" + named + "\"}");
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        String s = input.asString();
        if (s == null || s.isEmpty()) return false;
        postInput("{\"type\":\"text\",\"text\":" + jsonString(s) + "}");
        return true;
    }

    /** GLFW key → the CDP key name face/headless-cinema.js knows, or null. */
    private static String cdpKeyName(int keyCode) {
        switch (keyCode) {
            case GLFW.GLFW_KEY_ENTER:
            case GLFW.GLFW_KEY_KP_ENTER:   return "Enter";
            case GLFW.GLFW_KEY_BACKSPACE:  return "Backspace";
            case GLFW.GLFW_KEY_TAB:        return "Tab";
            case GLFW.GLFW_KEY_DELETE:     return "Delete";
            case GLFW.GLFW_KEY_UP:         return "ArrowUp";
            case GLFW.GLFW_KEY_DOWN:       return "ArrowDown";
            case GLFW.GLFW_KEY_LEFT:       return "ArrowLeft";
            case GLFW.GLFW_KEY_RIGHT:      return "ArrowRight";
            case GLFW.GLFW_KEY_PAGE_UP:    return "PageUp";
            case GLFW.GLFW_KEY_PAGE_DOWN:  return "PageDown";
            case GLFW.GLFW_KEY_HOME:       return "Home";
            case GLFW.GLFW_KEY_END:        return "End";
            default:                       return null;
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────────

    @Override
    public boolean shouldPause() {
        return false; // keep the world (and our render loop) ticking at native fps
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false; // handled in keyPressed so we can also tear down the poller
    }

    @Override
    public void close() {
        // Always stop the poller — it owns a daemon thread and native-memory
        // NativeImages that must be released.
        if (poller != null) {
            try { poller.shutdown(); } catch (Throwable ignored) {}
            poller = null;
        }
        // Deliberately DON'T close liveTex here. It's registered with the
        // TextureManager under LIVE_TEX_ID, which closes the prior texture for us
        // when the next CinemaScreen re-registers under the same id. Closing it
        // ourselves would double-free it on that next open. Worst case between a
        // close and the next open is one resident ~4 MB texture — bounded and fine.
        super.close();
    }

    // ── HTTP + JSON ──────────────────────────────────────────────────────────────

    private void postInput(String body) {
        HttpRequest.Builder rb = HttpRequest.newBuilder(inputEndpoint)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .timeout(Duration.ofSeconds(3));
        String tok = RuntimeHost.token();
        if (!tok.isBlank()) rb.header("Authorization", "Bearer " + tok);
        // Fire-and-forget: a lost gesture is no worse than a dropped frame, and
        // the render thread must never block on the network.
        http.sendAsync(rb.build(), HttpResponse.BodyHandlers.discarding())
            .exceptionally(t -> null);
    }

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

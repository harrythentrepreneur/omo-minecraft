// OmoArtGen — offline asset generator for the in-game Omo HUD overlay.
//
// Run once via the bundled JDK 21:
//
//   ./.jdk/jdk-21.0.11+10/Contents/Home/bin/java tools/OmoArtGen.java \
//       src/main/resources/assets/agentcraft-terminal/textures/omo
//
// Produces six 256x256 transparent-background PNGs of Omo's face — one per
// HUD state plus a "blink" frame layered over idle.
//
// Rendering approach: Java2D with full anti-aliasing, bilinear interpolation,
// and stroke-pure circles. Cyan crystalline head, pink antenna orb, dark
// anime eyes with shine highlights, soft pink blush, small mouth. The
// design intentionally echoes face/public/avatar.js (CYAN 0x00e5ff, PINK
// 0xff66b3) so the in-game face reads as the same character as the
// browser hologram.
//
// We pull every shape out of pure java.awt primitives so the asset gen has
// no external deps — just the JDK. No PNG decoding needed at mod runtime;
// the mod loads these as standard texture resources.

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

public class OmoArtGen {

    static final int SIZE = 256;

    // Palette mirrors face/public/avatar.js so the in-game face matches the
    // browser hologram aesthetically. Keep these in sync if avatar.js drifts.
    static final Color CYAN_CORE   = new Color(0x73, 0xF0, 0xFF);
    static final Color CYAN_MID    = new Color(0x33, 0xD6, 0xFF);
    static final Color CYAN_RIM    = new Color(0x00, 0xB8, 0xD4);
    static final Color CYAN_GLOW   = new Color(0x00, 0xE5, 0xFF);
    static final Color PINK        = new Color(0xFF, 0x66, 0xB3);
    static final Color PINK_LIGHT  = new Color(0xFF, 0x99, 0xCC);
    static final Color PINK_DEEP   = new Color(0xE0, 0x3F, 0x95);
    static final Color EYE_DARK    = new Color(0x06, 0x0A, 0x18);
    static final Color MOUTH_PINK  = new Color(0xE0, 0x52, 0x8C);

    public static void main(String[] args) throws Exception {
        Path outDir = Path.of(args.length > 0 ? args[0] : ".");
        Files.createDirectories(outDir);

        write(outDir.resolve("omo_idle.png"),
              render(new FaceParams().withName("idle")));
        write(outDir.resolve("omo_blink.png"),
              render(new FaceParams().withName("blink").blink(true)));
        write(outDir.resolve("omo_listening.png"),
              render(new FaceParams().withName("listening")
                  .eyeWidenFactor(1.12).mouthOpen(0.35)
                  .haloColor(PINK_LIGHT).haloIntensity(0.55)));
        write(outDir.resolve("omo_thinking.png"),
              render(new FaceParams().withName("thinking")
                  .eyeOffsetX(0.07).eyeOffsetY(-0.04)
                  .headTilt(-0.08).mouthOpen(0.0).mouthCurve(-0.05)));
        write(outDir.resolve("omo_speaking.png"),
              render(new FaceParams().withName("speaking")
                  .mouthOpen(0.55).mouthWidth(1.15)));
        write(outDir.resolve("omo_celebrating.png"),
              render(new FaceParams().withName("celebrating")
                  .eyeArc(true).mouthOpen(0.6).mouthWidth(1.25)
                  .sparkles(true).haloColor(PINK_LIGHT).haloIntensity(0.7)));

        System.out.println("[OmoArtGen] wrote 6 PNGs to " + outDir.toAbsolutePath());
    }

    // ─── Parameters ────────────────────────────────────────────────────
    // A single struct controls every per-state expression knob. Keeps the
    // renderer code state-agnostic — only the param values differ per file.
    static class FaceParams {
        String name = "idle";
        boolean blink = false;
        double eyeWidenFactor = 1.0;    // 1.0 = neutral, >1 wider, <1 squinted
        double eyeOffsetX = 0.0;        // -1..1 fraction of head radius, look L/R
        double eyeOffsetY = 0.0;        // -1..1, look up/down
        boolean eyeArc = false;         // happy "^ ^" closed eyes for celebrating
        double mouthOpen = 0.18;        // 0 = closed line, 1 = wide oval
        double mouthWidth = 1.0;        // multiplier on neutral mouth width
        double mouthCurve = 0.05;       // + = smile, - = frown
        double headTilt = 0.0;          // radians, slight tilt for thinking
        Color  haloColor = CYAN_GLOW;
        double haloIntensity = 0.32;
        boolean sparkles = false;

        FaceParams withName(String n)     { this.name = n; return this; }
        FaceParams blink(boolean v)        { this.blink = v; return this; }
        FaceParams eyeWidenFactor(double v){ this.eyeWidenFactor = v; return this; }
        FaceParams eyeOffsetX(double v)    { this.eyeOffsetX = v; return this; }
        FaceParams eyeOffsetY(double v)    { this.eyeOffsetY = v; return this; }
        FaceParams eyeArc(boolean v)       { this.eyeArc = v; return this; }
        FaceParams mouthOpen(double v)     { this.mouthOpen = v; return this; }
        FaceParams mouthWidth(double v)    { this.mouthWidth = v; return this; }
        FaceParams mouthCurve(double v)    { this.mouthCurve = v; return this; }
        FaceParams headTilt(double v)      { this.headTilt = v; return this; }
        FaceParams haloColor(Color v)      { this.haloColor = v; return this; }
        FaceParams haloIntensity(double v) { this.haloIntensity = v; return this; }
        FaceParams sparkles(boolean v)     { this.sparkles = v; return this; }
    }

    // ─── Renderer ──────────────────────────────────────────────────────
    static BufferedImage render(FaceParams p) {
        BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

            // Apply head-tilt for the whole composition — feels alive,
            // especially on the thinking variant.
            g.rotate(p.headTilt, SIZE / 2.0, SIZE / 2.0);

            // Geometry: head is 70% of the canvas, centered slightly above
            // center to leave room for an antenna without clipping.
            double cx = SIZE / 2.0;
            double cy = SIZE * 0.56;
            double headR = SIZE * 0.34;

            drawHalo(g, cx, cy, headR, p);
            drawAntenna(g, cx, cy, headR);
            drawHead(g, cx, cy, headR);
            drawBlush(g, cx, cy, headR);
            drawEyes(g, cx, cy, headR, p);
            drawMouth(g, cx, cy, headR, p);
            drawHighlight(g, cx, cy, headR);
            if (p.sparkles) drawSparkles(g, cx, cy, headR);
        } finally {
            g.dispose();
        }
        return img;
    }

    // Soft outer cyan/pink glow — a radial gradient that fades to fully
    // transparent so the corner of the screen stays unmolested.
    static void drawHalo(Graphics2D g, double cx, double cy, double r, FaceParams p) {
        float outer = (float) (r * 2.0);
        float[] stops = { 0f, 0.45f, 1f };
        int alpha = (int) Math.round(255 * p.haloIntensity);
        Color base = p.haloColor;
        Color[] colors = {
            new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha),
            new Color(base.getRed(), base.getGreen(), base.getBlue(), (int) (alpha * 0.35)),
            new Color(base.getRed(), base.getGreen(), base.getBlue(), 0),
        };
        RadialGradientPaint paint = new RadialGradientPaint(
            new Point2D.Double(cx, cy), outer, stops, colors);
        Graphics2D gg = (Graphics2D) g.create();
        try {
            gg.setComposite(AlphaComposite.SrcOver);
            gg.setPaint(paint);
            gg.fill(new Ellipse2D.Double(cx - outer, cy - outer, outer * 2, outer * 2));
        } finally {
            gg.dispose();
        }
    }

    // Thin antenna line + glowing pink orb at the top of the head.
    static void drawAntenna(Graphics2D g, double cx, double cy, double r) {
        double topY = cy - r;
        double antennaH = r * 0.35;
        double orbY = topY - antennaH;
        double orbR = r * 0.11;

        // Antenna line — cyan, semi-transparent so it reads as crystal not metal.
        g.setStroke(new BasicStroke((float) (r * 0.05), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(CYAN_RIM.getRed(), CYAN_RIM.getGreen(), CYAN_RIM.getBlue(), 200));
        g.draw(new Line2D.Double(cx, topY + r * 0.02, cx, orbY + orbR * 0.4));

        // Orb outer halo (soft pink).
        float orbHalo = (float) (orbR * 2.6);
        float[] stops = {0f, 0.5f, 1f};
        Color[] cols = {
            new Color(PINK_LIGHT.getRed(), PINK_LIGHT.getGreen(), PINK_LIGHT.getBlue(), 170),
            new Color(PINK.getRed(), PINK.getGreen(), PINK.getBlue(), 70),
            new Color(PINK.getRed(), PINK.getGreen(), PINK.getBlue(), 0),
        };
        Graphics2D gg = (Graphics2D) g.create();
        try {
            gg.setPaint(new RadialGradientPaint(new Point2D.Double(cx, orbY), orbHalo, stops, cols));
            gg.fill(new Ellipse2D.Double(cx - orbHalo, orbY - orbHalo, orbHalo * 2, orbHalo * 2));
        } finally {
            gg.dispose();
        }

        // Orb body — pink radial for a glassy feel.
        Color[] orbCols = {
            new Color(255, 220, 235),
            PINK_LIGHT,
            PINK_DEEP,
        };
        RadialGradientPaint orbPaint = new RadialGradientPaint(
            new Point2D.Double(cx - orbR * 0.3, orbY - orbR * 0.4),
            (float) (orbR * 1.5),
            new float[]{0f, 0.5f, 1f}, orbCols);
        Graphics2D gh = (Graphics2D) g.create();
        try {
            gh.setPaint(orbPaint);
            gh.fill(new Ellipse2D.Double(cx - orbR, orbY - orbR, orbR * 2, orbR * 2));
            // Tiny white shine top-left of orb.
            gh.setColor(new Color(255, 255, 255, 220));
            gh.fill(new Ellipse2D.Double(cx - orbR * 0.55, orbY - orbR * 0.6, orbR * 0.45, orbR * 0.32));
        } finally {
            gh.dispose();
        }
    }

    // Cyan crystalline head — radial gradient from bright core to deep rim,
    // plus a subtle outer ring for the holo "shell" feel.
    static void drawHead(Graphics2D g, double cx, double cy, double r) {
        // Soft body shadow underneath for depth.
        Graphics2D gShadow = (Graphics2D) g.create();
        try {
            gShadow.setColor(new Color(0, 30, 50, 70));
            gShadow.fill(new Ellipse2D.Double(cx - r * 0.95, cy + r * 0.85, r * 1.9, r * 0.35));
        } finally {
            gShadow.dispose();
        }

        // Head body — radial gradient, slight off-center so it has a
        // light source (top-left).
        float[] stops = {0f, 0.55f, 1f};
        Color[] cols = {CYAN_CORE, CYAN_MID, CYAN_RIM};
        RadialGradientPaint paint = new RadialGradientPaint(
            new Point2D.Double(cx - r * 0.25, cy - r * 0.3),
            (float) (r * 1.25),
            stops, cols);
        Graphics2D gg = (Graphics2D) g.create();
        try {
            gg.setPaint(paint);
            gg.fill(new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2));
        } finally {
            gg.dispose();
        }

        // Holo rim — bright cyan outline so the silhouette pops against
        // any HUD background.
        g.setStroke(new BasicStroke((float) (r * 0.04), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(CYAN_CORE.getRed(), CYAN_CORE.getGreen(), CYAN_CORE.getBlue(), 180));
        g.draw(new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2));
    }

    // Pink blush dots on each cheek — small, soft, alpha-fades to nothing.
    static void drawBlush(Graphics2D g, double cx, double cy, double r) {
        double blushR = r * 0.16;
        double bx = r * 0.55;
        double by = r * 0.18;
        for (int side = -1; side <= 1; side += 2) {
            double bxc = cx + side * bx;
            double byc = cy + by;
            RadialGradientPaint p = new RadialGradientPaint(
                new Point2D.Double(bxc, byc),
                (float) blushR,
                new float[]{0f, 1f},
                new Color[]{
                    new Color(PINK.getRed(), PINK.getGreen(), PINK.getBlue(), 150),
                    new Color(PINK.getRed(), PINK.getGreen(), PINK.getBlue(), 0),
                });
            Graphics2D gg = (Graphics2D) g.create();
            try {
                gg.setPaint(p);
                gg.fill(new Ellipse2D.Double(bxc - blushR, byc - blushR, blushR * 2, blushR * 2));
            } finally {
                gg.dispose();
            }
        }
    }

    // Two large round dark eyes with tiny white shine highlights. Honors
    // blink / arc / pupil-offset params from the FaceParams struct.
    static void drawEyes(Graphics2D g, double cx, double cy, double r, FaceParams p) {
        double eyeSepX = r * 0.42;
        double eyeY    = cy - r * 0.06;
        double eyeRX   = r * 0.18 * p.eyeWidenFactor;
        double eyeRY   = r * 0.22 * p.eyeWidenFactor;

        for (int side = -1; side <= 1; side += 2) {
            double exc = cx + side * eyeSepX;

            if (p.blink) {
                // Closed lid — a thick horizontal stroke arc.
                g.setStroke(new BasicStroke((float) (r * 0.07), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(EYE_DARK);
                g.draw(new Arc2D.Double(
                    exc - eyeRX, eyeY - eyeRY * 0.25, eyeRX * 2, eyeRY * 0.5,
                    180, 180, Arc2D.OPEN));
                continue;
            }
            if (p.eyeArc) {
                // Happy "^" upturned arc — for celebrating.
                g.setStroke(new BasicStroke((float) (r * 0.08), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(EYE_DARK);
                g.draw(new Arc2D.Double(
                    exc - eyeRX, eyeY - eyeRY * 0.6, eyeRX * 2, eyeRY * 1.2,
                    20, 140, Arc2D.OPEN));
                continue;
            }

            // White socket so the dark pupil reads as anime-style eye, not
            // a hole. Same color as head shading edge for cohesion.
            Graphics2D gWhite = (Graphics2D) g.create();
            try {
                gWhite.setColor(new Color(245, 252, 255, 240));
                gWhite.fill(new Ellipse2D.Double(
                    exc - eyeRX * 1.05, eyeY - eyeRY * 1.05,
                    eyeRX * 2.1, eyeRY * 2.1));
            } finally {
                gWhite.dispose();
            }

            // Pupil — dark navy, offset slightly by eyeOffsetX/Y so Omo
            // can look around.
            double pupilOffX = eyeRX * p.eyeOffsetX;
            double pupilOffY = eyeRY * p.eyeOffsetY;
            double pupilCX = exc + pupilOffX;
            double pupilCY = eyeY + pupilOffY;
            g.setColor(EYE_DARK);
            g.fill(new Ellipse2D.Double(
                pupilCX - eyeRX, pupilCY - eyeRY,
                eyeRX * 2, eyeRY * 2));

            // Shine — small offset white oval top-right of pupil.
            g.setColor(new Color(255, 255, 255, 240));
            double shineR = eyeRX * 0.32;
            g.fill(new Ellipse2D.Double(
                pupilCX + eyeRX * 0.18, pupilCY - eyeRY * 0.5,
                shineR * 1.8, shineR * 1.4));
            // Tiny secondary shine.
            double shine2 = eyeRX * 0.14;
            g.fill(new Ellipse2D.Double(
                pupilCX - eyeRX * 0.35, pupilCY + eyeRY * 0.32,
                shine2 * 1.6, shine2 * 1.2));
        }
    }

    // Small mouth. If open, draws a pink oval; if closed, a soft smile arc.
    static void drawMouth(Graphics2D g, double cx, double cy, double r, FaceParams p) {
        double mouthY = cy + r * 0.42;
        double baseW  = r * 0.22 * p.mouthWidth;

        if (p.mouthOpen > 0.05) {
            double mouthH = r * 0.08 + r * 0.22 * p.mouthOpen;
            g.setColor(MOUTH_PINK);
            g.fill(new Ellipse2D.Double(
                cx - baseW / 2, mouthY - mouthH / 2,
                baseW, mouthH));
            // Tongue / lower lip hint — a slightly brighter inner oval at bottom.
            g.setColor(new Color(PINK.getRed(), PINK.getGreen(), PINK.getBlue(), 200));
            g.fill(new Ellipse2D.Double(
                cx - baseW * 0.35, mouthY + mouthH * 0.05,
                baseW * 0.7, mouthH * 0.45));
        } else {
            // Closed smile — gentle curve. mouthCurve > 0 = smile, < 0 = frown.
            g.setStroke(new BasicStroke((float) (r * 0.05), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(MOUTH_PINK);
            Path2D path = new Path2D.Double();
            double dropY = r * 0.06 * Math.signum(p.mouthCurve);
            path.moveTo(cx - baseW / 2, mouthY);
            path.quadTo(cx, mouthY + dropY * 2, cx + baseW / 2, mouthY);
            g.draw(path);
        }
    }

    // A soft white highlight near the top-left of the head to suggest a
    // light source above the camera — pure glassy crystal vibe.
    static void drawHighlight(Graphics2D g, double cx, double cy, double r) {
        double hxc = cx - r * 0.45;
        double hyc = cy - r * 0.55;
        double hw = r * 0.55;
        double hh = r * 0.35;
        RadialGradientPaint paint = new RadialGradientPaint(
            new Point2D.Double(hxc, hyc),
            (float) Math.max(hw, hh),
            new float[]{0f, 1f},
            new Color[]{
                new Color(255, 255, 255, 140),
                new Color(255, 255, 255, 0),
            });
        Graphics2D gg = (Graphics2D) g.create();
        try {
            gg.setPaint(paint);
            gg.fill(new Ellipse2D.Double(hxc - hw, hyc - hh, hw * 2, hh * 2));
        } finally {
            gg.dispose();
        }
    }

    // Small 4-point sparkles around the head, used by the celebrating frame.
    static void drawSparkles(Graphics2D g, double cx, double cy, double r) {
        double[][] spots = {
            { cx - r * 1.1, cy - r * 0.7, r * 0.10 },
            { cx + r * 1.05, cy - r * 0.5, r * 0.08 },
            { cx + r * 0.9,  cy + r * 0.7, r * 0.07 },
            { cx - r * 0.95, cy + r * 0.55, r * 0.09 },
        };
        for (double[] s : spots) {
            sparkle(g, s[0], s[1], s[2]);
        }
    }

    static void sparkle(Graphics2D g, double cx, double cy, double size) {
        Path2D p = new Path2D.Double();
        double a = size;
        double b = size * 0.18;
        p.moveTo(cx, cy - a);
        p.lineTo(cx + b, cy - b);
        p.lineTo(cx + a, cy);
        p.lineTo(cx + b, cy + b);
        p.lineTo(cx, cy + a);
        p.lineTo(cx - b, cy + b);
        p.lineTo(cx - a, cy);
        p.lineTo(cx - b, cy - b);
        p.closePath();
        g.setColor(new Color(255, 230, 245, 220));
        g.fill(p);
        // Inner bright core.
        double c = size * 0.35;
        g.setColor(new Color(255, 255, 255, 230));
        g.fill(new Ellipse2D.Double(cx - c, cy - c, c * 2, c * 2));
    }

    static void write(Path path, BufferedImage img) throws Exception {
        ImageIO.write(img, "PNG", path.toFile());
        System.out.println("  wrote " + path.getFileName() + " (" + new File(path.toString()).length() + "b)");
    }
}

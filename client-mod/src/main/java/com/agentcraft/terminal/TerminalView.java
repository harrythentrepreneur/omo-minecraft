package com.agentcraft.terminal;

import net.minecraft.client.MinecraftClient;

/**
 * Per-screen view state shared by {@link TerminalScreen} and
 * {@link TeamTerminalScreen}: the scrollback viewport offset and the
 * click-drag text selection, plus the grid geometry needed to map mouse
 * coordinates to cells. Kept in one place so both terminals scroll, select and
 * copy identically.
 *
 * <p>Coordinates here are <b>visible</b> rows/cols (0 = top-left of the on-
 * screen grid). Selection is a linear span (like a text editor): everything
 * between the anchor and focus cells in reading order.
 */
public final class TerminalView {

    /** Lines scrolled up from the live bottom (0 = following live output). */
    public int viewOffset;
    private int lastSbSize;

    private boolean hasSelection;
    private boolean selecting;
    private int aRow, aCol, fRow, fCol; // anchor + focus, visible coords

    // Grid geometry, refreshed every render() so mouse math matches the draw.
    private int gx0, gy0, cellW, cellH, cols, rows;

    /** Refresh the on-screen geometry; call once per render before drawing cells. */
    public void setGeometry(int x0, int y0, int cellW, int cellH, int cols, int rows) {
        this.gx0 = x0; this.gy0 = y0;
        this.cellW = cellW; this.cellH = cellH;
        this.cols = cols; this.rows = rows;
    }

    /**
     * Keep a scrolled-up view pinned to the same content as new lines stream
     * into scrollback. Call once per render with the current scrollback size.
     */
    public void pin(int sbSize) {
        if (viewOffset > 0 && sbSize > lastSbSize) {
            viewOffset = Math.min(viewOffset + (sbSize - lastSbSize), sbSize);
        }
        lastSbSize = sbSize;
    }

    public boolean scrolledUp() { return viewOffset > 0; }

    /** Scroll by whole lines (positive = toward older history). Clamped. */
    public void scrollLines(int delta, int sbSize) {
        viewOffset = Math.max(0, Math.min(viewOffset + delta, sbSize));
    }

    /** Return to the live bottom and drop any selection (e.g. on keypress). */
    public void snapToBottom() {
        viewOffset = 0;
        hasSelection = false;
        selecting = false;
    }

    /** Full reset — used when a screen swaps to a different agent's buffer. */
    public void reset() {
        viewOffset = 0;
        lastSbSize = 0;
        hasSelection = false;
        selecting = false;
    }

    // ── selection ─────────────────────────────────────────────────────────────

    /** Begin a selection at the cell under (mx,my) if it lands on the grid. */
    public boolean beginSelection(double mx, double my) {
        int[] cell = cellAt(mx, my, false);
        if (cell == null) { hasSelection = false; selecting = false; return false; }
        aRow = fRow = cell[0];
        aCol = fCol = cell[1];
        hasSelection = true;
        selecting = true;
        return true;
    }

    /** Extend the in-progress selection to (mx,my) (clamped to the grid). */
    public boolean dragSelection(double mx, double my) {
        if (!selecting) return false;
        int[] cell = cellAt(mx, my, true);
        if (cell == null) return false;
        fRow = cell[0];
        fCol = cell[1];
        return true;
    }

    public void endSelection() { selecting = false; }

    public boolean isSelected(int r, int c) {
        if (!hasSelection || cols <= 0) return false;
        int lo = Math.min(aRow * cols + aCol, fRow * cols + fCol);
        int hi = Math.max(aRow * cols + aCol, fRow * cols + fCol);
        int idx = r * cols + c;
        return idx >= lo && idx <= hi;
    }

    /** Copy the current selection (trailing spaces trimmed) to the clipboard. */
    public void copyToClipboard(TerminalBuffer buffer) {
        if (!hasSelection || buffer == null || cols <= 0) return;
        int lo = Math.min(aRow * cols + aCol, fRow * cols + fCol);
        int hi = Math.max(aRow * cols + aCol, fRow * cols + fCol);
        StringBuilder out = new StringBuilder();
        StringBuilder line = new StringBuilder();
        int curRow = lo / cols;
        for (int idx = lo; idx <= hi; idx++) {
            int r = idx / cols, c = idx % cols;
            if (r != curRow) {
                appendTrimmed(out, line).append('\n');
                line.setLength(0);
                curRow = r;
            }
            char ch = buffer.cellAtView(r, c, viewOffset).ch();
            line.append(ch == 0 ? ' ' : ch);
        }
        appendTrimmed(out, line);
        MinecraftClient.getInstance().keyboard.setClipboard(out.toString());
    }

    private static StringBuilder appendTrimmed(StringBuilder out, StringBuilder line) {
        int end = line.length();
        while (end > 0 && line.charAt(end - 1) == ' ') end--;
        return out.append(line, 0, end);
    }

    /** Map a mouse position to {row,col}; null if off-grid (unless {@code clamp}). */
    private int[] cellAt(double mx, double my, boolean clamp) {
        if (cellW <= 0 || cellH <= 0 || cols <= 0 || rows <= 0) return null;
        int c = (int) Math.floor((mx - gx0) / cellW);
        int r = (int) Math.floor((my - gy0) / cellH);
        if (clamp) {
            c = Math.max(0, Math.min(c, cols - 1));
            r = Math.max(0, Math.min(r, rows - 1));
        } else if (r < 0 || r >= rows || c < 0 || c >= cols) {
            return null;
        }
        return new int[]{ r, c };
    }
}

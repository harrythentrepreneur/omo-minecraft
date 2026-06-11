package com.agentcraft.terminal;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A small VT100-ish terminal emulator. Holds the cell grid + cursor + parser
 * state in one place so the rendering code never has to touch escape sequences.
 *
 * Scope (intentional MVP, see Omo_x_Minecraft_PRD_EN.md §12.3):
 *   - text + CR/LF/BS/TAB/BEL
 *   - CSI: cursor moves (A/B/C/D/G/H/f), erase (J/K), SGR (m), DA (c), cursor
 *     visibility (?25), alt screen (?1049), bracketed paste (?2004),
 *     app cursor keys (?1), scroll region (r), save/restore (s/u)
 *   - OSC swallowed (window title etc. — ignored)
 *   - everything unknown is dropped silently rather than printed
 *
 * Anything beyond this is deliberately out of scope for the first cut — enough
 * to drive a shell prompt + `claude` interactively without garbage on screen.
 */
public class TerminalBuffer {

    public static final int SCROLLBACK_MAX = 2000;

    // Parser states
    private static final int S_GROUND  = 0;
    private static final int S_ESC     = 1;
    private static final int S_CSI     = 2;
    private static final int S_OSC     = 3;
    private static final int S_CHARSET = 4; // ESC ( or ESC ) — next char ignored

    // 16-color ANSI palette (xterm defaults, close enough)
    private static final int[] PALETTE_16 = {
        0x000000, 0xCC0000, 0x00CC00, 0xCCCC00, 0x0066CC, 0xCC00CC, 0x00CCCC, 0xCCCCCC,
        0x808080, 0xFF6666, 0x66FF66, 0xFFFF66, 0x6699FF, 0xFF66FF, 0x66FFFF, 0xFFFFFF
    };

    private int cols, rows;
    private Cell[][] main;
    private Cell[][] alt;
    private boolean inAlt;

    // Scrollback as a fixed ring buffer so the renderer can index any line in
    // O(1) (an ArrayDeque is O(n) by index). Index 0 == oldest retained line.
    private final Cell[][] sb = new Cell[SCROLLBACK_MAX][];
    private int sbHead;   // ring index of the oldest line
    private int sbCount;  // number of retained lines (<= SCROLLBACK_MAX)

    // Leftover bytes of an incomplete UTF-8 sequence carried to the next feed().
    private static final byte[] NO_CARRY = new byte[0];
    private byte[] carry = NO_CARRY;

    private int row, col;
    private int savedRow, savedCol;
    private int fg = Cell.DEFAULT_FG;
    private int bg = Cell.DEFAULT_BG;
    private int sgrFlags;

    private boolean cursorVisible = true;
    private boolean autoWrap = true;
    private boolean wrapPending;
    private int scrollTop, scrollBottom;
    private boolean bracketedPaste;
    private boolean appCursorKeys;

    private int parseState = S_GROUND;
    private final StringBuilder csiBuf = new StringBuilder(16);
    private final StringBuilder oscBuf = new StringBuilder(64);
    private boolean csiPrivate; // saw '?'
    private char csiIntermediate;

    private long renderRev;

    public TerminalBuffer(int cols, int rows) {
        resize(cols, rows);
    }

    public synchronized void resize(int newCols, int newRows) {
        if (newCols < 1) newCols = 1;
        if (newRows < 1) newRows = 1;
        Cell[][] newMain = new Cell[newRows][newCols];
        Cell[][] newAlt = new Cell[newRows][newCols];
        for (int r = 0; r < newRows; r++) {
            for (int c = 0; c < newCols; c++) {
                newMain[r][c] = Cell.EMPTY;
                newAlt[r][c] = Cell.EMPTY;
            }
        }
        // Best-effort copy from the previous grids (top-left origin).
        if (main != null) {
            int rCopy = Math.min(rows, newRows);
            int cCopy = Math.min(cols, newCols);
            for (int r = 0; r < rCopy; r++) {
                System.arraycopy(main[r], 0, newMain[r], 0, cCopy);
                System.arraycopy(alt[r], 0, newAlt[r], 0, cCopy);
            }
        }
        main = newMain;
        alt = newAlt;
        cols = newCols;
        rows = newRows;
        scrollTop = 0;
        scrollBottom = rows - 1;
        if (row >= rows) row = rows - 1;
        if (col >= cols) col = cols - 1;
        wrapPending = false;
        renderRev++;
    }

    public int cols() { return cols; }
    public int rows() { return rows; }
    public int cursorRow() { return row; }
    public int cursorCol() { return col; }
    public boolean cursorVisible() { return cursorVisible; }
    public boolean bracketedPaste() { return bracketedPaste; }
    public boolean appCursorKeys() { return appCursorKeys; }
    public long renderRev() { return renderRev; }

    public synchronized Cell cellAt(int r, int c) {
        if (r < 0 || r >= rows || c < 0 || c >= cols) return Cell.EMPTY;
        return active()[r][c];
    }

    /** Number of retained scrollback lines (max {@link #SCROLLBACK_MAX}). */
    public synchronized int scrollbackSize() { return sbCount; }

    /**
     * The cell at visible row {@code vr} (0 = top of the viewport), column
     * {@code c}, when the view is scrolled up by {@code viewOffset} lines from
     * the live bottom. {@code viewOffset == 0} is the live view (== {@link
     * #cellAt}); larger offsets pull rows down from scrollback. The viewport is
     * the last {@code rows} lines of (scrollback ++ active grid), shifted up.
     */
    public synchronized Cell cellAtView(int vr, int c, int viewOffset) {
        if (vr < 0 || vr >= rows || c < 0) return Cell.EMPTY;
        int off = Math.max(0, Math.min(viewOffset, sbCount));
        int idx = (sbCount - off) + vr;          // index into scrollback ++ active
        if (idx >= sbCount) {                    // live grid region
            int ar = idx - sbCount;
            if (ar < 0 || ar >= rows || c >= cols) return Cell.EMPTY;
            return active()[ar][c];
        }
        Cell[] line = sb[(sbHead + idx) % SCROLLBACK_MAX];  // scrollback region
        return (line != null && c < line.length) ? line[c] : Cell.EMPTY;
    }

    private Cell[][] active() { return inAlt ? alt : main; }

    // ── Input feed ─────────────────────────────────────────────────────────

    public synchronized void feed(byte[] data, int len) {
        // Decode UTF-8 across read() boundaries: a multi-byte sequence split
        // between two PTY reads (common for box-drawing glyphs / emoji in
        // claude's TUI) used to garble into U+FFFD. We prepend any carried
        // bytes, decode only up to the last *complete* sequence, and carry the
        // incomplete tail (≤3 bytes) into the next feed().
        byte[] buf;
        int total;
        if (carry.length == 0) {
            buf = data; total = len;
        } else {
            total = carry.length + len;
            buf = new byte[total];
            System.arraycopy(carry, 0, buf, 0, carry.length);
            System.arraycopy(data, 0, buf, carry.length, len);
        }
        int complete = completePrefixLength(buf, total);
        String s = new String(buf, 0, complete, StandardCharsets.UTF_8);
        carry = complete < total ? Arrays.copyOfRange(buf, complete, total) : NO_CARRY;
        for (int i = 0; i < s.length(); i++) {
            feedChar(s.charAt(i));
        }
        renderRev++;
    }

    /**
     * Length of the longest prefix of {@code b[0..len)} that ends on a complete
     * UTF-8 sequence. If the buffer's tail is an incomplete multi-byte sequence,
     * returns the index where that tail begins (so it can be carried).
     */
    private static int completePrefixLength(byte[] b, int len) {
        if (len == 0) return 0;
        for (int i = len - 1, back = 0; i >= 0 && back < 4; i--, back++) {
            int x = b[i] & 0xFF;
            if ((x & 0xC0) == 0x80) continue;     // continuation byte — keep scanning back
            int need;                             // bytes the lead byte at i implies
            if (x < 0x80) need = 1;
            else if ((x & 0xE0) == 0xC0) need = 2;
            else if ((x & 0xF0) == 0xE0) need = 3;
            else if ((x & 0xF8) == 0xF0) need = 4;
            else need = 1;                        // invalid lead — let the decoder replace it
            return (len - i >= need) ? len : i;   // complete → all; incomplete → carry from i
        }
        return len;                               // no lead byte in last 4 — decode everything
    }

    private void feedChar(char ch) {
        switch (parseState) {
            case S_ESC -> handleEsc(ch);
            case S_CSI -> handleCsi(ch);
            case S_OSC -> handleOsc(ch);
            case S_CHARSET -> parseState = S_GROUND;
            default -> handleGround(ch);
        }
    }

    private void handleGround(char ch) {
        switch (ch) {
            case 0x07 -> { /* BEL — ignore */ }
            case 0x08 -> { // BS
                if (col > 0) col--;
                wrapPending = false;
            }
            case 0x09 -> { // TAB → next multiple of 8
                int next = Math.min(cols - 1, (col + 8) & ~7);
                col = next;
                wrapPending = false;
            }
            case 0x0A, 0x0B, 0x0C -> { // LF/VT/FF
                lineFeed();
                wrapPending = false;
            }
            case 0x0D -> { // CR
                col = 0;
                wrapPending = false;
            }
            case 0x1B -> {
                parseState = S_ESC;
                csiBuf.setLength(0);
                oscBuf.setLength(0);
                csiPrivate = false;
                csiIntermediate = 0;
            }
            default -> {
                if (ch < 0x20) return; // drop other C0 controls
                putChar(ch);
            }
        }
    }

    private void putChar(char ch) {
        if (autoWrap && wrapPending) {
            col = 0;
            lineFeed();
            wrapPending = false;
        }
        if (col >= cols) col = cols - 1;
        if (row >= rows) row = rows - 1;
        active()[row][col] = new Cell(ch, fg, bg, sgrFlags);
        if (col + 1 >= cols) {
            wrapPending = autoWrap;
        } else {
            col++;
        }
    }

    private void lineFeed() {
        if (row == scrollBottom) {
            scrollUp(1);
        } else if (row < rows - 1) {
            row++;
        }
    }

    private void scrollUp(int n) {
        Cell[][] g = active();
        for (int k = 0; k < n; k++) {
            // Only push the main buffer's top line into scrollback.
            if (!inAlt && scrollTop == 0) pushScrollback(g[scrollTop]);
            for (int r = scrollTop; r < scrollBottom; r++) {
                g[r] = g[r + 1];
            }
            g[scrollBottom] = blankRow();
        }
    }

    private void scrollDown(int n) {
        Cell[][] g = active();
        for (int k = 0; k < n; k++) {
            for (int r = scrollBottom; r > scrollTop; r--) {
                g[r] = g[r - 1];
            }
            g[scrollTop] = blankRow();
        }
    }

    private Cell[] blankRow() {
        Cell[] r = new Cell[cols];
        for (int c = 0; c < cols; c++) r[c] = Cell.EMPTY;
        return r;
    }

    private void pushScrollback(Cell[] line) {
        if (sbCount < SCROLLBACK_MAX) {
            sb[(sbHead + sbCount) % SCROLLBACK_MAX] = line;
            sbCount++;
        } else {
            sb[sbHead] = line;                       // overwrite the oldest
            sbHead = (sbHead + 1) % SCROLLBACK_MAX;
        }
    }

    private void clearScrollback() { sbHead = 0; sbCount = 0; }

    // ── Escape / CSI / OSC dispatch ────────────────────────────────────────

    private void handleEsc(char ch) {
        switch (ch) {
            case '[' -> parseState = S_CSI;
            case ']' -> parseState = S_OSC;
            case '(' , ')' , '*' , '+' -> parseState = S_CHARSET;
            case '7' -> { savedRow = row; savedCol = col; parseState = S_GROUND; }
            case '8' -> { row = savedRow; col = savedCol; parseState = S_GROUND; }
            case 'D' -> { lineFeed(); parseState = S_GROUND; }
            case 'M' -> { // RI — reverse index
                if (row == scrollTop) scrollDown(1);
                else row = Math.max(0, row - 1);
                parseState = S_GROUND;
            }
            case 'E' -> { col = 0; lineFeed(); parseState = S_GROUND; }
            case 'c' -> { reset(); parseState = S_GROUND; }
            default -> parseState = S_GROUND;
        }
    }

    private void handleCsi(char ch) {
        if (ch == '?' && csiBuf.length() == 0) {
            csiPrivate = true;
            return;
        }
        if ((ch >= '0' && ch <= '9') || ch == ';') {
            csiBuf.append(ch);
            return;
        }
        if (ch >= ' ' && ch <= '/') {
            csiIntermediate = ch;
            return;
        }
        if (ch < '@' || ch > '~') {
            // Bad CSI byte — bail.
            parseState = S_GROUND;
            return;
        }
        dispatchCsi(ch);
        parseState = S_GROUND;
    }

    private void handleOsc(char ch) {
        if (ch == 0x07) { // BEL terminator
            parseState = S_GROUND;
            return;
        }
        if (ch == 0x1B) { // wait for the following backslash
            return;
        }
        if (ch == '\\' && !oscBuf.isEmpty() && oscBuf.charAt(oscBuf.length() - 1) == 0x1B) {
            oscBuf.deleteCharAt(oscBuf.length() - 1);
            parseState = S_GROUND;
            return;
        }
        if (oscBuf.length() < 256) oscBuf.append(ch);
    }

    private int[] csiParams(int defaultIfMissing) {
        if (csiBuf.isEmpty()) return new int[]{ defaultIfMissing };
        String[] parts = csiBuf.toString().split(";", -1);
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) out[i] = defaultIfMissing;
            else {
                try { out[i] = Integer.parseInt(parts[i]); }
                catch (NumberFormatException e) { out[i] = defaultIfMissing; }
            }
        }
        return out;
    }

    private void dispatchCsi(char op) {
        if (csiPrivate) {
            dispatchPrivateCsi(op);
            return;
        }
        switch (op) {
            case 'A' -> row = Math.max(0, row - Math.max(1, csiParams(1)[0]));
            case 'B' -> row = Math.min(rows - 1, row + Math.max(1, csiParams(1)[0]));
            case 'C' -> col = Math.min(cols - 1, col + Math.max(1, csiParams(1)[0]));
            case 'D' -> col = Math.max(0, col - Math.max(1, csiParams(1)[0]));
            case 'E' -> { col = 0; row = Math.min(rows - 1, row + Math.max(1, csiParams(1)[0])); }
            case 'F' -> { col = 0; row = Math.max(0, row - Math.max(1, csiParams(1)[0])); }
            case 'G' -> col = clamp(csiParams(1)[0] - 1, 0, cols - 1);
            case 'H', 'f' -> {
                int[] p = csiParams(1);
                int r = clamp((p.length > 0 ? p[0] : 1) - 1, 0, rows - 1);
                int c = clamp((p.length > 1 ? p[1] : 1) - 1, 0, cols - 1);
                row = r; col = c; wrapPending = false;
            }
            case 'J' -> eraseDisplay(csiParams(0)[0]);
            case 'K' -> eraseLine(csiParams(0)[0]);
            case 'L' -> insertLines(Math.max(1, csiParams(1)[0]));
            case 'M' -> deleteLines(Math.max(1, csiParams(1)[0]));
            case 'P' -> deleteChars(Math.max(1, csiParams(1)[0]));
            case '@' -> insertChars(Math.max(1, csiParams(1)[0]));
            case 'X' -> {
                int n = Math.max(1, csiParams(1)[0]);
                for (int i = 0; i < n && col + i < cols; i++) active()[row][col + i] = Cell.EMPTY;
            }
            case 'S' -> scrollUp(Math.max(1, csiParams(1)[0]));
            case 'T' -> scrollDown(Math.max(1, csiParams(1)[0]));
            case 'd' -> row = clamp(csiParams(1)[0] - 1, 0, rows - 1);
            case 'm' -> applySgr(csiParams(0));
            case 'r' -> {
                int[] p = csiParams(1);
                int t = clamp((p.length > 0 ? p[0] : 1) - 1, 0, rows - 1);
                int b = clamp((p.length > 1 ? p[1] : rows) - 1, 0, rows - 1);
                if (t < b) { scrollTop = t; scrollBottom = b; }
                row = 0; col = 0;
            }
            case 's' -> { savedRow = row; savedCol = col; }
            case 'u' -> { row = savedRow; col = savedCol; }
            default -> { /* ignore */ }
        }
    }

    private void dispatchPrivateCsi(char op) {
        int[] params = csiParams(0);
        for (int p : params) {
            boolean enable = op == 'h';
            switch (p) {
                case 1    -> appCursorKeys = enable;
                case 7    -> autoWrap = enable;
                case 25   -> cursorVisible = enable;
                case 47, 1047, 1048, 1049 -> {
                    if (enable && !inAlt) {
                        savedRow = row; savedCol = col;
                        inAlt = true;
                        // Clear alt buffer on enter (some apps assume this).
                        for (int r = 0; r < rows; r++)
                            for (int c = 0; c < cols; c++) alt[r][c] = Cell.EMPTY;
                        row = 0; col = 0;
                    } else if (!enable && inAlt) {
                        inAlt = false;
                        row = savedRow; col = savedCol;
                    }
                }
                case 2004 -> bracketedPaste = enable;
                default   -> { /* ignore */ }
            }
        }
    }

    private void eraseDisplay(int mode) {
        switch (mode) {
            case 0 -> {
                for (int c = col; c < cols; c++) active()[row][c] = Cell.EMPTY;
                for (int r = row + 1; r < rows; r++)
                    for (int c = 0; c < cols; c++) active()[r][c] = Cell.EMPTY;
            }
            case 1 -> {
                for (int c = 0; c <= col && c < cols; c++) active()[row][c] = Cell.EMPTY;
                for (int r = 0; r < row; r++)
                    for (int c = 0; c < cols; c++) active()[r][c] = Cell.EMPTY;
            }
            case 2, 3 -> {
                for (int r = 0; r < rows; r++)
                    for (int c = 0; c < cols; c++) active()[r][c] = Cell.EMPTY;
                if (mode == 3) clearScrollback();
            }
            default -> { }
        }
    }

    private void eraseLine(int mode) {
        switch (mode) {
            case 0 -> { for (int c = col; c < cols; c++) active()[row][c] = Cell.EMPTY; }
            case 1 -> { for (int c = 0; c <= col && c < cols; c++) active()[row][c] = Cell.EMPTY; }
            case 2 -> { for (int c = 0; c < cols; c++) active()[row][c] = Cell.EMPTY; }
            default -> { }
        }
    }

    private void insertLines(int n) {
        if (row < scrollTop || row > scrollBottom) return;
        Cell[][] g = active();
        for (int k = 0; k < n; k++) {
            for (int r = scrollBottom; r > row; r--) g[r] = g[r - 1];
            g[row] = blankRow();
        }
    }

    private void deleteLines(int n) {
        if (row < scrollTop || row > scrollBottom) return;
        Cell[][] g = active();
        for (int k = 0; k < n; k++) {
            for (int r = row; r < scrollBottom; r++) g[r] = g[r + 1];
            g[scrollBottom] = blankRow();
        }
    }

    private void deleteChars(int n) {
        Cell[] line = active()[row];
        for (int c = col; c < cols - n; c++) line[c] = line[c + n];
        for (int c = Math.max(col, cols - n); c < cols; c++) line[c] = Cell.EMPTY;
    }

    private void insertChars(int n) {
        Cell[] line = active()[row];
        for (int c = cols - 1; c >= col + n; c--) line[c] = line[c - n];
        for (int c = col; c < Math.min(col + n, cols); c++) line[c] = Cell.EMPTY;
    }

    private void applySgr(int[] params) {
        if (params.length == 0) { resetSgr(); return; }
        for (int i = 0; i < params.length; i++) {
            int p = params[i];
            switch (p) {
                case 0 -> resetSgr();
                case 1 -> sgrFlags |= Cell.FLAG_BOLD;
                case 4 -> sgrFlags |= Cell.FLAG_UNDERLINE;
                case 7 -> sgrFlags |= Cell.FLAG_REVERSE;
                case 22 -> sgrFlags &= ~Cell.FLAG_BOLD;
                case 24 -> sgrFlags &= ~Cell.FLAG_UNDERLINE;
                case 27 -> sgrFlags &= ~Cell.FLAG_REVERSE;
                case 39 -> fg = Cell.DEFAULT_FG;
                case 49 -> bg = Cell.DEFAULT_BG;
                case 38, 48 -> {
                    // 38;5;N  or  38;2;R;G;B
                    if (i + 1 >= params.length) return;
                    int mode = params[++i];
                    int color;
                    if (mode == 5) {
                        if (i + 1 >= params.length) return;
                        color = palette256(params[++i]);
                    } else if (mode == 2) {
                        if (i + 3 >= params.length) return;
                        int r = params[++i] & 0xFF;
                        int g = params[++i] & 0xFF;
                        int b = params[++i] & 0xFF;
                        color = (r << 16) | (g << 8) | b;
                    } else {
                        return;
                    }
                    if (p == 38) fg = color; else bg = color;
                }
                default -> {
                    if (p >= 30 && p <= 37) fg = PALETTE_16[p - 30];
                    else if (p >= 40 && p <= 47) bg = PALETTE_16[p - 40];
                    else if (p >= 90 && p <= 97) fg = PALETTE_16[8 + p - 90];
                    else if (p >= 100 && p <= 107) bg = PALETTE_16[8 + p - 100];
                }
            }
        }
    }

    private void resetSgr() {
        fg = Cell.DEFAULT_FG;
        bg = Cell.DEFAULT_BG;
        sgrFlags = 0;
    }

    private static int palette256(int n) {
        if (n < 16) return PALETTE_16[n];
        if (n >= 232) {
            int v = 8 + 10 * (n - 232);
            return (v << 16) | (v << 8) | v;
        }
        int k = n - 16;
        int r = k / 36;
        int g = (k / 6) % 6;
        int b = k % 6;
        int[] ramp = {0, 95, 135, 175, 215, 255};
        return (ramp[r] << 16) | (ramp[g] << 8) | ramp[b];
    }

    private void reset() {
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++) { main[r][c] = Cell.EMPTY; alt[r][c] = Cell.EMPTY; }
        row = 0; col = 0; savedRow = 0; savedCol = 0;
        fg = Cell.DEFAULT_FG; bg = Cell.DEFAULT_BG; sgrFlags = 0;
        cursorVisible = true; autoWrap = true; wrapPending = false;
        scrollTop = 0; scrollBottom = rows - 1;
        inAlt = false; bracketedPaste = false; appCursorKeys = false;
        clearScrollback();
        carry = NO_CARRY;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}

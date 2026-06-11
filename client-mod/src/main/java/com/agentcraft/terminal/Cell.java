package com.agentcraft.terminal;

/**
 * One cell in the terminal grid. Cells are immutable so the buffer can share
 * a single {@link #EMPTY} instance for the (very common) empty case.
 */
public record Cell(char ch, int fg, int bg, int flags) {
    public static final int FLAG_BOLD     = 1;
    public static final int FLAG_REVERSE  = 2;
    public static final int FLAG_UNDERLINE = 4;

    public static final int DEFAULT_FG = 0xCCCCCC;
    public static final int DEFAULT_BG = 0x000000;

    public static final Cell EMPTY = new Cell(' ', DEFAULT_FG, DEFAULT_BG, 0);

    public boolean bold()      { return (flags & FLAG_BOLD) != 0; }
    public boolean reverse()   { return (flags & FLAG_REVERSE) != 0; }
    public boolean underline() { return (flags & FLAG_UNDERLINE) != 0; }
}

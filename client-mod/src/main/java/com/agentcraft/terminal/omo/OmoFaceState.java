package com.agentcraft.terminal.omo;

/**
 * The five animation modes the in-game Omo overlay can be in. Mirrors the
 * `FaceMode` union in runtime/src/faceState.ts. If you add a mode, also
 * add a matching `<name>_<N>.png` set under
 * resources/assets/agentcraft-terminal/textures/omo/ and bump FRAMES in
 * {@link OmoHudLayer}.
 */
public enum OmoFaceState {
    IDLE("idle"),
    LISTENING("listening"),
    THINKING("thinking"),
    SPEAKING("speaking"),
    CELEBRATING("celebrating");

    private final String wire;

    OmoFaceState(String wire) { this.wire = wire; }

    public String wire() { return wire; }

    public static OmoFaceState parse(String s) {
        if (s == null) return IDLE;
        switch (s.trim().toLowerCase()) {
            case "listening":   return LISTENING;
            case "thinking":    return THINKING;
            case "speaking":    return SPEAKING;
            case "celebrating": return CELEBRATING;
            default:            return IDLE;
        }
    }
}

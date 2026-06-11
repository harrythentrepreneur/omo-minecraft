package com.agentcraft.terminal;

/**
 * Single source of truth for where the omo-mc runtime lives, for every
 * client-mod connection that leaves this player's machine: the F4 team
 * terminal (ws, :8767) and the three Omo HTTP endpoints (frame/state/voice,
 * :8766). Everything else (plugin <-> runtime on :8765, the local backtick
 * PTY) stays on its own box and never touches this.
 *
 * Resolution order, first non-blank wins:
 *   1. JVM system property   -Dagentcraft.host=&lt;host&gt;
 *   2. environment variable   AGENTCRAFT_HOST=&lt;host&gt;
 *   3. {@link #DEFAULT_HOST} baked in below.
 *
 * For a server friends join over the internet: set {@link #DEFAULT_HOST} to
 * your box's public address (a DuckDNS-style hostname survives a changing
 * home IP). Friends install the mod and connect with zero config; the
 * property/env override stays as an escape hatch so you can repoint the
 * runtime without rebuilding the mod.
 *
 * Host is the only location knob — the ports are fixed and must match
 * runtime/src/terminalServer.ts (8767) and runtime/src/http.ts (8766).
 *
 * A second knob, {@link #token()}, is the access token presented to those two
 * ports when connecting over the network. It resolves the same three ways
 * (-Dagentcraft.token &gt; AGENTCRAFT_TOKEN &gt; baked {@link #DEFAULT_TOKEN})
 * and must equal OMO_CLIENT_TOKEN in runtime/.env. Leave it blank for local
 * play — the runtime trusts loopback and ignores the token there.
 */
public final class RuntimeHost {

    /** EDIT ME for a remote server, e.g. "harry.duckdns.org". */
    private static final String DEFAULT_HOST = "127.0.0.1";

    /**
     * EDIT ME for a remote server: must equal OMO_CLIENT_TOKEN in runtime/.env.
     * Blank = local play only (the runtime trusts loopback). When set, it is
     * sent as the terminal WS {@code hello} token and as an
     * {@code Authorization: Bearer} header on the Omo HTTP polls.
     */
    private static final String DEFAULT_TOKEN = "";

    private static final int TERMINAL_PORT = 8767;
    private static final int HTTP_PORT = 8766;

    private RuntimeHost() {}

    /** The resolved runtime host (property &gt; env &gt; baked default). */
    public static String host() {
        String prop = System.getProperty("agentcraft.host");
        if (prop != null && !prop.isBlank()) return prop.trim();
        String env = System.getenv("AGENTCRAFT_HOST");
        if (env != null && !env.isBlank()) return env.trim();
        return DEFAULT_HOST;
    }

    /** The resolved access token (property &gt; env &gt; baked default); "" = none. */
    public static String token() {
        String prop = System.getProperty("agentcraft.token");
        if (prop != null && !prop.isBlank()) return prop.trim();
        String env = System.getenv("AGENTCRAFT_TOKEN");
        if (env != null && !env.isBlank()) return env.trim();
        return DEFAULT_TOKEN;
    }

    /** {@code ws://<host>:8767} — the team/agent terminal multiplex. */
    public static String terminalWsUrl() {
        return "ws://" + host() + ":" + TERMINAL_PORT;
    }

    /** {@code http://<host>:8766<path>} — Omo frame/state/voice endpoints. */
    public static String httpUrl(String path) {
        return "http://" + host() + ":" + HTTP_PORT + path;
    }
}

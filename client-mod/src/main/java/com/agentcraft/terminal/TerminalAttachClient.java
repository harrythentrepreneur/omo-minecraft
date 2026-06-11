package com.agentcraft.terminal;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Connects to the runtime's terminal multiplex server (default
 * {@code ws://127.0.0.1:8767}), subscribes to one agent's PTY stream, and
 * pumps the bytes into a {@link TerminalBuffer}. Keystrokes flow back the
 * other way as base64 input frames.
 *
 * Frame format mirrors {@code runtime/src/terminalServer.ts} verbatim — keep
 * the two in sync.
 */
public class TerminalAttachClient {

    public interface Sink {
        /** Called on the WS thread when bytes arrive. The screen renders on
         *  the client thread via MinecraftClient.execute. */
        void onBytes(byte[] bytes);
        void onAgents(String json);
        /**
         * {@code team} is the peer roster the runtime supplied for ←/→
         * cycling — workshop teammates for code-lab villagers, sanctuary
         * cubes for hermes villagers. {@code kind} is either
         * {@code "workshop"} (PTY-backed) or {@code "hermes"} (event-stream
         * backed), letting the screen tweak its footer hints accordingly.
         */
        void onSubscribed(String agentId, List<String> team, String kind);
        void onError(String message);
    }

    private final String url;
    private final Sink sink;
    private final HttpClient http;
    private WebSocket ws;
    private final AtomicBoolean alive = new AtomicBoolean(false);
    private final StringBuilder pending = new StringBuilder();
    private volatile String subscribedAgent;

    public TerminalAttachClient(String url, Sink sink) {
        this.url = url;
        this.sink = sink;
        this.http = HttpClient.newHttpClient();
    }

    public CompletableFuture<Void> connect() {
        CompletableFuture<Void> ready = new CompletableFuture<>();
        http.newWebSocketBuilder()
            .buildAsync(URI.create(url), new Listener(ready))
            .whenComplete((sock, err) -> {
                if (err != null) {
                    sink.onError("connect failed: " + err.getMessage());
                    ready.completeExceptionally(err);
                    return;
                }
                // onOpen already set `ws` + requested the roster before completing
                // `ready`; this is just a defensive backstop.
                if (ws == null) ws = sock;
                alive.set(true);
            });
        return ready;
    }

    public String currentAgent() {
        return subscribedAgent;
    }

    public boolean isAlive() {
        return alive.get();
    }

    public void requestAgents() {
        sendJson("{\"type\":\"list\"}");
    }

    /** First frame on every connection: present the access token (blank for
     *  local play). The runtime authenticates remote clients on this. */
    private void sendHello() {
        String tok = RuntimeHost.token();
        sendJson("{\"type\":\"hello\",\"token\":\"" + escape(tok == null ? "" : tok) + "\"}");
    }

    public void subscribe(String agentId) {
        subscribedAgent = agentId;
        if (agentId == null || agentId.isBlank()) {
            sendJson("{\"type\":\"subscribe\"}");
            return;
        }
        sendJson("{\"type\":\"subscribe\",\"agentId\":\"" + escape(agentId) + "\"}");
    }

    public void sendInput(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return;
        String b64 = Base64.getEncoder().encodeToString(bytes);
        sendJson("{\"type\":\"input\",\"bytes\":\"" + b64 + "\"}");
    }

    public void sendResize(int cols, int rows) {
        sendJson("{\"type\":\"resize\",\"cols\":" + cols + ",\"rows\":" + rows + "}");
    }

    /**
     * Ask the runtime to read an image off the host OS clipboard and feed its
     * path to claude. Minecraft's clipboard API is text-only, so the game
     * client can't carry the image itself — the runtime does the read.
     */
    public void sendPasteImage() {
        sendJson("{\"type\":\"paste_image\"}");
    }

    public void close() {
        alive.set(false);
        if (ws != null && !ws.isOutputClosed()) {
            try { ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye"); } catch (Throwable ignored) {}
        }
    }

    private void sendJson(String json) {
        if (ws == null || !alive.get()) return;
        ws.sendText(json, true);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Listener that reassembles WebSocket text fragments and dispatches one
     * full JSON frame at a time to the sink.
     */
    private final class Listener implements WebSocket.Listener {
        private final CompletableFuture<Void> ready;

        Listener(CompletableFuture<Void> ready) {
            this.ready = ready;
        }

        @Override
        public void onOpen(WebSocket socket) {
            // Set `ws` HERE — onOpen fires before buildAsync's future resolves,
            // so if we wait for that to assign `ws`, the subscribe() that runs
            // on `ready` completion sees ws==null and is silently dropped
            // (→ terminal stuck on "connecting…"). Assign first, then signal.
            ws = socket;
            alive.set(true);
            socket.request(1);
            // Authenticate first. A remote runtime drops every other frame until
            // it sees a valid hello token; a loopback runtime ignores it. Must
            // go out before requestAgents()/subscribe() or those get dropped.
            sendHello();
            requestAgents();
            ready.complete(null);
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            pending.append(data);
            if (last) {
                String frame = pending.toString();
                pending.setLength(0);
                dispatch(frame);
            }
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int code, String reason) {
            alive.set(false);
            sink.onError("disconnected (" + code + ")");
            return null;
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            alive.set(false);
            sink.onError("error: " + error.getMessage());
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) {
            socket.request(1);
            return null;
        }
    }

    private void dispatch(String json) {
        // Cheap targeted parser — we know the message shapes from terminalServer.ts.
        // Avoids pulling in a JSON dep.
        String type = jsonString(json, "type");
        if (type == null) return;
        switch (type) {
            case "pty_data" -> {
                String b64 = jsonString(json, "b64");
                if (b64 == null) return;
                try {
                    byte[] bytes = Base64.getDecoder().decode(b64);
                    sink.onBytes(bytes);
                } catch (IllegalArgumentException ignored) {}
            }
            case "subscribed" -> {
                String agentId = jsonString(json, "agentId");
                String kind = jsonString(json, "kind");
                List<String> team = jsonStringArray(json, "team");
                if (agentId != null) {
                    subscribedAgent = agentId;
                    sink.onSubscribed(agentId, team, kind == null ? "workshop" : kind);
                }
                String replay = jsonString(json, "replay");
                if (replay != null && !replay.isEmpty()) {
                    try { sink.onBytes(Base64.getDecoder().decode(replay)); }
                    catch (IllegalArgumentException ignored) {}
                }
            }
            case "agents" -> sink.onAgents(json);
            case "error" -> {
                String msg = jsonString(json, "message");
                sink.onError(msg == null ? "(unknown)" : msg);
            }
            default -> { /* ignore */ }
        }
    }

    /**
     * Pull a string field out of a one-level-deep JSON object. Tolerates
     * escaped quotes/backslashes inside the value; bails on nested objects
     * which we don't need.
     */
    private static String jsonString(String json, String key) {
        String needle = "\"" + key + "\":";
        int idx = json.indexOf(needle);
        if (idx < 0) return null;
        int p = idx + needle.length();
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) p++;
        if (p >= json.length() || json.charAt(p) != '"') return null;
        p++;
        StringBuilder out = new StringBuilder();
        while (p < json.length()) {
            char c = json.charAt(p++);
            if (c == '\\' && p < json.length()) {
                char esc = json.charAt(p++);
                switch (esc) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case 'r' -> out.append('\r');
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'u' -> {
                        if (p + 4 <= json.length()) {
                            int cp = Integer.parseInt(json.substring(p, p + 4), 16);
                            out.append((char) cp);
                            p += 4;
                        }
                    }
                    default -> out.append(esc);
                }
            } else if (c == '"') {
                return out.toString();
            } else {
                out.append(c);
            }
        }
        return null;
    }

    /**
     * Extract a flat array of strings ({@code "key":["a","b","c"]}) from a
     * one-level-deep JSON object. Tolerates whitespace and the same escape
     * sequences as {@link #jsonString}; bails on nested arrays / objects
     * inside the array.
     */
    private static List<String> jsonStringArray(String json, String key) {
        List<String> out = new ArrayList<>();
        String needle = "\"" + key + "\":";
        int idx = json.indexOf(needle);
        if (idx < 0) return out;
        int p = idx + needle.length();
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) p++;
        if (p >= json.length() || json.charAt(p) != '[') return out;
        p++;
        while (p < json.length()) {
            while (p < json.length() && Character.isWhitespace(json.charAt(p))) p++;
            if (p >= json.length()) break;
            char c = json.charAt(p);
            if (c == ']') break;
            if (c == ',') { p++; continue; }
            if (c != '"') break;
            p++;
            StringBuilder buf = new StringBuilder();
            while (p < json.length()) {
                char ch = json.charAt(p++);
                if (ch == '\\' && p < json.length()) {
                    char esc = json.charAt(p++);
                    switch (esc) {
                        case 'n' -> buf.append('\n');
                        case 't' -> buf.append('\t');
                        case 'r' -> buf.append('\r');
                        case '"' -> buf.append('"');
                        case '\\' -> buf.append('\\');
                        case '/' -> buf.append('/');
                        default -> buf.append(esc);
                    }
                } else if (ch == '"') {
                    out.add(buf.toString());
                    break;
                } else {
                    buf.append(ch);
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unused")
    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}

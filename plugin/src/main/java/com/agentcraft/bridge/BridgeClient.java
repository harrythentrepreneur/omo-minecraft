package com.agentcraft.bridge;

import com.agentcraft.AgentCraftPlugin;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public class BridgeClient {

    private final AgentCraftPlugin plugin;
    private final HttpClient http = HttpClient.newHttpClient();
    private volatile WebSocket socket;
    private IncomingHandler handler;
    private BukkitTask reconnectTask;
    private final StringBuilder textBuffer = new StringBuilder();
    private volatile boolean shuttingDown = false;

    public BridgeClient(AgentCraftPlugin plugin) { this.plugin = plugin; }

    public void setHandler(IncomingHandler h) { this.handler = h; }

    public synchronized void connect() {
        if (shuttingDown) return;
        String url = plugin.getConfig().getString("bridge.url", "ws://127.0.0.1:8765");
        plugin.getLogger().info("connecting to " + url);

        http.newWebSocketBuilder()
            .buildAsync(URI.create(url), new Listener())
            .whenComplete((ws, err) -> {
                if (err != null) {
                    plugin.getLogger().warning("bridge connect failed: " + err.getMessage());
                    scheduleReconnect();
                } else {
                    this.socket = ws;
                    sendHello();
                }
            });
    }

    private void sendHello() {
        JsonObject m = new JsonObject();
        m.addProperty("type", "hello");
        m.addProperty("token", plugin.getConfig().getString("bridge.token", ""));
        m.addProperty("serverName", Bukkit.getServer().getName());
        send(m);
    }

    public void send(JsonObject obj) {
        WebSocket s = this.socket;
        if (s == null) return;
        s.sendText(obj.toString(), true);
    }

    public boolean isConnected() { return socket != null; }

    private void scheduleReconnect() {
        if (shuttingDown) return;
        if (reconnectTask != null && !reconnectTask.isCancelled()) return;
        int seconds = plugin.getConfig().getInt("bridge.reconnect_seconds", 5);
        reconnectTask = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            reconnectTask = null;
            connect();
        }, seconds * 20L);
    }

    public void shutdown() {
        shuttingDown = true;
        if (reconnectTask != null) reconnectTask.cancel();
        WebSocket s = this.socket;
        if (s != null) s.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
    }

    private class Listener implements WebSocket.Listener {
        @Override public void onOpen(WebSocket ws) {
            plugin.getLogger().info("bridge open");
            ws.request(1);
        }

        @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                String msg = textBuffer.toString();
                textBuffer.setLength(0);
                // Hop onto the main thread before touching Bukkit APIs.
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        if (handler != null) handler.onMessage(msg);
                    } catch (Throwable t) {
                        plugin.getLogger().warning("handler error: " + t);
                    }
                });
            }
            ws.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
            plugin.getLogger().warning("bridge closed " + code + " " + reason);
            socket = null;
            scheduleReconnect();
            return CompletableFuture.completedFuture(null);
        }

        @Override public void onError(WebSocket ws, Throwable error) {
            plugin.getLogger().warning("bridge error: " + error);
            socket = null;
            scheduleReconnect();
        }
    }
}

package com.agentcraft.terminal;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wraps a Pty4J child process + a reader thread that feeds the {@link TerminalBuffer}.
 * Lifecycle: {@link #start()} -> read thread runs until the process exits or
 * {@link #close()} is called.
 *
 * Threading: bytes from the PTY are decoded on the reader thread and pushed
 * into TerminalBuffer under its own monitor — Minecraft's render thread reads
 * the same monitor when it walks the grid, so reads/writes are safe.
 */
public class TerminalSession implements AutoCloseable {

    private static final int READ_BUF = 4096;

    private final TerminalBuffer buffer;
    private final String[] command;
    private final String cwd;
    private final Map<String, String> env;

    private PtyProcess process;
    private OutputStream stdin;
    private Thread reader;
    private final AtomicBoolean alive = new AtomicBoolean(false);
    private Runnable onExit;
    private volatile String lastError;

    public TerminalSession(TerminalBuffer buffer, String[] command, String cwd) {
        this.buffer = buffer;
        this.command = command;
        this.cwd = cwd;
        this.env = defaultEnv();
    }

    /** Hook fired once on the read thread when the child exits (cleanly or not). */
    public void onExit(Runnable r) { this.onExit = r; }

    public boolean isAlive() { return alive.get() && process != null && process.isAlive(); }

    public String lastError() { return lastError; }

    public synchronized void start() throws IOException {
        if (alive.get()) return;
        PtyProcessBuilder b = new PtyProcessBuilder(command)
            .setEnvironment(env)
            .setInitialColumns(buffer.cols())
            .setInitialRows(buffer.rows());
        if (cwd != null) b.setDirectory(cwd);
        process = b.start();
        stdin = process.getOutputStream();
        InputStream in = process.getInputStream();
        alive.set(true);

        reader = new Thread(() -> readLoop(in), "agentcraft-terminal-pty");
        reader.setDaemon(true);
        reader.start();
    }

    private void readLoop(InputStream in) {
        byte[] buf = new byte[READ_BUF];
        try {
            int n;
            while (alive.get() && (n = in.read(buf)) > 0) {
                buffer.feed(buf, n);
            }
        } catch (IOException e) {
            lastError = e.getMessage();
        } finally {
            alive.set(false);
            if (onExit != null) {
                try { onExit.run(); } catch (Throwable ignored) { }
            }
        }
    }

    public void write(byte[] data) {
        OutputStream out = stdin;
        if (out == null) return;
        try {
            out.write(data);
            out.flush();
        } catch (IOException e) {
            lastError = e.getMessage();
        }
    }

    public void write(String s) {
        write(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public void resize(int cols, int rows) {
        if (process == null) return;
        try {
            process.setWinSize(new WinSize(cols, rows));
        } catch (Exception ignored) {
            // Pty4J throws on some platforms — non-fatal.
        }
        buffer.resize(cols, rows);
    }

    @Override
    public synchronized void close() {
        alive.set(false);
        if (process != null && process.isAlive()) {
            try { process.destroy(); } catch (Throwable ignored) { }
        }
        if (reader != null) {
            reader.interrupt();
        }
    }

    private static Map<String, String> defaultEnv() {
        Map<String, String> e = new HashMap<>(System.getenv());
        // Tell the shell + Claude Code we can render xterm-256color escapes.
        e.put("TERM", "xterm-256color");
        e.put("COLORTERM", "truecolor");
        e.putIfAbsent("LANG", "en_US.UTF-8");
        e.putIfAbsent("LC_ALL", "en_US.UTF-8");
        // Make sure common install dirs for `claude` are findable (brew, npm global, asdf).
        String path = e.getOrDefault("PATH", "/usr/bin:/bin");
        String extra = "/opt/homebrew/bin:/usr/local/bin:" + System.getProperty("user.home") + "/.local/bin";
        if (!path.contains("/opt/homebrew/bin") && !path.contains("/usr/local/bin")) {
            e.put("PATH", path + ":" + extra);
        }
        return e;
    }

    /** Default shell discovery: $SHELL → /bin/zsh → /bin/bash. */
    public static String[] defaultShellCommand() {
        String shell = System.getenv("SHELL");
        if (shell != null && !shell.isBlank()) return new String[] { shell, "-l" };
        if (new java.io.File("/bin/zsh").canExecute()) return new String[] { "/bin/zsh", "-l" };
        if (new java.io.File("/bin/bash").canExecute()) return new String[] { "/bin/bash", "-l" };
        return new String[] { "/bin/sh", "-l" };
    }
}

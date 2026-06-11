package com.agentcraft.terminal.omo;

import com.agentcraft.terminal.RuntimeHost;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Push-to-talk. Hold the "V" keybind to record this client's microphone; on
 * release the clip is transcribed by the runtime's local whisper.cpp and the
 * text is sent as a <em>normal chat message</em> via
 * {@link ClientPlayNetworkHandler#sendChatMessage(String)}. That's the whole
 * point: the words travel the exact same path as if the player had typed them,
 * so the plugin's ChatListener routes them to the agent you're looking at,
 * opens terminals, everything — voice is just a different input device for chat.
 *
 * <p>Lifecycle: {@link #setHeld} is polled from the client tick. A rising edge
 * starts a dedicated capture thread; that one thread owns the whole press —
 * record → wrap WAV → POST → hop back to the client thread to send chat — so
 * the tick thread only ever flips a boolean and never blocks the game.
 *
 * <p>Capture format is 16 kHz / mono / signed-16-LE, which is exactly what
 * {@code whisper-cli} wants, so no resample happens anywhere.
 */
public final class VoiceCapture {

    // We deliberately do NOT force a 16 kHz mono capture line. macOS default
    // inputs run at 44.1/48 kHz, and opening an unsupported format is the #1
    // reason push-to-talk "doesn't work" (mic-unavailable, or it opens and
    // hands whisper aliased garbage). Instead we open the FIRST of these the
    // device actually accepts, capture at that native rate, downmix to mono
    // ourselves, and let the runtime resample to the 16 kHz mono whisper.cpp
    // wants. Order: cheapest-to-transcribe first (16k mono needs no resample).
    private static final AudioFormat[] CAPTURE_CANDIDATES = {
        new AudioFormat(16_000f, 16, 1, true, false),
        new AudioFormat(48_000f, 16, 1, true, false),
        new AudioFormat(44_100f, 16, 1, true, false),
        new AudioFormat(48_000f, 16, 2, true, false),
        new AudioFormat(44_100f, 16, 2, true, false),
    };
    private static final long MAX_MS = 60_000L;

    private static final URI ENDPOINT = URI.create(RuntimeHost.httpUrl("/api/voice-transcribe"));
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .version(HttpClient.Version.HTTP_1_1)
        .build();

    // The client ticks at 20 Hz (~50 ms). Require this many *consecutive* "not
    // held" ticks before we actually stop, so a single transient false read of
    // the key state (a focus blip, a dropped poll) can't chop your sentence in
    // half. ~200 ms of grace is imperceptible on a real release but kills the
    // "I'm still holding V and it cut" bug.
    private static final int RELEASE_GRACE_TICKS = 4;
    // After any stop, ignore "held" for a beat so an auto-stop (60 s cap) or a
    // failed mic-open can't instantly re-trigger a new recording every tick.
    private static final long RESTART_COOLDOWN_MS = 400L;

    private static final AtomicBoolean RECORDING = new AtomicBoolean(false);
    private static volatile long startMs = 0L;
    private static volatile long lastWarnMs = 0L;
    private static int releaseTicks = 0;
    private static volatile long cooldownUntilMs = 0L;

    private VoiceCapture() {}

    /**
     * Called every client tick with the current held-state of the talk key
     * (already gated by the caller to "in-world, no screen open"). Owns the
     * start/stop edges, the release debounce, the 60 s safety cap, and keeping
     * the on-screen "listening" cue from fading mid-hold.
     */
    public static void setHeld(MinecraftClient client, boolean held) {
        if (RECORDING.get()) {
            if (System.currentTimeMillis() - startMs >= MAX_MS) {
                info("stop: 60s cap");
                stop(client);
                releaseTicks = 0;
                return;
            }
            if (held) {
                releaseTicks = 0;
                // The action-bar overlay fades after ~3 s; re-set it each tick
                // so a long hold still shows "listening" the whole time.
                actionbar(client, Formatting.RED, "● listening — release to send");
            } else if (++releaseTicks >= RELEASE_GRACE_TICKS) {
                info("stop: released");
                stop(client);
                releaseTicks = 0;
            }
        } else {
            releaseTicks = 0;
            if (held && System.currentTimeMillis() >= cooldownUntilMs) start(client);
        }
    }

    private static void start(MinecraftClient client) {
        if (!RECORDING.compareAndSet(false, true)) return;
        startMs = System.currentTimeMillis();
        info("start");
        Thread t = new Thread(() -> captureAndSend(client), "agentcraft-omo-ptt");
        t.setDaemon(true);
        t.start();
        actionbar(client, Formatting.RED, "● listening — release to send");
    }

    private static void stop(MinecraftClient client) {
        // Flip the flag; the capture thread sees it, closes the line and takes
        // over from here. "transcribing…" stays up until it posts chat / clears.
        if (RECORDING.compareAndSet(true, false)) {
            cooldownUntilMs = System.currentTimeMillis() + RESTART_COOLDOWN_MS;
            actionbar(client, Formatting.GRAY, "transcribing…");
        }
    }

    private static void captureAndSend(MinecraftClient client) {
        TargetDataLine line;
        AudioFormat fmt;
        try {
            line = openCaptureLine();
            fmt = line.getFormat();
            line.start();
        } catch (LineUnavailableException | SecurityException | IllegalArgumentException e) {
            RECORDING.set(false);
            cooldownUntilMs = System.currentTimeMillis() + 3000L; // don't spin-retry a denied/busy mic
            warn("mic unavailable: " + e.getMessage());
            actionbar(client, Formatting.RED, "mic unavailable — check macOS mic permission");
            return;
        }

        int channels = Math.max(1, fmt.getChannels());
        int monoBytesPerSec = Math.round(fmt.getSampleRate()) * 2;   // 16-bit mono output
        int minBytes = monoBytesPerSec / 4;                          // ignore < 0.25s taps
        int maxMonoBytes = monoBytesPerSec * 60;                     // 60s safety cap

        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        try {
            while (RECORDING.get() && pcm.size() < maxMonoBytes) {
                int n = line.read(buf, 0, buf.length);
                if (n <= 0) continue;
                if (channels == 1) pcm.write(buf, 0, n);
                else downmixToMono(buf, n, channels, pcm);
            }
        } finally {
            try { line.stop(); line.close(); } catch (Exception ignored) {}
        }
        RECORDING.set(false); // in case the 60s cap, not a release, ended it

        byte[] pcmBytes = pcm.toByteArray();
        info("captured " + pcmBytes.length + " bytes (~" + (pcmBytes.length / Math.max(1, monoBytesPerSec))
            + "s @ " + Math.round(fmt.getSampleRate()) + "Hz×" + channels + ")");
        if (pcmBytes.length < minBytes) {
            info("ignored: clip shorter than 0.25s");
            actionbar(client, Formatting.DARK_GRAY, ""); // accidental tap — say nothing
            return;
        }
        byte[] wav = wrapWav(pcmBytes, fmt.getSampleRate());
        if (wav == null) { actionbar(client, Formatting.RED, "voice error"); return; }

        String text = postForTranscript(wav);
        client.execute(() -> {
            if (text == null) {
                actionbar(client, Formatting.RED, "voice error — is the runtime up?");
                return;
            }
            String t = text.trim();
            if (t.isEmpty()) {
                actionbar(client, Formatting.DARK_GRAY, "didn't catch that");
                return;
            }
            sendAsChat(client, t);
            actionbar(client, Formatting.DARK_GRAY, ""); // chat itself is the feedback now
        });
    }

    /**
     * Open the mic with the first capture format the device actually accepts.
     * We probe by really calling {@code open()} — {@code isLineSupported} is
     * unreliable on macOS mixers (claims a format works then delivers garbage,
     * or reports false for one it can convert), so an actual open is the only
     * honest test. Throws if every candidate fails.
     */
    private static TargetDataLine openCaptureLine() throws LineUnavailableException {
        LineUnavailableException last = null;
        for (AudioFormat fmt : CAPTURE_CANDIDATES) {
            try {
                DataLine.Info lineInfo = new DataLine.Info(TargetDataLine.class, fmt);
                TargetDataLine line = (TargetDataLine) AudioSystem.getLine(lineInfo);
                line.open(fmt);
                info("mic opened @ " + Math.round(fmt.getSampleRate()) + "Hz×" + fmt.getChannels());
                return line;
            } catch (LineUnavailableException e) {
                last = e;
            } catch (IllegalArgumentException e) {
                // No matching line / format on the default mixer — try the next.
            }
        }
        if (last != null) throw last;
        throw new LineUnavailableException("no supported microphone capture format");
    }

    /**
     * Average interleaved signed-16-LE channels down to one mono channel and
     * append to {@code out}. Halves the bytes we ship and gives whisper a
     * cleaner signal than picking an arbitrary single channel would.
     */
    private static void downmixToMono(byte[] buf, int n, int channels, ByteArrayOutputStream out) {
        int frameBytes = 2 * channels;
        int frames = n / frameBytes;
        for (int f = 0; f < frames; f++) {
            int base = f * frameBytes;
            int sum = 0;
            for (int c = 0; c < channels; c++) {
                int lo = buf[base + c * 2] & 0xFF;
                int hi = buf[base + c * 2 + 1];      // signed → sign-extends on shift
                sum += (hi << 8) | lo;
            }
            int avg = sum / channels;
            out.write(avg & 0xFF);
            out.write((avg >> 8) & 0xFF);
        }
    }

    /** Wrap raw mono PCM (captured at {@code rate} Hz) as a WAV container in memory. */
    private static byte[] wrapWav(byte[] pcm, float rate) {
        AudioFormat outFmt = new AudioFormat(rate, 16, 1, true, false);
        long frames = pcm.length / outFmt.getFrameSize();
        try (AudioInputStream ais = new AudioInputStream(new ByteArrayInputStream(pcm), outFmt, frames);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, out);
            return out.toByteArray();
        } catch (Exception e) {
            warn("wav encode failed: " + e.getMessage());
            return null;
        }
    }

    /** POST the WAV, return the transcript body, or null on any failure. */
    private static String postForTranscript(byte[] wav) {
        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder(ENDPOINT)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "audio/wav")
                .POST(HttpRequest.BodyPublishers.ofByteArray(wav));
            String tok = RuntimeHost.token();
            if (!tok.isBlank()) rb.header("Authorization", "Bearer " + tok);
            HttpResponse<String> res = HTTP.send(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() >= 400) {
                warn("transcribe → HTTP " + res.statusCode());
                return null;
            }
            return res.body();
        } catch (Throwable t) {
            warn("transcribe failed: " + t.getClass().getSimpleName()
                + (t.getMessage() != null ? " " + t.getMessage() : ""));
            return null;
        }
    }

    /**
     * Send the transcript as real chat. Vanilla truncates a chat packet to 256
     * chars and the server disconnects you for a longer one, so we split long
     * dictation on whitespace into multiple messages — each routes to the agent
     * independently, same as typing two lines.
     */
    private static void sendAsChat(MinecraftClient client, String text) {
        ClientPlayNetworkHandler nh = client.getNetworkHandler();
        if (nh == null || client.player == null) return;
        for (String piece : splitForChat(text, 256)) {
            if (!piece.isEmpty()) nh.sendChatMessage(piece);
        }
    }

    private static java.util.List<String> splitForChat(String text, int max) {
        java.util.List<String> out = new java.util.ArrayList<>();
        String rest = text.trim();
        while (rest.length() > max) {
            int cut = rest.lastIndexOf(' ', max);
            if (cut <= 0) cut = max; // one very long token — hard cut
            out.add(rest.substring(0, cut).trim());
            rest = rest.substring(cut).trim();
        }
        if (!rest.isEmpty()) out.add(rest);
        return out;
    }

    private static void actionbar(MinecraftClient client, Formatting color, String msg) {
        client.execute(() -> {
            if (client.player != null) {
                client.player.sendMessage(Text.literal(msg).formatted(color), true);
            }
        });
    }

    private static void warn(String msg) {
        long now = System.currentTimeMillis();
        if (now - lastWarnMs > 60_000L) {
            lastWarnMs = now;
            System.out.println("[omo-ptt] " + msg);
        }
    }

    // Per-transition log (start / stop / captured bytes). Low volume — one line
    // per press — so it's not throttled. Lands in the client's latest.log, the
    // ground truth if push-to-talk still misbehaves.
    private static void info(String msg) {
        System.out.println("[omo-ptt] " + msg);
    }
}

package com.agentcraft.terminal;

import com.agentcraft.terminal.cinema.CinemaScreen;
import com.agentcraft.terminal.omo.VoiceCapture;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Entrypoint. Four trigger paths:
 *
 * <ul>
 *   <li>Backtick (`)        — open a local PTY shell ({@link TerminalScreen})</li>
 *   <li>F4                  — open the team terminal ({@link TeamTerminalScreen})</li>
 *   <li>V (hold)            — push-to-talk: records the mic while held and, on
 *                              release, sends the transcript as a normal chat
 *                              message so it routes to agents exactly like
 *                              typing ({@link VoiceCapture})</li>
 *   <li>Server-side sentinel — when the player types in chat near a Code Lab
 *                              agent the plugin sends a system message of the
 *                              form {@code §§ACT-TERMINAL§§ <agentId>}. We
 *                              suppress it and open the team terminal attached
 *                              to that agent. This is what makes "type → see
 *                              claude live" feel automatic.</li>
 * </ul>
 */
public class TerminalMod implements ClientModInitializer {

    public static final String MOD_ID = "agentcraft-terminal";
    public static final String SENTINEL_OPEN  = "§§ACT-TERMINAL§§";       // §§ACT-TERMINAL§§
    public static final String SENTINEL_CLOSE = "§§ACT-CLOSE-TERMINAL§§"; // §§ACT-CLOSE-TERMINAL§§
    public static final String SENTINEL_CINEMA = "§§ACT-CINEMA§§";        // §§ACT-CINEMA§§ <cinemaId>

    private static KeyBinding openLocalKey;
    private static KeyBinding openTeamKey;
    private static KeyBinding talkToOmoKey;

    @Override
    public void onInitializeClient() {
        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));

        openLocalKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.agentcraft-terminal.open",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_GRAVE_ACCENT,
            category
        ));
        openTeamKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.agentcraft-terminal.team",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_F4,
            category
        ));
        // "V" for push-to-talk. Configurable in Options → Controls, like the
        // others. HOLD to record your mic; release to send what you said as a
        // chat message. We poll its held-state below instead of wasPressed()
        // because this is a hold, not a tap.
        talkToOmoKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.agentcraft-terminal.talk-to-omo",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_V,
            category
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openLocalKey.wasPressed()) openLocal(client);
            while (openTeamKey.wasPressed())  openTeam(client, null);
            // Push-to-talk: drive the recorder off the *held* state of the key.
            VoiceCapture.setHeld(client, isTalkKeyHeld(client));
        });

        // (The Omo "holoscope" corner HUD was removed — no persistent face
        // overlay is drawn on screen. Voice is push-to-talk on the V key.)

        // Auto-open trigger: the plugin sends a system message starting with the
        // sentinel after ChatListener routes a chat line to a Code Lab agent.
        // Also handles SENTINEL_CLOSE, which the voice-driven close_terminal
        // tool emits — dismisses any TerminalScreen / TeamTerminalScreen.
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            if (overlay) return true;
            String s = message.getString();
            if (s == null) return true;
            if (s.startsWith(SENTINEL_CLOSE)) {
                MinecraftClient client = MinecraftClient.getInstance();
                client.execute(() -> closeTerminal(client));
                return false; // swallow the sentinel
            }
            // §§ACT-CINEMA§§ <cinemaId> — right-clicking a cinema wall opens the
            // fullscreen "real computer" view of that screen (GPU blit, native fps).
            if (s.startsWith(SENTINEL_CINEMA)) {
                String cinemaId = s.substring(SENTINEL_CINEMA.length()).trim();
                if (cinemaId.isEmpty()) return false;
                MinecraftClient client = MinecraftClient.getInstance();
                client.execute(() -> openCinema(client, cinemaId));
                return false; // swallow the sentinel so it doesn't show in chat
            }
            if (!s.startsWith(SENTINEL_OPEN)) return true;
            String agentId = s.substring(SENTINEL_OPEN.length()).trim();
            MinecraftClient client = MinecraftClient.getInstance();
            client.execute(() -> openTeam(client, agentId.isEmpty() ? null : agentId));
            return false; // swallow the sentinel so it doesn't show in chat
        });
    }

    private static void openLocal(MinecraftClient client) {
        if (client.currentScreen instanceof TerminalScreen) return;
        if (client.currentScreen != null) return;
        client.setScreen(new TerminalScreen());
    }

    private static void openTeam(MinecraftClient client, String agentId) {
        if (client.currentScreen instanceof TeamTerminalScreen existing) {
            // Already open. Recreate the screen ONLY when a *different* agent is
            // requested. Re-sent sentinels for the same agent — e.g. the pressure
            // plate re-firing PHYSICAL events while the player stands on it — must
            // be ignored, otherwise each one wipes the buffer + reconnects the
            // WebSocket, which the player sees as the terminal "refreshing" every
            // couple of seconds.
            if (agentId != null && !agentId.equals(existing.currentAgent())) {
                client.setScreen(new TeamTerminalScreen(agentId));
            }
            return;
        }
        // Don't steal focus from chat/menus.
        if (client.currentScreen != null && !(client.currentScreen instanceof TerminalScreen)) return;
        client.setScreen(agentId == null ? new TeamTerminalScreen() : new TeamTerminalScreen(agentId));
    }

    /**
     * Open (or re-target) the fullscreen cinema "zoom" view. Re-firing the same
     * sentinel for the cinema already on screen is a no-op so a held right-click
     * (or a repeated emit) doesn't churn the poller/texture; a different cinema
     * id swaps the screen.
     */
    private static void openCinema(MinecraftClient client, String cinemaId) {
        if (client.currentScreen instanceof CinemaScreen existing) {
            if (!cinemaId.equals(existing.cinemaId())) {
                client.setScreen(new CinemaScreen(cinemaId));
            }
            return;
        }
        // Don't steal focus from chat/menus/terminals.
        if (client.currentScreen != null) return;
        client.setScreen(new CinemaScreen(cinemaId));
    }

    private static void closeTerminal(MinecraftClient client) {
        // Only drop our screens. If the player is in the inventory / chat /
        // settings we leave them alone — Omo only owns terminal + cinema panes.
        if (client.currentScreen instanceof TerminalScreen
            || client.currentScreen instanceof TeamTerminalScreen
            || client.currentScreen instanceof CinemaScreen) {
            client.setScreen(null);
        }
    }

    /**
     * Is the push-to-talk key held right now? Uses {@link KeyBinding#isPressed()}
     * — the exact per-tick held-state the engine itself polls for movement keys
     * in {@code KeyboardInput#tick}, so it tracks a hold cleanly for its whole
     * duration. (An earlier raw {@code glfwGetKey} poll was chopping recordings
     * short.) The {@link VoiceCapture} side adds a short release debounce + a
     * 60s cap so neither a one-tick blip nor a missed release event can misfire.
     *
     * <p>Gated to "in-world, no screen open" so pressing V while typing in chat
     * types a "v" instead of arming the mic.
     */
    private static boolean isTalkKeyHeld(MinecraftClient client) {
        if (client.player == null || client.currentScreen != null) return false;
        return talkToOmoKey.isPressed();
    }
}

package com.agentcraft.agents;

import com.agentcraft.AgentCraftPlugin;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Lectern;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * In-world representation of one agent. Surfaces an agent's state through every Minecraft
 * affordance we have available:
 *   - a villager NPC (visible body)
 *   - a stack of armor-stand holograms above its head (reasoning board)
 *   - the villager's main hand item (current tool indicator)
 *   - a per-agent BossBar at the top of every nearby player's screen
 *   - a lectern + auto-updating WRITTEN_BOOK next to it (full transcript)
 */
public class AgentNpc {

    private static final double BASE_Y = 2.2;
    private static final double STEP_Y = 0.3;
    private static final TextColor RULE_COLOR = NamedTextColor.DARK_GRAY;
    private static final String RULE = "──────────────────────────";
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    // Max chars per book page so lines wrap cleanly. Empirical: ~19 chars × ~14 lines per page.
    private static final int PAGE_LINE_BUDGET = 14;
    // How far an idle villager can wander from their spawn before being leashed back.
    private static final double LEASH_RADIUS = 5.0;
    private static final double LEASH_RADIUS_SQ = LEASH_RADIUS * LEASH_RADIUS;
    // Position tick frequency (in server ticks). 10 = 0.5s — smooth enough for the
    // floating screen to track without obvious lag, cheap enough to run for many agents.
    private static final long POSITION_TICK_PERIOD = 10L;

    private final String agentId;
    private final String role;
    private final String room;
    private final Villager villager;
    private final ArmorStand nameTag;
    private final ArmorStand footer;
    private final List<ArmorStand> contentLines = new ArrayList<>();
    private final ArmorStand header;
    private final BossBar bossBar;
    private Block lecternBlock;
    private final List<Component> transcriptLines = new ArrayList<>();
    private String status = "idle";
    /** Original spawn location — used as the leash anchor when the villager wanders. */
    private final Location originalHome;
    /** When true the villager never wanders: AI stays off and it's pinned to originalHome. */
    private final boolean stationary;
    /** Per-villager tick that wanders, leashes, and slides the screen holograms. */
    private BukkitTask positionTask;

    // ── live-build puppet state ──────────────────────────────────────────────
    // While a BuildPlot streams blocks in, it drives this villager every tick via
    // beginBuild/buildStep/endBuild so it walks the build front and climbs the
    // structure as it rises. AI + gravity go off so the body can be placed exactly.
    private static final double BUILD_STAND_OFFSET = 1.6; // stand this far outside the active block
    private static final double BUILD_MAX_STEP = 1.15;    // eased blocks/tick toward the target (lively pace)
    private static final double BUILD_MAX_STANDOFF = 5.0; // push out this far to escape walls closing in
    /** True while in live-build mode (owns the body; tickPosition stands down). */
    private boolean building = false;
    /** Current eased puppet position (glides toward the target each tick). */
    private Location buildPos;
    /** Plot centre — the mason stands outward from it so it orbits the build. */
    private Location buildCenter;
    /** Animation cadence counter for swings + particles. */
    private int buildAnimTick = 0;

    public AgentNpc(AgentCraftPlugin plugin, String agentId, String role, String room,
                    Location home, int screenLineCount) {
        this(plugin, agentId, role, room, home, screenLineCount, false);
    }

    public AgentNpc(AgentCraftPlugin plugin, String agentId, String role, String room,
                    Location home, int screenLineCount, boolean stationary) {
        this.agentId = agentId;
        this.role = role;
        this.room = room;
        this.originalHome = home.clone();
        this.stationary = stationary;

        var world = home.getWorld();
        villager = (Villager) world.spawnEntity(home, EntityType.VILLAGER);
        villager.customName(Component.text(agentId, NamedTextColor.AQUA));
        villager.setCustomNameVisible(true);
        // AI is ON so they wander around their cube/desk when idle (unless
        // stationary). The position tick below leashes them within LEASH_RADIUS
        // and freezes them while busy.
        villager.setAI(!stationary);
        villager.setInvulnerable(true);
        villager.setSilent(true);
        villager.setPersistent(true);
        villager.setRemoveWhenFarAway(false);

        nameTag = spawnHolo(stackAt(home, 0), Component.text("[" + status + "] " + role, NamedTextColor.YELLOW));
        footer = spawnHolo(stackAt(home, 1), Component.text(RULE, RULE_COLOR));

        for (int i = 0; i < screenLineCount; i++) {
            contentLines.add(spawnHolo(stackAt(home, 2 + i), Component.text("·", NamedTextColor.DARK_GRAY)));
        }

        header = spawnHolo(stackAt(home, 2 + screenLineCount), headerComponent());

        bossBar = BossBar.bossBar(
            bossTitle("idle", ""),
            0f,
            BossBar.Color.WHITE,
            BossBar.Overlay.PROGRESS);

        // Place lectern + initial book.
        lecternBlock = placeLectern(home);
        transcriptLines.add(timestamped(NamedTextColor.GOLD, "▸ spawned in " + room));
        rebuildBook();

        // Start the wander/leash/screen-follow tick.
        positionTask = Bukkit.getScheduler().runTaskTimer(
            plugin, this::tickPosition, POSITION_TICK_PERIOD, POSITION_TICK_PERIOD);
    }

    /**
     * Per-villager position tick:
     *  - Holograms (name, screen, header) follow the villager as it walks.
     *  - When busy (thinking / tool_call / speaking) the villager is frozen at
     *    its desk so the player sees a focused, working agent.
     *  - When idle, AI is on and the villager wanders freely until it strays
     *    past LEASH_RADIUS, at which point it gets teleported home.
     */
    private void tickPosition() {
        if (villager.isDead()) {
            if (positionTask != null) { positionTask.cancel(); positionTask = null; }
            return;
        }
        // While live-building, buildStep() owns the body (1/tick) and the holograms
        // stay parked at the post so the reasoning board stays readable on the deck.
        if (building) return;
        Location current = villager.getLocation();

        // Stationary villagers (e.g. the build-studio mason) never wander: AI
        // stays off and they're pinned to their spawn, facing it, regardless of
        // busy state — so the architect always stands at the plot edge.
        if (stationary) {
            if (villager.hasAI()) villager.setAI(false);
            if (current.distanceSquared(originalHome) > 0.25) {
                villager.teleport(originalHome);
                current = originalHome.clone();
            }
            syncHologramsTo(current);
            return;
        }

        // Leash: snap back if they wandered too far from their spawn point.
        if (current.getWorld() != null
                && current.getWorld().equals(originalHome.getWorld())
                && current.distanceSquared(originalHome) > LEASH_RADIUS_SQ) {
            villager.teleport(originalHome);
            current = originalHome.clone();
        }

        // Focus: freeze in place at the desk while doing work.
        if (isBusy()) {
            if (villager.hasAI()) villager.setAI(false);
            if (current.distanceSquared(originalHome) > 0.25) {
                villager.teleport(originalHome);
                current = originalHome.clone();
            }
        } else {
            if (!villager.hasAI()) villager.setAI(true);
        }

        // Slide the floating screen so it always sits above the villager's head.
        syncHologramsTo(current);
    }

    private boolean isBusy() {
        return "thinking".equals(status) || "tool_call".equals(status) || "speaking".equals(status);
    }

    // ── live-build puppet (driven by BuildPlot.drain at 1/tick) ───────────────

    /**
     * Enter live-build mode. The villager becomes a puppet: AI and gravity go off
     * so {@link #buildStep} can place it precisely beside each block as it's laid,
     * orbiting {@code plotCenter} and climbing the structure as it rises. The
     * reasoning-board holograms stay parked at the post (tickPosition stands down).
     */
    public void beginBuild(Location plotCenter) {
        building = true;
        buildCenter = plotCenter;
        buildPos = villager.getLocation().clone();
        buildAnimTick = 0;
        villager.setAI(false);
        villager.setGravity(false);
    }

    /**
     * One animation step toward the build front. {@code focus} is the world centre
     * of the blocks placed this tick; {@code holding} is the block being laid
     * (shown in the mason's hand). Eases the body to a spot just outside the
     * structure at that height, faces the work, and swings + sparkles on a cadence.
     */
    public void buildStep(Location focus, Material holding) {
        if (!building || focus == null || villager.isDead()) return;
        World w = villager.getWorld();
        buildAnimTick++;

        // Base bearing: outward from the plot centre toward the block, so he works
        // the OUTSIDE of the structure facing in.
        double dirX = buildCenter != null ? focus.getX() - buildCenter.getX() : -1;
        double dirZ = buildCenter != null ? focus.getZ() - buildCenter.getZ() : 0;
        if (Math.abs(dirX) < 1e-3 && Math.abs(dirZ) < 1e-3) { dirX = -1; dirZ = 0; }
        double baseAngle = Math.atan2(dirZ, dirX);
        // Strafe: swing side-to-side around the active block (±~65°) so he's never
        // standing still — he paces and circles the spot he's working on.
        double angle = baseAngle + Math.sin(buildAnimTick * 0.22) * 1.15;
        // Bob/hop while working so there's constant vertical motion too.
        double standY = focus.getY() + Math.abs(Math.sin(buildAnimTick * 0.5)) * 0.45;
        // Keep him out of the structure. The ideal 1.6-block standoff can land
        // inside a wall as the build closes in around the work; push the target
        // outward (then up) until his body has a clear 2-block air gap. He still
        // eases toward it, so he glides to open space instead of getting sealed
        // inside what he's laying.
        Location stand = clearStand(w, focus, angle, standY);
        double standX = stand.getX();
        standY = stand.getY();
        double standZ = stand.getZ();

        // Ease toward the target so the body glides/runs rather than snapping.
        if (buildPos == null || buildPos.getWorld() == null) buildPos = villager.getLocation().clone();
        double ex = standX - buildPos.getX();
        double ey = standY - buildPos.getY();
        double ez = standZ - buildPos.getZ();
        double edist = Math.sqrt(ex * ex + ey * ey + ez * ez);
        if (edist > BUILD_MAX_STEP) {
            double s = BUILD_MAX_STEP / edist;
            buildPos.add(ex * s, ey * s, ez * s);
        } else {
            buildPos = new Location(w, standX, standY, standZ);
        }
        buildPos.setDirection(focus.toVector().subtract(buildPos.toVector()));
        villager.teleport(buildPos);

        // Hold the block being placed; swing hard + sparkle on a tight cadence.
        EntityEquipment eq = villager.getEquipment();
        if (eq != null && holding != null && holding.isItem()) {
            eq.setItemInMainHand(new ItemStack(holding), true);
        }
        if (buildAnimTick % 3 == 0) villager.swingMainHand();
        if (buildAnimTick % 2 == 0) {
            w.spawnParticle(Particle.HAPPY_VILLAGER, focus, 5, 0.35, 0.35, 0.35, 0.0);
        }
    }

    /**
     * A stand position with room for the mason's body. Starts at the ideal
     * {@link #BUILD_STAND_OFFSET} along {@code angle} out from {@code focus}; if
     * that's walled in, steps the standoff outward to {@link #BUILD_MAX_STANDOFF},
     * then climbs straight up as a last resort. Returns the first spot whose feet
     * and head blocks are both passable, so he never suffocates inside the build.
     */
    private Location clearStand(World w, Location focus, double angle, double baseY) {
        double cos = Math.cos(angle), sin = Math.sin(angle);
        for (double off = BUILD_STAND_OFFSET; off <= BUILD_MAX_STANDOFF; off += 0.7) {
            double x = focus.getX() + cos * off;
            double z = focus.getZ() + sin * off;
            if (bodyFits(w, x, baseY, z)) return new Location(w, x, baseY, z);
        }
        // Boxed in on every side at this height — perch above the structure.
        double x = focus.getX() + cos * BUILD_STAND_OFFSET;
        double z = focus.getZ() + sin * BUILD_STAND_OFFSET;
        for (double up = 1.0; up <= 6.0; up += 1.0) {
            if (bodyFits(w, x, baseY + up, z)) return new Location(w, x, baseY + up, z);
        }
        return new Location(w, x, baseY, z); // give up gracefully at the ideal spot
    }

    /** True if a villager body (feet + head block) fits here with no solid block. */
    private boolean bodyFits(World w, double x, double y, double z) {
        Block feet = w.getBlockAt((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        Block head = w.getBlockAt((int) Math.floor(x), (int) Math.floor(y) + 1, (int) Math.floor(z));
        return feet.isPassable() && head.isPassable();
    }

    /** Exit live-build mode: restore gravity, drop the block, return to the post. */
    public void endBuild() {
        building = false;
        buildPos = null;
        buildCenter = null;
        villager.setGravity(true);
        villager.setAI(false); // stationary mason stays put at its post
        EntityEquipment eq = villager.getEquipment();
        if (eq != null) eq.setItemInMainHand(null, true);
        if (!villager.isDead()) villager.teleport(originalHome);
    }

    private void syncHologramsTo(Location loc) {
        if (loc == null || loc.getWorld() == null) return;
        teleportHolo(nameTag, stackAt(loc, 0));
        teleportHolo(footer, stackAt(loc, 1));
        for (int i = 0; i < contentLines.size(); i++) {
            teleportHolo(contentLines.get(i), stackAt(loc, 2 + i));
        }
        teleportHolo(header, stackAt(loc, 2 + contentLines.size()));
    }

    private static void teleportHolo(ArmorStand s, Location loc) {
        if (s == null || s.isDead()) return;
        s.teleport(loc);
    }

    private Location stackAt(Location home, int index) {
        return home.clone().add(0, BASE_Y + index * STEP_Y, 0);
    }

    private Component headerComponent() {
        return Component.text()
            .append(Component.text("── ", RULE_COLOR))
            .append(Component.text(agentId, NamedTextColor.YELLOW, TextDecoration.BOLD))
            .append(Component.text(" · ", NamedTextColor.GRAY))
            .append(Component.text(room, NamedTextColor.GOLD))
            .append(Component.text(" ──────────────", RULE_COLOR))
            .build();
    }

    private static ArmorStand spawnHolo(Location loc, Component text) {
        ArmorStand s = (ArmorStand) loc.getWorld().spawnEntity(loc, EntityType.ARMOR_STAND);
        s.setInvisible(true);
        s.setMarker(true);
        s.setGravity(false);
        s.setSmall(true);
        s.setBasePlate(false);
        s.setInvulnerable(true);
        s.setCustomNameVisible(true);
        s.customName(text);
        s.setPersistent(true);
        return s;
    }

    public void setStatus(String status, String detail) {
        this.status = status;
        Component line = Component.text("[" + status + "] " + role, statusColor(status));
        if (detail != null && !detail.isBlank()) {
            line = line.append(Component.text(" — " + detail, NamedTextColor.GRAY));
        }
        nameTag.customName(line);
        updateHeldItem(status, detail);
        updateBossBar(status, detail);
    }

    /**
     * Head back to the desk NOW — called the instant a player addresses this agent
     * (via chat / gaze) so it returns to its workstation as you talk to it, instead
     * of waiting out the model latency before the busy-lock pins it. Walks back if
     * it's close (the leash keeps it within a few blocks), snaps if it somehow
     * strayed far. No-op while live-building (the build puppet owns the body).
     */
    public void returnHome() {
        if (villager == null || villager.isDead() || building) return;
        Location cur = villager.getLocation();
        if (cur.getWorld() == null || originalHome.getWorld() == null
                || !cur.getWorld().equals(originalHome.getWorld())) return;
        double d2 = cur.distanceSquared(originalHome);
        if (d2 < 0.25) return;                       // already home
        if (stationary || d2 > 64.0) {               // pinned, or far → snap
            villager.teleport(originalHome);
            syncHologramsTo(originalHome);
            return;
        }
        if (!villager.hasAI()) villager.setAI(true);
        villager.getPathfinder().moveTo(originalHome, 1.2);  // brisk walk back to the desk
    }

    /** entries: oldest first, newest last. Newest renders at contentLines[0] (closest to head). */
    public void setScreen(List<ScreenEntry> entries) {
        int total = contentLines.size();
        for (int i = 0; i < total; i++) {
            int srcIndex = entries.size() - 1 - i;
            if (srcIndex >= 0) {
                contentLines.get(i).customName(formatEntry(entries.get(srcIndex)));
            } else {
                contentLines.get(i).customName(Component.empty());
            }
        }
        header.customName(headerComponent());
    }

    /** Append a single entry to the lectern book transcript. */
    public void appendTranscript(ScreenEntry entry, boolean isNewTurn) {
        if (isNewTurn && !transcriptLines.isEmpty()) {
            transcriptLines.add(Component.text("", NamedTextColor.DARK_GRAY));
            transcriptLines.add(Component.text("─── new turn ───", NamedTextColor.DARK_GRAY));
        }
        transcriptLines.add(formatTranscript(entry));
        rebuildBook();
    }

    public void chatBubble(String text) {
        if (contentLines.isEmpty()) return;
        contentLines.get(0).customName(
            Component.text("» " + truncate(text, 56), NamedTextColor.GREEN, TextDecoration.BOLD));
    }

    /** True while the underlying villager body still exists in the world. */
    public boolean isAlive() {
        return villager != null && !villager.isDead();
    }

    public void remove() {
        if (positionTask != null) { positionTask.cancel(); positionTask = null; }
        if (villager != null && !villager.isDead()) villager.remove();
        if (nameTag != null && !nameTag.isDead()) nameTag.remove();
        if (footer != null && !footer.isDead()) footer.remove();
        if (header != null && !header.isDead()) header.remove();
        for (ArmorStand s : contentLines) if (!s.isDead()) s.remove();
        for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(bossBar);
        // Leave the lectern + final book behind as a record. The player can break it manually.
    }

    // ── held-item indicator ────────────────────────────────────────────────

    private void updateHeldItem(String status, String detail) {
        // While building, the mason holds the block it's placing (set in buildStep);
        // don't let a status flip swap it out.
        if (building) return;
        EntityEquipment eq = villager.getEquipment();
        if (eq == null) return;
        Material m = materialFor(status, detail);
        eq.setItemInMainHand(m == null ? null : new ItemStack(m), true);
    }

    private static Material materialFor(String status, String detail) {
        if (status == null) return null;
        switch (status) {
            case "idle":     return null;
            case "speaking": return Material.WHEAT;          // wheat = visible "yapping" gesture
            case "thinking": return Material.CLOCK;
            case "done":     return Material.EMERALD;
            case "error":    return Material.REDSTONE;
            case "tool_call": {
                if (detail == null) return Material.PAPER;
                String t = detail.toLowerCase();
                if (t.startsWith("gmail"))     return Material.PAPER;
                if (t.startsWith("meta_ads"))  return Material.GOLD_INGOT;
                if (t.startsWith("notes"))     return Material.WRITABLE_BOOK;
                if (t.equals("bash"))          return Material.COMMAND_BLOCK;
                if (t.equals("read"))          return Material.BOOK;
                if (t.equals("edit") || t.equals("write")) return Material.WRITABLE_BOOK;
                if (t.equals("grep") || t.equals("glob"))  return Material.SPYGLASS;
                if (t.equals("finish_task"))   return Material.EMERALD;
                if (t.startsWith("say"))       return Material.WHEAT;
                return Material.PAPER;
            }
            default: return null;
        }
    }

    // ── BossBar HUD ────────────────────────────────────────────────────────

    private void updateBossBar(String status, String detail) {
        boolean active = !"idle".equals(status);
        bossBar.name(bossTitle(status, detail));
        bossBar.color(bossColor(status));
        bossBar.progress(active ? 1f : 0f);
        World world = villager.getWorld();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (active && p.getWorld().equals(world)) {
                p.showBossBar(bossBar);
            } else {
                p.hideBossBar(bossBar);
            }
        }
    }

    private Component bossTitle(String status, String detail) {
        var b = Component.text()
            .append(Component.text("[" + agentId + "] ", NamedTextColor.AQUA, TextDecoration.BOLD))
            .append(Component.text(status, statusColor(status)));
        if (detail != null && !detail.isBlank()) {
            b.append(Component.text(" — " + detail, NamedTextColor.WHITE));
        }
        return b.build();
    }

    private static BossBar.Color bossColor(String status) {
        return switch (status) {
            case "thinking" -> BossBar.Color.BLUE;
            case "tool_call" -> BossBar.Color.PURPLE;
            case "speaking" -> BossBar.Color.GREEN;
            case "done" -> BossBar.Color.YELLOW;
            case "error" -> BossBar.Color.RED;
            default -> BossBar.Color.WHITE;
        };
    }

    // ── lectern + transcript book ──────────────────────────────────────────

    private Block placeLectern(Location home) {
        // Try cardinal neighbors at the villager's foot level; first air block wins.
        BlockFace[] faces = { BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST };
        Block at = home.getBlock();
        for (BlockFace f : faces) {
            Block b = at.getRelative(f);
            if (b.getType() == Material.AIR || b.getType() == Material.LECTERN) {
                if (b.getType() != Material.LECTERN) b.setType(Material.LECTERN, false);
                return b;
            }
        }
        return null; // no spot — board still works, book just won't be reachable.
    }

    private void rebuildBook() {
        if (lecternBlock == null || lecternBlock.getType() != Material.LECTERN) return;
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.title(Component.text(agentId + "'s log"));
        meta.author(Component.text("AgentCraft"));
        meta.pages(buildPages());
        book.setItemMeta(meta);
        try {
            Lectern state = (Lectern) lecternBlock.getState();
            state.getInventory().setItem(0, book);
            state.update();
        } catch (ClassCastException ignored) {
            // Lectern was removed; drop the reference so future appends don't keep retrying.
            lecternBlock = null;
        }
    }

    private List<Component> buildPages() {
        // Build cover page, then paginate the transcript into chunks of PAGE_LINE_BUDGET lines.
        List<Component> pages = new ArrayList<>();
        pages.add(Component.text()
            .append(Component.text(agentId + "\n", NamedTextColor.DARK_AQUA, TextDecoration.BOLD))
            .append(Component.text(role + "\n", NamedTextColor.DARK_GRAY))
            .append(Component.text(room + "\n\n", NamedTextColor.DARK_GRAY))
            .append(Component.text("→ flip the page", NamedTextColor.GRAY, TextDecoration.ITALIC))
            .build());
        // Render newest entries last (matches reading order).
        for (int i = 0; i < transcriptLines.size(); i += PAGE_LINE_BUDGET) {
            var b = Component.text();
            int end = Math.min(i + PAGE_LINE_BUDGET, transcriptLines.size());
            for (int j = i; j < end; j++) {
                b.append(transcriptLines.get(j));
                if (j < end - 1) b.append(Component.text("\n"));
            }
            pages.add(b.build());
        }
        // WRITTEN_BOOK hard cap is 100 pages — drop oldest pages once we hit the ceiling.
        while (pages.size() > 100) pages.remove(1); // keep cover page
        return pages;
    }

    private Component formatTranscript(ScreenEntry e) {
        TextColor color = colorFor(e.kind() == null ? "system" : e.kind());
        return Component.text()
            .append(Component.text(CLOCK.format(LocalTime.now()) + " ", NamedTextColor.DARK_GRAY))
            .append(Component.text(glyphFor(e.kind()) + " ", color, TextDecoration.BOLD))
            .append(Component.text(truncate(e.text(), 80), color))
            .build();
    }

    private Component timestamped(NamedTextColor color, String text) {
        return Component.text()
            .append(Component.text(CLOCK.format(LocalTime.now()) + " ", NamedTextColor.DARK_GRAY))
            .append(Component.text(text, color))
            .build();
    }

    // ── shared formatting helpers ──────────────────────────────────────────

    private static Component formatEntry(ScreenEntry e) {
        String kind = e.kind() == null ? "system" : e.kind();
        TextColor color = colorFor(kind);
        String glyph = glyphFor(kind);
        return Component.text()
            .append(Component.text("│ ", RULE_COLOR))
            .append(Component.text(glyph + " ", color, TextDecoration.BOLD))
            .append(Component.text(truncate(e.text(), 56), color))
            .build();
    }

    private static String glyphFor(String kind) {
        return switch (kind) {
            case "think"  -> "▸";
            case "tool"   -> "■";
            case "result" -> "←";
            case "say"    -> "»";
            case "done"   -> "✓";
            case "error"  -> "✗";
            default       -> "·";
        };
    }

    private static TextColor colorFor(String kind) {
        return switch (kind) {
            case "think"  -> NamedTextColor.AQUA;
            case "tool"   -> NamedTextColor.LIGHT_PURPLE;
            case "result" -> NamedTextColor.GRAY;
            case "say"    -> NamedTextColor.GREEN;
            case "done"   -> NamedTextColor.GOLD;
            case "error"  -> NamedTextColor.RED;
            default       -> NamedTextColor.DARK_GRAY;
        };
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    private static NamedTextColor statusColor(String s) {
        return switch (s) {
            case "thinking" -> NamedTextColor.AQUA;
            case "tool_call" -> NamedTextColor.LIGHT_PURPLE;
            case "speaking" -> NamedTextColor.GREEN;
            case "error" -> NamedTextColor.RED;
            case "done" -> NamedTextColor.GOLD;
            default -> NamedTextColor.YELLOW;
        };
    }

    public String agentId() { return agentId; }
    public String role() { return role; }
    public String room() { return room; }
    public Location home() { return villager.getLocation(); }
    public Vector position() { return villager.getLocation().toVector(); }

    /** True if {@code e} is this agent's villager body (used to resolve clicks). */
    public boolean isBody(org.bukkit.entity.Entity e) {
        return villager != null && e != null && villager.getUniqueId().equals(e.getUniqueId());
    }
}

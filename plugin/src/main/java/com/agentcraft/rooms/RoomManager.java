package com.agentcraft.rooms;

import com.agentcraft.AgentCraftPlugin;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class RoomManager {

    private final AgentCraftPlugin plugin;
    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerRoom = new ConcurrentHashMap<>();
    private final File file;

    public RoomManager(AgentCraftPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "rooms.yml");
        load();
    }

    public Room define(String name, Location center) {
        int radius = plugin.getConfig().getInt("display.room_radius", 8);
        return define(name, center, radius);
    }

    /**
     * Variant of {@link #define(String, Location)} that lets the caller pick
     * a custom containment radius. Useful for big rooms like the cinema
     * whose footprint is much larger than the default {@code display.room_radius}.
     */
    public Room define(String name, Location center, int radius) {
        Room r = new Room(name, center.getWorld().getName(), center.getX(), center.getY(), center.getZ(), radius);
        rooms.put(name.toLowerCase(Locale.ROOT), r);
        save();
        return r;
    }

    /**
     * Register {@code alias} as a copy of an existing room (same coordinates,
     * same radius). No-op if {@code alias} already exists or the source isn't
     * registered. Used at plugin enable time to make canonical district ids
     * (ads, mail, task, …) resolve even when only the hyphenated room name
     * (ads-command, mail-room, agent-camp, …) was registered by the builder.
     */
    public boolean defineAlias(String alias, String sourceName) {
        return defineAlias(alias, sourceName, false);
    }

    /**
     * Variant of {@link #defineAlias(String, String)} that overwrites a stale
     * alias when {@code overwrite} is true. The canonical district ids
     * ("hq", "ads", "mail", "code", "task") sometimes hang around in
     * rooms.yml as stale leaf entries from an older world build. The
     * plugin-enable backfill uses {@code overwrite=true} so the alias
     * re-aligns to the freshest registered candidate (the PRD-correct
     * district from the current /omo build) instead of shadowing it.
     *
     * <p>Returns {@code true} iff the alias now resolves to the source room.
     */
    public boolean defineAlias(String alias, String sourceName, boolean overwrite) {
        if (alias == null || sourceName == null) return false;
        String aliasKey = alias.toLowerCase(Locale.ROOT);
        Room src = rooms.get(sourceName.toLowerCase(Locale.ROOT));
        if (src == null) return false;
        if (rooms.containsKey(aliasKey) && !overwrite) return false;
        Room copy = new Room(alias, src.worldName(), src.x(), src.y(), src.z(), src.radius());
        rooms.put(aliasKey, copy);
        save();
        return true;
    }

    public Room get(String name) { return rooms.get(name.toLowerCase(Locale.ROOT)); }

    /** Remove a room from the registry. Does not touch the world. */
    public Room remove(String name) {
        if (name == null) return null;
        Room r = rooms.remove(name.toLowerCase(Locale.ROOT));
        if (r != null) save();
        return r;
    }

    public Collection<Room> all() { return rooms.values(); }

    public Room roomAt(Location loc) {
        for (Room r : rooms.values()) if (r.contains(loc)) return r;
        return null;
    }

    public String currentRoom(Player p) { return playerRoom.get(p.getUniqueId()); }

    public void setCurrentRoom(Player p, String room) {
        if (room == null) playerRoom.remove(p.getUniqueId());
        else playerRoom.put(p.getUniqueId(), room);
    }

    private void load() {
        if (!file.exists()) return;
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        for (String key : cfg.getKeys(false)) {
            String world = cfg.getString(key + ".world");
            double x = cfg.getDouble(key + ".x");
            double y = cfg.getDouble(key + ".y");
            double z = cfg.getDouble(key + ".z");
            int radius = cfg.getInt(key + ".radius", 8);
            rooms.put(key.toLowerCase(Locale.ROOT), new Room(key, world, x, y, z, radius));
        }
    }

    private void save() {
        FileConfiguration cfg = new YamlConfiguration();
        for (Room r : rooms.values()) {
            cfg.set(r.name() + ".world", r.worldName());
            cfg.set(r.name() + ".x", r.x());
            cfg.set(r.name() + ".y", r.y());
            cfg.set(r.name() + ".z", r.z());
            cfg.set(r.name() + ".radius", r.radius());
        }
        try {
            plugin.getDataFolder().mkdirs();
            cfg.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("could not save rooms.yml: " + e.getMessage());
        }
    }
}

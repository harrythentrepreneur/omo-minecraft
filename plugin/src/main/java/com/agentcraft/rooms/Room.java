package com.agentcraft.rooms;

import org.bukkit.Location;
import org.bukkit.World;

public record Room(String name, String worldName, double x, double y, double z, int radius) {

    public boolean contains(Location loc) {
        if (loc.getWorld() == null) return false;
        if (!loc.getWorld().getName().equals(worldName)) return false;
        double dx = loc.getX() - x;
        double dy = loc.getY() - y;
        double dz = loc.getZ() - z;
        return (dx * dx + dy * dy + dz * dz) <= ((double) radius * radius);
    }

    public Location center(World w) {
        return new Location(w, x, y, z);
    }
}

package dev.xautral.death.diff.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.generator.structure.Structure;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class StructureCache {

    private record Entry(long computedAt, Location location) {
    }

    private static final long TTL_TICKS = 600L; // 30 segundos

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public Location locate(World world, String name, Structure structure) {
        if (structure == null || world.getPlayers().isEmpty()) {
            return null;
        }
        String key = world.getUID() + ":" + name;
        long now = Bukkit.getCurrentTick();
        Entry entry = cache.get(key);
        if (entry == null || now - entry.computedAt() > TTL_TICKS) {
            var result = world.locateNearestStructure(
                    world.getPlayers().get(0).getLocation(), structure, 256, false);
            entry = new Entry(now, result == null ? null : result.getLocation());
            cache.put(key, entry);
        }
        return entry.location();
    }
}

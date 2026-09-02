package dev.xautral.death.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class PendingStore {

    private static final Type TYPE = new TypeToken<Map<String, String>>() {
    }.getType();

    private final JavaPlugin plugin;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<String, String> pending = new HashMap<>();
    private final Map<UUID, Location> deathLocations = new HashMap<>();
    private final Map<UUID, Location> chestLocations = new HashMap<>();
    private Path file;

    public PendingStore(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        file = plugin.getDataFolder().toPath().resolve("pending.json");
        try {
            Files.createDirectories(plugin.getDataFolder().toPath());
            if (!Files.exists(file)) {
                return;
            }
            lock.writeLock().lock();
            try {
                Map<String, String> loaded =
                        gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), TYPE);
                if (loaded != null) {
                    pending.putAll(loaded);
                }
            } finally {
                lock.writeLock().unlock();
            }
        } catch (IOException e) {
            plugin.getSLF4JLogger().error("No se pudo cargar pending.json", e);
        }
    }

    public void markPending(UUID uuid, GameMode previous) {
        lock.writeLock().lock();
        try {
            pending.put(uuid.toString(), previous.name());
            save();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public GameMode takePending(UUID uuid) {
        lock.writeLock().lock();
        try {
            String name = pending.remove(uuid.toString());
            if (name == null) {
                return null;
            }
            save();
            try {
                return GameMode.valueOf(name);
            } catch (IllegalArgumentException e) {
                return GameMode.SURVIVAL;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void markDeathLocation(UUID uuid, Location location) {
        lock.writeLock().lock();
        try {
            deathLocations.put(uuid, location.clone());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Location takeDeathLocation(UUID uuid) {
        lock.writeLock().lock();
        try {
            return deathLocations.remove(uuid);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void markChestLocation(UUID uuid, Location location) {
        lock.writeLock().lock();
        try {
            chestLocations.put(uuid, location.clone());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Location takeChestLocation(UUID uuid) {
        lock.writeLock().lock();
        try {
            return chestLocations.remove(uuid);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void save() {
        try {
            Files.writeString(file, gson.toJson(pending, TYPE), StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getSLF4JLogger().error("No se pudo guardar pending.json", e);
        }
    }
}

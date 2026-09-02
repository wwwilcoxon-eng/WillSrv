package dev.willsrv.xlogin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.security.SecureRandom;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

final class PlayerStore {

    record StoredPlayer(String name, String passSalt, String passHash,
                        String ipHash, long createdAt) {
    }

    private final XLoginPlugin plugin;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Type type = new TypeToken<Map<String, StoredPlayer>>() {
    }.getType();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final Path dataFile;
    private final Path secretFile;
    private final Map<String, StoredPlayer> players = new HashMap<>();
    private byte[] ipSecretKey;

    PlayerStore(XLoginPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = plugin.getDataFolder().toPath().resolve("data.json");
        this.secretFile = plugin.getDataFolder().toPath().resolve("secret.key");
    }

    void load() throws IOException {
        Files.createDirectories(plugin.getDataFolder().toPath());
        loadSecretKey();
        if (Files.exists(dataFile)) {
            lock.writeLock().lock();
            try {
                Map<String, StoredPlayer> loaded = gson.fromJson(Files.readString(dataFile, StandardCharsets.UTF_8), type);
                if (loaded != null) {
                    players.putAll(loaded);
                }
            } finally {
                lock.writeLock().unlock();
            }
        }
    }

    private void loadSecretKey() throws IOException {
        if (Files.exists(secretFile)) {
            ipSecretKey = Base64.getDecoder().decode(Files.readString(secretFile, StandardCharsets.UTF_8).trim());
        } else {
            ipSecretKey = new byte[32];
            new SecureRandom().nextBytes(ipSecretKey);
            Files.writeString(secretFile, Base64.getEncoder().encodeToString(ipSecretKey), StandardCharsets.UTF_8);
        }
    }

    boolean isRegistered(UUID uuid) {
        lock.readLock().lock();
        try {
            return players.containsKey(uuid.toString());
        } finally {
            lock.readLock().unlock();
        }
    }

    StoredPlayer get(UUID uuid) {
        lock.readLock().lock();
        try {
            return players.get(uuid.toString());
        } finally {
            lock.readLock().unlock();
        }
    }

    void register(UUID uuid, String name, char[] password, String ip) {
        CryptoUtil.HashedSecret hash = CryptoUtil.pbkdf2(password);
        StoredPlayer stored = new StoredPlayer(name, hash.saltB64(), hash.hashB64(),
                CryptoUtil.hmacSha256(ipSecretKey, normalizeIp(ip)), System.currentTimeMillis());
        lock.writeLock().lock();
        try {
            players.put(uuid.toString(), stored);
            save();
        } finally {
            lock.writeLock().unlock();
        }
    }

    void delete(UUID uuid) {
        lock.writeLock().lock();
        try {
            players.remove(uuid.toString());
            save();
        } finally {
            lock.writeLock().unlock();
        }
    }

    boolean ipMatches(UUID uuid, String ip) {
        StoredPlayer stored = get(uuid);
        if (stored == null) {
            return false;
        }
        String candidate = CryptoUtil.hmacSha256(ipSecretKey, normalizeIp(ip));
        return constantTimeEquals(stored.ipHash(), candidate);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String normalizeIp(String ip) {
        if (ip == null) return "";
        if (ip.startsWith("/")) ip = ip.substring(1);
        // IPv6 con brackets: [::1]:12345
        if (ip.startsWith("[")) {
            int close = ip.indexOf(']');
            if (close > 0) {
                // dentro de [] es la IP, lo que sigue es :puerto
                return ip.substring(1, close);
            }
        }
        // si contiene :: es IPv6 sin brackets, no intentar quitar puerto simple
        if (ip.contains("::")) {
            // para IPv6 puro sin puerto, devuelve tal cual (sin :puerto)
            // si tiene puerto al final tipo ::1:12345 es ambiguo, mejor solo quitar último :port si es numérico
            int lastColon = ip.lastIndexOf(':');
            if (lastColon > 0) {
                String after = ip.substring(lastColon + 1);
                if (after.matches("\\d+")) {
                    // verifica que antes del último colon haya :: -> probablemente puerto
                    String before = ip.substring(0, lastColon);
                    if (before.contains(":")) return before;
                }
            }
            return ip;
        }
        int index = ip.indexOf(':');
        if (index > 0) return ip.substring(0, index);
        return ip;
    }

    private void save() {
        try {
            Files.writeString(dataFile, gson.toJson(players, type), StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getSLF4JLogger().error("No se pudo guardar data.json", e);
        }
    }
}

package dev.xautral.death.lives;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import net.kyori.adventure.text.Component;
import org.bukkit.BanEntry;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Sistema de VIDAS (paralelo al LifeSteal de corazones).
 *
 * Cada jugador empieza con 10 vidas. Morir resta una vida; matar a alguien
 * suma una (hasta el maximo). Al llegar a 0 vidas el jugador queda
 * PERMABANEADO temporalmente (1 dia real, con cuenta atras en el mensaje de
 * kick al intentar entrar). Solo se puede revivir con un Totem of Life.
 *
 * Persistente a prueba de reinicios: todo se guarda en lives.json (data folder),
 * que NO se toca con /reload (requiere reinicio, y los datos sobreviven).
 */
public final class LivesManager implements Listener {

    public static final int MAX_LIVES = 10;
    public static final String BAN_PREFIX = "PERMALIVES: ";
    private static final long BAN_MILLIS = 24L * 60 * 60 * 1000;     // 1 dia
    private static final long MOTD_MILLIS = 5L * 60 * 1000;          // 5 minutos

    private static final Type PLAYERS_TYPE =
            new TypeToken<Map<String, PlayerData>>() {}.getType();
    private static final Type MAP_STR_TYPE =
            new TypeToken<Map<String, String>>() {}.getType();

    private final JavaPlugin plugin;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<String, PlayerData> players = new HashMap<>(); // uuid -> datos
    private final Map<String, String> pendingRevive = new HashMap<>(); // name.lower -> revivor
    private MotdData motd;
    private Path file;

    public LivesManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------------- Datos persistentes ----------------

    private static final class PlayerData {
        int lives = MAX_LIVES;
        int totalDeaths = 0;

        PlayerData() {
        }
    }

    private static final class MotdData {
        String reviveName;
        String revivedName;
        long timestamp;

        MotdData() {
        }
    }

    public void load() {
        file = plugin.getDataFolder().toPath().resolve("lives.json");
        try {
            Files.createDirectories(plugin.getDataFolder().toPath());
            if (!Files.exists(file)) {
                return;
            }
            JsonObject root = JsonParser.parseString(
                    Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.has("players")) {
                Map<String, PlayerData> loaded =
                        gson.fromJson(root.get("players"), PLAYERS_TYPE);
                if (loaded != null) {
                    players.putAll(loaded);
                }
            }
            if (root.has("pendingRevive")) {
                Map<String, String> pr =
                        gson.fromJson(root.get("pendingRevive"), MAP_STR_TYPE);
                if (pr != null) {
                    pendingRevive.putAll(pr);
                }
            }
            if (root.has("motd")) {
                motd = gson.fromJson(root.get("motd"), MotdData.class);
            }
        } catch (Exception e) {
            plugin.getSLF4JLogger().error("No se pudo cargar lives.json", e);
        }
    }

    private synchronized void save() {
        try {
            JsonObject root = new JsonObject();
            root.add("players", gson.toJsonTree(players, PLAYERS_TYPE));
            root.add("pendingRevive", gson.toJsonTree(pendingRevive, MAP_STR_TYPE));
            if (motd != null) {
                root.add("motd", gson.toJsonTree(motd, MotdData.class));
            }
            Files.writeString(file, gson.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getSLF4JLogger().error("No se pudo guardar lives.json", e);
        }
    }

    // ---------------- Consultas ----------------

    private PlayerData data(UUID uuid) {
        return players.computeIfAbsent(uuid.toString(), k -> new PlayerData());
    }

    /** Vidas actuales de un jugador. */
    public int livesOf(UUID uuid) {
        return data(uuid).lives;
    }

    /** Muertes totales acumuladas (para el sonido de wither). */
    public int totalDeaths(UUID uuid) {
        return data(uuid).totalDeaths;
    }

    /**
     * Procesa una muerte: resta una vida, suma una al asesino y,
     * si llega a 0 vidas, PERMABANEA temporalmente (1 dia).
     *
     * @return vidas restantes despues de la muerte.
     */
    public int handleDeath(Player victim, Player killer, String deathLog) {
        PlayerData pd = data(victim.getUniqueId());
        pd.totalDeaths++;
        pd.lives = Math.max(0, pd.lives - 1);
        if (killer != null && killer != victim) {
            grantLife(killer.getUniqueId());
        }
        save();
        if (pd.lives <= 0) {
            permaban(victim, deathLog);
        }
        return pd.lives;
    }

    private void grantLife(UUID uuid) {
        PlayerData pd = data(uuid);
        pd.lives = Math.min(MAX_LIVES, pd.lives + 1);
    }

    // ---------------- Permaban ----------------

    private void permaban(Player victim, String deathLog) {
        String name = victim.getName();
        Date exp = new Date(System.currentTimeMillis() + BAN_MILLIS);
        Bukkit.getBanList(BanList.Type.NAME)
                .addBan(name, BAN_PREFIX + deathLog, exp, "TotemOfLife");
        Bukkit.broadcastMessage("§4§l[Vidas] §c" + name
                + " §7perdió todas sus vidas y fue §4§lPERMABANEADO§7! (§e1 día§7)");
        Component kickMsg = Component.text("§4§l¡Has sido PERMABANEADO!\n")
                .append(Component.text("§7Tu muerte: §f" + deathLog + "\n"))
                .append(Component.text("§cVidas restantes: 0"));
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (victim.isOnline()) {
                victim.kick(kickMsg);
            }
        });
    }

    /** ¿Esta el jugador permabaneado (temporal) por el sistema de vidas? */
    public boolean isPermaBanned(String name) {
        BanEntry be = Bukkit.getBanList(BanList.Type.NAME).getBanEntry(name);
        return be != null && be.getReason() != null
                && be.getReason().startsWith(BAN_PREFIX);
    }

    /** Revive a un jugador (solo se consume el Totem en TotemOfLife). */
    public void onRevive(Player reviver, String bannedName) {
        Bukkit.getBanList(BanList.Type.NAME).pardon(bannedName);
        OfflinePlayer off = Bukkit.getOfflinePlayer(bannedName);
        data(off.getUniqueId()).lives = MAX_LIVES;
        pendingRevive.put(bannedName.toLowerCase(), reviver.getName());
        motd = new MotdData();
        motd.reviveName = reviver.getName();
        motd.revivedName = bannedName;
        motd.timestamp = System.currentTimeMillis();
        save();
    }

    // ---------------- Eventos ----------------

    /** Reaparecer al revivido en su spawnpoint y avisar quien lo revivio. */
    @EventHandler(priority = EventPriority.LOW)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        String reviveName = pendingRevive.remove(p.getName().toLowerCase());
        if (reviveName == null) {
            return;
        }
        data(p.getUniqueId()).lives = MAX_LIVES;
        save();
        p.sendMessage("§d§l[Totem of Life] §aHas sido revivido por §e"
                + reviveName + "§a. ¡Tus 10 vidas fueron restauradas!");
        Bukkit.broadcastMessage("§d§l[Totem of Life] §a" + reviveName
                + " §7ha resucitado a §e" + p.getName() + "§7.");
        Location target = resolveSpawn(p);
        p.getWorld().playSound(p.getLocation(), Sound.ITEM_TOTEM_USE, 1.5f, 1f);
        p.getWorld().spawnParticle(org.bukkit.Particle.TOTEM_OF_UNDYING,
                p.getLocation().clone().add(0, 1, 0), 40, 0.5, 0.5, 0.5, 0.2);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) {
                p.teleportAsync(target).thenRun(() -> p.setFallDistance(0));
            }
        });
    }

    /** Mensaje de kick con temporizador de 1 dia para los permabaneados. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLogin(PlayerLoginEvent event) {
        String name = event.getPlayer().getName();
        BanEntry be = Bukkit.getBanList(BanList.Type.NAME).getBanEntry(name);
        if (be == null || be.getReason() == null
                || !be.getReason().startsWith(BAN_PREFIX)) {
            return;
        }
        String deathLog = be.getReason().substring(BAN_PREFIX.length());
        Date exp = be.getExpiration();
        long remaining = exp == null
                ? BAN_MILLIS : exp.getTime() - System.currentTimeMillis();
        if (remaining <= 0) {
            Bukkit.getBanList(BanList.Type.NAME).pardon(name);
            return;
        }
        Component msg = Component.text("§4§l¡Has sido PERMABANEADO!\n")
                .append(Component.text("§7Motivo: §f" + deathLog + "\n"))
                .append(Component.text("§cVidas restantes: 0\n"))
                .append(Component.text("§eTiempo restante: §f"
                        + formatRemaining(remaining)));
        event.disallow(PlayerLoginEvent.Result.KICK_BANNED, msg);
    }

    /** Durante 5 min tras un revive, el MOTD muestra quién lo revivió. */
    @EventHandler(priority = EventPriority.LOW)
    public void onPing(ServerListPingEvent event) {
        if (motd == null) {
            return;
        }
        if (System.currentTimeMillis() - motd.timestamp > MOTD_MILLIS) {
            motd = null;
            save();
            return;
        }
        String line = "§d§l[Totem of Life] §7Revivido por §e" + motd.reviveName
                + "§7: §6" + motd.revivedName;
        event.setMotd(event.getMotd() + "\n" + line);
    }

    // ---------------- Helpers ----------------

    private static Location resolveSpawn(Player p) {
        Location respawn = p.getRespawnLocation();
        if (respawn != null && respawn.getWorld() != null) {
            return respawn.clone();
        }
        World w = p.getWorld();
        if (w == null) {
            w = Bukkit.getWorlds().stream()
                    .filter(world -> world.getEnvironment() == World.Environment.NORMAL)
                    .findFirst().orElse(null);
        }
        if (w == null) {
            return p.getLocation().clone();
        }
        return w.getSpawnLocation().clone();
    }

    private static String formatRemaining(long ms) {
        long totalSec = Math.max(0, ms / 1000);
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) {
            return h + "h " + m + "m " + s + "s";
        }
        if (m > 0) {
            return m + "m " + s + "s";
        }
        return s + "s";
    }
}

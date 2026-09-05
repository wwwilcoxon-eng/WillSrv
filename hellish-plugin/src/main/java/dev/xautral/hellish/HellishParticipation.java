package dev.xautral.hellish;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rastrea la participacion de cada jugador durante una activacion de Hellish:
 * tiempo dentro del modo, muertes y golpes recibidos.
 */
public final class HellishParticipation implements Listener {

    public static final long MIN_PLAYTIME_MS = 60_000L; // 1 minuto

    public static final class Data {
        public long joinedAt;
        public int deaths;
        public int hits;
        public final java.util.Set<String> bossesKilled = new java.util.HashSet<>();
    }

    private final Map<UUID, Data> participants = new ConcurrentHashMap<>();

    /** Registra a todos los online al iniciar el modo. */
    void startForAll(Iterable<? extends Player> online) {
        participants.clear();
        for (Player player : online) {
            join(player);
        }
    }

    public void join(Player player) {
        if (participants.containsKey(player.getUniqueId())) {
            return;
        }
        Data data = new Data();
        data.joinedAt = System.currentTimeMillis();
        participants.put(player.getUniqueId(), data);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // Se conserva el registro para entregar recompensas al volver? No:
        // si se va durante el modo pierde su tiempo; se limpia
        participants.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        Data data = participants.get(player.getUniqueId());
        if (data != null && event.getFinalDamage() > 0) {
            data.hits++;
        }
    }

    public void recordDeath(Player player) {
        Data data = participants.get(player.getUniqueId());
        if (data != null) {
            data.deaths++;
        }
    }

    public void recordBossKill(Player killer, String bossId) {
        Data data = participants.get(killer.getUniqueId());
        if (data != null) {
            data.bossesKilled.add(bossId);
        }
    }

    public Data get(Player player) {
        return participants.get(player.getUniqueId());
    }

    public Map<UUID, Data> all() {
        return participants;
    }

    public void clearForRestart() {
        participants.clear();
    }

    public static long playedMs(Data data) {
        return System.currentTimeMillis() - data.joinedAt;
    }
}

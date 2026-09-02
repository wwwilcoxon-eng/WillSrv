package dev.xautral.death.item;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Team;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Angry Wand simple: toca a la victima A (click izq o der), luego a la
 * victima B — y se odian al instante hasta la muerte.
 */
public final class AngryWandManager implements Listener, Runnable {

    private static final int HOVER_REVEAL_TICKS = 100; // 5 segundos
    private static final double HOVER_RANGE = 24.0;

    private final JavaPlugin plugin;
    private final AngryWand wand;

    /** Victima A pendiente por jugador (esperando la B). */
    private final Map<UUID, UUID> pendingFirst = new HashMap<>();

    /** Odios activos: uuid -> uuid del enemigo (ambas direcciones). */
    private final Map<UUID, UUID> grudges = new HashMap<>();
    /** Glows temporales de revelacion: uuid -> tick de expiracion. */
    private final Map<UUID, Long> revealGlow = new HashMap<>();

    public AngryWandManager(JavaPlugin plugin, AngryWand wand) {
        this.plugin = plugin;
        this.wand = wand;
        Bukkit.getScheduler().runTaskTimer(plugin, this, 10L, 10L);
    }

    // ---------------- Clicks ----------------

    @EventHandler
    public void onRightClick(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        handle(event.getPlayer(), event.getRightClicked(), event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onLeftClick(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            handle(player, event.getEntity(), null);
        }
    }

    private void handle(Player player, org.bukkit.entity.Entity clicked,
                        org.bukkit.event.Cancellable cancellable) {
        if (!wand.isWand(player.getInventory().getItemInMainHand())) {
            return;
        }
        if (!(clicked instanceof LivingEntity target)) {
            return;
        }
        if (cancellable != null) {
            cancellable.setCancelled(true);
        }

        UUID pending = pendingFirst.remove(player.getUniqueId());
        if (pending == null) {
            // Marcar victima A
            pendingFirst.put(player.getUniqueId(), target.getUniqueId());
            target.setGlowing(true);
            player.sendMessage("§6[Death] §bVictima A§e marcada: "
                    + displayName(target)
                    + "§7. Toca ahora a quien debe ODIARLA.");
            player.playSound(player.getLocation(),
                    Sound.UI_BUTTON_CLICK, 1.0f, 0.8f);
            return;
        }

        if (pending.equals(target.getUniqueId())) {
            // Misma entidad: cancelar marca
            unsetGlowing(target);
            player.sendMessage("§6[Death] §7Odio cancelado.");
            return;
        }

        LivingEntity first = living(pending);
        if (first == null || !first.isValid()) {
            unsetGlowing(target);
            player.sendMessage("§6[Death] §cLa victima A ya no existe. Marca de nuevo.");
            return;
        }
        if (first instanceof Player && target instanceof Player) {
            unsetGlowing(first);
            player.sendMessage("§6[Death] §cNo puedes enfrentar a dos jugadores.");
            return;
        }

        // ¡ODIO INMEDIATO!
        startGrudge(first, target);

        first.getWorld().spawnParticle(Particle.ANGRY_VILLAGER,
                first.getLocation().add(0, 1.5, 0), 6, 0.3, 0.3, 0.3, 0);
        target.getWorld().spawnParticle(Particle.ANGRY_VILLAGER,
                target.getLocation().add(0, 1.5, 0), 6, 0.3, 0.3, 0.3, 0);
        player.playSound(player.getLocation(),
                Sound.ENTITY_ENDERMAN_SCREAM, 1.0f, 0.6f);
        player.sendMessage("§6[Death] §4¡" + displayName(first) + " §cy "
                + "§4" + displayName(target) + "§c SE ODIAN hasta la muerte!");

        // Bossbar estilo Raid si hay mas de 3 jugadores conectados
        if (Bukkit.getOnlinePlayers().size() > 3) {
            showWarBar();
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID pending = pendingFirst.remove(event.getPlayer().getUniqueId());
        if (pending != null && Bukkit.getEntity(pending) instanceof LivingEntity le) {
            unsetGlowing(le);
        }
    }

    // ---------------- Tarea: mantener odios + revelado ----------------

    @Override
    public void run() {
        maintainGrudges();
        revealOnHover();
        expireRevealGlows();
        updateWarBar();
    }

    private void maintainGrudges() {
        Iterator<Map.Entry<UUID, UUID>> it = grudges.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, UUID> entry = it.next();
            LivingEntity self = living(entry.getKey());
            EntityHolder enemy = get(entry.getValue());
            if (self == null || !self.isValid() || self.isDead()
                    || enemy == null || !enemy.entity.isValid()
                    || enemy.entity.isDead()) {
                it.remove();
                continue;
            }
            retarget(self, enemy.entity);
        }
    }

    /** Crosshair: mirar a una entidad con odio -> glow rojo + enemigo brillante 5s. */
    private void revealOnHover() {
        Team redTeam = redTeam();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!wand.isWand(player.getInventory().getItemInMainHand())) {
                continue;
            }
            var result = player.getWorld().rayTraceEntities(
                    player.getEyeLocation(),
                    player.getEyeLocation().getDirection(),
                    HOVER_RANGE,
                    e -> e instanceof LivingEntity && e != player);
            if (result == null || !(result.getHitEntity() instanceof LivingEntity hit)) {
                continue;
            }
            UUID enemyId = grudges.get(hit.getUniqueId());
            if (enemyId == null) {
                continue;
            }
            // Glow ROJO sobre el que ya odia a alguien
            hit.setGlowing(true);
            redTeam.addEntry(hit.getUniqueId().toString());

            // Su enemigo aparece brillante durante 5 segundos
            EntityHolder enemy = get(enemyId);
            if (enemy != null && enemy.valid()) {
                enemy.entity.setGlowing(true);
                revealGlow.put(enemyId,
                        (long) Bukkit.getCurrentTick() + HOVER_REVEAL_TICKS);
                player.sendActionBar(net.kyori.adventure.text.Component.text(
                        "§4" + displayName(hit) + " §7odia a §c"
                                + displayName(enemy.entity)));
            }
        }
    }

    private void expireRevealGlows() {
        Iterator<Map.Entry<UUID, Long>> it = revealGlow.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> entry = it.next();
            if (Bukkit.getCurrentTick() < entry.getValue()) {
                continue;
            }
            it.remove();
            EntityHolder e = get(entry.getKey());
            if (e != null && e.valid()) {
                boolean stillAtWar = grudges.containsValue(e.entity.getUniqueId())
                        || grudges.containsKey(e.entity.getUniqueId());
                if (!stillAtWar) {
                    unsetGlowing(e.entity);
                } else {
                    redTeam().addEntry(e.entity.getUniqueId().toString());
                    e.entity.setGlowing(true);
                }
            }
        }
    }

    // ---------------- Bossbar estilo Raid ----------------

    private org.bukkit.boss.KeyedBossBar warBar;
    private int warInitialFighters;

    private void showWarBar() {
        removeWarBar();
        org.bukkit.NamespacedKey key =
                new org.bukkit.NamespacedKey(plugin, "war_of_hate");
        warBar = Bukkit.createBossBar(key,
                "§4⚔ Guerra de Odios ⚔",
                org.bukkit.boss.BarColor.RED,
                org.bukkit.boss.BarStyle.SEGMENTED_10);
        for (Player online : Bukkit.getOnlinePlayers()) {
            warBar.addPlayer(online);
        }
        warInitialFighters = countAliveFighters();
        updateWarBar();
    }

    private int countAliveFighters() {
        int alive = 0;
        for (UUID id : grudges.keySet()) {
            LivingEntity le = living(id);
            if (le != null && le.isValid() && !le.isDead()) {
                alive++;
            }
        }
        return alive;
    }

    private void updateWarBar() {
        if (warBar == null) {
            return;
        }
        int alive = countAliveFighters();
        if (alive <= 1 || grudges.isEmpty()) {
            removeWarBar();
            Bukkit.broadcastMessage("§6[Death] §7La guerra de odios ha terminado.");
            return;
        }
        warBar.setTitle("§4⚔ Guerra de Odios §7- §c" + (alive / 2) + " duelos restantes");
        double progress = warInitialFighters > 0
                ? Math.max(0.0, Math.min(1.0, (double) alive / warInitialFighters))
                : 0.0;
        warBar.setProgress(progress);
    }

    private void removeWarBar() {
        if (warBar != null) {
            warBar.removeAll();
            Bukkit.removeBossBar(warBar.getKey());
            warBar = null;
        }
    }

    // ---------------- Helpers ----------------

    private void startGrudge(LivingEntity a, LivingEntity b) {
        grudges.put(a.getUniqueId(), b.getUniqueId());
        grudges.put(b.getUniqueId(), a.getUniqueId());
        retarget(a, b);
        retarget(b, a);
        unsetGlowing(a);
        unsetGlowing(b);
    }

    private void retarget(LivingEntity self, LivingEntity enemy) {
        if (self instanceof Mob mob && mob.getTarget() != enemy) {
            mob.setTarget(enemy);
        }
    }

    private void unsetGlowing(LivingEntity entity) {
        Team team = redTeam();
        team.removeEntry(entity.getUniqueId().toString());
        entity.setGlowing(false);
    }

    private Team redTeam() {
        org.bukkit.scoreboard.Scoreboard sb =
                Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = sb.getTeam("dm_hate_red");
        if (team == null) {
            team = sb.registerNewTeam("dm_hate_red");
        }
        team.setColor(ChatColor.DARK_RED);
        return team;
    }

    private record EntityHolder(LivingEntity entity) {
        boolean valid() {
            return entity.isValid() && !entity.isDead();
        }
    }

    private EntityHolder get(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        if (Bukkit.getEntity(uuid) instanceof LivingEntity le) {
            return new EntityHolder(le);
        }
        return null;
    }

    private LivingEntity living(UUID uuid) {
        return Bukkit.getEntity(uuid) instanceof LivingEntity le ? le : null;
    }

    private static String displayName(LivingEntity entity) {
        if (entity.customName() != null) {
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                    .plainText().serialize(entity.customName());
        }
        return entity.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}

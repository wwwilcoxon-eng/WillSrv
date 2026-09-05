package dev.xautral.hellish.task;

import dev.xautral.hellish.DiffMode;
import dev.xautral.hellish.DiffModeManager;
import dev.xautral.hellish.item.HellishItems;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.entity.Skeleton;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Jefe Esqueleto de Hellish: un esqueleto que vuela sobre un phantom
 * invisible, alterna entre avalanzarse con mazo y disparar, y esta rodeado
 * por 4 displays de ballesta girando que disparan flechas lentas
 * teledirigidas cada 1.5 segundos (acelerando hasta impactar).
 */
public final class SkeletonBossManager implements Listener, Runnable {

    private static final double BOSS_MAX_HEALTH = 100.0;
    private static final int HOMING_INTERVAL_TICKS = 30; // 1.5s
    private static final double HOMING_ACCELERATION = 0.05;
    private static final double HOMING_MAX_SPEED = 2.2;

    private final JavaPlugin plugin;
    private final DiffModeManager manager;
    private final Random random;
    private final NamespacedKey bossKey;
    private final NamespacedKey dodecaedroKey;

    // Flechas teledirigidas activas: uuid de flecha -> uuid del objetivo
    private final Map<UUID, UUID> homingArrows = new HashMap<>();
    private LivingEntity bossVehicle;   // phantom invisible que lo vuela
    private Skeleton boss;
    private final ItemDisplay[] displays = new ItemDisplay[4];
    private double orbitAngle;
    private int tick;
    private boolean diving;

    public SkeletonBossManager(JavaPlugin plugin, DiffModeManager manager,
                               Random random, NamespacedKey dodecaedroKey) {
        this.plugin = plugin;
        this.manager = manager;
        this.random = random;
        this.dodecaedroKey = dodecaedroKey;
        this.bossKey = new NamespacedKey(plugin, "boss_skeleton");
        Bukkit.getScheduler().runTaskTimer(plugin, this, 5L, 3L);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public boolean hasNoBoss() {
        return boss == null || !boss.isValid();
    }

    /** Intenta spawnear al jefe cerca de un jugador al azar. */
    public void trySpawn() {
        if (!hasNoBoss()) {
            return;
        }
        java.util.List<Player> candidates = new java.util.ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getGameMode() == org.bukkit.GameMode.SURVIVAL) {
                candidates.add(player);
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        spawnNear(candidates.get(random.nextInt(candidates.size())));
    }

    /** Publico para el comando /deathsummon. */
    public void summonNear(Player player) {
        spawnNear(player);
    }

    void spawnNear(Player player) {
        Location base = player.getLocation();
        // Aparece directamente delante del jugador (todas las dificultades)
        Vector facing = base.getDirection();
        facing.setY(0);
        if (facing.lengthSquared() < 0.01) {
            facing = new Vector(0, 0, -1);
        }
        facing.normalize();
        Location loc = base.clone()
                .add(facing.clone().multiply(6 + random.nextDouble() * 4))
                .add(0, 5 + random.nextInt(4), 0);
        if (!loc.getChunk().isLoaded()) {
            return;
        }
        int guard = 0;
        while (loc.getBlock().getType().isSolid() && guard < 8) {
            loc.add(0, 2, 0);
            guard++;
        }
        Phantom vehicle = (Phantom) loc.getWorld().spawnEntity(loc, EntityType.PHANTOM);
        vehicle.setInvisible(true);
        vehicle.setSilent(true);
        vehicle.setPersistent(false);
        vehicle.setInvulnerable(false);
        manager.markSpawned(vehicle);

        Skeleton skeleton = (Skeleton) loc.getWorld().spawnEntity(loc, EntityType.SKELETON);
        skeleton.customName(net.kyori.adventure.text.Component.text("§4☠ Jefe Esqueleto"));
        skeleton.setCustomNameVisible(true);
        skeleton.setGlowing(true);
        skeleton.setPersistent(false);
        skeleton.getPersistentDataContainer()
                .set(bossKey, PersistentDataType.BYTE, (byte) 1);
        manager.markSpawned(skeleton);
        var health = skeleton.getAttribute(Attribute.MAX_HEALTH);
        if (health != null) {
            health.setBaseValue(BOSS_MAX_HEALTH);
            skeleton.setHealth(BOSS_MAX_HEALTH);
        }
        skeleton.getEquipment().setItemInMainHand(new ItemStack(Material.MACE));
        vehicle.addPassenger(skeleton);

        for (int i = 0; i < displays.length; i++) {
            displays[i] = loc.getWorld().spawn(loc, ItemDisplay.class, display -> {
                display.setItemStack(new ItemStack(Material.CROSSBOW));
                display.setItemDisplayTransform(
                        ItemDisplay.ItemDisplayTransform.FIXED);
                display.setBillboard(org.bukkit.entity.Display.Billboard.FIXED);
                display.setPersistent(false);
                display.setShadowRadius(0);
                manager.markSpawned(display);
            });
        }
        boss = skeleton;
        bossVehicle = vehicle;
        diving = false;
        tick = 0;
        Bukkit.broadcastMessage("§4[Hellish] ☠ §cEl §lJefe Esqueleto§c ha aparecido!");
    }

    @Override
    public void run() {
        if (boss == null || !boss.isValid()) {
            despawnDisplays();
            return;
        }
        Player target = nearestPlayer(boss.getLocation());
        if (target == null) {
            return;
        }
        tick += 3;

        // Vuelo: el phantom persigue flotando sobre la victima
        if (bossVehicle != null && bossVehicle.isValid()) {
            Location from = bossVehicle.getLocation();
            Location hover = target.getLocation().add(0, 8 + random.nextInt(6), 0);
            Vector dir = hover.toVector().subtract(from.toVector());
            if (dir.lengthSquared() > 1) {
                bossVehicle.setVelocity(dir.normalize()
                        .multiply(diving ? 0.9 : 0.32));
            }
        }

        // Alternancia: avalanzarse con mazo <-> mantenerse aereos
        if (tick % 120 == 0) {
            diving = !diving;
            if (diving && boss.getEquipment() != null) {
                boss.getEquipment().setItemInMainHand(new ItemStack(Material.MACE));
                Vector lunge = target.getLocation().toVector()
                        .subtract(boss.getLocation().toVector()).normalize()
                        .multiply(1.2).setY(-0.4);
                boss.setVelocity(lunge);
            } else if (boss.getEquipment() != null) {
                boss.getEquipment().setItemInMainHand(new ItemStack(Material.BOW));
            }
        }

        // Displays de ballesta girando alrededor del jefe
        orbitAngle += Math.PI / 16.0;
        Location center = boss.getLocation();
        for (int i = 0; i < displays.length; i++) {
            ItemDisplay display = displays[i];
            if (display == null || !display.isValid()) {
                continue;
            }
            double angle = orbitAngle + i * (Math.PI / 2.0);
            Location pos = center.clone().add(
                    Math.cos(angle) * 2.5, 0.5 + Math.sin(tick * 0.05 + i) * 0.4,
                    Math.sin(angle) * 2.5);
            pos.setYaw((float) Math.toDegrees(angle + Math.PI / 2.0));
            display.teleport(pos);

            // Cada ballesta dispara una flecha lenta teledirigida cada 1.5s,
            // desfasadas entre si
            if ((tick + i * 7) % HOMING_INTERVAL_TICKS == 0) {
                fireHomingArrow(display.getLocation(), target);
            }
        }

        // Guia las flechas teledirigidas vivas
        guideHomingArrows();
    }

    private void fireHomingArrow(Location from, Player target) {
        Arrow arrow = from.getWorld().spawnArrow(from,
                new Vector(0, -0.1, 0), 0.35f, 0f);
        arrow.setShooter(boss);
        arrow.setCritical(true);
        arrow.setPersistent(false);
        arrow.addScoreboardTag("dm_boss_arrow");
        manager.markSpawned(arrow);
        homingArrows.put(arrow.getUniqueId(), target.getUniqueId());
    }

    private void guideHomingArrows() {
        Iterator<Map.Entry<UUID, UUID>> it = homingArrows.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, UUID> entry = it.next();
            Arrow arrow = (Arrow) Bukkit.getEntity(entry.getKey());
            Player target = Bukkit.getPlayer(entry.getValue());
            if (arrow == null || !arrow.isValid()
                    || arrow.isOnGround() || target == null || !target.isOnline()) {
                it.remove();
                continue;
            }
            double speed = Math.min(
                    HOMING_MAX_SPEED,
                    arrow.getVelocity().length() + HOMING_ACCELERATION);
            Vector dir = target.getLocation().add(0, 1, 0).toVector()
                    .subtract(arrow.getLocation().toVector());
            if (dir.lengthSquared() < 0.01) {
                it.remove();
                continue;
            }
            arrow.setVelocity(dir.normalize().multiply(speed));
        }
    }

    @EventHandler
    public void onBossDeath(EntityDeathEvent event) {
        if (boss == null || event.getEntity() != boss) {
            return;
        }
        boss = null;
        if (bossVehicle != null && bossVehicle.isValid()) {
            bossVehicle.remove();
            bossVehicle = null;
        }
        despawnDisplays();
        event.getDrops().clear();

        Player killer = event.getEntity().getKiller();
        if (killer != null) {
            manager.participation().recordBossKill(killer, "skeleton");
        }
        event.getEntity().getWorld().dropItemNaturally(
                event.getEntity().getLocation(),
                HellishItems.dodecaedro(dodecaedroKey));
        Bukkit.broadcastMessage("§6[Death] §eEl Jefe Esqueleto ha caido! Ha soltado un "
                + "§bDodecaedro Angelical§e.");
    }

    private void despawnDisplays() {
        for (int i = 0; i < displays.length; i++) {
            if (displays[i] != null && displays[i].isValid()) {
                displays[i].remove();
            }
            displays[i] = null;
        }
    }

    private Player nearestPlayer(Location location) {
        Player best = null;
        double bestDist = Double.MAX_VALUE;
        for (Player player : location.getWorld().getPlayers()) {
            double dist = player.getLocation().distance(location);
            if (dist < bestDist) {
                bestDist = dist;
                best = player;
            }
        }
        return bestDist <= 96 ? best : null;
    }
}

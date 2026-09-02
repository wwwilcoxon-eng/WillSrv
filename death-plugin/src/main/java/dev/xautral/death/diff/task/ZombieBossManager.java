package dev.xautral.death.diff.task;

import dev.xautral.death.diff.DiffModeManager;
import dev.xautral.death.diff.item.HellishItems;
import dev.xautral.death.diff.item.HellishRewards;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Husk;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.Random;

/**
 * Jefe Husk de Hellish: 150 de vida, corre, salta y se avalanza con mazo,
 * 2 espadas girando a su alrededor que hacen dano al contacto y con poca
 * vida usa elytra para impulsarse de lado a lado.
 */
public final class ZombieBossManager implements Listener, Runnable {

    private static final double BOSS_MAX_HEALTH = 150.0;

    private final JavaPlugin plugin;
    private final DiffModeManager manager;
    private final Random random;
    private final NamespacedKey bossKey;
    private final NamespacedKey stackTotemKey;

    private LivingEntity boss;
    private final ItemDisplay[] swords = new ItemDisplay[2];
    private double orbitAngle;
    private int tick;
    private int dashCooldown;

    public ZombieBossManager(JavaPlugin plugin, DiffModeManager manager,
                             Random random, NamespacedKey stackTotemKey) {
        this.plugin = plugin;
        this.manager = manager;
        this.random = random;
        this.stackTotemKey = stackTotemKey;
        this.bossKey = new NamespacedKey(plugin, "boss_husk");
        Bukkit.getScheduler().runTaskTimer(plugin, this, 5L, 3L);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public boolean hasNoBoss() {
        return boss == null || !boss.isValid();
    }

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
        Player player = candidates.get(random.nextInt(candidates.size()));
        spawnHusk(player);
    }

    /** Publico para el comando /deathsummon: invoca delante del jugador. */
    public void summonNear(Player player) {
        if (hasNoBoss()) {
            spawnHusk(player);
        }
    }

    private void spawnHusk(Player player) {
        Location loc = player.getLocation().add(
                (random.nextDouble() - 0.5) * 40, 0, (random.nextDouble() - 0.5) * 40);
        if (!loc.getChunk().isLoaded()) {
            return;
        }
        Husk husk = (Husk) loc.getWorld().spawnEntity(loc, EntityType.HUSK);
        husk.customName(net.kyori.adventure.text.Component.text("§2☠ Jefe Husk"));
        husk.setCustomNameVisible(true);
        husk.setGlowing(true);
        husk.setPersistent(false);
        husk.getPersistentDataContainer().set(bossKey, PersistentDataType.BYTE, (byte) 1);
        manager.markSpawned(husk);
        var health = husk.getAttribute(Attribute.MAX_HEALTH);
        if (health != null) {
            health.setBaseValue(BOSS_MAX_HEALTH);
            husk.setHealth(BOSS_MAX_HEALTH);
        }
        var speed = husk.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null) {
            speed.setBaseValue(speed.getBaseValue() * 1.4); // puede correr
        }
        husk.getEquipment().setItemInMainHand(new ItemStack(Material.MACE));
        for (int i = 0; i < swords.length; i++) {
            swords[i] = loc.getWorld().spawn(loc, ItemDisplay.class, display -> {
                display.setItemStack(new ItemStack(Material.IRON_SWORD));
                display.setItemDisplayTransform(
                        ItemDisplay.ItemDisplayTransform.FIXED);
                display.setBillboard(org.bukkit.entity.Display.Billboard.FIXED);
                display.setPersistent(false);
                display.setShadowRadius(0);
                manager.markSpawned(display);
            });
        }
        boss = husk;
        tick = 0;
        dashCooldown = 0;
        Bukkit.broadcastMessage("§4[Hellish] ☠ §cEl §lJefe Husk§c ha aparecido!");
    }

    @Override
    public void run() {
        if (boss == null || !boss.isValid()) {
            despawnDisplays();
            return;
        }
        tick += 3;
        double maxHealth = boss.getMaxHealth();
        boolean lowHealth = maxHealth > 0 && boss.getHealth() / maxHealth < 0.35;

        // Elytra de emergencia: con poca vida se impulsa de lado a lado
        if (lowHealth && dashCooldown <= 0) {
            var eq = boss.getEquipment();
            if (eq != null && eq.getChestplate() == null) {
                eq.setChestplate(new ItemStack(Material.ELYTRA));
            }
            Vector strafe = new Vector(Math.cos(tick * 0.2) * 0.8, 0.35,
                    Math.sin(tick * 0.2) * 0.8);
            boss.setVelocity(strafe);
            boss.setGliding(true);
            dashCooldown = 30;
        } else if (dashCooldown > 0) {
            dashCooldown -= 3;
        }

        // Avalanza: salta hacia su objetivo usando el mazo
        if (!lowHealth && tick % 100 == 0) {
            LivingEntity target = ((org.bukkit.entity.Mob) boss).getTarget();
            if (target != null) {
                Vector lunge = target.getLocation().toVector()
                        .subtract(boss.getLocation().toVector()).normalize()
                        .multiply(1.1).setY(0.55);
                boss.setVelocity(lunge);
            }
        }

        // Las 2 espadas giran lentamente y hacen dano al contacto
        orbitAngle += Math.PI / 32.0; // giro lento
        Location center = boss.getLocation();
        for (int i = 0; i < swords.length; i++) {
            ItemDisplay sword = swords[i];
            if (sword == null || !sword.isValid()) {
                continue;
            }
            double angle = orbitAngle + i * Math.PI;
            Location pos = center.clone().add(Math.cos(angle) * 2.0, 1.0,
                    Math.sin(angle) * 2.0);
            sword.teleport(pos);
            // Dano al contacto con la espada
            for (Player player : center.getWorld().getNearbyEntities(pos, 1.2, 1.2, 1.2,
                    e -> e instanceof Player && e != boss)
                    .toArray(new Player[0])) {
                player.damage(3.0, boss);
            }
        }
    }

    @EventHandler
    public void onBossDeath(EntityDeathEvent event) {
        if (boss == null || event.getEntity() != boss) {
            return;
        }
        boss = null;
        despawnDisplays();
        event.getDrops().clear();

        // 16 manzanas de notch + totem stackeable hasta 6
        event.getDrops().add(new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 16));
        event.getDrops().add(HellishRewards.stackableTotem(stackTotemKey));

        Player killer = event.getEntity().getKiller();
        if (killer != null) {
            manager.participation().recordBossKill(killer, "husk");
        }
        Bukkit.broadcastMessage("§6[Death] §eEl Jefe Husk ha caido! Solto manzanas de "
                + "notch y un Totem del Jefe.");
    }

    private void despawnDisplays() {
        for (int i = 0; i < swords.length; i++) {
            if (swords[i] != null && swords[i].isValid()) {
                swords[i].remove();
            }
            swords[i] = null;
        }
    }
}

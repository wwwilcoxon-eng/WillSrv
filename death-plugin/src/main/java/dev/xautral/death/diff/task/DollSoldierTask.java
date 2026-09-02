package dev.xautral.death.diff.task;

import dev.xautral.death.diff.DiffMode;
import dev.xautral.death.diff.DiffModeManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.Vector;

import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Mannequins y Armor Stands con arma en mano se comportan como soldados:
 * caminan (saltando) hacia el monstruo hostil mas cercano, lo atacan como
 * un jugador, y los armor stands cambian de pose al pelear/deambular.
 */
public final class DollSoldierTask implements Runnable {

    private static final double SCAN_RADIUS = 48.0;
    private static final double AGGRO_RADIUS = 20.0;
    private static final double ATTACK_RANGE = 1.9;
    private static final double ATTACK_DAMAGE = 4.0;

    private final DiffModeManager manager;
    private final Random random;
    private int tick;

    public DollSoldierTask(DiffModeManager manager, Random random) {
        this.manager = manager;
        this.random = random;
    }

    @Override
    public void run() {
        if (!manager.isActive(DiffMode.HELLISH)) {
            return;
        }
        tick += 10;
        java.util.Set<UUID> processed = new java.util.HashSet<>();
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            for (Player player : world.getPlayers()) {
                for (var entity : world.getNearbyEntities(player.getLocation(),
                        SCAN_RADIUS, SCAN_RADIUS, SCAN_RADIUS,
                        e -> e instanceof ArmorStand || e instanceof Mannequin)) {
                    if (!processed.add(entity.getUniqueId())) {
                        continue;
                    }
                    handleDoll((LivingEntity) entity);
                }
            }
        }
    }

    private void handleDoll(LivingEntity doll) {
        // Solo los munecos armados son soldados
        ItemStackHolder held = new ItemStackHolder(doll);
        if (held.empty()) {
            return;
        }

        // Objetivo: el monstruo hostil mas cercano
        Monster target = null;
        double bestDist = Double.MAX_VALUE;
        for (var nearby : doll.getWorld().getNearbyEntities(
                doll.getLocation(), AGGRO_RADIUS, AGGRO_RADIUS, AGGRO_RADIUS,
                e -> e instanceof Monster && e.isValid())) {
            double dist = nearby.getLocation().distance(doll.getLocation());
            if (dist < bestDist) {
                bestDist = dist;
                target = (Monster) nearby;
            }
        }

        if (target != null) {
            if (bestDist > ATTACK_RANGE) {
                // Caminar saltando hacia el objetivo (como jugador apurado)
                Vector dir = target.getLocation().toVector()
                        .subtract(doll.getLocation().toVector());
                dir.setY(0);
                if (dir.lengthSquared() > 0.01) {
                    dir.normalize().multiply(0.32);
                    dir.setY(0.24); // saltito de paso
                    doll.setVelocity(dir);
                }
                faceTowards(doll, target);
            } else {
                attack(doll, target);
            }
        } else if (tick % 100 == 0) {
            // Deambular: salto aleatorio ocasional
            Vector wander = new Vector((random.nextDouble() - 0.5) * 0.5,
                    0.22, (random.nextDouble() - 0.5) * 0.5);
            doll.setVelocity(wander);
            randomPose(doll);
        }
    }

    private void attack(LivingEntity doll, LivingEntity victim) {
        victim.damage(ATTACK_DAMAGE, doll);
        doll.getWorld().playSound(doll.getLocation(),
                Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.6f, 1.2f);
        // Pose de golpe: brazo derecho al frente por un instante
        if (doll instanceof ArmorStand stand) {
            stand.setRightArmPose(new EulerAngle(Math.PI / 1.6, 0, 0));
            Bukkit.getScheduler().runTaskLater(manager.plugin(),
                    () -> {
                        if (stand.isValid()) {
                            stand.setRightArmPose(
                                    new EulerAngle(Math.PI / 8.0, 0, 0));
                        }
                    }, 8L);
        }
    }

    /** Los armor stands cambian de pose aleatoria al deambular. */
    private void randomPose(LivingEntity doll) {
        if (!(doll instanceof ArmorStand stand)) {
            return; // los mannequins usan Pose nativa del cliente
        }
        switch (random.nextInt(4)) {
            case 0 -> { // saludo militar
                stand.setRightArmPose(new EulerAngle(Math.PI, 0, 0));
                stand.setLeftArmPose(EulerAngle.ZERO);
            }
            case 1 -> { // brazos abiertos
                stand.setRightArmPose(new EulerAngle(0, 0, Math.PI / 3));
                stand.setLeftArmPose(new EulerAngle(0, 0, -Math.PI / 3));
            }
            case 2 -> { // guardia con espada
                stand.setRightArmPose(new EulerAngle(-Math.PI / 2, 0, 0));
                stand.setLeftArmPose(EulerAngle.ZERO);
            }
            default -> { // relax
                stand.setRightArmPose(EulerAngle.ZERO);
                stand.setLeftArmPose(EulerAngle.ZERO);
            }
        }
        stand.setBodyPose(new EulerAngle(
                (random.nextDouble() - 0.5) * 0.15, 0,
                (random.nextDouble() - 0.5) * 0.15));
    }

    private void faceTowards(LivingEntity doll, LivingEntity to) {
        Location loc = doll.getLocation();
        Vector dir = to.getLocation().toVector().subtract(loc.toVector());
        loc.setYaw((float) Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ())));
        doll.teleport(loc);
    }

    // Wrapper para chequear item en mano sin repetir null-checks
    private record ItemStackHolder(LivingEntity doll) {
        boolean empty() {
            var eq = doll.getEquipment();
            if (eq == null) {
                return true;
            }
            ItemStack item = eq.getItemInMainHand();
            if (item == null || item.getType().isAir()) {
                return true;
            }
            // Las espadas/hachas/armas cuentan; un bloque decorativo no pelea
            return !isWeapon(item.getType());
        }

        private static boolean isWeapon(Material m) {
            Set<Material> weapons = Set.of(
                    Material.WOODEN_SWORD, Material.STONE_SWORD,
                    Material.IRON_SWORD, Material.GOLDEN_SWORD,
                    Material.DIAMOND_SWORD, Material.NETHERITE_SWORD,
                    Material.WOODEN_AXE, Material.STONE_AXE,
                    Material.IRON_AXE, Material.GOLDEN_AXE,
                    Material.DIAMOND_AXE, Material.NETHERITE_AXE,
                    Material.TRIDENT, Material.MACE, Material.BOW,
                    Material.CROSSBOW);
            return weapons.contains(m);
        }
    }

    private static World world(Location loc) {
        return loc.getWorld();
    }
}

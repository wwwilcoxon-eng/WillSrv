package dev.xautral.hellish.listener;

import dev.xautral.hellish.DiffMode;
import dev.xautral.hellish.DiffModeManager;
import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.PiglinAbstract;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Slime;
import org.bukkit.entity.SmallFireball;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.SlimeSplitEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.Random;

public final class SpawnListener implements Listener {

    private static final int WITHER_SKELETON_RANGE = 25;
    private static final double CREEPER_CHARGED_CHANCE = 0.20;
    private static final int MAX_SLIME_SIZE = 7;

    private final DiffModeManager manager;
    private final Random random;

    public SpawnListener(DiffModeManager manager, Random random) {
        this.manager = manager;
        this.random = random;
    }

    @EventHandler(ignoreCancelled = true)
    public void onCombust(EntityCombustEvent event) {
        if (!manager.isActive(DiffMode.HELLISH)) {
            return;
        }
        // Los #undead no se queman por el sol durante Hellish
        if (org.bukkit.Tag.ENTITY_TYPES_UNDEAD.isTagged(event.getEntity().getType())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (!manager.isActive(DiffMode.HELLISH)) {
            return;
        }
        LivingEntity entity = event.getEntity();

        switch (entity.getType()) {
            case ZOMBIFIED_PIGLIN -> equipZombifiedPiglin(entity);
            case WITHER_SKELETON -> buffWitherSkeleton(entity);
            case DROWNED -> buffDrowned(entity);
            case ZOMBIE -> buffZombie(entity);
            case SKELETON -> buffSkeleton(entity);
            case BLAZE -> buffBlaze(entity);
            default -> {
                if (entity instanceof Creeper creeper) {
                    buffCreeper(creeper);
                } else if (entity instanceof Slime slime && slime.getSize() > MAX_SLIME_SIZE) {
                    slime.setSize(MAX_SLIME_SIZE);
                } else if (entity instanceof Phantom phantom) {
                    // Rate fix: cancela 60% de los phantoms vanilla extra y limita por mundo/cerca
                    // para no spamear durante Hellish
                    if (event.getSpawnReason() == org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.NATURAL) {
                        // 60% de los spawns naturales cancelados
                        if (random.nextInt(100) < 60) {
                            event.setCancelled(true);
                            return;
                        }
                        long worldPhantoms = phantom.getWorld().getEntitiesByClass(Phantom.class).stream()
                                .filter(p -> p.getScoreboardTags().contains("dm_hellish_phantom")).count();
                        if (worldPhantoms >= 10) {
                            event.setCancelled(true);
                            return;
                        }
                    }
                    manager.buffPhantom(phantom);
                    // 30% de los phantoms spawnean YA con su esqueleto montado
                    if (random.nextInt(100) < 30) {
                        mountSkeletonRider(phantom);
                    }
                }
            }
        }
        // Nivel 5: TODOS los mobs hostiles/undead llevan un TOTEM en su mano secundaria
        if (manager.difficultyLevel() >= 5
                && (entity instanceof org.bukkit.entity.Monster
                    || org.bukkit.Tag.ENTITY_TYPES_UNDEAD.isTagged(entity.getType()))) {
            grantLv5Totem(entity);
        }
    }

    /** Nivel 5: totem de resurreccion en la mano secundaria de mobs hostiles. */
    private void grantLv5Totem(LivingEntity entity) {
        EntityEquipment eq = entity.getEquipment();
        if (eq == null) {
            return;
        }
        eq.setItemInOffHand(new ItemStack(Material.TOTEM_OF_UNDYING));
        eq.setItemInOffHandDropChance(0.0f);
        entity.addScoreboardTag("dm_lv5_totem");
    }

    // ---------------- Ahogados: 3 tridentes Channeling + Impaling V que vuelven ----------------
    private void buffDrowned(LivingEntity drowned) {
        EntityEquipment eq = drowned.getEquipment();
        if (eq == null) return;
        // Siempre con tridente en Hellish
        ItemStack trident = new ItemStack(Material.TRIDENT);
        var meta = trident.getItemMeta();
        if (meta != null) {
            meta.addEnchant(org.bukkit.enchantments.Enchantment.CHANNELING, 1, true);
            meta.addEnchant(org.bukkit.enchantments.Enchantment.IMPALING, 5, true);
            meta.addEnchant(org.bukkit.enchantments.Enchantment.LOYALTY, 3, true);
            meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 3, true);
            trident.setItemMeta(meta);
        }
        eq.setItemInMainHand(trident);
        eq.setItemInMainHandDropChance(0.12f);
        // Velocidad un poco mas
        var speed = drowned.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null) speed.setBaseValue(speed.getBaseValue() * 1.2);
        drowned.addScoreboardTag("dm_drowned_triple");
    }

    // ---------------- Zombies: 50% hacha hierro, velocidad y cota de malla ----------------
    private void buffZombie(LivingEntity zombie) {
        EntityEquipment eq = zombie.getEquipment();
        if (eq == null) return;
        // 50% con hacha de hierro
        if (random.nextInt(100) < 50) {
            ItemStack axe = new ItemStack(Material.IRON_AXE);
            var meta = axe.getItemMeta();
            if (meta != null) {
                meta.addEnchant(org.bukkit.enchantments.Enchantment.SHARPNESS, 3, true);
                meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 2, true);
                axe.setItemMeta(meta);
            }
            eq.setItemInMainHand(axe);
            eq.setItemInMainHandDropChance(0.08f);
        }
        // Cota de malla completa
        eq.setHelmet(new ItemStack(Material.CHAINMAIL_HELMET));
        eq.setChestplate(new ItemStack(Material.CHAINMAIL_CHESTPLATE));
        eq.setLeggings(new ItemStack(Material.CHAINMAIL_LEGGINGS));
        eq.setBoots(new ItemStack(Material.CHAINMAIL_BOOTS));
        eq.setHelmetDropChance(0.01f);
        eq.setChestplateDropChance(0.01f);
        eq.setLeggingsDropChance(0.01f);
        eq.setBootsDropChance(0.01f);
        // Velocidad
        var speed = zombie.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null) speed.setBaseValue(speed.getBaseValue() * 1.35);
        // Mantén el buff original de espada diamante para 30% de los que no tienen hacha? No, reemplaza
        // Si no lleva hacha, deja el buff de mazo/escudo que maneja HellishMobTask
    }

    // ---------------- Esqueletos: flecha de wither y arco maxeado ----------------

    private void buffSkeleton(LivingEntity skeletonEntity) {
        EntityEquipment eq = skeletonEntity.getEquipment();
        if (eq == null) {
            return;
        }

        // Jinete de caballo esqueleto: equipo completo de lujo
        boolean isHorseman = skeletonEntity.isInsideVehicle()
                && skeletonEntity.getVehicle() != null
                && skeletonEntity.getVehicle().getType()
                        == org.bukkit.entity.EntityType.SKELETON_HORSE;
        if (isHorseman) {
            ItemStack maxedBow = new ItemStack(Material.BOW);
            var bowMeta = maxedBow.getItemMeta();
            if (bowMeta != null) {
                bowMeta.addEnchant(org.bukkit.enchantments.Enchantment.POWER, 5, true);
                bowMeta.addEnchant(org.bukkit.enchantments.Enchantment.PUNCH, 2, true);
                bowMeta.addEnchant(org.bukkit.enchantments.Enchantment.FLAME, 1, true);
                bowMeta.addEnchant(org.bukkit.enchantments.Enchantment.INFINITY, 1, true);
                bowMeta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 3, true);
                maxedBow.setItemMeta(bowMeta);
            }
            eq.setItemInMainHand(maxedBow);
            eq.setItemInOffHand(effectArrows());
            eq.setItemInOffHandDropChance(0.25f);
            return;
        }

        // 10%: arco maxeado
        if (random.nextInt(100) < 10) {
            ItemStack maxedBow = new ItemStack(Material.BOW);
            var bowMeta = maxedBow.getItemMeta();
            if (bowMeta != null) {
                bowMeta.addEnchant(org.bukkit.enchantments.Enchantment.POWER, 5, true);
                bowMeta.addEnchant(org.bukkit.enchantments.Enchantment.PUNCH, 2, true);
                bowMeta.addEnchant(org.bukkit.enchantments.Enchantment.FLAME, 1, true);
                bowMeta.addEnchant(org.bukkit.enchantments.Enchantment.INFINITY, 1, true);
                bowMeta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 3, true);
                maxedBow.setItemMeta(bowMeta);
            }
            eq.setItemInMainHand(maxedBow);
        }

        // 30%: flecha de Wither III (1 minuto) en la mano secundaria, 10% drop
        if (random.nextInt(100) < 30) {
            eq.setItemInOffHand(witherArrow());
            eq.setItemInOffHandDropChance(0.10f);
        }
    }

    private ItemStack witherArrow() {
        ItemStack arrow = new ItemStack(Material.TIPPED_ARROW);
        var meta = arrow.getItemMeta();
        if (meta instanceof org.bukkit.inventory.meta.PotionMeta potionMeta) {
            potionMeta.setColor(org.bukkit.Color.BLACK);
            potionMeta.addCustomEffect(
                    new org.bukkit.potion.PotionEffect(
                            org.bukkit.potion.PotionEffectType.WITHER,
                            20 * 60, 2), true); // Wither III por 1 minuto
            arrow.setItemMeta(potionMeta);
        }
        return arrow;
    }

    /** Flechas de jinete: debilidad + wither + ceguera. */
    private ItemStack effectArrows() {
        ItemStack arrow = new ItemStack(Material.TIPPED_ARROW);
        var meta = arrow.getItemMeta();
        if (meta instanceof org.bukkit.inventory.meta.PotionMeta potionMeta) {
            potionMeta.setColor(org.bukkit.Color.fromRGB(40, 40, 40));
            potionMeta.addCustomEffect(new org.bukkit.potion.PotionEffect(
                    org.bukkit.potion.PotionEffectType.WITHER, 20 * 60, 1), true);
            potionMeta.addCustomEffect(new org.bukkit.potion.PotionEffect(
                    org.bukkit.potion.PotionEffectType.WEAKNESS, 20 * 60, 1), true);
            potionMeta.addCustomEffect(new org.bukkit.potion.PotionEffect(
                    org.bukkit.potion.PotionEffectType.BLINDNESS, 20 * 15, 0), true);
            arrow.setItemMeta(potionMeta);
        }
        return arrow;
    }

    /** 30%: phantom spawnea con esqueleto montado (jinete por defecto). */
    private void mountSkeletonRider(Phantom phantom) {
        org.bukkit.entity.Skeleton rider = (org.bukkit.entity.Skeleton)
                phantom.getWorld().spawnEntity(
                        phantom.getLocation(), org.bukkit.entity.EntityType.SKELETON);
        rider.getEquipment().setItemInMainHand(new ItemStack(Material.BOW));
        manager.markSpawned(rider);
        phantom.addPassenger(rider);
    }

    private void equipZombifiedPiglin(LivingEntity piglin) {
        EntityEquipment eq = piglin.getEquipment();
        if (eq == null) {
            return;
        }
        eq.setHelmet(new ItemStack(Material.IRON_HELMET));
        eq.setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
        eq.setLeggings(new ItemStack(Material.IRON_LEGGINGS));
        eq.setBoots(new ItemStack(Material.IRON_BOOTS));
        eq.setItemInMainHand(new ItemStack(Material.IRON_AXE));
        eq.setHelmetDropChance(0.0f);
        eq.setChestplateDropChance(0.0f);
        eq.setLeggingsDropChance(0.0f);
        eq.setBootsDropChance(0.0f);
        eq.setItemInMainHandDropChance(0.0f);
    }

    private void buffWitherSkeleton(LivingEntity witherSkeleton) {
        AttributeInstance range = witherSkeleton.getAttribute(Attribute.FOLLOW_RANGE);
        if (range != null && range.getBaseValue() < WITHER_SKELETON_RANGE) {
            range.setBaseValue(WITHER_SKELETON_RANGE);
        }
    }

    private void buffCreeper(Creeper creeper) {
        if (random.nextDouble() < CREEPER_CHARGED_CHANCE) {
            creeper.setPowered(true);
        }
        AttributeInstance speed = creeper.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null) {
            double mult = manager.difficultyLevel() >= 2 ? 1.55 : 1.3;
            speed.setBaseValue(speed.getBaseValue() * mult);
        }
        if (manager.difficultyLevel() >= 2) {
            AttributeInstance range = creeper.getAttribute(Attribute.FOLLOW_RANGE);
            if (range != null && range.getBaseValue() < 48) {
                range.setBaseValue(48);
            }
        }
    }

    /** Nivel 2+: los blazes detectan y disparan desde hasta 25 bloques. */
    private void buffBlaze(LivingEntity blaze) {
        if (manager.difficultyLevel() >= 2) {
            AttributeInstance range = blaze.getAttribute(Attribute.FOLLOW_RANGE);
            if (range != null && range.getBaseValue() < 25) {
                range.setBaseValue(25);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSlimeSplit(SlimeSplitEvent event) {
        if (!manager.isActive(DiffMode.HELLISH)) {
            return;
        }
        // Limita tambien el tamano de los hijos
        if (event.getEntity().getSize() > MAX_SLIME_SIZE) {
            event.getEntity().setSize(MAX_SLIME_SIZE);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (!manager.isActive(DiffMode.HELLISH)) {
            return;
        }
        if (!(event.getEntity() instanceof org.bukkit.entity.Mob skeleton)
                || !(skeleton instanceof AbstractSkeleton)) {
            return;
        }
        LivingEntity target = skeleton.getTarget();
        if (target == null || !target.isValid()) {
            return;
        }
        // Nivel 4 en el Nether: flechas con TNT (daño en masa, sin destruir)
        // o flechas que lanzan bolas de fuego en espiral
        if (manager.difficultyLevel() >= 4
                && skeleton.getWorld().getEnvironment() == World.Environment.NETHER) {
            event.setCancelled(true);
            if (random.nextBoolean()) {
                fireTntArrow(skeleton, target);
            } else {
                fireSpiralArrow(skeleton, target);
            }
            return;
        }
        // Nivel 2+: 50% de los disparos de esqueleto son bolas de fuego explosivas
        if (manager.difficultyLevel() >= 2 && random.nextInt(100) < 50) {
            event.setCancelled(true);
            fireExplosiveFireball(skeleton, target);
            return;
        }
        // Punteria casi perfecta: redirige la flecha al centro del objetivo con
        // compensacion de gravedad y dispersion diminuta
        org.bukkit.entity.Projectile projectile =
                (org.bukkit.entity.Projectile) event.getProjectile();
        Vector from = projectile.getLocation().toVector();
        Vector to = target.getLocation().add(0, target.getHeight() * 0.5, 0).toVector();
        double distance = from.distance(to);
        double speed = projectile.getVelocity().length();
        Vector dir = to.subtract(from);
        if (dir.lengthSquared() < 0.001) {
            return;
        }
        dir.normalize();
        dir.setY(dir.getY() + 0.5 * 0.05 * (distance / speed));
        dir.add(new Vector((random.nextDouble() - 0.5) * 0.04,
                (random.nextDouble() - 0.5) * 0.04,
                (random.nextDouble() - 0.5) * 0.04));
        projectile.setVelocity(dir.normalize().multiply(speed));
    }

    private void fireExplosiveFireball(org.bukkit.entity.Mob shooter,
                                       LivingEntity target) {
        Location from = shooter.getEyeLocation();
        Vector dir = target.getLocation().add(0, target.getHeight() * 0.5, 0).toVector()
                .subtract(from.toVector()).normalize();
        org.bukkit.entity.LargeFireball fireball = (org.bukkit.entity.LargeFireball)
                shooter.getWorld().spawnEntity(from, EntityType.FIREBALL);
        fireball.setShooter(shooter);
        fireball.setVelocity(dir.multiply(1.3));
        fireball.setYield(2.0f);
        fireball.setIsIncendiary(true);
    }

    private Vector aimDir(LivingEntity shooter, LivingEntity target, double speed) {
        Vector to = target.getLocation().add(0, target.getHeight() * 0.5, 0).toVector()
                .subtract(shooter.getEyeLocation().toVector());
        double distance = to.length();
        to.normalize();
        to.setY(to.getY() + 0.5 * 0.05 * (distance / speed));
        return to.normalize();
    }

    private void fireTntArrow(org.bukkit.entity.Mob shooter, LivingEntity target) {
        Arrow arrow = shooter.getWorld().spawnArrow(
                shooter.getEyeLocation(), aimDir(shooter, target, 2.6), 2.6f, 0f);
        arrow.setShooter(shooter);
        arrow.setCritical(true);
        arrow.setFireTicks(32767); // mecha encendida (visual TNT)
        arrow.addScoreboardTag("dm_tnt_arrow");
    }

    private void fireSpiralArrow(org.bukkit.entity.Mob shooter, LivingEntity target) {
        Arrow arrow = shooter.getWorld().spawnArrow(
                shooter.getEyeLocation(), aimDir(shooter, target, 2.2), 2.2f, 0f);
        arrow.setShooter(shooter);
        arrow.addScoreboardTag("dm_spiral_arrow");
        org.bukkit.scheduler.BukkitRunnable spiral = new org.bukkit.scheduler.BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                if (!arrow.isValid() || ticks > 60) {
                    cancel();
                    return;
                }
                if (ticks % 4 == 0) {
                    Location cur = arrow.getLocation();
                    Vector fwd = arrow.getVelocity().clone().normalize();
                    for (int i = 0; i < 3; i++) {
                        double ang = ticks * 0.7 + i * Math.PI * 2 / 3;
                        SmallFireball fb = (SmallFireball) cur.getWorld()
                                .spawnEntity(cur, EntityType.SMALL_FIREBALL);
                        fb.setShooter(shooter);
                        Vector dir = fwd.clone()
                                .add(new Vector(Math.cos(ang) * 0.4,
                                        Math.sin(ang) * 0.4,
                                        Math.sin(ang + Math.PI / 2) * 0.4))
                                .normalize().multiply(0.9);
                        fb.setVelocity(dir);
                        fb.setYield(1);
                        manager.markSpawned(fb);
                    }
                }
                ticks++;
            }
        };
        spiral.runTaskTimer(manager.plugin(), 0L, 1L);
    }
}

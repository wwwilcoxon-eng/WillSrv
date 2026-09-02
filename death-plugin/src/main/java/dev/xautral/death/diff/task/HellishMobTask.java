package dev.xautral.death.diff.task;

import dev.xautral.death.diff.DiffMode;
import dev.xautral.death.diff.DiffModeManager;
import dev.xautral.death.diff.item.HellishItems;
import dev.xautral.death.diff.util.StructureCache;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Ghast;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.entity.Spider;
import org.bukkit.entity.WindCharge;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Tarea periodica unica de Hellish: escanea solo los mobs en radio de 64
 * bloques alrededor de cada jugador (con deduplicacion) y ejecuta todos los
 * comportamientos. Estructuras cacheadas para no llamar a locateNearestStructure
 * mas de una vez cada 30 segundos por mundo.
 */
public final class HellishMobTask implements Runnable {

    private static final int PHANTOM_INSOMNIA_TICKS = 24000; // un dia sin dormir
    private static final int WITHER_SKELETON_RANGE = 25;
    private static final double SCAN_RADIUS = 64.0;
    // Phantom rate fix: limites para evitar spam
    private static final long PHANTOM_MIN_INTERVAL_TICKS = 1200L; // 60s por jugador
    private static final int PHANTOM_MAX_NEAR = 3; // max phantoms hellish cerca del jugador
    private static final int PHANTOM_MAX_WORLD = 10; // max phantoms hellish en el mundo
    private static final int PHANTOM_SPAWN_CHANCE = 8; // 8% por chequeo (antes 15%)

    private final DiffModeManager manager;
    private final StructureCache structures;
    private final NamespacedKey totemDefinitivoKey;
    private final dev.xautral.death.store.SlotLockStore slotLocks;
    private final SkeletonBossManager bossManager;
    private final Random random;
    private final org.bukkit.NamespacedKey creeperLastSeenKey;
    private final org.bukkit.NamespacedKey creeperChaseKey;
    private final java.util.Map<UUID, Long> lastPhantomSpawn = new java.util.HashMap<>();
    private final java.util.Map<UUID, Long> lastMove = new java.util.HashMap<>();
    private final java.util.Map<UUID, Location> lastPlayerLoc = new java.util.HashMap<>();
    private final java.util.Map<UUID, Long> lastSupernova = new java.util.HashMap<>();
    private final java.util.Map<UUID, Integer> surfaceTicks = new java.util.HashMap<>();
    private final java.util.Map<UUID, Long> lastHuskTribe = new java.util.HashMap<>();
    private final java.util.Set<World> acidWarned = new HashSet<>();
    private boolean modeActivePrev;

    private int runCount;

    public HellishMobTask(DiffModeManager manager, StructureCache structures,
                          NamespacedKey totemDefinitivoKey,
                          dev.xautral.death.store.SlotLockStore slotLocks,
                          SkeletonBossManager bossManager,
                          Random random) {
        this.manager = manager;
        this.structures = structures;
        this.totemDefinitivoKey = totemDefinitivoKey;
        this.slotLocks = slotLocks;
        this.bossManager = bossManager;
        this.random = random;
        this.creeperLastSeenKey = new org.bukkit.NamespacedKey(manager.plugin(),
                "creeper_last_seen");
        this.creeperChaseKey = new org.bukkit.NamespacedKey(manager.plugin(),
                "creeper_chase_start");
    }

    @Override
    public void run() {
        if (!manager.isActive(DiffMode.HELLISH)) {
            modeActivePrev = false;
            return;
        }
        if (!modeActivePrev) {
            // Modo recien activado: limpia avisos de lluvia del modo anterior
            acidWarned.clear();
        }
        modeActivePrev = true;
        runCount++;
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() == World.Environment.NORMAL) {
                spawnInsomniaPhantoms(world);
            } else if (world.getEnvironment() == World.Environment.THE_END) {
                spawnEndGhasts(world);
                spawnEndPhantoms(world);
                if (manager.difficultyLevel() >= 6) {
                    spawnEnderBlazes(world);
                }
            }
            if (manager.difficultyLevel() >= 7
                    && (world.getEnvironment() == World.Environment.NORMAL
                    || world.getEnvironment() == World.Environment.THE_END)) {
                spawnSupernovaCreepers(world);
            }
            if (manager.difficultyLevel() >= 3) {
                if (world.getEnvironment() == World.Environment.NETHER) {
                    spawnLv3NetherCreepers(world);
                } else if (world.getEnvironment() == World.Environment.NORMAL) {
                    spawnLv3PillagerPatrols(world);
                }
            }
            if (manager.difficultyLevel() >= 5
                    && world.getEnvironment() == World.Environment.NORMAL) {
                spawnLv5Breezes(world);
                spawnLv5HuskTribe(world);
            }
            if (manager.difficultyLevel() >= 4
                    && world.getEnvironment() == World.Environment.NORMAL) {
                checkAcidRain(world);
            }
            handleMobsNearPlayers(world);
            sweepLockedSlots(world);
        }
        // El jefe esqueleto/zombi NO aparece automaticamente (bosses eliminados).
        // Aun se puede invocar manualmente con /deathsummon.
        // El lore de totems es lento de verificar: cada 4 pasadas (~6s)
        if (runCount % 4 == 0) {
            for (World world : Bukkit.getWorlds()) {
                HellishItems.updateTotemLores(world, totemDefinitivoKey,
                        manager.difficultyLevel());
            }
        }
    }

    // ---------------- Nivel 3: creepers del Nether y patrullas ----------------

    private static final int PILLAGER_PATROL_INTERVAL_TICKS = 1200; // 1 minuto
    private static final int PILLAGER_PATROL_COUNT = 3;
    private static final int LV3_MAX_NEAR = 6;
    private final java.util.Map<UUID, Long> lastPillagerPatrol = new java.util.HashMap<>();

    /** Nivel 3: en el Nether, creepers cargados e invisibles a ~30 bloques del jugador. */
    private void spawnLv3NetherCreepers(World world) {
        if (world.getPlayers().isEmpty() || random.nextInt(100) >= 12) {
            return;
        }
        Player player = world.getPlayers().get(random.nextInt(world.getPlayers().size()));
        long near = world.getNearbyEntities(player.getLocation(), 64, 64, 64,
                e -> e instanceof Creeper c
                        && c.getScoreboardTags().contains("dm_lv3_ncreeper")).size();
        if (near >= LV3_MAX_NEAR) {
            return;
        }
        double angle = random.nextDouble() * Math.PI * 2;
        double dist = 24 + random.nextDouble() * 8; // radio ~30
        double px = player.getLocation().getX() + Math.cos(angle) * dist;
        double pz = player.getLocation().getZ() + Math.sin(angle) * dist;
        int y = world.getHighestBlockYAt((int) px, (int) pz);
        Location loc = new Location(world, px, y, pz);
        if (!loc.getChunk().isLoaded()) {
            loc.getChunk().load();
            if (!loc.getChunk().isLoaded()) {
                return;
            }
        }
        Creeper creeper = (Creeper) world.spawnEntity(loc, EntityType.CREEPER);
        creeper.addScoreboardTag("dm_lv3_ncreeper");
        creeper.setPowered(true);
        creeper.setInvisible(true);
        creeper.addPotionEffect(new org.bukkit.potion.PotionEffect(
                org.bukkit.potion.PotionEffectType.SLOW_FALLING, 600, 1));
        manager.markSpawned(creeper);
        world.playSound(loc, Sound.ENTITY_CREEPER_HURT, 0.8f, 0.7f);
    }

    /** Nivel 3: en el Overworld, una patrulla de pillagers te caza cada minuto. */
    private void spawnLv3PillagerPatrols(World world) {
        if (world.getPlayers().isEmpty()) {
            return;
        }
        long now = Bukkit.getCurrentTick();
        Player player = world.getPlayers().get(random.nextInt(world.getPlayers().size()));
        Long last = lastPillagerPatrol.get(player.getUniqueId());
        if (last != null && now - last < PILLAGER_PATROL_INTERVAL_TICKS) {
            return;
        }
        long near = world.getNearbyEntities(player.getLocation(), 100, 100, 100,
                e -> e instanceof org.bukkit.entity.Pillager
                        && e.getScoreboardTags().contains("dm_lv3_pillager")).size();
        if (near >= LV3_MAX_NEAR) {
            return;
        }
        lastPillagerPatrol.put(player.getUniqueId(), now);
        double angle = random.nextDouble() * Math.PI * 2;
        for (int i = 0; i < PILLAGER_PATROL_COUNT; i++) {
            double a = angle + (i - 1) * 0.7;
            double distA = 18 + random.nextDouble() * 12;
            double px = player.getLocation().getX() + Math.cos(a) * distA;
            double pz = player.getLocation().getZ() + Math.sin(a) * distA;
            int y = world.getHighestBlockYAt((int) px, (int) pz);
            Location loc = new Location(world, px, y, pz);
            if (!loc.getChunk().isLoaded()) {
                loc.getChunk().load();
                if (!loc.getChunk().isLoaded()) {
                    continue;
                }
            }
            org.bukkit.entity.Pillager pillager = (org.bukkit.entity.Pillager)
                    world.spawnEntity(loc, EntityType.PILLAGER);
            pillager.addScoreboardTag("dm_lv3_pillager");
            pillager.getEquipment().setItemInMainHand(new ItemStack(Material.CROSSBOW));
            pillager.getEquipment().setItemInMainHandDropChance(0.0f);
            manager.markSpawned(pillager);
        }
    }

    // ---------------- Nivel 5: Breeze, tribus ----------------

    private static final int LV5_SURFACE_TICKS_NEEDED = 20 * 60 * 4; // 4 min en superficie
    private static final int LV5_MAX_BREEZES = 1;

    /** Nivel 5: si pasas mucho tiempo en la superficie aparece (max 1) un Breeze. */
    private void spawnLv5Breezes(World world) {
        long breezes = world.getEntitiesByClass(org.bukkit.entity.Breeze.class).stream()
                .filter(b -> b.getScoreboardTags().contains("dm_lv5_breeze")).count();
        if (breezes >= LV5_MAX_BREEZES) {
            return;
        }
        for (Player p : world.getPlayers()) {
            if (!isExposedToSky(p)) {
                continue;
            }
            int t = surfaceTicks.getOrDefault(p.getUniqueId(), 0) + 60;
            surfaceTicks.put(p.getUniqueId(), t);
            if (t < LV5_SURFACE_TICKS_NEEDED) {
                continue;
            }
            surfaceTicks.put(p.getUniqueId(), 0);
            spawnBreezeNear(p);
            return;
        }
    }

    private void spawnBreezeNear(Player p) {
        double ang = random.nextDouble() * Math.PI * 2;
        double dist = 24 + random.nextDouble() * 8;
        double px = p.getLocation().getX() + Math.cos(ang) * dist;
        double pz = p.getLocation().getZ() + Math.sin(ang) * dist;
        int y = p.getWorld().getHighestBlockYAt((int) px, (int) pz);
        Location loc = new Location(p.getWorld(), px, y, pz);
        if (!loc.getChunk().isLoaded()) {
            loc.getChunk().load();
            if (!loc.getChunk().isLoaded()) {
                return;
            }
        }
        org.bukkit.entity.Breeze breeze = (org.bukkit.entity.Breeze)
                loc.getWorld().spawnEntity(loc, EntityType.BREEZE);
        breeze.addScoreboardTag("dm_lv5_breeze");
        breeze.setTarget(p);
        var range = breeze.getAttribute(Attribute.FOLLOW_RANGE);
        if (range != null) {
            range.setBaseValue(64);
        }
        manager.markSpawned(breeze);
        p.sendMessage("§4§l[Death] §3Un §bBreeze§3 apareció por pasar tanto tiempo en la superficie...");
    }

    /** Lanza balas de shulker SIN ruta (caen solas) en direccion de espiral. */
    private void handleLv5Breeze(org.bukkit.entity.Breeze breeze) {
        LivingEntity target = breeze.getTarget();
        if (target == null || !target.isValid()) {
            Player nearest = null;
            double best = Double.MAX_VALUE;
            for (Player pl : breeze.getWorld().getPlayers()) {
                double d = pl.getLocation().distance(breeze.getLocation());
                if (d < best && d <= 48) {
                    best = d;
                    nearest = pl;
                }
            }
            if (nearest == null) {
                return;
            }
            breeze.setTarget(nearest);
        }
        long now = Bukkit.getCurrentTick();
        var pdc = breeze.getPersistentDataContainer();
        org.bukkit.NamespacedKey key = new org.bukkit.NamespacedKey(manager.plugin(), "breeze_last");
        long last = pdc.getOrDefault(key, org.bukkit.persistence.PersistentDataType.LONG, 0L);
        if (now - last < 40) {
            return; // cada ~2s
        }
        pdc.set(key, org.bukkit.persistence.PersistentDataType.LONG, now);
        Location from = breeze.getLocation().add(0, 1.4, 0);
        for (int i = 0; i < 8; i++) {
            double ang = (now * 0.05) + i * Math.PI / 4;
            org.bukkit.entity.ShulkerBullet bullet = (org.bukkit.entity.ShulkerBullet)
                    from.getWorld().spawnEntity(from, EntityType.SHULKER_BULLET);
            bullet.setShooter(breeze);
            Vector dir = new Vector(Math.cos(ang) * 1.2,
                    0.35 + (i % 2) * 0.2, Math.sin(ang) * 1.2);
            bullet.setVelocity(dir);
            manager.markSpawned(bullet);
        }
        from.getWorld().playSound(from, Sound.ENTITY_SHULKER_SHOOT, 0.9f, 1.3f);
    }

    private static final int LV5_HUSK_TRIBE_INTERVAL = 20 * 60 * 2; // cada 2 min
    private static final int LV5_HUSK_TRIBE_SIZE = 4;

    /** Nivel 5: una tribu de husks persigue a cada jugador sin dejarlo. */
    private void spawnLv5HuskTribe(World world) {
        if (world.getPlayers().isEmpty()) {
            return;
        }
        long now = Bukkit.getCurrentTick();
        Player p = world.getPlayers().get(random.nextInt(world.getPlayers().size()));
        Long last = lastHuskTribe.get(p.getUniqueId());
        if (last != null && now - last < LV5_HUSK_TRIBE_INTERVAL) {
            return;
        }
        long near = world.getNearbyEntities(p.getLocation(), 120, 120, 120,
                e -> e instanceof org.bukkit.entity.Husk h
                        && h.getScoreboardTags().contains("dm_lv5_husk_tribe")).size();
        if (near >= LV5_HUSK_TRIBE_SIZE) {
            return;
        }
        lastHuskTribe.put(p.getUniqueId(), now);
        double angle = random.nextDouble() * Math.PI * 2;
        for (int i = 0; i < LV5_HUSK_TRIBE_SIZE; i++) {
            double a = angle + i * 0.8;
            double dist = 28 + random.nextDouble() * 16;
            double px = p.getLocation().getX() + Math.cos(a) * dist;
            double pz = p.getLocation().getZ() + Math.sin(a) * dist;
            int y = world.getHighestBlockYAt((int) px, (int) pz);
            Location loc = new Location(world, px, y, pz);
            if (!loc.getChunk().isLoaded()) {
                loc.getChunk().load();
                if (!loc.getChunk().isLoaded()) {
                    continue;
                }
            }
            org.bukkit.entity.Husk husk = (org.bukkit.entity.Husk)
                    world.spawnEntity(loc, EntityType.HUSK);
            husk.addScoreboardTag("dm_lv5_husk_tribe");
            var eq = husk.getEquipment();
            if (eq != null) {
                ItemStack axe = new ItemStack(Material.IRON_AXE);
                var axeMeta = axe.getItemMeta();
                if (axeMeta != null) {
                    axeMeta.addEnchant(org.bukkit.enchantments.Enchantment.SHARPNESS, 3, true);
                    axe.setItemMeta(axeMeta);
                }
                eq.setItemInMainHand(axe);
                eq.setItemInMainHandDropChance(0.05f);
                eq.setHelmet(new ItemStack(Material.CHAINMAIL_HELMET));
                eq.setChestplate(new ItemStack(Material.CHAINMAIL_CHESTPLATE));
                eq.setLeggings(new ItemStack(Material.CHAINMAIL_LEGGINGS));
                eq.setBoots(new ItemStack(Material.CHAINMAIL_BOOTS));
                eq.setHelmetDropChance(0.0f);
                eq.setChestplateDropChance(0.0f);
                eq.setLeggingsDropChance(0.0f);
                eq.setBootsDropChance(0.0f);
                eq.setItemInOffHand(new ItemStack(Material.TOTEM_OF_UNDYING));
                eq.setItemInOffHandDropChance(0.0f);
            }
            var speed = husk.getAttribute(Attribute.MOVEMENT_SPEED);
            if (speed != null) {
                speed.setBaseValue(speed.getBaseValue() * 1.65);
            }
            var range = husk.getAttribute(Attribute.FOLLOW_RANGE);
            if (range != null) {
                range.setBaseValue(96);
            }
            husk.setTarget(p);
            manager.markSpawned(husk);
        }
    }

    /** Los husks de la tribu nunca sueltan a su presa. */
    private void handleHuskTribe(org.bukkit.entity.Husk husk) {
        LivingEntity tgt = husk.getTarget();
        if (tgt == null || !tgt.isValid()
                || !(tgt instanceof Player p && p.isOnline())) {
            Player nearest = null;
            double best = Double.MAX_VALUE;
            for (Player pl : husk.getWorld().getPlayers()) {
                double d = pl.getLocation().distance(husk.getLocation());
                if (d < best && d <= 96) {
                    best = d;
                    nearest = pl;
                }
            }
            if (nearest != null) {
                husk.setTarget(nearest);
            }
        }
    }

    /** Los esqueletos de la trampa (y su arana montura) persiguen al asesino. */
    private void handleLv5TrapSkeleton(AbstractSkeleton skeleton) {
        LivingEntity target = skeleton.getTarget();
        if (target == null || !target.isValid()
                || !(target instanceof Player tp && tp.isOnline())) {
            Player nearest = null;
            double best = Double.MAX_VALUE;
            for (Player pl : skeleton.getWorld().getPlayers()) {
                double d = pl.getLocation().distance(skeleton.getLocation());
                if (d < best && d <= 96) {
                    best = d;
                    nearest = pl;
                }
            }
            if (nearest != null) {
                skeleton.setTarget(nearest);
                if (skeleton.getVehicle() instanceof Mob spider) {
                    spider.setTarget(nearest);
                }
            }
        } else if (skeleton.getVehicle() instanceof Mob spider) {
            spider.setTarget(target);
        }
    }

    // ---------------- Slots sellados ----------------

    private void sweepLockedSlots(World world) {
        for (Player player : world.getPlayers()) {
            slotLocks.sweepLockedSlots(player);
        }
    }

    // ---------------- Phantoms por insomnio (rate arreglado) ----------------

    private void spawnInsomniaPhantoms(World world) {
        boolean level4 = manager.difficultyLevel() >= 4;
        // Limite global del mundo: en nivel 4 solo 3 maximo
        int worldCap = level4 ? 3 : PHANTOM_MAX_WORLD;
        long worldPhantoms = world.getEntitiesByClass(Phantom.class).stream()
                .filter(p -> p.getScoreboardTags().contains("dm_hellish_phantom")).count();
        if (worldPhantoms >= worldCap) {
            return;
        }
        long now = Bukkit.getCurrentTick();
        for (Player player : world.getPlayers()) {
            if (player.getStatistic(Statistic.TIME_SINCE_REST) < PHANTOM_INSOMNIA_TICKS
                    || random.nextInt(100) >= PHANTOM_SPAWN_CHANCE) {
                continue;
            }
            // Cooldown por jugador: minimo 60s entre spawns hellish
            Long last = lastPhantomSpawn.get(player.getUniqueId());
            if (last != null && (now - last) < PHANTOM_MIN_INTERVAL_TICKS) {
                continue;
            }
            // Limite cercano: si ya tiene 3+ phantoms hellish cerca, no spawnear mas
            long near = world.getNearbyEntities(player.getLocation(), 64, 64, 64,
                    e -> e instanceof Phantom ph && ph.getScoreboardTags().contains("dm_hellish_phantom")).size();
            if (near >= PHANTOM_MAX_NEAR) {
                continue;
            }
            // Solo de noche o tormenta (como vanilla), evita spam diurno.
            // En nivel 4 los phantoms aparecen TAMBIEN de dia
            boolean isNight = world.getTime() >= 13000 && world.getTime() <= 23000;
            boolean thunder = world.isThundering();
            if (!isNight && !thunder && !level4) {
                // 80% de cancelar si es de dia
                if (random.nextInt(100) < 80) continue;
            }
            // Altura: arriba del todo (cielo) para que baje en picado
            int highest = world.getHighestBlockYAt(player.getLocation());
            int spawnY = Math.min(world.getMaxHeight() - 5, Math.max(highest + 25, player.getLocation().getBlockY() + 20));
            Location spawnLoc = new Location(world,
                    player.getLocation().getX() + (random.nextDouble() - 0.5) * 40,
                    spawnY + random.nextInt(6),
                    player.getLocation().getZ() + (random.nextDouble() - 0.5) * 40);
            if (!spawnLoc.getChunk().isLoaded()) {
                spawnLoc.getChunk().load();
                if (!spawnLoc.getChunk().isLoaded()) continue;
            }
            lastPhantomSpawn.put(player.getUniqueId(), now);
            // Pack reducido a 1 solo para no spamear (antes 1-2)
            Phantom phantom = (Phantom) world.spawnEntity(spawnLoc, EntityType.PHANTOM);
            try { phantom.setSize(2); } catch (Throwable ignored) {}
            phantom.getPersistentDataContainer().set(
                    new org.bukkit.NamespacedKey(manager.plugin(), "phantom_bites"),
                    org.bukkit.persistence.PersistentDataType.INTEGER, 0);
            manager.buffPhantom(phantom);
            manager.markSpawned(phantom);
            // 30% de nacer con esqueleto montado
            if (random.nextInt(100) < 30) {
                AbstractSkeleton rider = (AbstractSkeleton) world.spawnEntity(
                        spawnLoc, EntityType.SKELETON);
                rider.getEquipment().setItemInMainHand(new ItemStack(Material.BOW));
                manager.markSpawned(rider);
                phantom.addPassenger(rider);
            }
        }
    }

    // ---------------- Ghasts del End ----------------

    private void spawnEndGhasts(World world) {
        if (world.getPlayers().isEmpty() || random.nextInt(100) >= 10) {
            return;
        }
        Player player = world.getPlayers().get(random.nextInt(world.getPlayers().size()));
        long ghasts = world.getNearbyEntities(player.getLocation(), 100, 100, 100,
                e -> e.getType() == EntityType.GHAST).size();
        if (ghasts >= 5) {
            return;
        }
        double angle = random.nextDouble() * Math.PI * 2;
        double dist = 24 + random.nextDouble() * 24;
        Location loc = player.getLocation().clone()
                .add(Math.cos(angle) * dist, 10 + random.nextInt(14), Math.sin(angle) * dist);
        if (loc.getChunk().isLoaded()) {
            org.bukkit.entity.Entity ghast = world.spawnEntity(loc, EntityType.GHAST);
            manager.markSpawned(ghast);
        }
    }

    // ---------------- Comportamientos por mob ----------------

    private void handleMobsNearPlayers(World world) {
        // Zona de trial chambers calculada una vez por pasada (cacheada)
        Location trialCenter = structures.locate(
                world, "trial_chambers", manager.trialChambersStructure());
        Set<UUID> processed = new HashSet<>();
        for (Player player : world.getPlayers()) {
            for (Entity entity : world.getNearbyEntities(player.getLocation(),
                    SCAN_RADIUS, SCAN_RADIUS, SCAN_RADIUS, e -> e instanceof LivingEntity)) {
                LivingEntity living = (LivingEntity) entity;
                if (!processed.add(living.getUniqueId())) {
                    continue;
                }
                // Nuevos Hellish: golems, shulkers, dragon y end phantom
                if (living instanceof org.bukkit.entity.Snowman snowman) {
                    handleSnowGolem(snowman);
                    continue;
                } else if (living instanceof org.bukkit.entity.IronGolem ironGolem) {
                    handleIronGolem(ironGolem);
                    continue;
                } else if (living instanceof org.bukkit.entity.Shulker shulker) {
                    handleShulker(shulker);
                    continue;
                } else if (living instanceof Phantom phantom && living.getScoreboardTags().contains("dm_end_phantom")) {
                    Player p = phantom.getTarget() instanceof Player pl ? pl : world.getPlayers().stream().min(java.util.Comparator.comparingDouble(pl -> pl.getLocation().distance(phantom.getLocation()))).orElse(null);
                    handleEndPhantom(phantom, p);
                    continue;
                }
                if (living instanceof Zombie zombie && !(living instanceof org.bukkit.entity.PiglinAbstract)) {
                    if (withinTargetRange(zombie, 8)) {
                        cycleShield(zombie);
                        // Cerca de una trial chamber usan mazo
                        if (trialCenter != null
                                && zombie.getLocation().distance(trialCenter) < 128
                                && zombie.getEquipment() != null
                                && zombie.getEquipment().getItemInMainHand().getType() != Material.MACE) {
                            zombie.getEquipment().setItemInMainHand(new ItemStack(Material.MACE));
                            zombie.getEquipment().setItemInMainHandDropChance(0.0f);
                        }
                    }
                } else if (living instanceof Phantom phantom
                        && living.getScoreboardTags().contains("dm_hellish_phantom")) {
                    handleHellishPhantom(phantom);
                } else if (living.getScoreboardTags().contains("dm_angry_animal")) {
                    // Vacas/polvos furiosos: simulan ataque cuerpo a cuerpo
                    handleAngryAnimal(living);
                } else if (living instanceof Creeper creeper) {
                    handleCreeper(creeper, player);
                } else if (living instanceof AbstractSkeleton skeleton) {
                    handleSkeleton(skeleton, player);
                    if (manager.difficultyLevel() >= 6
                            && withinTargetRange(skeleton, 8)) {
                        cycleShield(skeleton);
                    }
                } else if (living instanceof org.bukkit.entity.Blaze blaze
                        && manager.difficultyLevel() >= 6
                        && blaze.getWorld().getEnvironment()
                        == World.Environment.THE_END) {
                    handleEnderBlaze(blaze, player);
                } else if (living instanceof Ghast ghast) {
                    handleGhast(ghast, player);
                } else if (living instanceof org.bukkit.entity.Husk husk
                        && living.getScoreboardTags().contains("dm_lv5_husk_tribe")) {
                    handleHuskTribe(husk);
                } else if (living instanceof AbstractSkeleton skeleton
                        && living.getScoreboardTags().contains("dm_lv5_trap")) {
                    handleLv5TrapSkeleton(skeleton);
                } else if (living instanceof org.bukkit.entity.Breeze breeze
                        && living.getScoreboardTags().contains("dm_lv5_breeze")) {
                    handleLv5Breeze(breeze);
                } else if (living instanceof Monster monster) {
                    if (withinTargetRange(monster, 8)) {
                        cycleShield(monster);
                    }
                } else if (manager.difficultyLevel() >= 4
                        && isPeacefulHostile(living)) {
                    handleHostilePeaceful(living, player);
                    continue;
                }
            }
        }
    }

    private boolean withinTargetRange(Mob mob, double range) {
        LivingEntity target = mob.getTarget();
        return target != null && target.isValid()
                && mob.getLocation().distance(target.getLocation()) <= range;
    }

    /**
     * Vacas y polvos furiosos: vanilla no les da IA de ataque, asi que la
     * simulamos — re-apuntan a su agresor y le pegan si lo alcanzan.
     */
    private void handleAngryAnimal(LivingEntity animal) {
        String revenge = animal.getPersistentDataContainer().get(
                new org.bukkit.NamespacedKey(manager.plugin(), "revenge_target"),
                org.bukkit.persistence.PersistentDataType.STRING);
        LivingEntity attacker = null;
        if (revenge != null) {
            try {
                attacker = (LivingEntity) Bukkit.getEntity(UUID.fromString(revenge));
            } catch (IllegalArgumentException ignored) {
            }
        }
        boolean gone = attacker == null || !attacker.isValid()
                || (attacker instanceof org.bukkit.entity.Player p && !p.isOnline());
        if (gone) {
            animal.removeScoreboardTag("dm_angry_animal");
            return;
        }
        if (animal instanceof Mob mob && (mob.getTarget() == null
                || !mob.getTarget().equals(attacker))) {
            mob.setTarget(attacker);
        }
        // Golpe si esta a rango de "mordisco"
        if (animal.getLocation().distance(attacker.getLocation()) <= 1.6) {
            attacker.damage(2.0, animal);
            world(animal.getLocation()).playSound(animal.getLocation(),
                    org.bukkit.Sound.ENTITY_LLAMA_SWAG, 0.7f, 0.7f);
        }
    }

    private static World world(Location loc) {
        return loc.getWorld();
    }

    // ---------------- Nivel 4: pacificos agresivos y lluvia acida ----------------

    private boolean isPeacefulHostile(LivingEntity entity) {
        switch (entity.getType()) {
            case COW, CHICKEN, PIG, VILLAGER -> {
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void handleHostilePeaceful(LivingEntity mob, Player player) {
        mob.addScoreboardTag("dm_lv4_peaceful");
        if (mob instanceof Mob m && (m.getTarget() == null || !m.getTarget().equals(player))) {
            m.setTarget(player);
        }
        double d = mob.getLocation().distance(player.getLocation());
        if (d <= 1.8) {
            player.damage(2.0, mob);
            player.getWorld().playSound(player.getLocation(),
                    Sound.ENTITY_CHICKEN_HURT, 1f, 0.7f);
            player.getWorld().spawnParticle(org.bukkit.Particle.DAMAGE_INDICATOR,
                    player.getLocation().add(0, 1.2, 0), 4, 0.2, 0.3, 0.2, 0.1);
        }
    }

    private static final long ACID_RAIN_WARN_TICKS = 20 * 10; // 10s de aviso
    private static final long ACID_RAIN_CLEAR_TICKS = 20 * 30; // 30s claros => reset

    /** Nivel 4: lluvia acida persistente sin efectos de fuego, daño escalado por armadura y derrite madera/troncos. */
    private void checkAcidRain(World world) {
        boolean storming = world.hasStorm() || world.isThundering();
        if (storming) {
            for (Player p : world.getPlayers()) {
                if (p.getGameMode() == org.bukkit.GameMode.CREATIVE || p.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
                if (!isExposedToSky(p)) continue;
                // Daño persistente sin fuego/efectos: menor por cada pieza de armadura
                int armorPieces = countArmorPieces(p);
                double base = 1.2; // base
                double dmg = Math.max(0.25, base - armorPieces * 0.22); // 4 piezas => 0.32, 0 piezas =>1.2
                // Aplica daño directo sin prender fuego ni efectos
                p.damage(dmg);
                // Partículas sutiles cada 15 ticks para no spamear pero persistente visual
                if (runCount % 15 == 0) {
                    Location where = p.getLocation().add(0, 1.6, 0);
                    where.getWorld().spawnParticle(org.bukkit.Particle.DRIPPING_WATER,
                            where, 3, 0.25, 0.15, 0.25, 0.01);
                    where.getWorld().spawnParticle(org.bukkit.Particle.SMOKE,
                            where, 1, 0.15, 0.1, 0.15, 0.005);
                }
                // Derrite madera/troncos de forma persistente cada ~1.5s por jugador
                if (runCount % 20 == 0) {
                    meltNearbyWood(p);
                }
            }
            acidWarned.add(world);
            return;
        }
        long weather = world.getWeatherDuration();
        if (weather > 0 && weather < ACID_RAIN_WARN_TICKS
                && !acidWarned.contains(world)) {
            for (Player p : world.getPlayers()) {
                p.sendMessage("§4§l[Death] §c⚠ §eLluvia Ácida§c en camino... "
                        + "¡en unos segundos te quemará el cielo!");
                p.getWorld().playSound(p.getLocation(),
                        Sound.ENTITY_WARDEN_SONIC_BOOM, 0.6f, 1.8f);
            }
            acidWarned.add(world);
        } else if (weather > ACID_RAIN_CLEAR_TICKS) {
            acidWarned.remove(world);
        }
    }

    private boolean isExposedToSky(Player p) {
        Location loc = p.getLocation();
        return loc.getWorld().getHighestBlockYAt(loc) <= loc.getBlockY() + 1;
    }

    private int countArmorPieces(Player p) {
        var inv = p.getInventory();
        int c = 0;
        if (inv.getHelmet() != null && !inv.getHelmet().getType().isAir()) c++;
        if (inv.getChestplate() != null && !inv.getChestplate().getType().isAir()) c++;
        if (inv.getLeggings() != null && !inv.getLeggings().getType().isAir()) c++;
        if (inv.getBoots() != null && !inv.getBoots().getType().isAir()) c++;
        return c;
    }

    /** Nivel 5: la lluvia acida derrite bloques tipo madera cercanos. */
    private void meltNearbyWood(Player p) {
        Location loc = p.getLocation();
        World w = loc.getWorld();
        double ang = random.nextDouble() * Math.PI * 2;
        double rad = 3 + random.nextDouble() * 5;
        int x = loc.getBlockX() + (int) (Math.cos(ang) * rad);
        int z = loc.getBlockZ() + (int) (Math.sin(ang) * rad);
        for (int y = loc.getBlockY(); y < loc.getBlockY() + 4; y++) {
            org.bukkit.block.Block b = w.getBlockAt(x, y, z);
            if (isMeltableWood(b.getType())) {
                b.getWorld().playSound(b.getLocation(),
                        Sound.BLOCK_FIRE_EXTINGUISH, 1f, 1.3f);
                b.getWorld().spawnParticle(org.bukkit.Particle.SMOKE,
                        b.getLocation().add(0.5, 0.5, 0.5),
                        8, 0.3, 0.3, 0.3, 0.01);
                b.setType(Material.AIR);
                return; // 1 bloque por tick del task (cada 3s)
            }
        }
    }

    private boolean isMeltableWood(Material m) {
        String n = m.name();
        return n.endsWith("_LOG") || n.endsWith("_PLANKS")
                || n.endsWith("_LEAVES") || n.endsWith("_FENCE")
                || n.endsWith("_FENCE_GATE") || n.endsWith("_DOOR")
                || n.endsWith("_TRAPDOOR") || n.endsWith("_STAIRS")
                || n.endsWith("_SLAB") || n.equals("WOODEN_BUTTON")
                || n.equals("WOODEN_PRESSURE_PLATE") || n.equals("LADDER")
                || n.equals("BOOKSHELF") || n.startsWith("MANGROVE_")
                || n.startsWith("PALE_OAK_");
    }

    /**
     * Los phantoms de Hellish son SIEMPRE hostiles: si no tienen objetivo
     * atacan al jugador mas cercano, y buscan activamente al esqueleto mas
     * cercano para que lo monte y dispare desde el cielo.
     */
    private void handleHellishPhantom(Phantom phantom) {
        // Hostilidad garantizada: objetivo = jugador mas cercano en 64 bloques
        if (phantom.getTarget() == null || !phantom.getTarget().isValid()) {
            Player nearest = null;
            double bestDist = Double.MAX_VALUE;
            for (Player player : phantom.getWorld().getPlayers()) {
                double dist = player.getLocation().distance(phantom.getLocation());
                if (dist < bestDist && dist <= 64) {
                    bestDist = dist;
                    nearest = player;
                }
            }
            if (nearest != null) {
                phantom.setTarget(nearest);
            }
        }

        // Rescate MAS COMUN: busca esqueletos en un radio mucho mayor
        if (phantom.getPassengers().isEmpty()) {
            AbstractSkeleton nearestSkeleton = null;
            double bestDist = Double.MAX_VALUE;
            for (Entity nearby : phantom.getWorld().getNearbyEntities(
                    phantom.getLocation(), 64, 48, 64)) {
                if (!(nearby instanceof AbstractSkeleton skeleton)
                        || !skeleton.isValid()
                        || skeleton.isInsideVehicle()) {
                    continue;
                }
                if (skeleton.getScoreboardTags().contains("dm_fled")) {
                    continue; // ya tiene phantom o ya huyo antes
                }
                double dist = skeleton.getLocation().distance(phantom.getLocation());
                if (dist < bestDist) {
                    bestDist = dist;
                    nearestSkeleton = skeleton;
                }
            }
            if (nearestSkeleton != null) {
                // Vuela hacia el esqueleto hasta estar encima para recogerlo
                Location pickup = nearestSkeleton.getLocation().add(0, 2.5, 0);
                if (phantom.getLocation().distance(pickup) > 4.0) {
                    Vector dir = pickup.toVector()
                            .subtract(phantom.getLocation().toVector()).normalize();
                    phantom.setVelocity(dir.multiply(0.6));
                    return; // todavia viajando hacia el
                }
                if (nearestSkeleton.getVehicle() instanceof org.bukkit.entity.Spider spider) {
                    spider.removePassenger(nearestSkeleton);
                }
                phantom.addPassenger(nearestSkeleton);
                nearestSkeleton.addScoreboardTag("dm_fled");
                if (phantom.getTarget() == null && nearestSkeleton.getTarget() != null) {
                    phantom.setTarget(nearestSkeleton.getTarget());
                }
            }
        }
    }

    /**
     * Alternancia de escudo: al detectar a su victima se equipa el escudo y
     * alterna entre defenderse y atacar. En nivel 6+ los escudos se rigen por
     * una nueva regla: maximo ~2s levantado, se bajan si el jugador esta
     * desarmado/descansando y se rompen con el uso. Ademas NO se usa
     * startUsingItem (que hacia que el dano pareciera no aplicarse); la
     * reduccion de dano se aplica manualmente en el listener de dano.
     */
    private static final int SHIELD_MAX_HP = 8;
    private static final long SHIELD_HOLD_TICKS = 40; // 2s

    private void cycleShield(LivingEntity mob) {
        if (manager.difficultyLevel() < 6) {
            legacyCycleShield(mob);
            return;
        }
        boolean undeadGear = mob instanceof Zombie || mob instanceof AbstractSkeleton;
        if (undeadGear) {
            applyLv6UndeadGear(mob);
        }
        EntityEquipment eq = mob.getEquipment();
        if (eq == null) {
            return;
        }
        LivingEntity target = mob instanceof Mob m ? m.getTarget() : null;
        Player player = target instanceof Player p
                ? p : nearestPlayer(mob);
        boolean resting = player != null
                && (player.isSneaking() || player.getFoodLevel() < 6);
        ItemStack main = player != null
                ? player.getInventory().getItemInMainHand() : null;
        boolean unarmed = main == null || main.getType().isAir()
                || !isWeapon(main.getType());
        boolean wantShield = player != null && !unarmed && !resting
                && random.nextInt(100) < 50;

        var pdc = mob.getPersistentDataContainer();
        long now = Bukkit.getCurrentTick();
        boolean defending = pdc.getOrDefault(manager.shieldActiveKey(),
                org.bukkit.persistence.PersistentDataType.BYTE, (byte) 0) == 1;
        long till = pdc.getOrDefault(manager.shieldTillKey(),
                org.bukkit.persistence.PersistentDataType.LONG, 0L);
        int hp = pdc.getOrDefault(manager.shieldHpKey(),
                org.bukkit.persistence.PersistentDataType.INTEGER, SHIELD_MAX_HP);
        if (defending) {
            if (now >= till || hp <= 0 || !wantShield) {
                eq.setItemInOffHand(null);
                pdc.set(manager.shieldActiveKey(),
                        org.bukkit.persistence.PersistentDataType.BYTE, (byte) 0);
                pdc.set(manager.shieldTillKey(),
                        org.bukkit.persistence.PersistentDataType.LONG, now + 10L);
            }
        } else if (wantShield && now >= till) {
            eq.setItemInOffHand(new ItemStack(Material.SHIELD));
            eq.setItemInOffHandDropChance(0.0f);
            pdc.set(manager.shieldActiveKey(),
                    org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
            pdc.set(manager.shieldTillKey(),
                    org.bukkit.persistence.PersistentDataType.LONG, now + SHIELD_HOLD_TICKS);
            pdc.set(manager.shieldHpKey(),
                    org.bukkit.persistence.PersistentDataType.INTEGER, SHIELD_MAX_HP);
        }
    }

    private Player nearestPlayer(LivingEntity mob) {
        Player best = null;
        double b = Double.MAX_VALUE;
        for (Player p : mob.getWorld().getPlayers()) {
            double d = p.getLocation().distance(mob.getLocation());
            if (d < b && d <= 64) {
                b = d;
                best = p;
            }
        }
        return best;
    }

    private boolean isWeapon(Material m) {
        return m.name().endsWith("_SWORD") || m.name().endsWith("_AXE")
                || m.name().endsWith("_PICKAXE") || m.name().endsWith("_HOE")
                || m.name().endsWith("_SHOVEL") || m == Material.TRIDENT
                || m == Material.BOW || m == Material.CROSSBOW
                || m == Material.MACE;
    }

    /** Nivel 6: armadura para esqueletos y equipamiento para undead gear. */
    private void applyLv6UndeadGear(LivingEntity mob) {
        var pdc = mob.getPersistentDataContainer();
        if (pdc.has(new NamespacedKey(manager.plugin(), "dm_lv6_geared"),
                org.bukkit.persistence.PersistentDataType.BYTE)) {
            return;
        }
        EntityEquipment eq = mob.getEquipment();
        if (eq == null) {
            return;
        }
        if (mob instanceof org.bukkit.entity.PigZombie zp) {
            // Piglins zombis siempre enojados con armadura de oro
            zp.setAnger(4000);
            var gold = new ItemStack[]{new ItemStack(Material.GOLDEN_HELMET),
                    new ItemStack(Material.GOLDEN_CHESTPLATE),
                    new ItemStack(Material.GOLDEN_LEGGINGS),
                    new ItemStack(Material.GOLDEN_BOOTS)};
            for (ItemStack piece : gold) {
                var im = piece.getItemMeta();
                if (im != null) {
                    im.addEnchant(org.bukkit.enchantments.Enchantment.PROTECTION, 5, true);
                    piece.setItemMeta(im);
                }
            }
            eq.setHelmet(gold[0]);
            eq.setChestplate(gold[1]);
            eq.setLeggings(gold[2]);
            eq.setBoots(gold[3]);
            eq.setHelmetDropChance(0.20f);
            eq.setChestplateDropChance(0.20f);
            eq.setLeggingsDropChance(0.20f);
            eq.setBootsDropChance(0.20f);
            ItemStack sword = new ItemStack(Material.GOLDEN_SWORD);
            var sm = sword.getItemMeta();
            if (sm != null) {
                sm.addEnchant(org.bukkit.enchantments.Enchantment.KNOCKBACK, 2, true);
                sm.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 3, true);
                sword.setItemMeta(sm);
            }
            eq.setItemInMainHand(sword);
            eq.setItemInMainHandDropChance(0.1f);
            var follow = zp.getAttribute(Attribute.FOLLOW_RANGE);
            if (follow != null) follow.setBaseValue(64);
            if (zp.getTarget() == null) {
                Player p = nearestPlayer(zp);
                if (p != null) zp.setTarget(p);
            }
        } else if (mob instanceof org.bukkit.entity.WitherSkeleton ws) {
            ItemStack axe = new ItemStack(Material.IRON_AXE);
            var am = axe.getItemMeta();
            if (am != null) {
                am.addEnchant(org.bukkit.enchantments.Enchantment.SHARPNESS, 3, true);
                axe.setItemMeta(am);
            }
            ws.getEquipment().setItemInMainHand(axe);
            ws.getEquipment().setItemInMainHandDropChance(0.1f);
            ws.getEquipment().setItemInOffHand(null);
        } else if (mob instanceof AbstractSkeleton sk
                && !(mob instanceof org.bukkit.entity.WitherSkeleton)) {
            // Esqueletos: armadura de hierro ligera
            eq.setHelmet(new ItemStack(Material.IRON_HELMET));
            eq.setChestplate(new ItemStack(Material.CHAINMAIL_CHESTPLATE));
            eq.setLeggings(new ItemStack(Material.CHAINMAIL_LEGGINGS));
            eq.setBoots(new ItemStack(Material.IRON_BOOTS));
            eq.setHelmetDropChance(0.05f);
            eq.setChestplateDropChance(0.05f);
            eq.setLeggingsDropChance(0.05f);
            eq.setBootsDropChance(0.05f);
        }
        pdc.set(new NamespacedKey(manager.plugin(), "dm_lv6_geared"),
                org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
    }

    /** Comportamiento previo a nivel 6 (alternancia simple de 1s con startUsingItem). */
    private void legacyCycleShield(LivingEntity mob) {
        EntityEquipment eq = mob.getEquipment();
        if (eq == null) {
            return;
        }
        var pdc = mob.getPersistentDataContainer();
        long now = Bukkit.getCurrentTick();
        if (!mob.getScoreboardTags().contains("dm_shield")) {
            // Primera deteccion: se equipa y se cubre de inmediato
            mob.addScoreboardTag("dm_shield");
            pdc.set(manager.shieldStateKey(), org.bukkit.persistence.PersistentDataType.LONG, now);
            pdc.set(manager.shieldPhaseKey(), org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
            eq.setItemInOffHand(new ItemStack(Material.SHIELD));
            eq.setItemInOffHandDropChance(0.0f);
            mob.startUsingItem(org.bukkit.inventory.EquipmentSlot.OFF_HAND);
            return;
        }
        byte phase = pdc.getOrDefault(manager.shieldPhaseKey(),
                org.bukkit.persistence.PersistentDataType.BYTE, (byte) 0);
        long nextToggle = pdc.getOrDefault(manager.shieldStateKey(),
                org.bukkit.persistence.PersistentDataType.LONG, 0L);
        if (now < nextToggle) {
            return;
        }
        boolean defending = phase == 1;
        if (defending) {
            eq.setItemInOffHand(null);
            pdc.set(manager.shieldPhaseKey(), org.bukkit.persistence.PersistentDataType.BYTE, (byte) 0);
        } else {
            eq.setItemInOffHand(new ItemStack(Material.SHIELD));
            mob.startUsingItem(org.bukkit.inventory.EquipmentSlot.OFF_HAND);
            pdc.set(manager.shieldPhaseKey(), org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        }
        pdc.set(manager.shieldStateKey(), org.bukkit.persistence.PersistentDataType.LONG, now + 20L);
    }

    private void handleCreeper(Creeper creeper, Player player) {
        if (creeper.isIgnited() || creeper.getWorld() != player.getWorld()) {
            return;
        }
        if (manager.difficultyLevel() >= 7
                && creeper.getScoreboardTags().contains("dm_supernova")) {
            handleSupernova(creeper, player);
            return;
        }
        if (manager.difficultyLevel() >= 2) {
            handleCreeperLevel2(creeper, player);
            return;
        }
        Location cl = creeper.getLocation();
        Location pl = player.getLocation();
        double dx = Math.abs(cl.getX() - pl.getX());
        double dy = Math.abs(cl.getY() - pl.getY());
        double dz = Math.abs(cl.getZ() - pl.getZ());
        // Detecta a traves de paredes: cerca pero sin linea de vision
        if (dx <= 4 && dy <= 3 && dz <= 4 && !creeper.hasLineOfSight(player)) {
            creeper.ignite();
        }
    }

    /**
     * Nivel 2+: los creepers te persiguen y son mas rapidos. Recuerdan el
     * ultimo punto donde te vieron y van a por el aunque te escondas, te ven
     * entre paredes y explotan al alcanzar la mitad de su radio de explosion.
     */
    private void handleCreeperLevel2(Creeper creeper, Player player) {
        Location cl = creeper.getLocation();
        double dist = cl.distance(player.getLocation());
        var pdc = creeper.getPersistentDataContainer();
        long now = Bukkit.getCurrentTick();

        creeper.setTarget(player);
        boolean los = creeper.hasLineOfSight(player);

        // Guarda el ultimo punto visto del jugador (para ir tras el si se esconde)
        if (los) {
            pdc.set(creeperLastSeenKey, org.bukkit.persistence.PersistentDataType.LONG, now);
            pdc.set(new NamespacedKey(manager.plugin(), "creeper_last_x"),
                    org.bukkit.persistence.PersistentDataType.DOUBLE, player.getLocation().getX());
            pdc.set(new NamespacedKey(manager.plugin(), "creeper_last_y"),
                    org.bukkit.persistence.PersistentDataType.DOUBLE, player.getLocation().getY());
            pdc.set(new NamespacedKey(manager.plugin(), "creeper_last_z"),
                    org.bukkit.persistence.PersistentDataType.DOUBLE, player.getLocation().getZ());
        }

        // La mitad del radio de explosion = punto en el que detona
        float blast = creeper.getExplosionRadius();
        double blastHalf = Math.max(1.0, blast * 0.5);

        if (los) {
            pdc.set(creeperChaseKey, org.bukkit.persistence.PersistentDataType.DOUBLE, dist);
        }
        double startDist = pdc.getOrDefault(creeperChaseKey,
                org.bukkit.persistence.PersistentDataType.DOUBLE, dist);

        // Si perdimos de vista a la victima (se escondio), ir al ultimo punto
        if (!los) {
            Double lx = pdc.get(new NamespacedKey(manager.plugin(), "creeper_last_x"),
                    org.bukkit.persistence.PersistentDataType.DOUBLE);
            Double ly = pdc.get(new NamespacedKey(manager.plugin(), "creeper_last_y"),
                    org.bukkit.persistence.PersistentDataType.DOUBLE);
            Double lz = pdc.get(new NamespacedKey(manager.plugin(), "creeper_last_z"),
                    org.bukkit.persistence.PersistentDataType.DOUBLE);
            if (lx != null && ly != null && lz != null) {
                Location last = new Location(creeper.getWorld(), lx, ly, lz);
                if (creeper.getLocation().distance(last) > 1.2) {
                    creeper.getPathfinder().moveTo(last, 0.6); // persigue el ultimo punto
                } else {
                    // Llego al ultimo punto y no te ve: explota
                    creeper.getWorld().playSound(cl, Sound.ENTITY_CREEPER_PRIMED, 1f, 1.2f);
                    pdc.remove(creeperChaseKey);
                    creeper.ignite();
                    return;
                }
            }
        }

        // Si huyo lejos del punto donde empezo a perseguir, explota
        boolean fled = dist > startDist + 10;
        if (fled) {
            creeper.getWorld().playSound(cl, Sound.ENTITY_CREEPER_PRIMED, 1f, 1.2f);
            pdc.remove(creeperChaseKey);
            creeper.ignite();
            return;
        }

        // Detona dentro de la mitad de su radio de explosion
        if (dist <= blastHalf || (!los && dist <= blast * 0.9)) {
            creeper.getWorld().playSound(cl, Sound.ENTITY_CREEPER_PRIMED, 1f, 1.2f);
            pdc.remove(creeperChaseKey);
            creeper.ignite();
        } else if (los && dist > blastHalf && dist <= 4 && !creeper.hasLineOfSight(player)) {
            // entre paredes a rango de detonacion
            creeper.getWorld().playSound(cl, Sound.ENTITY_CREEPER_PRIMED, 1f, 1.2f);
            pdc.remove(creeperChaseKey);
            creeper.ignite();
        }
    }

    /**
     * Nivel 7: Creepers Supernova. Vuelan, solo atacan a su victima designada,
     * y detonan a 5-15 bloques usando un radio de explosion grande. Aparecen
     * de forma artificial cuando llevas un rato inactivo.
     */
    private void handleSupernova(Creeper creeper, Player player) {
        var pdc = creeper.getPersistentDataContainer();
        String victimUuid = pdc.get(new NamespacedKey(manager.plugin(), "sn_victim"),
                org.bukkit.persistence.PersistentDataType.STRING);
        if (victimUuid == null) {
            creeper.getWorld().playSound(creeper.getLocation(),
                    Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.8f);
            creeper.remove();
            return;
        }
        Player victim = null;
        try {
            Entity e = Bukkit.getEntity(java.util.UUID.fromString(victimUuid));
            if (e instanceof Player p && p.isOnline()) victim = p;
        } catch (IllegalArgumentException ignored) {
        }
        if (victim == null) {
            creeper.remove();
            return;
        }
        if (creeper.getTarget() == null || !creeper.getTarget().equals(victim)) {
            creeper.setTarget(victim);
        }
        double dist = creeper.getLocation().distance(victim.getLocation());
        // Vuela (flota en el aire) hacia el blanco
        Vector to = victim.getLocation().toVector()
                .subtract(creeper.getLocation().toVector()).normalize();
        if (dist > 6) {
            creeper.setVelocity(to.multiply(0.45));
        } else {
            creeper.setVelocity(new Vector(0, 0.08, 0));
        }
        // Explota a 5-15 bloques de su victima
        if (dist <= 15 && dist >= 5) {
            creeper.getWorld().playSound(creeper.getLocation(), Sound.ENTITY_CREEPER_PRIMED, 1f, 0.8f);
            creeper.getWorld().spawnParticle(org.bukkit.Particle.END_ROD,
                    creeper.getLocation(), 20, 0.4, 0.4, 0.4, 0.05);
            if (dist <= 8) {
                detonateSupernova(creeper, victim, dist);
            }
        }
    }

    /**
     * Detonacion ligera de la Supernova. NO usa createExplosion (muy lento:
     * lagea el servidor entero), solo sonido + particulas + dano directo
     * escalado por distancia. Sin destruccion de bloques de mundo real.
     */
    private void detonateSupernova(Creeper creeper, Player victim, double dist) {
        Location loc = creeper.getLocation();
        org.bukkit.World w = loc.getWorld();
        w.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1.6f, 0.7f);
        w.spawnParticle(org.bukkit.Particle.EXPLOSION, loc, 1);
        w.spawnParticle(org.bukkit.Particle.CLOUD, loc, 40, 2.0, 2.0, 2.0, 0.05);
        w.spawnParticle(org.bukkit.Particle.FLAME, loc, 25, 1.0, 1.0, 1.0, 0.1);
        // Dano directo escalado: mas cerca = mas dano.
        double dmg = 8.0 * (1.0 - (Math.max(dist, 5.0) - 5.0) / 10.0);
        if (dmg > 1.0) {
            victim.damage(dmg, creeper);
        }
        creeper.remove();
    }

    /** Nivel 7: los wither skeletons huyen y disparan flechas, o cazan con hachas si te acercas. */
    private void handleLv7WitherSkeleton(org.bukkit.entity.WitherSkeleton ws, Player player) {
        var pdc = ws.getPersistentDataContainer();
        if (!pdc.has(new NamespacedKey(manager.plugin(), "dm_lv6_geared"),
                org.bukkit.persistence.PersistentDataType.BYTE)) {
            applyLv6UndeadGear(ws);
        }
        double dist = ws.getLocation().distance(player.getLocation());
        org.bukkit.inventory.EntityEquipment eq = ws.getEquipment();
        if (dist < 10) {
            // Cerca: deja de huir y caza con el hacha, mas rapido
            ws.setTarget(player);
            if (eq != null && !pdc.has(new NamespacedKey(manager.plugin(), "dm_lv7_axe"),
                    org.bukkit.persistence.PersistentDataType.BYTE)) {
                ItemStack axe = new ItemStack(Material.IRON_AXE);
                var im = axe.getItemMeta();
                if (im != null) {
                    im.addEnchant(org.bukkit.enchantments.Enchantment.SHARPNESS, 5, true);
                    axe.setItemMeta(im);
                }
                eq.setItemInMainHand(axe);
                eq.setItemInMainHandDropChance(0.05f);
                pdc.set(new NamespacedKey(manager.plugin(), "dm_lv7_axe"),
                        org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
            }
            var speed = ws.getAttribute(Attribute.MOVEMENT_SPEED);
            if (speed != null && speed.getBaseValue() < 0.4) {
                speed.setBaseValue(speed.getBaseValue() * 1.5);
            }
            ws.getPathfinder().moveTo(player.getLocation(), 1.0);
        } else if (dist < 20) {
            // Distancia media: huye y dispara flechas de fuego con arco punch
            ws.setTarget(null);
            Location away = ws.getLocation().clone()
                    .add(ws.getLocation().toVector()
                            .subtract(player.getLocation().toVector()).normalize().multiply(6));
            ws.getPathfinder().moveTo(away, 1.0);
            if (eq != null && !pdc.has(new NamespacedKey(manager.plugin(), "dm_lv7_bow"),
                    org.bukkit.persistence.PersistentDataType.BYTE)) {
                ItemStack bow = new ItemStack(Material.BOW);
                var bm = bow.getItemMeta();
                if (bm != null) {
                    bm.addEnchant(org.bukkit.enchantments.Enchantment.PUNCH, 2, true);
                    bm.addEnchant(org.bukkit.enchantments.Enchantment.FLAME, 1, true);
                    bow.setItemMeta(bm);
                }
                eq.setItemInMainHand(bow);
                eq.setItemInMainHandDropChance(0.05f);
                pdc.set(new NamespacedKey(manager.plugin(), "dm_lv7_bow"),
                        org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
            }
            // Tiro throttled: una flecha como maximo cada ~4s por esqueleto
            long now2 = Bukkit.getCurrentTick();
            long lastShot = pdc.getOrDefault(new NamespacedKey(manager.plugin(), "dm_lv7_shot"),
                    org.bukkit.persistence.PersistentDataType.LONG, 0L);
            if (now2 - lastShot > 80 && random.nextInt(100) < 12) {
                pdc.set(new NamespacedKey(manager.plugin(), "dm_lv7_shot"),
                        org.bukkit.persistence.PersistentDataType.LONG, now2);
                shootFireArrow(ws, player);
            }
        }
    }

    private void shootFireArrow(org.bukkit.entity.WitherSkeleton ws, Player target) {
        Location from = ws.getEyeLocation();
        Vector dir = target.getLocation().add(0, 1.0, 0).toVector()
                .subtract(from.toVector()).normalize();
        org.bukkit.entity.Arrow arrow = ws.getWorld().spawnArrow(from, dir, 1.8f, 12f);
        arrow.setShooter(ws);
        arrow.setFireTicks(99999);
        arrow.setDamage(arrow.getDamage() * 1.5);
        arrow.getPersistentDataContainer().set(
                new NamespacedKey(manager.plugin(), "dm_sn_arrow"),
                org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
    }

    /** Nivel 6: Ender Blazes en el End. Silenciosos, particulas en espiral, bola de dragon y teletransporte. */
    private void handleEnderBlaze(org.bukkit.entity.Blaze blaze, Player player) {
        if (!manager.isActive(DiffMode.HELLISH)) return;
        var pdc = blaze.getPersistentDataContainer();
        if (blaze.getTarget() == null || !blaze.getTarget().equals(player)) {
            blaze.setTarget(player);
        }
        long now = Bukkit.getCurrentTick();
        long lastShot = pdc.getOrDefault(new NamespacedKey(manager.plugin(), "eb_last"),
                org.bukkit.persistence.PersistentDataType.LONG, 0L);
        if (now - lastShot > 60) { // cada 3s
            pdc.set(new NamespacedKey(manager.plugin(), "eb_last"),
                    org.bukkit.persistence.PersistentDataType.LONG, now);
            Location from = blaze.getEyeLocation();
            Vector dir = player.getLocation().add(0, 0.6, 0).toVector()
                    .subtract(from.toVector()).normalize();
            org.bukkit.entity.DragonFireball fb = (org.bukkit.entity.DragonFireball)
                    blaze.getWorld().spawnEntity(from, EntityType.DRAGON_FIREBALL);
            fb.setDirection(dir);
            fb.setVelocity(dir.multiply(0.9));
            fb.setShooter(blaze);
            manager.markSpawned(fb);
        }
        // Particulas end en espiral (silencioso, sin sonido)
        double t = now * 0.3;
        for (int i = 0; i < 4; i++) {
            double ang = t + i * Math.PI / 2;
            blaze.getWorld().spawnParticle(org.bukkit.Particle.PORTAL,
                    blaze.getLocation().add(Math.cos(ang) * 0.8, Math.sin(t) * 0.6,
                            Math.sin(ang) * 0.8), 1, 0, 0, 0, 0.02);
        }
        // Teletransporte hacia el jugador si esta lejos
        double dist = blaze.getLocation().distance(player.getLocation());
        if (dist > 12 && random.nextInt(100) < 6) {
            Location dest = player.getLocation();
            if (dest.getChunk().isLoaded()) {
                blaze.teleport(dest);
                blaze.getWorld().spawnParticle(org.bukkit.Particle.PORTAL,
                        blaze.getLocation(), 14, 0.3, 0.3, 0.3, 0.1);
            }
        }
    }

    /** Nivel 6: spawn de Ender Blazes en el End (pocos, para evitar lag). */
    private void spawnEnderBlazes(World world) {
        if (world.getPlayers().isEmpty() || random.nextInt(100) >= 6) return;
        long near = world.getEntitiesByClass(org.bukkit.entity.Blaze.class).stream()
                .filter(b -> b.getPersistentDataContainer().has(manager.enderBlazeKey(),
                        org.bukkit.persistence.PersistentDataType.BYTE)).count();
        if (near >= 4) return;
        Player p = world.getPlayers().get(random.nextInt(world.getPlayers().size()));
        Location loc = p.getLocation().clone()
                .add((random.nextDouble() - 0.5) * 24, 4 + random.nextInt(10),
                        (random.nextDouble() - 0.5) * 24);
        if (!loc.getChunk().isLoaded()) return;
        org.bukkit.entity.Blaze blaze = (org.bukkit.entity.Blaze)
                world.spawnEntity(loc, EntityType.BLAZE);
        blaze.addScoreboardTag("dm_ender_blaze");
        blaze.setCustomName("§5Ender Blaze");
        blaze.setCustomNameVisible(false);
        blaze.getPersistentDataContainer().set(manager.enderBlazeKey(),
                org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        manager.markSpawned(blaze);
    }

    /**
     * Nivel 7: Supernovas aparecen tras ~3 min de inactividad. "Saben" quien
     * es su victima y vuelan hacia ella para detonar a 5-15 bloques.
     */
    private void spawnSupernovaCreepers(World world) {
        if (world.getPlayers().isEmpty()) {
            return;
        }
        long now = Bukkit.getCurrentTick();
        for (Player p : world.getPlayers()) {
            if (p.getGameMode() == org.bukkit.GameMode.CREATIVE
                    || p.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                continue;
            }
            Location cur = p.getLocation();
            UUID id = p.getUniqueId();
            Location prev = lastPlayerLoc.get(id);
            if (prev == null || prev.distance(cur) > 0.5) {
                lastPlayerLoc.put(id, cur.clone());
                lastMove.put(id, now);
                lastSupernova.remove(id);
                continue;
            }
            long movedAt = lastMove.getOrDefault(id, now);
            long inactive = now - movedAt;
            if (inactive >= 3600 && random.nextInt(100) < 12) {
                Long lastSn = lastSupernova.get(id);
                if (lastSn != null && now - lastSn < 2400) {
                    continue;
                }
                lastSupernova.put(id, now);
                spawnSupernovaFor(p);
            }
        }
    }

    private void spawnSupernovaFor(Player p) {
        long near = p.getWorld().getNearbyEntities(p.getLocation(), 80, 80, 80,
                e -> e instanceof Creeper c
                        && c.getScoreboardTags().contains("dm_supernova")).size();
        if (near >= 3) {
            return;
        }
        double ang = random.nextDouble() * Math.PI * 2;
        double dist = 14 + random.nextDouble() * 8;
        Location loc = p.getLocation().clone()
                .add(Math.cos(ang) * dist, 6 + random.nextDouble() * 8,
                        Math.sin(ang) * dist);
        if (!loc.getChunk().isLoaded()) {
            return;
        }
        // Silencioso: sin sonido de spawn, aparece "de la nada"
        Creeper c = (Creeper) p.getWorld().spawnEntity(loc, EntityType.CREEPER);
        c.addScoreboardTag("dm_supernova");
        c.setCustomName("§dCreper Supernova");
        c.setCustomNameVisible(false);
        c.setPowered(true);
        c.getPersistentDataContainer().set(
                new NamespacedKey(manager.plugin(), "sn_victim"),
                org.bukkit.persistence.PersistentDataType.STRING,
                p.getUniqueId().toString());
        manager.markSpawned(c);
        p.sendMessage("§d[Death] §7Algo se ha manifestado a tu alrededor...");
    }

    private void handleSkeleton(AbstractSkeleton skeleton, Player player) {
        if (manager.difficultyLevel() >= 7
                && skeleton instanceof org.bukkit.entity.WitherSkeleton
                && player != null) {
            handleLv7WitherSkeleton((org.bukkit.entity.WitherSkeleton) skeleton, player);
            return;
        }
        // Disparo cargado: a mas de 24 bloques tarda 1.5s en calcular la
        // trayectoria y dispara una flecha critica con mucho mas dano y velocidad
        LivingEntity target = skeleton.getTarget();
        var pdc = skeleton.getPersistentDataContainer();
        if (target != null && target.isValid()
                && !pdc.has(manager.aimingKey(), org.bukkit.persistence.PersistentDataType.LONG)) {
            double distance = skeleton.getLocation().distance(target.getLocation());
            if (distance > 24 && random.nextInt(100) < 25) {
                long now = Bukkit.getCurrentTick();
                pdc.set(manager.aimingKey(), org.bukkit.persistence.PersistentDataType.LONG, now);
                Bukkit.getScheduler().runTaskLater(manager.plugin(), () -> {
                    try {
                        fireChargedShot(skeleton, target);
                    } finally {
                        skeleton.getPersistentDataContainer()
                                .remove(manager.aimingKey());
                    }
                }, 30L); // ~1.5 segundos calculando
            }
        }

        // Con poca vida huye a montar una arana
        double maxHealth = skeleton.getMaxHealth();
        if (maxHealth > 0 && skeleton.getHealth() / maxHealth < 0.35
                && !skeleton.isInsideVehicle()
                && !skeleton.getScoreboardTags().contains("dm_fled")
                && skeleton.getTarget() != null) {
            for (Entity nearby : skeleton.getWorld().getNearbyEntities(
                    skeleton.getLocation(), 32, 16, 32)) {
                if (nearby instanceof Spider spider
                        && spider.getPassengers().isEmpty() && spider.isValid()) {
                    skeleton.setTarget(null);
                    skeleton.getPathfinder().moveTo(spider.getLocation(), 1.2);
                    spider.addPassenger(skeleton);
                    skeleton.addScoreboardTag("dm_fled");
                    break;
                }
            }
            return;
        }
        // En sus ratos libres caminan hacia un pillager outpost
        if (skeleton.getTarget() == null && random.nextInt(100) < 5) {
            Location outpost = structures.locate(skeleton.getWorld(),
                    "outpost", manager.outpostStructure());
            if (outpost != null && skeleton.getLocation().distance(outpost) < 512) {
                skeleton.getPathfinder().moveTo(outpost, 0.9);
            }
        }
    }

    private void fireChargedShot(AbstractSkeleton skeleton, LivingEntity target) {
        if (!skeleton.isValid() || !target.isValid() || !manager.isActive(DiffMode.HELLISH)) {
            return;
        }
        Location from = skeleton.getEyeLocation();
        Vector dir = target.getLocation().add(0, target.getHeight() * 0.5, 0).toVector()
                .subtract(from.toVector()).normalize();
        org.bukkit.entity.Arrow arrow = skeleton.getWorld().spawnArrow(from, dir, 3.2f, 0f);
        arrow.setShooter(skeleton);
        arrow.setCritical(true);
        arrow.setDamage(arrow.getDamage() * 3.0);
        // Atraviesa el aire con mucha mas velocidad
        arrow.setVelocity(dir.multiply(4.0));
    }

    private void handleGhast(Ghast ghast, Player player) {
        if (ghast.getLocation().distance(player.getLocation()) > 64) {
            return;
        }
        // En Nether: espiral de bolitas pequeñas se maneja en ProjectileListener
        // Los ghasts del End pueden teletransportarse
        if (ghast.getWorld().getEnvironment() == World.Environment.THE_END && random.nextInt(100) < 8) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = 10 + random.nextDouble() * 10;
            Location dest = player.getLocation().clone()
                    .add(Math.cos(angle) * dist, random.nextInt(12) - 4, Math.sin(angle) * dist);
            if (dest.getChunk().isLoaded()) {
                ghast.teleport(dest);
            }
        }
    }

    private void handleSnowGolem(org.bukkit.entity.Snowman snowman) {
        // Agresivo contra todos: busca el jugador mas cercano
        Player nearest = null;
        double best = Double.MAX_VALUE;
        for (Player p : snowman.getWorld().getPlayers()) {
            double d = p.getLocation().distance(snowman.getLocation());
            if (d < best && d < 32) { best = d; nearest = p; }
        }
        if (nearest != null && snowman.getTarget() == null) {
            snowman.setTarget(nearest);
        }
        // Si no tiene objetivo, busca cualquier LivingEntity hostil
        if (snowman.getTarget() == null) {
            for (Entity e : snowman.getWorld().getNearbyEntities(snowman.getLocation(), 24, 12, 24)) {
                if (e instanceof LivingEntity le && !(e instanceof org.bukkit.entity.Snowman) && !(e instanceof Player && ((Player)le).getGameMode() == org.bukkit.GameMode.CREATIVE)) {
                    snowman.setTarget(le);
                    break;
                }
            }
        }
    }

    private void handleIronGolem(org.bukkit.entity.IronGolem golem) {
        // Hostil contra todos menos aldeanos
        if (golem.getTarget() instanceof org.bukkit.entity.Villager) {
            golem.setTarget(null);
        }
        if (golem.getTarget() == null) {
            Player nearest = null;
            double best = Double.MAX_VALUE;
            for (Player p : golem.getWorld().getPlayers()) {
                double d = p.getLocation().distance(golem.getLocation());
                if (d < best && d < 28) { best = d; nearest = p; }
                }
            if (nearest != null) golem.setTarget(nearest);
            else {
                for (Entity e : golem.getWorld().getNearbyEntities(golem.getLocation(), 20, 10, 20)) {
                    if (e instanceof LivingEntity le && !(e instanceof org.bukkit.entity.Villager) && !(e instanceof org.bukkit.entity.IronGolem)) {
                        golem.setTarget(le);
                        break;
                    }
                }
            }
        }
    }

    private void handleShulker(org.bukkit.entity.Shulker shulker) {
        // Cada 3s dispara 15 balas en aro si tiene objetivo
        LivingEntity target = shulker.getTarget();
        if (target == null || !target.isValid()) return;
        long now = Bukkit.getCurrentTick();
        var pdc = shulker.getPersistentDataContainer();
        long last = pdc.getOrDefault(new NamespacedKey(manager.plugin(), "shulker_last"), org.bukkit.persistence.PersistentDataType.LONG, 0L);
        if (now - last < 60) return; // 3s
        pdc.set(new NamespacedKey(manager.plugin(), "shulker_last"), org.bukkit.persistence.PersistentDataType.LONG, now);
        Location from = shulker.getLocation().add(0, 0.5, 0);
        for (int i=0;i<15;i++) {
            double ang = i * Math.PI*2/15;
            Vector dir = new Vector(Math.cos(ang), 0.2 + random.nextDouble()*0.3, Math.sin(ang)).normalize();
            Vector toTarget = target.getLocation().add(0,1,0).toVector().subtract(from.toVector()).normalize();
            dir.add(toTarget.clone().multiply(0.4)).normalize();
            org.bukkit.entity.ShulkerBullet bullet = (org.bukkit.entity.ShulkerBullet) shulker.getWorld().spawnEntity(from, EntityType.SHULKER_BULLET);
            bullet.setShooter(shulker);
            bullet.setTarget(target);
            bullet.setVelocity(dir.multiply(0.9));
            manager.markSpawned(bullet);
        }
        shulker.getWorld().playSound(from, Sound.ENTITY_SHULKER_SHOOT, 1f, 0.8f);
    }

    // End Phantoms
    private void spawnEndPhantoms(World world) {
        if (world.getPlayers().isEmpty() || random.nextInt(100) >= 4) return; // muy poca frecuencia 4%
        long existing = world.getEntitiesByClass(Phantom.class).stream().filter(p -> p.getScoreboardTags().contains("dm_end_phantom")).count();
        if (existing >= 3) return;
        Player p = world.getPlayers().get(random.nextInt(world.getPlayers().size()));
        Location loc = p.getLocation().clone().add((random.nextDouble()-0.5)*30, 15+random.nextInt(10), (random.nextDouble()-0.5)*30);
        if (!loc.getChunk().isLoaded()) return;
        Phantom phantom = (Phantom) world.spawnEntity(loc, EntityType.PHANTOM);
        phantom.setCustomName("§5End Phantom");
        phantom.setCustomNameVisible(false);
        phantom.addScoreboardTag("dm_end_phantom");
        phantom.addScoreboardTag("dm_hellish_phantom");
        try { phantom.setSize(2); } catch (Throwable ignored) {}
        manager.buffPhantom(phantom);
        manager.markSpawned(phantom);
        // 2 ataques contador
        phantom.getPersistentDataContainer().set(new NamespacedKey(manager.plugin(), "end_phantom_hits"), org.bukkit.persistence.PersistentDataType.INTEGER, 0);
    }

    public void handleEndPhantom(Phantom phantom, Player player) {
        // Si el jugador lo mira fijamente (crosshair), teletransporte
        if (player != null && isLookingAt(player, phantom, 12)) {
            Location dest = player.getLocation().clone().add((random.nextDouble()-0.5)*16, 8, (random.nextDouble()-0.5)*16);
            if (dest.getChunk().isLoaded()) {
                phantom.teleport(dest);
                phantom.getWorld().spawnParticle(org.bukkit.Particle.PORTAL, phantom.getLocation(), 12, 0.3,0.3,0.3, 0.1);
                phantom.getWorld().playSound(phantom.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 0.9f);
            }
        }
    }

    private boolean isLookingAt(Player player, LivingEntity target, double maxDist) {
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        Vector toTarget = target.getEyeLocation().toVector().subtract(eye.toVector()).normalize();
        double dot = dir.dot(toTarget);
        double dist = eye.distance(target.getLocation());
        return dist < maxDist && dot > 0.96; // ~16 grados
    }
}

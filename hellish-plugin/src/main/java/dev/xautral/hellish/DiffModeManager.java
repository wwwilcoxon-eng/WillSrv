package dev.xautral.hellish;

import dev.xautral.hellish.item.HellishItems;
import dev.xautral.hellish.item.HellishRewards;
import dev.xautral.hellish.listener.CombatListener;
import dev.xautral.hellish.listener.ProjectileListener;
import dev.xautral.hellish.listener.RaidListener;
import dev.xautral.hellish.listener.SlotLockListener;
import dev.xautral.hellish.listener.SpawnListener;
import dev.xautral.hellish.task.HellishMobTask;
import dev.xautral.hellish.task.SkeletonBossManager;
import dev.xautral.hellish.task.ZombieBossManager;
import dev.xautral.hellish.util.AdvancementUtil;
import dev.xautral.hellish.util.StructureCache;
import dev.xautral.hellish.store.SlotLockStore;
import dev.xautral.hellish.util.TimeUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.KeyedBossBar;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.entity.Mob;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.generator.structure.Structure;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class DiffModeManager implements Listener {

    private static final long LONG_MODE_MS = 7 * 60 * 1000L; // 7 minutos

    /** Entidades GENERADAS por Hellish (no las vanilla modificadas). */
    public static final String SPAWNED_TAG = "dm_hellish_spawned";

    private final JavaPlugin plugin;
    private final RandomHolder randomHolder = new RandomHolder();
    private final StructureCache structures = new StructureCache();

    private final NamespacedKey bossBarKey;
    private final NamespacedKey totemDefinitivoKey;
    private final NamespacedKey blazeFireballsKey;
    private final NamespacedKey dodecaedroKey;
    private final NamespacedKey shieldStateKey;
    private final NamespacedKey shieldPhaseKey;
    private final NamespacedKey aimingKey;
    private final NamespacedKey stackTotemKey;
    private final NamespacedKey spawnedKey;
    private final NamespacedKey shieldActiveKey;
    private final NamespacedKey shieldTillKey;
    private final NamespacedKey shieldHpKey;
    private final NamespacedKey creeperLastKey;
    private final NamespacedKey enderBlazeKey;

    private final SlotLockStore slotLocks;
    private final HellishParticipation participation = new HellishParticipation();
    private SkeletonBossManager bossManager;
    private ZombieBossManager huskManager;

    private DiffMode activeMode;
    private long startMs;
    private long endTick;
    private long startTick;
    private int durationSeconds;
    private int hellishLevel = 1;
    private KeyedBossBar bossBar;
    private BukkitTask barTask;

    public DiffModeManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.bossBarKey = new NamespacedKey(plugin, "diffmode");
        this.totemDefinitivoKey = new NamespacedKey(plugin, "totem_definitivo");
        this.blazeFireballsKey = new NamespacedKey(plugin, "blaze_fireballs");
        this.dodecaedroKey = new NamespacedKey(plugin, "dodecaedro_angelical");
        this.shieldStateKey = new NamespacedKey(plugin, "shield_state");
        this.shieldPhaseKey = new NamespacedKey(plugin, "shield_phase");
        this.aimingKey = new NamespacedKey(plugin, "aiming");
        this.stackTotemKey = new NamespacedKey(plugin, "stack_totem");
        this.spawnedKey = new NamespacedKey(plugin, "hellish_spawned");
        this.shieldActiveKey = new NamespacedKey(plugin, "shield_active");
        this.shieldTillKey = new NamespacedKey(plugin, "shield_till");
        this.shieldHpKey = new NamespacedKey(plugin, "shield_hp");
        this.creeperLastKey = new NamespacedKey(plugin, "creeper_last_seen");
        this.enderBlazeKey = new NamespacedKey(plugin, "ender_blaze");
        this.slotLocks = new SlotLockStore(plugin);
    }

    /** Registra listeners, tareas y recetas. Llamar en onEnable. */
    public void register() {
        slotLocks.load();

        Bukkit.getPluginManager().registerEvents(participation, plugin);
        Bukkit.getPluginManager().registerEvents(
                new SpawnListener(this, randomHolder.get()), plugin);
        Bukkit.getPluginManager().registerEvents(
                new CombatListener(this, randomHolder.get()), plugin);
        Bukkit.getPluginManager().registerEvents(
                new dev.xautral.hellish.listener.HellishSpecialListener(this, randomHolder.get()), plugin);
        Bukkit.getPluginManager().registerEvents(new RaidListener(this), plugin);
        Bukkit.getPluginManager().registerEvents(
                new ProjectileListener(this, blazeFireballsKey), plugin);
        Bukkit.getPluginManager().registerEvents(
                new SlotLockListener(this, slotLocks, dodecaedroKey), plugin);
        Bukkit.getPluginManager().registerEvents(this, plugin);

        bossManager = new SkeletonBossManager(
                plugin, this, randomHolder.get(), dodecaedroKey);
        huskManager = new ZombieBossManager(
                plugin, this, randomHolder.get(), stackTotemKey);

        HellishMobTask mobTask = new HellishMobTask(
                this, structures, totemDefinitivoKey, slotLocks, bossManager, randomHolder.get());
        Bukkit.getScheduler().runTaskTimer(plugin, mobTask, 60L, 80L);
        // TPS opt: dolls cada 2.5s (antes 1.5s) - reduce getNearbyEntities
        Bukkit.getScheduler().runTaskTimer(plugin,
                new dev.xautral.hellish.task.DollSoldierTask(
                        this, randomHolder.get()), 20L, 50L);
        barTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickBossBar, 10L, 10L);

        registerRecipes();

        // Instala el datapack de logros vanilla cuando el mundo ya cargo
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> AdvancementUtil.ensureDatapack(plugin), 60L);

        // Aura de particulas del Dodecaedro Angelical
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!isActive(DiffMode.HELLISH)) {
                return;
            }
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (holdsDodecaedro(player)) {
                    Location loc = player.getLocation().add(0, 1.1, 0);
                    double t = System.currentTimeMillis() / 300.0;
                    world(loc).spawnParticle(org.bukkit.Particle.END_ROD,
                            loc.clone().add(Math.cos(t) * 0.9, 0.3, Math.sin(t) * 0.9),
                            1, 0, 0, 0, 0);
                    world(loc).spawnParticle(org.bukkit.Particle.PORTAL,
                            loc.clone().add(Math.cos(-t) * 0.9, -0.2, Math.sin(-t) * 0.9),
                            3, 0.05, 0.05, 0.05, 0.2);
                    world(loc).spawnParticle(org.bukkit.Particle.DUST,
                            loc, 2, 0.4, 0.6, 0.4,
                            new org.bukkit.Particle.DustOptions(
                                    org.bukkit.Color.fromRGB(120, 220, 255), 1.0f));
                }
            }
        }, 20L, 10L);
    }

    private boolean holdsDodecaedro(Player player) {
        for (ItemStack item : new ItemStack[]{
                player.getInventory().getItemInMainHand(),
                player.getInventory().getItemInOffHand()}) {
            if (isDodecaedro(item)) {
                return true;
            }
        }
        return isDodecaedro(player.getItemOnCursor());
    }

    private boolean isDodecaedro(ItemStack item) {
        if (item == null || item.getType() != Material.DIAMOND || !item.hasItemMeta()) {
            return false;
        }
        var meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer()
                .has(dodecaedroKey, org.bukkit.persistence.PersistentDataType.BYTE);
    }

    private static World world(Location loc) {
        return loc.getWorld();
    }

    /** Spawnea al Jefe Husk con poca frecuencia durante Hellish. */
    public void trySpawnHuskBoss() {
        if (huskManager != null && huskManager.hasNoBoss()) {
            huskManager.trySpawn();
        }
    }

    /** Invoca al Jefe Esqueleto delante del jugador (comando /deathsummon). */
    public void summonSkeletonBoss(Player player) {
        if (bossManager != null && bossManager.hasNoBoss()) {
            bossManager.summonNear(player);
        }
    }

    /** Invoca al Jefe Husk delante del jugador (comando /deathsummon). */
    public void summonHuskBoss(Player player) {
        if (huskManager != null && huskManager.hasNoBoss()) {
            huskManager.summonNear(player);
        }
    }

    /**
     * Nivel 5: la trampa de esqueletos que persigue al asesino. Se invoca cuando
     * muere un miembro de la tribu de husks. Los esqueletos llevan un totem en su
     * mano secundaria y te persiguen sin descanso.
     */
    public void spawnLv5SkeletonTrap(LivingEntity killer) {
        Location base = killer != null && killer.isValid()
                ? killer.getLocation() : null;
        if (base == null || base.getWorld() == null) {
            return;
        }
        int count = 3 + randomHolder.get().nextInt(2);
        for (int i = 0; i < count; i++) {
            double ang = randomHolder.get().nextDouble() * Math.PI * 2;
            double dist = 14 + randomHolder.get().nextDouble() * 14;
            double px = base.getX() + Math.cos(ang) * dist;
            double pz = base.getZ() + Math.sin(ang) * dist;
            int y = base.getWorld().getHighestBlockYAt((int) px, (int) pz);
            Location loc = new Location(base.getWorld(), px, y, pz);
            if (!loc.getChunk().isLoaded()) {
                loc.getChunk().load();
                if (!loc.getChunk().isLoaded()) {
                    continue;
                }
            }
            org.bukkit.entity.AbstractSkeleton skeleton = spawnTrapSkeleton(loc, killer);
            // 50% sobre una arana, como una trampa de esqueleto clasica
            if (randomHolder.get().nextBoolean()) {
                org.bukkit.entity.Spider spider = (org.bukkit.entity.Spider)
                        base.getWorld().spawnEntity(loc, org.bukkit.entity.EntityType.SPIDER);
                spider.setTarget(killer);
                markSpawned(spider);
                spider.addPassenger(skeleton);
            }
        }
        base.getWorld().playSound(base, org.bukkit.Sound.ENTITY_SKELETON_HURT, 1f, 0.7f);
    }

    private org.bukkit.entity.AbstractSkeleton spawnTrapSkeleton(Location loc, LivingEntity killer) {
        boolean stray = randomHolder.get().nextBoolean();
        org.bukkit.entity.AbstractSkeleton skeleton =
                (org.bukkit.entity.AbstractSkeleton) loc.getWorld().spawnEntity(loc,
                        stray ? org.bukkit.entity.EntityType.STRAY
                              : org.bukkit.entity.EntityType.SKELETON);
        skeleton.addScoreboardTag("dm_lv5_trap");
        var eq = skeleton.getEquipment();
        if (eq != null) {
            ItemStack bow = new ItemStack(org.bukkit.Material.BOW);
            var bm = bow.getItemMeta();
            if (bm != null) {
                bm.addEnchant(org.bukkit.enchantments.Enchantment.POWER, 3, true);
                bm.addEnchant(org.bukkit.enchantments.Enchantment.INFINITY, 1, true);
                bow.setItemMeta(bm);
            }
            eq.setItemInMainHand(bow);
            eq.setItemInMainHandDropChance(0.0f);
            eq.setItemInOffHand(new ItemStack(org.bukkit.Material.TOTEM_OF_UNDYING));
            eq.setItemInOffHandDropChance(0.0f);
        }
        var speed = skeleton.getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED);
        if (speed != null) {
            speed.setBaseValue(speed.getBaseValue() * 1.5);
        }
        var range = skeleton.getAttribute(org.bukkit.attribute.Attribute.FOLLOW_RANGE);
        if (range != null) {
            range.setBaseValue(96);
        }
        if (killer != null) {
            skeleton.setTarget(killer);
        }
        markSpawned(skeleton);
        return skeleton;
    }

    public void unregister() {
        if (barTask != null) {
            barTask.cancel();
            barTask = null;
        }
        removeBossBar();
        activeMode = null;
    }

    private void registerRecipes() {
        ShapedRecipe dodecaedroRecipe = new ShapedRecipe(
                new NamespacedKey(plugin, "dodecaedro_angelical"),
                HellishItems.dodecaedro(dodecaedroKey));
        dodecaedroRecipe.shape("DDD", "DND", "DDD");
        dodecaedroRecipe.setIngredient('D', Material.DIAMOND_BLOCK);
        dodecaedroRecipe.setIngredient('N',
                new RecipeChoice.ExactChoice(new ItemStack(Material.DIAMOND_NAUTILUS_ARMOR)));
        Bukkit.addRecipe(dodecaedroRecipe);
    }

    // ---------------- Control de modos ----------------

    public void start(DiffMode mode, int seconds, int level) {
        stop(false);
        activeMode = mode;
        durationSeconds = seconds;
        hellishLevel = mode == DiffMode.HELLISH
                ? Math.max(1, Math.min(7, level)) : 1;
        startTick = Bukkit.getCurrentTick();
        startMs = System.currentTimeMillis();
        endTick = startTick + seconds * 20L;

        String colorCode = "§d";
        bossBar = Bukkit.createBossBar(bossBarKey,
                colorCode + mode.display() + " §7- " + TimeUtil.format(seconds),
                mode.color(), BarStyle.SEGMENTED_10);
        for (Player online : Bukkit.getOnlinePlayers()) {
            bossBar.addPlayer(online);
        }

        // Unico modo restante: Hellish (Lifesteal eliminado).
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.playSound(online.getLocation(),
                    Sound.BLOCK_END_PORTAL_SPAWN, 1.0f, 1.0f);
            online.playSound(online.getLocation(),
                    Sound.ENTITY_SKELETON_HORSE_DEATH, 1.0f, 1.0f);
        }
        // Bilingüe: envía mensaje per-player según idioma
        for (Player p : Bukkit.getOnlinePlayers()) {
            String broadcast = Tr.t(p, "§6[Death] §5Modo Hellish activado por §b" + TimeUtil.format(seconds) + "§d. §7Nivel de peligro: §c" + hellishLevel + "§7/§c7§d. Revisa tu inventario...", "§6[Death] §5Hellish Mode enabled for §b" + TimeUtil.format(seconds) + "§d. §7Danger level: §c" + hellishLevel + "§7/§c7§d. Check your inventory...");
            p.sendMessage(broadcast);
        }
        ItemStack book = HellishItems.book(seconds, hellishLevel);
        for (Player online : Bukkit.getOnlinePlayers()) {
            HellishItems.give(online, book.clone());
        }
        participation.startForAll(Bukkit.getOnlinePlayers());
        plugin.getSLF4JLogger().info("DiffMode {} activado por {}s", mode, seconds);
    }

    public void stop(boolean announce) {
        DiffMode finished = activeMode;
        boolean naturalEnd = announce && finished == DiffMode.HELLISH;
        long lastedMs = System.currentTimeMillis() - startMs;
        activeMode = null;
        removeBossBar();
        if (announce && finished != null) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                String msg = Tr.t(p, "§6[Death] §7Modo " + finished.display() + " terminado.", "§6[Death] §7Mode " + finished.display() + " ended.");
                p.sendMessage(msg);
            }
        }
        if (naturalEnd) {
            playEndSounds(lastedMs >= LONG_MODE_MS);
            distributeHellishRewards();
        }
        // Al acabar el modo, TODAS las entidades generadas por Hellish mueren
        if (finished == DiffMode.HELLISH) {
            purgeSpawnedEntities();
            settleHostilePeaceful();
            // Los slots sellados solo existen durante el evento: al terminar
            // Hellish se liberan todos y se retiran los marcadores del inventario.
            slotLocks.clearAll();
        }
    }

    /** Restaura a los mobs pacificos agresivos (nivel 4) al terminar el modo. */
    private void settleHostilePeaceful() {
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            for (org.bukkit.entity.Entity entity : world.getEntities()) {
                if (entity.getScoreboardTags().contains("dm_lv4_peaceful")
                        || entity.getScoreboardTags().contains("dm_angry_animal")) {
                    entity.removeScoreboardTag("dm_lv4_peaceful");
                    entity.removeScoreboardTag("dm_angry_animal");
                    if (entity instanceof Mob mob) {
                        mob.setTarget(null);
                    }
                }
            }
        }
    }

    /** Elimina todas las entidades que Hellish genero (phantoms, ghasts, jefes, displays, flechas). */
    private void purgeSpawnedEntities() {
        int killed = 0;
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            for (org.bukkit.entity.Entity entity : world.getEntities()) {
                if (entity.getPersistentDataContainer().has(
                        spawnedKey, org.bukkit.persistence.PersistentDataType.BYTE)) {
                    entity.remove();
                    killed++;
                }
            }
        }
        plugin.getSLF4JLogger().info("Hellish: {} entidades generadas eliminadas al terminar", killed);
    }

    /** Sonidos de fin: challenge completado pitch 0 (+ dragon si duro mas de 7 min). */
    private void playEndSounds(boolean longMode) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.playSound(online.getLocation(),
                    Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 0.0f);
            if (longMode) {
                online.playSound(online.getLocation(),
                        Sound.ENTITY_ENDER_DRAGON_DEATH, 1.0f, 0.0f);
            }
        }
    }

    /**
     * Reparte recompensas de Hellish segun participacion: tiempo minimo de un
     * minuto para el Totem Definitivo, logros por no morir / no recibir dano,
     * y logro especial por matar a todos los jefes sin morir.
     */
    private void distributeHellishRewards() {
        ItemStack totem = HellishItems.totemDefinitivo(totemDefinitivoKey);
        for (var entry : participation.all().entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            DataWrapper wrapper = new DataWrapper(entry.getValue());
            long played = HellishParticipation.playedMs(entry.getValue());
            if (player == null || !player.isOnline()) {
                continue;
            }
            if (played < HellishParticipation.MIN_PLAYTIME_MS) {
                String noReward = Tr.t(player, "§6[Death] §cNo recibiste recompensas: estuviste §e" + (played / 1000) + "s§c pero el minimo es de §e" + (HellishParticipation.MIN_PLAYTIME_MS / 1000) + "s§c dentro del modo.", "§6[Death] §cNo rewards: you played §e" + (played / 1000) + "s§c but minimum is §e" + (HellishParticipation.MIN_PLAYTIME_MS / 1000) + "s§c inside the mode.");
                player.sendMessage(noReward);
                continue;
            }

            // Base: Totem Definitivo por sobrevivir mas de un minuto
            HellishItems.give(player, totem.clone());
            boolean noDeaths = wrapper.deaths() == 0;
            boolean noHits = wrapper.hits() == 0;

            if (noDeaths && noHits) {
                // Challenge: Full No-Hit Perfect
                AdvancementUtil.grant(player, AdvancementUtil.NO_HIT);
                HellishRewards.giveApples(player);
                HellishItems.give(player, totem.clone());
            } else if (noDeaths) {
                // Logro simple: Full No-Death (+1 totem adicional)
                AdvancementUtil.grant(player, AdvancementUtil.NO_DEATH);
                HellishItems.give(player, totem.clone());
            }

            // Logro especial: todos los jefes muertos sin morir ni una vez
            if (noDeaths && wrapper.bossesKilled().contains("skeleton")
                    && wrapper.bossesKilled().contains("husk")) {
                AdvancementUtil.grant(player, AdvancementUtil.ALL_BOSSES);
            }
        }
        participation.clearForRestart();
    }

    // Solo lectura de datos de participacion sin exponer la clase interna
    private record DataWrapper(HellishParticipation.Data data) {
        int deaths() {
            return data.deaths;
        }

        int hits() {
            return data.hits;
        }

        java.util.Set<String> bossesKilled() {
            return data.bossesKilled;
        }
    }

    @EventHandler
    public void onPing(ServerListPingEvent event) {
        if (isActive(DiffMode.HELLISH)) {
            event.motd(event.motd().append(Component.newline())
                    .append(Component.text("Hellish Mode ON", NamedTextColor.LIGHT_PURPLE)));
        }
    }

    // ---------------- Bossbar / ciclo ----------------

    private void removeBossBar() {
        if (bossBar != null) {
            bossBar.removeAll();
            Bukkit.removeBossBar(bossBarKey);
            bossBar = null;
        }
    }

    private void tickBossBar() {
        if (activeMode == null || bossBar == null) {
            return;
        }
        long remainingTicks = endTick - Bukkit.getCurrentTick();
        if (remainingTicks <= 0) {
            stop(true);
            return;
        }
        long totalTicks = endTick - startTick;
        bossBar.setTitle("§d" + activeMode.display() + " §7- "
                + TimeUtil.format((int) (remainingTicks / 20)));
        bossBar.setProgress(Math.min(1.0,
                Math.max(0.0, (double) remainingTicks / totalTicks)));
    }

    public boolean isActive(DiffMode mode) {
        return activeMode == mode && Bukkit.getCurrentTick() < endTick;
    }

    /** Nivel de peligro 1-3 del Hellish actual (1 = comportamiento base). */
    public int difficultyLevel() {
        return hellishLevel;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (bossBar != null && activeMode != null) {
            bossBar.addPlayer(event.getPlayer());
            if (activeMode == DiffMode.HELLISH) {
                HellishItems.give(event.getPlayer(),
                        HellishItems.book(durationSeconds, hellishLevel));
                participation.join(event.getPlayer());
            }
        }
    }

    // ---------------- Muertes ----------------

    /** Llamado desde DeathListener al morir un jugador. */
    public void handlePlayerDeath(Player victim, Player killer) {
        if (activeMode == DiffMode.HELLISH) {
            participation.recordDeath(victim);
        }
    }

    // ---------------- Exposiciones para handlers ----------------

    public JavaPlugin plugin() {
        return plugin;
    }

    public dev.xautral.hellish.store.SlotLockStore slotLocks() {
        return slotLocks;
    }

    /** Marca una entidad como GENERADA por Hellish (morira al terminar el modo). */
    public void markSpawned(org.bukkit.entity.Entity entity) {
        if (entity instanceof org.bukkit.entity.ShulkerBullet bullet) {
            long activeBullets = bullet.getWorld().getEntitiesByClass(org.bukkit.entity.ShulkerBullet.class)
                    .stream()
                    .filter(existing -> existing.getPersistentDataContainer().has(
                            spawnedKey, org.bukkit.persistence.PersistentDataType.BYTE))
                    .count();
            if (activeBullets > 64) {
                bullet.remove();
                return;
            }
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (bullet.isValid() && !bullet.isDead()) bullet.remove();
            }, 100L);
        }
        entity.getPersistentDataContainer()
                .set(spawnedKey, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
    }

    public HellishParticipation participation() {
        return participation;
    }

    public NamespacedKey totemDefinitivoKey() {
        return totemDefinitivoKey;
    }

    public NamespacedKey shieldStateKey() {
        return shieldStateKey;
    }

    public NamespacedKey shieldPhaseKey() {
        return shieldPhaseKey;
    }

    public NamespacedKey aimingKey() {
        return aimingKey;
    }

    public NamespacedKey shieldActiveKey() {
        return shieldActiveKey;
    }

    public NamespacedKey shieldTillKey() {
        return shieldTillKey;
    }

    public NamespacedKey shieldHpKey() {
        return shieldHpKey;
    }

    public NamespacedKey creeperLastKey() {
        return creeperLastKey;
    }

    public NamespacedKey enderBlazeKey() {
        return enderBlazeKey;
    }

    public Structure outpostStructure() {
        return registryStructure("pillager_outpost");
    }

    public Structure trialChambersStructure() {
        return registryStructure("trial_chambers");
    }

    public void buffPhantom(Phantom phantom) {
        phantom.addScoreboardTag("dm_hellish_phantom");
        var damage = phantom.getAttribute(Attribute.ATTACK_DAMAGE);
        if (damage != null) {
            damage.setBaseValue(damage.getBaseValue() + 2.0);
        }
        var range = phantom.getAttribute(Attribute.FOLLOW_RANGE);
        if (range != null) {
            range.setBaseValue(64.0);
        }
    }

    private Structure registryStructure(String key) {
        try {
            return io.papermc.paper.registry.RegistryAccess.registryAccess()
                    .getRegistry(io.papermc.paper.registry.RegistryKey.STRUCTURE)
                    .get(NamespacedKey.minecraft(key));
        } catch (Throwable t) {
            plugin.getSLF4JLogger().warn("No se pudo obtener la estructura {}", key, t);
            return null;
        }
    }

    private static final class RandomHolder {
        private final java.util.Random value = new java.util.Random();

        java.util.Random get() {
            return value;
        }
    }
}

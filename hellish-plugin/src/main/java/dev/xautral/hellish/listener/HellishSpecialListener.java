package dev.xautral.hellish.listener;

import dev.xautral.hellish.DiffMode;
import dev.xautral.hellish.DiffModeManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.util.Vector;

import java.util.Random;

public final class HellishSpecialListener implements Listener {

    private final DiffModeManager manager;
    private final Random random;

    public HellishSpecialListener(DiffModeManager manager, Random random) {
        this.manager = manager;
        this.random = random;
    }

    // Snowman: bola nieve -> 10 shulker bullets en aro
    @EventHandler
    public void onSnowballHit(ProjectileHitEvent e) {
        if (!manager.isActive(DiffMode.HELLISH)) return;
        if (!(e.getEntity() instanceof Snowball ball)) return;
        if (!(ball.getShooter() instanceof Snowman)) return;
        Location hit = e.getEntity().getLocation();
        LivingEntity target = null;
        // busca enemigo cercano
        for (Entity n : hit.getWorld().getNearbyEntities(hit, 16, 8, 16)) {
            if (n instanceof LivingEntity le && !(le instanceof Snowman) && le != ball.getShooter()) {
                target = le;
                break;
            }
        }
        if (target == null) target = (LivingEntity) ball.getShooter();
        for (int i=0;i<10;i++) {
            double ang = i * Math.PI*2/10;
            Vector dir = new Vector(Math.cos(ang), 0.15, Math.sin(ang)).normalize();
            if (target != null) {
                Vector toTarget = target.getLocation().add(0,0.5,0).toVector().subtract(hit.toVector()).normalize();
                dir.add(toTarget.clone().multiply(0.6)).normalize();
            }
            ShulkerBullet b = (ShulkerBullet) hit.getWorld().spawnEntity(hit, EntityType.SHULKER_BULLET);
            b.setShooter((LivingEntity) ball.getShooter());
            if (target != null) b.setTarget(target);
            b.setVelocity(dir.multiply(0.7));
            manager.markSpawned(b);
        }
        hit.getWorld().playSound(hit, Sound.ENTITY_SHULKER_SHOOT, 1f, 0.9f);
        hit.getWorld().spawnParticle(org.bukkit.Particle.ITEM_SNOWBALL, hit, 8, 0.3,0.3,0.3, 0.02);
    }

    // Drowned: 3 tridentes
    @EventHandler
    public void onTridentLaunch(ProjectileLaunchEvent e) {
        if (!manager.isActive(DiffMode.HELLISH)) return;
        if (!(e.getEntity() instanceof Trident trident)) return;
        if (!(trident.getShooter() instanceof Drowned drowned)) return;
        if (!drowned.getScoreboardTags().contains("dm_drowned_triple")) return;
        // Cancela el original y lanza 3 con dispersion
        e.setCancelled(true);
        Location from = drowned.getEyeLocation();
        LivingEntity target = drowned.getTarget();
        Vector base = target != null ? target.getLocation().add(0,1,0).toVector().subtract(from.toVector()).normalize() : trident.getVelocity().normalize();
        for (int i=-1;i<=1;i++) {
            Trident t = (Trident) from.getWorld().spawnEntity(from, EntityType.TRIDENT);
            t.setShooter(drowned);
            Vector dir = base.clone();
            dir.add(new Vector((random.nextDouble()-0.5)*0.2, (random.nextDouble()-0.5)*0.15, (random.nextDouble()-0.5)*0.2));
            dir.normalize().multiply(trident.getVelocity().length() > 0 ? trident.getVelocity().length() : 2.2);
            t.setVelocity(dir);
            // Copia encantamientos del original
            t.getItemStack().addEnchantments(trident.getItemStack().getEnchantments());
            t.setLoyaltyLevel(3);
            manager.markSpawned(t);
        }
        drowned.getWorld().playSound(from, Sound.ITEM_TRIDENT_THROW, 1f, 0.9f);
    }

    // Ghast Nether: bola grande -> espiral de pequeñas
    @EventHandler
    public void onGhastFireball(ProjectileLaunchEvent e) {
        if (!manager.isActive(DiffMode.HELLISH)) return;
        if (!(e.getEntity() instanceof LargeFireball fb)) return;
        if (!(fb.getShooter() instanceof Ghast ghast)) return;
        if (ghast.getWorld().getEnvironment() != World.Environment.NETHER) return;
        // Programa espiral durante trayecto
        Location start = fb.getLocation().clone();
        Vector dir = fb.getVelocity().clone().normalize();
        org.bukkit.scheduler.BukkitRunnable spiral = new org.bukkit.scheduler.BukkitRunnable() {
            int ticks = 0;
            Location cur = start.clone();
            @Override public void run() {
                if (!fb.isValid() || ticks > 60) { cancel(); return; }
                cur = fb.getLocation();
                // cada 4 ticks lanza 4 bolitas en espiral
                if (ticks % 4 == 0) {
                    for (int i=0;i<4;i++) {
                        double ang = ticks*0.6 + i*Math.PI*2/4;
                        Vector offset = new Vector(Math.cos(ang)*0.7, Math.sin(ang)*0.7, 0);
                        // rota offset para que sea perpendicular a dir
                        // simplifica: usa world spawn
                        SmallFireball small = (SmallFireball) cur.getWorld().spawnEntity(cur, EntityType.SMALL_FIREBALL);
                        small.setShooter(ghast);
                        Vector sDir = new Vector(offset.getX(), offset.getY(), offset.getZ()).normalize().multiply(0.9);
                        sDir.add(dir.clone().multiply(0.5));
                        small.setVelocity(sDir);
                        small.setYield(1);
                        manager.markSpawned(small);
                    }
                    cur.getWorld().spawnParticle(org.bukkit.Particle.FLAME, cur, 6, 0.2,0.2,0.2, 0.02);
                }
                ticks++;
            }
        };
        spiral.runTaskTimer(manager.plugin(), 0L, 1L);
    }

    // Shulker ya maneja 15 balas en HellishMobTask, pero asegura que no se limite
    // End Phantom: si lo miran se teletransporta, si lo atacan particulas
    @EventHandler
    public void onPhantomDamage(EntityDamageByEntityEvent e) {
        if (!manager.isActive(DiffMode.HELLISH)) return;
        if (!(e.getEntity() instanceof Phantom phantom)) return;
        if (!phantom.getScoreboardTags().contains("dm_end_phantom")) return;
        // Al ser atacado emite particulas y cuenta hits
        phantom.getWorld().spawnParticle(org.bukkit.Particle.PORTAL, phantom.getLocation(), 15, 0.4,0.4,0.4, 0.08);
        phantom.getWorld().spawnParticle(org.bukkit.Particle.REVERSE_PORTAL, phantom.getLocation(), 8, 0.3,0.3,0.3, 0.05);
        phantom.getWorld().playSound(phantom.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.7f, 1.1f);
        var pdc = phantom.getPersistentDataContainer();
        int hits = pdc.getOrDefault(new NamespacedKey(manager.plugin(), "end_phantom_hits"), org.bukkit.persistence.PersistentDataType.INTEGER, 0) + 1;
        pdc.set(new NamespacedKey(manager.plugin(), "end_phantom_hits"), org.bukkit.persistence.PersistentDataType.INTEGER, hits);
        if (hits >= 2) {
            // Despues de 2 ataques se teletransporta lejos
            Location dest = phantom.getLocation().clone().add((random.nextDouble()-0.5)*20, 6, (random.nextDouble()-0.5)*20);
            if (dest.getChunk().isLoaded()) phantom.teleport(dest);
        }
    }

    // Iron Golem hostil se maneja en HellishMobTask, pero asegura que no ataque aldeanos via TargetEvent
    @EventHandler
    public void onIronGolemTarget(org.bukkit.event.entity.EntityTargetEvent e) {
        if (!manager.isActive(DiffMode.HELLISH)) return;
        if (!(e.getEntity() instanceof IronGolem)) return;
        if (e.getTarget() instanceof Villager) e.setCancelled(true);
    }

    @EventHandler
    public void onSnowmanTarget(org.bukkit.event.entity.EntityTargetEvent e) {
        if (!manager.isActive(DiffMode.HELLISH)) return;
        if (!(e.getEntity() instanceof Snowman)) return;
        // Snowman agresivo ya manejado, pero asegura que no se cancele
    }

    private Player nearestPlayer(Location from, double maxDist) {
        Player best = null;
        double bestD = Double.MAX_VALUE;
        for (Player p : from.getWorld().getPlayers()) {
            double d = p.getLocation().distance(from);
            if (d < bestD && d <= maxDist) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    // Nivel 2+: las bolas de fuego destructivas detectan al jugador oculto
    // tras 1 bloque y lo explotan rompiendo esa pared
    @EventHandler
    public void onDestructiveFireballHit(ProjectileHitEvent e) {
        if (!manager.isActive(DiffMode.HELLISH) || manager.difficultyLevel() < 2) {
            return;
        }
        Projectile ball = e.getEntity();
        if (!(ball instanceof Fireball) && !(ball instanceof WitherSkull)) {
            return;
        }
        if (!(ball.getShooter() instanceof LivingEntity shooter)
                || shooter instanceof Player) {
            return;
        }
        org.bukkit.block.Block block = e.getHitBlock();
        if (block == null || !block.getType().isSolid()) {
            return;
        }
        Location hit = block.getLocation();
        // Solo si hay un jugador del otro lado de esa pared
        Player hidden = nearestPlayer(hit, 3.5);
        if (hidden == null) {
            return;
        }
        block.setType(Material.AIR);
        hit.add(0.5, 0.5, 0.5);
        hit.getWorld().createExplosion(hit, 2.4f, false, false);
        hit.getWorld().spawnParticle(org.bukkit.Particle.FLAME, hit, 20,
                0.5, 0.5, 0.5, 0.05);
        hit.getWorld().playSound(hit, Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.8f);
    }

    // Nivel 2+: las flechas teledirigidas del Jefe Esqueleto se desvanecen
    // apenas chocan con su objetivo (o cualquier superficie)
    @EventHandler
    public void onBossArrowHit(ProjectileHitEvent e) {
        if (!manager.isActive(DiffMode.HELLISH) || manager.difficultyLevel() < 2) {
            return;
        }
        if (!(e.getEntity() instanceof Arrow arrow)) {
            return;
        }
        if (!arrow.getScoreboardTags().contains("dm_boss_arrow")) {
            return;
        }
        Location hit = arrow.getLocation();
        arrow.remove();
        hit.getWorld().spawnParticle(org.bukkit.Particle.CRIT, hit, 6,
                0.15, 0.15, 0.15, 0.05);
    }

    // Nivel 3+: las flechas de los pillagers lanzan balas de shulker al impactar
    @EventHandler
    public void onPillagerArrowHit(ProjectileHitEvent e) {
        if (!manager.isActive(DiffMode.HELLISH) || manager.difficultyLevel() < 3) {
            return;
        }
        if (!(e.getEntity() instanceof Arrow arrow)) {
            return;
        }
        if (!(arrow.getShooter() instanceof org.bukkit.entity.Pillager pillager)
                || !pillager.getScoreboardTags().contains("dm_lv3_pillager")) {
            return;
        }
        Location hit = arrow.getLocation();
        Player target = nearestPlayer(hit, 24);
        for (int i = 0; i < 2; i++) {
            ShulkerBullet bullet = (ShulkerBullet) hit.getWorld()
                    .spawnEntity(hit, EntityType.SHULKER_BULLET);
            bullet.setShooter(pillager);
            if (target != null) {
                bullet.setTarget(target);
            }
            bullet.setVelocity(new Vector((random.nextDouble() - 0.5) * 0.4,
                    0.4, (random.nextDouble() - 0.5) * 0.4));
            manager.markSpawned(bullet);
        }
        arrow.remove();
        hit.getWorld().playSound(hit, Sound.ENTITY_SHULKER_SHOOT, 0.8f, 1.2f);
    }

    // Nivel 4: flechas TNT del Nether -> daño en masa SIN destruir bloques
    @EventHandler
    public void onTntArrowHit(ProjectileHitEvent e) {
        if (!manager.isActive(DiffMode.HELLISH)) return;
        if (!(e.getEntity() instanceof Arrow arrow)) return;
        if (!arrow.getScoreboardTags().contains("dm_tnt_arrow")) return;
        Location hit = arrow.getLocation();
        arrow.remove();
        hit.getWorld().createExplosion(hit, 3.5f, false, false);
        hit.getWorld().playSound(hit, Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.9f);
    }

    // Nivel 4: el phantom ataca 4 veces seguidas (mordidas consecutivas)
    @EventHandler(ignoreCancelled = true)
    public void onPhantomBite(EntityDamageByEntityEvent e) {
        if (!manager.isActive(DiffMode.HELLISH) || manager.difficultyLevel() < 4) return;
        if (!(e.getEntity() instanceof Player player)) return;
        if (!(e.getDamager() instanceof Phantom phantom)) return;
        if (!phantom.getScoreboardTags().contains("dm_hellish_phantom")) return;
        if (phantom.getScoreboardTags().contains("dm_end_phantom")) return;
        org.bukkit.NamespacedKey bitesKey = new org.bukkit.NamespacedKey(
                manager.plugin(), "phantom_bites");
        int bites = phantom.getPersistentDataContainer().getOrDefault(bitesKey,
                org.bukkit.persistence.PersistentDataType.INTEGER, 0) + 1;
        phantom.getPersistentDataContainer().set(bitesKey,
                org.bukkit.persistence.PersistentDataType.INTEGER, bites);
        if (bites < 4) {
            org.bukkit.Bukkit.getScheduler().runTaskLater(manager.plugin(), () -> {
                if (!phantom.isValid() || !player.isOnline()) return;
                Location above = player.getLocation().clone().add(0, 7, 0);
                phantom.teleport(above);
                phantom.setTarget(player);
                Vector dir = player.getLocation().toVector()
                        .subtract(above.toVector()).normalize();
                phantom.setVelocity(dir.multiply(1.3));
                phantom.getWorld().playSound(above, Sound.ENTITY_PHANTOM_SWOOP,
                        1f, 0.9f);
            }, 8L);
        }
    }

    // Nivel 4: aviso cuando LA LLUVIA ACIDA realmente empieza a caer
    @EventHandler
    public void onAcidRainStart(WeatherChangeEvent e) {
        if (!manager.isActive(DiffMode.HELLISH) || manager.difficultyLevel() < 4) return;
        if (e.toWeatherState()
                && e.getWorld().getEnvironment() == World.Environment.NORMAL) {
            org.bukkit.Bukkit.broadcastMessage(
                    "§4§l[Death] §c☠ ¡La §eLluvia Ácida§c está cayendo! "
                    + "¡No te expongas al cielo!");
        }
    }

    // Nivel 2+: al explotar, los creepers lanzan 4 TNT primed alrededor
    // Nivel 3+: los ghasts del Nether explotan muy fuerte al morir
    @EventHandler
    public void onMobDeath(EntityDeathEvent e) {
        if (!manager.isActive(DiffMode.HELLISH)) {
            return;
        }
        int level = manager.difficultyLevel();
        if (e.getEntity() instanceof Creeper creeper && level >= 2) {
            Location loc = creeper.getLocation();
            for (int i = 0; i < 4; i++) {
                double ang = i * Math.PI / 2 + random.nextDouble() * 0.5;
                double dx = Math.cos(ang) * 1.6;
                double dz = Math.sin(ang) * 1.6;
                TNTPrimed tnt = (TNTPrimed) creeper.getWorld().spawnEntity(
                        loc.clone().add(dx, 0.4, dz), EntityType.TNT);
                tnt.setFuseTicks(25 + random.nextInt(15));
                tnt.setVelocity(new Vector(dx * 0.3, 0.5, dz * 0.3));
                manager.markSpawned(tnt);
            }
        }
        if (e.getEntity() instanceof Ghast ghast
                && level >= 3
                && ghast.getWorld().getEnvironment() == World.Environment.NETHER) {
            ghast.getWorld().createExplosion(ghast.getLocation(), 5.5f, true, false);
            ghast.getWorld().playSound(ghast.getLocation(),
                    Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f);
        }
        // Nivel 5: al morir un miembro de la tribu de husks, se invoca una
        // trampa de esqueletos que persigue al asesino.
        if (e.getEntity().getScoreboardTags().contains("dm_lv5_husk_tribe")
                && level >= 5) {
            LivingEntity killer = e.getEntity().getKiller();
            manager.spawnLv5SkeletonTrap(killer);
            e.getEntity().getWorld().playSound(e.getEntity().getLocation(),
                    Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1f, 1f);
            e.getEntity().getWorld().strikeLightningEffect(e.getEntity().getLocation());
        }
    }

    // Nivel 6: escudo manual de los undead. Reduce el dano ~50% en vez de
    // bloquearlo del todo (fix del bug donde sonaba el escudo pero casi no
    // entraba dano) y permite que el escudo se rompa tras varios golpes.
    @EventHandler
    public void onShieldBlock(EntityDamageByEntityEvent e) {
        if (!manager.isActive(DiffMode.HELLISH)
                || manager.difficultyLevel() < 6) {
            return;
        }
        if (!(e.getEntity() instanceof LivingEntity victim)) {
            return;
        }
        if (victim instanceof Player) {
            return;
        }
        var pdc = victim.getPersistentDataContainer();
        if (pdc.getOrDefault(manager.shieldActiveKey(),
                org.bukkit.persistence.PersistentDataType.BYTE, (byte) 0) != 1) {
            return;
        }
        // Solo golpes de entidades/armas reducen (no explosiones quemaduras)
        if (!(e.getDamager() instanceof LivingEntity)) {
            return;
        }
        e.setDamage(e.getDamage() * 0.5);
        victim.getWorld().playSound(victim.getLocation(),
                org.bukkit.Sound.ITEM_SHIELD_BLOCK, 1f, 1.2f);
        // Desgaste: el escudo se puede romper
        int hp = pdc.getOrDefault(manager.shieldHpKey(),
                org.bukkit.persistence.PersistentDataType.INTEGER, 8) - 1;
        if (hp <= 0) {
            pdc.set(manager.shieldActiveKey(),
                    org.bukkit.persistence.PersistentDataType.BYTE, (byte) 0);
            victim.getEquipment().setItemInOffHand(null);
            victim.getWorld().playSound(victim.getLocation(),
                    org.bukkit.Sound.ENTITY_ITEM_BREAK, 1f, 1.3f);
            victim.getWorld().spawnParticle(org.bukkit.Particle.ITEM,
                    victim.getLocation().add(0, 1, 0), 12, 0.2, 0.3, 0.2, 0.05,
                    new org.bukkit.inventory.ItemStack(Material.SHIELD));
        } else {
            pdc.set(manager.shieldHpKey(),
                    org.bukkit.persistence.PersistentDataType.INTEGER, hp);
        }
    }
}

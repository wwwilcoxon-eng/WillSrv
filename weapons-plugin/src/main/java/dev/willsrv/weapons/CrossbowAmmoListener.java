package dev.willsrv.weapons;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.DragonFireball;
import org.bukkit.entity.Player;
import org.bukkit.entity.ShulkerBullet;
import org.bukkit.entity.SmallFireball;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.entity.WindCharge;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import io.papermc.paper.event.entity.EntityLoadCrossbowEvent;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Municion especial para la ballesta (crossbow).
 *
 * Lleva la municion en la mano secundaria (offhand) y carga la ballesta como
 * si fuera una flecha:
 *   - Carga de fuego (Fire Charge)      -> SmallFireball
 *   - Aliento de dragon (Dragon Breath) -> DragonFireball
 *   - Pocion (Potion)                   -> ThrownPotion (lanza la pocion)
 *   - Caparazon de shulker (Shulker Shell) -> ShulkerBullet
 *   - Carga de viento (Wind Charge)     -> WindCharge
 *   - Polvo de blaze (Blaze Powder)     -> Fireball grande (ghast)
 *   - Combo Shulker + Blaze             -> Fireball grande (ghast)
 *
 * Respeta Multishot (dispara 3) y Piercing (no consume / atraviesa).
 */
public final class CrossbowAmmoListener implements Listener {

    private static final double BASE_SPEED = 1.6;
    private static final double SHOT_POWER = 0.5;

    private final JavaPlugin plugin;
    private final java.util.Map<java.util.UUID, ItemStack> pending = new java.util.HashMap<>();

    public CrossbowAmmoListener(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Al empezar a cargar la ballesta, si el offhand trae municion especial
     * la metemos en la CrossbowMeta como proyectil cargado y se consume.
     * FIX: no cancelar el evento (cancalarlo impedia que la ballesta quedase cargada
     * y por eso al disparar solo salia una flecha). Ahora solo evitamos que se
     * consuma una flecha vanilla y añadimos nuestro proyectil custom.
     */
     @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLoad(EntityLoadCrossbowEvent e) {
        if (!(e.getEntity() instanceof Player player)) {
            return;
        }
        // Ammo en la mano opuesta a la ballesta
        ItemStack offhand = e.getHand() == EquipmentSlot.HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
        if (!isSpecialAmmo(offhand)) {
            return;
        }
        ItemStack crossbow = e.getCrossbow();
        if (!(crossbow.getItemMeta() instanceof CrossbowMeta meta)) {
            return;
        }
        // Evita sobrecargar (max 3 para multishot)
        if (meta.hasChargedProjectiles() && meta.getChargedProjectiles().size() >= 3) {
            return;
        }
        // Añade el proyectil custom a la lista de la ballesta
        ItemStack custom = offhand.asOne();
        // Guarda pending para garantizar disparo aunque la visual falle
        pending.put(player.getUniqueId(), custom.clone());
        // También intenta mostrar visual correcta en la ballesta
        meta.addChargedProjectile(custom);
        crossbow.setItemMeta(meta);

        // Consume manualmente 1 del offhand
        offhand.subtract(1);
        // No consumir flecha vanilla
        e.setConsumeItem(false);
        // Fuerza visual correcta: 1 tick despues el servidor añade una flecha vanilla por defecto;
        // la reemplazamos para que las propiedades muestren el proyectil elegido (fire_charge etc) y no una flecha
        ItemStack finalCustom = custom.clone();
        EquipmentSlot hand = e.getHand();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!(player.getInventory().getItem(hand).getItemMeta() instanceof CrossbowMeta m2)) return;
            List<ItemStack> cur = m2.getChargedProjectiles();
            // Si ya tiene nuestro custom, bien; si tiene flecha, lo reemplazamos
            boolean hasOur = false;
            if (cur != null) for (ItemStack it : cur) if (it.getType() == finalCustom.getType()) hasOur = true;
            if (!hasOur || (cur!=null && cur.size()>1 && cur.stream().noneMatch(i->i.getType()==Material.SHULKER_SHELL && cur.stream().anyMatch(j->j.getType()==Material.BLAZE_POWDER)))) {
                List<ItemStack> newList = new java.util.ArrayList<>();
                if (cur != null) for (ItemStack it : cur) if (isSpecialAmmo(it)) newList.add(it);
                if (newList.isEmpty()) newList.add(finalCustom);
                else if (newList.size()==1 && newList.get(0).getType()!=finalCustom.getType()) newList.add(finalCustom);
                if (newList.size()>3) newList=newList.subList(0,3);
                m2.setChargedProjectiles(newList);
            } else if (cur==null || cur.isEmpty()) {
                m2.setChargedProjectiles(List.of(finalCustom));
            }
            ItemStack held = player.getInventory().getItem(hand);
            held.setItemMeta(m2);
            player.getInventory().setItem(hand, held);
            player.updateInventory();
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player player)) {
            return;
        }
        if (e.getBow() == null
                || !(e.getBow().getItemMeta() instanceof CrossbowMeta meta)) {
            return;
        }
        List<ItemStack> charged = meta.getChargedProjectiles();
        ItemStack pendingAmmo = pending.remove(player.getUniqueId());
        // Prioriza pending (garantiza disparo custom aunque la visual sea flecha)
        ItemStack ammo = null;
        if (pendingAmmo != null && isSpecialAmmo(pendingAmmo)) {
            // Si charged tiene combo shulker+blaze, traduce a blaze
            boolean hasShulkerP = pendingAmmo.getType()==Material.SHULKER_SHELL && charged!=null && charged.stream().anyMatch(i->i.getType()==Material.BLAZE_POWDER);
            boolean hasBlazeP = pendingAmmo.getType()==Material.BLAZE_POWDER && charged!=null && charged.stream().anyMatch(i->i.getType()==Material.SHULKER_SHELL);
            if (hasShulkerP || hasBlazeP) ammo = new ItemStack(Material.BLAZE_POWDER);
            else ammo = pendingAmmo;
        } else {
            if (charged == null || charged.isEmpty()) return;
            boolean hasCustom = false;
            for (ItemStack it : charged) if (isSpecialAmmo(it)) { hasCustom = true; break; }
            if (!hasCustom) return;
            boolean hasShulker = charged.stream().anyMatch(i -> i.getType() == Material.SHULKER_SHELL);
            boolean hasBlaze = charged.stream().anyMatch(i -> i.getType() == Material.BLAZE_POWDER);
            if (hasShulker && hasBlaze) {
                ammo = new ItemStack(Material.BLAZE_POWDER);
            } else {
                ammo = charged.get(0);
                if (!isSpecialAmmo(ammo)) {
                    for (ItemStack it : charged) if (isSpecialAmmo(it)) { ammo = it; break; }
                }
            }
        }
        if (ammo==null || !isSpecialAmmo(ammo)) return;

        e.setCancelled(true);

        // Consume el proyectil cargado de la ballesta.
        meta.setChargedProjectiles(List.of());
        e.getBow().setItemMeta(meta);

        Location eye = player.getEyeLocation();
        // Dirección EXACTA de la mirada (ray-trace preciso, sin boost vertical artificial)
        Vector dir = eye.getDirection().clone().normalize();
        boolean multi = e.getBow().containsEnchantment(Enchantment.MULTISHOT);

        int count = multi ? 3 : 1;
        double base = baseSpeed(count);
        // Potencia ligeramente mayor para potions (caen por gravedad)
        boolean isPotion = ammo.getType() == Material.POTION || ammo.getType() == Material.SPLASH_POTION || ammo.getType() == Material.LINGERING_POTION;
        if (isPotion) base *= 1.15;
        Vector velocity = dir.clone().multiply(base);
        // Hereda un poco la velocidad del jugador para que no se sienta desfasado al correr
        Vector pv = player.getVelocity().clone(); pv.setY(0);
        if (pv.lengthSquared() > 1e-6) velocity.add(pv.multiply(0.18));

        // Perp horizontal exacto (proyectado en XZ, no depende de up)
        Vector hPerp = horizontalPerp(dir);

        for (int i = 0; i < count; i++) {
            Vector shotVel = velocity.clone();
            if (count > 1) {
                // Dispersión mínima, solo para sentir multishot sin perder precisión
                double spread = 0.025;
                shotVel.add(new Vector(
                        (ThreadLocalRandom.current().nextDouble() - 0.5) * spread,
                        (ThreadLocalRandom.current().nextDouble() - 0.5) * spread,
                        (ThreadLocalRandom.current().nextDouble() - 0.5) * spread));
                if (i == 0) {
                    shotVel.add(hPerp.clone().multiply(-0.30));
                } else if (i == 2) {
                    shotVel.add(hPerp.clone().multiply(0.30));
                }
            }
            // Spawn EXACTO frente a los ojos, no dentro de la cabeza
            Vector shotDir = shotVel.clone().normalize();
            Location spawnLoc = eye.clone().add(shotDir.clone().multiply(1.15)).add(new Vector(0, -0.08, 0));
            // Para laterales, desplaza el spawn también lateralmente para no atravesar la cara
            if (count > 1) {
                if (i == 0) spawnLoc.add(hPerp.clone().multiply(-0.22));
                else if (i == 2) spawnLoc.add(hPerp.clone().multiply(0.22));
            }
            spawnExact(player, ammo, spawnLoc, shotVel, shotDir);
        }
        // Sonido de disparo de la ballesta.
        player.getWorld().playSound(eye, org.bukkit.Sound.ITEM_CROSSBOW_SHOOT, 1.0f, 1.0f);
    }

    private static Vector horizontalPerp(Vector dir) {
        // Perpendicular horizontal (XZ) para multishot exacto. Si miras 100% vertical, usa Z.
        Vector h = new Vector(-dir.getZ(), 0, dir.getX());
        if (h.lengthSquared() < 1e-8) return new Vector(1, 0, 0);
        return h.normalize();
    }

    @SuppressWarnings("unused")
    private static Vector perp(Location eye) {
        Vector dir = eye.getDirection().clone();
        Vector up = new Vector(0, 1, 0);
        Vector cross = dir.getCrossProduct(up);
        if (cross.lengthSquared() < 1e-8) return dir.getX() >= 0 ? new Vector(0, 0, 1) : new Vector(0, 0, -1);
        return cross.normalize();
    }

    private static double baseSpeed(int count) {
        // Multishot reparte la energia:
        return count > 1 ? BASE_SPEED * 0.8 : BASE_SPEED;
    }

    private void spawnExact(Player player, ItemStack ammo, Location spawnLoc, Vector vel, Vector dir) {
        try {
            spawnEntity(player, ammo, spawnLoc, vel, dir);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    @SuppressWarnings("unused")
    private void spawn(Player player, ItemStack ammo, Location eye, Vector vel) {
        Vector dir = vel.clone().normalize();
        Location spawnLoc = eye.clone().add(dir.clone().multiply(0.9));
        spawnExact(player, ammo, spawnLoc, vel, dir);
    }

    private void spawnEntity(Player player, ItemStack ammo, Location spawnLoc, Vector vel, Vector dir) {
        Material type = ammo.getType();
        switch (type) {
            case FIRE_CHARGE -> {
                SmallFireball fb = player.getWorld().spawn(spawnLoc, SmallFireball.class);
                fb.setShooter(player);
                // Solo velocity exacta; dirección = velocity normalizada (sin aceleración extra)
                fb.setVelocity(vel);
                try { fb.setDirection(dir); } catch (Throwable ignored) {}
                fb.setYield(1f);
                fb.setIsIncendiary(true);
            }
            case DRAGON_BREATH -> {
                DragonFireball df = player.getWorld().spawn(spawnLoc, DragonFireball.class);
                df.setShooter(player);
                df.setVelocity(vel);
                try { df.setDirection(dir); } catch (Throwable ignored) {}
                df.setYield(1f);
                df.setIsIncendiary(true);
            }
            case SHULKER_SHELL -> {
                // Evita spam acumulativo: si ya hay muchos ShulkerBullets, borra los viejos
                var existing = player.getWorld().getEntitiesByClass(ShulkerBullet.class);
                if (existing.size() > 12) {
                    int toRemove = existing.size() - 8;
                    for (var e : existing) {
                        if (toRemove-- <= 0) break;
                        e.remove();
                    }
                    plugin.getSLF4JLogger().warn("ShulkerBullet cap: limpiados, quedan {}", player.getWorld().getEntitiesByClass(ShulkerBullet.class).size());
                }
                ShulkerBullet sb = player.getWorld().spawn(spawnLoc, ShulkerBullet.class);
                sb.setShooter(player);
                sb.setVelocity(vel);
                sb.setTarget(null);
                // Fuerza movimiento sin gravedad excesiva y despawn rápido para no colgar el tick (hover + scan de entidades es O(n²))
                try { sb.setGravity(false); } catch (Throwable ignored) {}
                // despawn automático 4s después si no impactó
                Bukkit.getScheduler().runTaskLater(plugin, () -> { if (sb.isValid() && !sb.isDead()) sb.remove(); }, 80L);
            }
            case BLAZE_POWDER -> {
                org.bukkit.entity.Fireball fb = player.getWorld().spawn(spawnLoc, org.bukkit.entity.Fireball.class);
                fb.setShooter(player);
                // Ghast fireball: velocity exacta + aceleración suave en la misma dirección para no perder la trayectoria
                fb.setVelocity(vel);
                try { fb.setDirection(dir); } catch (Throwable ignored) {}
                try { fb.setAcceleration(dir.clone().multiply(0.02)); } catch (Throwable ignored) {}
                fb.setYield(1.5f);
                fb.setIsIncendiary(true);
            }
            case WIND_CHARGE -> {
                WindCharge wc = player.getWorld().spawn(spawnLoc, WindCharge.class);
                wc.setShooter(player);
                wc.setVelocity(vel);
                // Aceleración mínima para mantener dirección sin desviar
                try { wc.setAcceleration(dir.clone().multiply(0.015)); } catch (Throwable ignored) {}
            }
            case POTION, SPLASH_POTION, LINGERING_POTION -> {
                ThrownPotion tp = player.getWorld().spawn(spawnLoc, ThrownPotion.class);
                tp.setItem(ammo);
                tp.setShooter(player);
                tp.setVelocity(vel);
            }
            default -> {}
        }
    }

    private boolean isSpecialAmmo(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        switch (item.getType()) {
            case FIRE_CHARGE:
            case DRAGON_BREATH:
            case SHULKER_SHELL:
            case WIND_CHARGE:
            case BLAZE_POWDER:
            case POTION:
            case SPLASH_POTION:
            case LINGERING_POTION:
                return true;
            default:
                return false;
        }
    }
}

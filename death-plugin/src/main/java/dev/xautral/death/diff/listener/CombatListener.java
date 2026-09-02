package dev.xautral.death.diff.listener;

import dev.xautral.death.diff.DiffMode;
import dev.xautral.death.diff.DiffModeManager;
import org.bukkit.Material;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Random;

public final class CombatListener implements Listener {

    private final DiffModeManager manager;
    private final Random random;

    public CombatListener(DiffModeManager manager, Random random) {
        this.manager = manager;
        this.random = random;
    }

    @EventHandler
    public void onResurrect(EntityResurrectEvent event) {
        if (!manager.isActive(DiffMode.HELLISH)) {
            return;
        }
        // Nivel 5: los miembros de la tribu de husks llevan un totem en su mano
        // secundaria, pero NO resucitan: su totem se consume y mueren igual,
        // invocando la trampa de esqueletos (se maneja en onMobDeath).
        if (!(event.getEntity() instanceof Player)
                && event.getEntity().getScoreboardTags().contains("dm_lv5_husk_tribe")) {
            event.setCancelled(true);
            event.getEntity().getWorld().playSound(event.getEntity().getLocation(),
                    org.bukkit.Sound.ITEM_TOTEM_USE, 1f, 1f);
            event.getEntity().getWorld().spawnParticle(
                    org.bukkit.Particle.TOTEM_OF_UNDYING,
                    event.getEntity().getLocation().add(0, 1, 0),
                    14, 0.4, 0.5, 0.4, 0.1);
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        EquipmentSlot hand = event.getHand();
        if (hand == null) {
            return;
        }
        ItemStack totem = player.getInventory().getItem(hand);
        if (totem == null || totem.getType() != Material.TOTEM_OF_UNDYING) {
            return;
        }
        ItemMeta meta = totem.getItemMeta();
        boolean definitivo = meta != null && meta.getPersistentDataContainer()
                .has(manager.totemDefinitivoKey(), PersistentDataType.BYTE);
        int level = manager.difficultyLevel();
        if (definitivo) {
            // Nivel 3+: el totem definitivo se DEBILITA al 65%; debilitado solo
            // tiene un 50% de funcionar
            if (level >= 3 && random.nextDouble() < 0.65) {
                boolean debilitadoFallo = random.nextDouble() < 0.50;
                if (debilitadoFallo) {
                    event.setCancelled(true);
                    org.bukkit.Bukkit.broadcast(net.kyori.adventure.text.Component.text(
                            "§7" + player.getName() + " §cFALLÓ §7totem definitivo debilitado (65%→50%)"));
                    player.sendMessage("§4Tu §6Totem Definitivo§4 se debilitó (50%) y ha FALLADO...");
                    player.getWorld().playSound(player.getLocation(),
                            org.bukkit.Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 1f, 0.6f);
                    return;
                }
                player.sendMessage("§6[Death] §eTu §6Totem Definitivo§e debilitado (50%) brilló a tiempo.");
                org.bukkit.Bukkit.broadcast(net.kyori.adventure.text.Component.text(
                        "§7" + player.getName() + " §eactivó §7totem definitivo debilitado (50% éxito)"));
                return;
            }
            player.sendMessage("§6[Death] §eTu §6Totem Definitivo §ebrillo sin fallar.");
            // Mensaje gris sin anunciante
            org.bukkit.Bukkit.broadcast(net.kyori.adventure.text.Component.text(player.getName() + " usó Totem Definitivo (fallo 0%)").color(net.kyori.adventure.text.format.NamedTextColor.GRAY));
            return;
        }
        int failPercent = dev.xautral.death.diff.item.HellishItems.totemFailPercent(level);
        // Nivel 3: solo 25% de funcionar. Nivel 1-2: 40% de fallo.
        boolean fallo = random.nextDouble() < failPercent / 100.0;
        // Mensaje gris sin anunciante con probabilidad
        String tipo = fallo ? "§cFALLÓ" : "§aactivó";
        org.bukkit.Bukkit.broadcast(net.kyori.adventure.text.Component.text("§7" + player.getName() + " " + tipo + " §7totem (fallo " + failPercent + "% → " + (fallo ? "falló" : "éxito") + ")"));
        if (fallo) {
            event.setCancelled(true);
            player.sendMessage("§4Tu totem ha FALLADO... (" + failPercent + "% fallo)");
            // Sonido de fallo
            player.getWorld().playSound(player.getLocation(), org.bukkit.Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 1f, 0.6f);
            player.getWorld().playSound(player.getLocation(), org.bukkit.Sound.BLOCK_ANVIL_LAND, 0.8f, 0.5f);
        } else {
            player.sendMessage("§aTu totem funcionó... (" + failPercent + "% fallo superado)");
            player.getWorld().playSound(player.getLocation(), org.bukkit.Sound.ITEM_TOTEM_USE, 1f, 1f);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!manager.isActive(DiffMode.HELLISH)) {
            return;
        }
        // Vacas y pollos son neutrales... hasta que los pegas
        if ((event.getEntity().getType() == org.bukkit.entity.EntityType.COW
                || event.getEntity().getType() == org.bukkit.entity.EntityType.CHICKEN)
                && event.getDamager() instanceof Player attacker) {
            LivingEntity animal = (LivingEntity) event.getEntity();
            animal.addScoreboardTag("dm_angry_animal");
            animal.getPersistentDataContainer().set(
                    new org.bukkit.NamespacedKey(manager.plugin(), "revenge_target"),
                    org.bukkit.persistence.PersistentDataType.STRING,
                    attacker.getUniqueId().toString());
            if (animal instanceof Mob mob) {
                mob.setTarget(attacker);
            }
            var speed = animal.getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED);
            if (speed != null) {
                speed.setBaseValue(speed.getBaseValue() * 1.5); // furia veloz
            }
            animal.getWorld().playSound(animal.getLocation(),
                    animal.getType() == org.bukkit.entity.EntityType.COW
                            ? org.bukkit.Sound.ENTITY_COW_HURT
                            : org.bukkit.Sound.ENTITY_CHICKEN_HURT,
                    1.0f, 0.6f);
            return;
        }

        // Los phantoms acuden a ayudar a los esqueletos con poca vida:
        // el esqueleto MONTA el phantom y dispara desde el cielo
        if (!(event.getEntity() instanceof AbstractSkeleton skeleton)
                || !(event.getDamager() instanceof LivingEntity attacker)) {
            return;
        }
        double maxHealth = skeleton.getMaxHealth();
        if (maxHealth <= 0 || skeleton.getHealth() / maxHealth >= 0.5) {
            return;
        }
        for (org.bukkit.entity.Entity nearby : skeleton.getWorld().getNearbyEntities(
                skeleton.getLocation(), 48, 48, 48)) {
            if (!(nearby instanceof Phantom phantom)
                    || !phantom.getScoreboardTags().contains("dm_hellish_phantom")) {
                continue;
            }
            phantom.setTarget(attacker);
            if (skeleton.isInsideVehicle()) {
                break; // ya esta montado, solo apunta al agresor
            }
            if (phantom.getPassengers().isEmpty()) {
                // Desmonta su arana si venia montado en una
                if (skeleton.getVehicle() instanceof org.bukkit.entity.Spider spider) {
                    spider.removePassenger(skeleton);
                }
                skeleton.setTarget(attacker);
                phantom.addPassenger(skeleton);
                skeleton.addScoreboardTag("dm_fled");
                break;
            }
        }
    }
}

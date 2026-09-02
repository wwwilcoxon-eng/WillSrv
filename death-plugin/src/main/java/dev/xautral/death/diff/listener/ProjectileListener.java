package dev.xautral.death.diff.listener;

import dev.xautral.death.diff.DiffMode;
import dev.xautral.death.diff.DiffModeManager;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Blaze;
import org.bukkit.entity.LargeFireball;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.SmallFireball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

public final class ProjectileListener implements Listener {

    private final DiffModeManager manager;
    private final NamespacedKey blazeFireballsKey;

    public ProjectileListener(DiffModeManager manager, NamespacedKey blazeFireballsKey) {
        this.manager = manager;
        this.blazeFireballsKey = blazeFireballsKey;
    }

    @EventHandler(ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (!manager.isActive(DiffMode.HELLISH)) {
            return;
        }
        if (!(event.getEntity() instanceof SmallFireball fireball)
                || !(fireball.getShooter() instanceof Blaze blaze)) {
            return;
        }
        // Cada 5 bolas de fuego el blaze lanza una bola grande de ghast
        var pdc = blaze.getPersistentDataContainer();
        int count = pdc.getOrDefault(blazeFireballsKey, PersistentDataType.INTEGER, 0) + 1;
        if (count % 5 != 0) {
            pdc.set(blazeFireballsKey, PersistentDataType.INTEGER, count);
            return;
        }
        event.setCancelled(true);
        Vector fallback = fireball.getVelocity().normalize();
        Vector direction;
        LivingEntity target = blaze.getTarget();
        if (target != null) {
            direction = target.getLocation().add(0, 1, 0).toVector()
                    .subtract(blaze.getEyeLocation().toVector()).normalize();
        } else {
            direction = fallback;
        }
        blaze.getWorld().spawn(blaze.getEyeLocation(), LargeFireball.class, large -> {
            large.setShooter(blaze);
            large.setDirection(direction);
            large.setYield(3);
        });
    }
}

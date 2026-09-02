package dev.xautral.death.diff.listener;

import dev.xautral.death.diff.DiffMode;
import dev.xautral.death.diff.DiffModeManager;
import org.bukkit.Location;
import org.bukkit.Raid;
import org.bukkit.entity.Illusioner;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.raid.RaidSpawnWaveEvent;

public final class RaidListener implements Listener {

    private final DiffModeManager manager;

    public RaidListener(DiffModeManager manager) {
        this.manager = manager;
    }

    @EventHandler(ignoreCancelled = true)
    public void onRaidWave(RaidSpawnWaveEvent event) {
        if (!manager.isActive(DiffMode.HELLISH)) {
            return;
        }
        // Las raids incluyen ilusionistas
        Raid raid = event.getRaid();
        Location loc = raid.getLocation();
        if (loc.getWorld() != null) {
            loc.getWorld().spawn(loc, Illusioner.class, illusioner ->
                    illusioner.setRemoveWhenFarAway(false));
        }
    }
}

package dev.xautral.hellish;

import dev.xautral.hellish.DiffMode;
import dev.xautral.hellish.DiffModeCommand;
import dev.xautral.hellish.DiffModeManager;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Hellish Mode: modo de dificultad extrema separado del plugin Death.
 *
 * Mecánica del modo (niveles 1-7): mobs buffeados, jefes, lluvia acida,
 * totems que fallan, slots que se sellan al morir (liberables con el
 * Dodecaedro Angelical), recompensas por participacion y logros.
 */
public final class HellishPlugin extends JavaPlugin implements Listener {

    private DiffModeManager diffModeManager;

    @Override
    public void onEnable() {
        diffModeManager = new DiffModeManager(this);
        diffModeManager.register();

        // Purga cualquier lock de slot heredado de una partida anterior: el
        // inventario nunca debe quedar bloqueado/sellado fuera de un Hellish activo.
        diffModeManager.slotLocks().clearAll();

        // Registra el manejo de muerte de Hellish de forma independiente a Death:
        // al morir durante Hellish se sella un slot aleatorio y se registra la
        // muerte en la participacion.
        getServer().getPluginManager().registerEvents(new HellishDeathListener(), this);

        // Resurrecto Biblico: libro para desbanear life steal / slots.
        dev.xautral.hellish.item.ResurrectoManager resurrecto =
                new dev.xautral.hellish.item.ResurrectoManager(this);
        getServer().getPluginManager().registerEvents(resurrecto, this);

        PluginCommand diffCommand = getServer().getPluginCommand("diffmode");
        if (diffCommand != null) {
            DiffModeCommand executor = new DiffModeCommand(diffModeManager);
            diffCommand.setExecutor(executor);
            diffCommand.setTabCompleter(executor);
        }

        PluginCommand summonCommand = getServer().getPluginCommand("deathsummon");
        if (summonCommand != null) {
            dev.xautral.hellish.DeathSummonCommand summoner =
                    new dev.xautral.hellish.DeathSummonCommand(diffModeManager);
            summonCommand.setExecutor(summoner);
            summonCommand.setTabCompleter(summoner);
        }

        getSLF4JLogger().info("Hellish Mode habilitado.");
        // Instala el datapack de logros vanilla (idempotente)
        dev.xautral.hellish.util.AdvancementUtil.ensureDatapack(this);
    }

    @Override
    public void onDisable() {
        if (diffModeManager != null) {
            diffModeManager.unregister();
        }
        getSLF4JLogger().info("Hellish Mode deshabilitado.");
    }

    DiffModeManager diffModes() {
        return diffModeManager;
    }

    /**
     * Maneja la muerte de un jugador durante Hellish de forma independiente a
     * Death: registra la muerte en la participacion y sella un slot aleatorio.
     */
    private final class HellishDeathListener implements Listener {

        private HellishDeathListener() {
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onDeath(PlayerDeathEvent event) {
            org.bukkit.entity.Player player = event.getEntity();
            if (!diffModeManager.isActive(DiffMode.HELLISH)) {
                return;
            }
            diffModeManager.handlePlayerDeath(player,
                    player.getKiller() instanceof org.bukkit.entity.Player killer
                            ? killer : null);
            // Re-aplica los marcadores de los slots ya sellados tras la muerte
            // (los items reales dentro de slots sellados no van al cofre de Death).
            diffModeManager.slotLocks().sweepLockedSlots(player);
        }
    }
}

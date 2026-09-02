package dev.xautral.death;

import dev.xautral.death.diff.DiffModeCommand;
import dev.xautral.death.diff.DiffModeManager;
import dev.xautral.death.store.PendingStore;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class DeathPlugin extends JavaPlugin {

    private PendingStore pendingStore;
    private DiffModeManager diffModeManager;
    private dev.xautral.death.lives.LivesManager livesManager;
    private dev.xautral.death.lives.TotemOfLife totemOfLife;

    @Override
    public void onEnable() {
        pendingStore = new PendingStore(this);
        pendingStore.load();

        diffModeManager = new DiffModeManager(this);
        diffModeManager.register();

        // Purga cualquier lock de slot heredado de una partida anterior: el
        // inventario nunca debe quedar bloqueado/sellado fuera de un Hellish
        // activo (heredado de la antigua mecanica de sellado persistente).
        diffModeManager.slotLocks().clearAll();

        // Sistema de vidas (paralelo a LifeSteal) + Totem of Life, persistentes
        // a prueba de reinicios (lives.json) y que requieren reinicio (nunca /reload).
        livesManager = new dev.xautral.death.lives.LivesManager(this);
        livesManager.load();
        getServer().getPluginManager().registerEvents(livesManager, this);
        totemOfLife = new dev.xautral.death.lives.TotemOfLife(this, livesManager);
        getServer().getPluginManager().registerEvents(totemOfLife, this);
        totemOfLife.registerRecipe();

        getServer().getPluginManager().registerEvents(new DeathListener(
                this, pendingStore,
                new dev.xautral.death.chest.DeathChest(getSLF4JLogger()),
                new dev.xautral.death.head.DeathHeadSpawner()), this);

        PluginCommand diffCommand = getServer().getPluginCommand("diffmode");
        if (diffCommand != null) {
            DiffModeCommand executor = new DiffModeCommand(diffModeManager);
            diffCommand.setExecutor(executor);
            diffCommand.setTabCompleter(executor);
        }

        // /deathsummon: invoca jefes y mobs del modo Hellish
        PluginCommand summonCommand = getServer().getPluginCommand("deathsummon");
        if (summonCommand != null) {
            dev.xautral.death.diff.DeathSummonCommand summoner =
                    new dev.xautral.death.diff.DeathSummonCommand(diffModeManager);
            summonCommand.setExecutor(summoner);
            summonCommand.setTabCompleter(summoner);
        }

        // /deathitems give [item] [jugador]
        dev.xautral.death.item.AngryWand angryWand = new dev.xautral.death.item.AngryWand(
                new org.bukkit.NamespacedKey(this, "angry_wand"));
        getServer().getPluginManager().registerEvents(
                new dev.xautral.death.item.AngryWandManager(this, angryWand), this);
        PluginCommand itemsCommand = getServer().getPluginCommand("deathitems");
        if (itemsCommand != null) {
        dev.xautral.death.item.DeathItemsCommand itemsExecutor =
                new dev.xautral.death.item.DeathItemsCommand(angryWand, totemOfLife);
            itemsCommand.setExecutor(itemsExecutor);
            itemsCommand.setTabCompleter(itemsExecutor);
        }

        getSLF4JLogger().info("Death habilitado.");
        // Instala el datapack de logros vanilla (idempotente: ya probado con probe)
        dev.xautral.death.diff.util.AdvancementUtil.ensureDatapack(this);
    }

    @Override
    public void onDisable() {
        if (diffModeManager != null) {
            diffModeManager.unregister();
        }
        getSLF4JLogger().info("Death deshabilitado.");
    }

    DiffModeManager diffModes() {
        return diffModeManager;
    }

    dev.xautral.death.lives.LivesManager lives() {
        return livesManager;
    }
}

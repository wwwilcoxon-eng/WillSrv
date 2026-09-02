package dev.willsrv.phaxi;

import org.bukkit.plugin.java.JavaPlugin;

public final class PhaxiPlugin extends JavaPlugin {

    private PhaxiManager manager;

    @Override
    public void onEnable() {
        manager = new PhaxiManager(this);
        manager.loadHomes();

        PhaxiCommand cmd = new PhaxiCommand(manager);
        var command = getServer().getPluginCommand("phaxi");
        if (command != null) {
            command.setExecutor(cmd);
            command.setTabCompleter(cmd);
        }
        getServer().getPluginManager().registerEvents(manager, this);
        getSLF4JLogger().info("Phaxi habilitado.");
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.cancelAll();
            manager.saveHomes();
        }
        getSLF4JLogger().info("Phaxi deshabilitado.");
    }
}

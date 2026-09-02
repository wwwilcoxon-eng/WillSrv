package dev.willsrv.restore;

import org.bukkit.plugin.java.JavaPlugin;

public final class RestorePlugin extends JavaPlugin {

    private RestoreManager manager;

    @Override
    public void onEnable() {
        manager = new RestoreManager(this);
        var cmd = getServer().getPluginCommand("restore");
        if (cmd != null) {
            RestoreCommand exec = new RestoreCommand(manager);
            cmd.setExecutor(exec);
            cmd.setTabCompleter(exec);
        }
        getSLF4JLogger().info("Restore habilitado - sin tracking de chunks (solo /restore chunk).");
    }

    @Override
    public void onDisable() {
        getSLF4JLogger().info("Restore deshabilitado.");
    }
}
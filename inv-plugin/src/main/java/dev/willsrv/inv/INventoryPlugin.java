package dev.willsrv.inv;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class INventoryPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(
                new ReadOnlyInventoryListener(), this);

        PluginCommand invCommand = getServer().getPluginCommand("inv");
        if (invCommand != null) {
            InvCommand executor = new InvCommand();
            invCommand.setExecutor(executor);
            invCommand.setTabCompleter(executor);
        }
        PluginCommand ecCommand = getServer().getPluginCommand("ec");
        if (ecCommand != null) {
            EcCommand executor = new EcCommand();
            ecCommand.setExecutor(executor);
            ecCommand.setTabCompleter(executor);
        }
        getSLF4JLogger().info("INventory habilitado.");
    }

    @Override
    public void onDisable() {
        getSLF4JLogger().info("INventory deshabilitado.");
    }
}
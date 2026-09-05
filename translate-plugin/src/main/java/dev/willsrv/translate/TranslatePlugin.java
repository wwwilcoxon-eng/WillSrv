package dev.willsrv.translate;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class TranslatePlugin extends JavaPlugin {

    private TranslateManager manager;
    private SuffixManager suffixManager;
    private CountryGui countryGui;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // Ensure config defaults
        if (!getConfig().contains("suffix-duration-seconds")) {
            getConfig().set("suffix-duration-seconds", 45);
        }
        if (!getConfig().contains("translate-enabled-by-default")) {
            getConfig().set("translate-enabled-by-default", true);
        }
        if (!getConfig().contains("provider")) {
            getConfig().set("provider", "mymemory");
        }
        saveConfig();

        manager = new TranslateManager(this);
        manager.load();

        suffixManager = new SuffixManager(this, manager);
        countryGui = new CountryGui(this, manager, suffixManager);

        TranslateCommand cmd = new TranslateCommand(manager, countryGui, suffixManager);
        var command = getServer().getPluginCommand("translate");
        if (command != null) {
            command.setExecutor(cmd);
            command.setTabCompleter(cmd);
        }

        Bukkit.getPluginManager().registerEvents(countryGui, this);
        Bukkit.getPluginManager().registerEvents(new ChatListener(this, manager), this);
        Bukkit.getPluginManager().registerEvents(new JoinListener(this, manager, suffixManager, countryGui), this);

        // For already online players (reload)
        Bukkit.getScheduler().runTaskLater(this, () -> {
            for (var p : Bukkit.getOnlinePlayers()) {
                if (manager.hasChosenLang(p)) suffixManager.applyTemporarySuffix(p);
            }
        }, 20L);

        getSLF4JLogger().info("Translate habilitado. Sufijo: {}s | {} idiomas", manager.getSuffixDurationSeconds(), Lang.values().length);
    }

    @Override
    public void onDisable() {
        if (manager != null) manager.save();
        if (suffixManager != null) suffixManager.removeAll();
        getSLF4JLogger().info("Translate deshabilitado.");
    }

    public TranslateManager getManager() { return manager; }
}

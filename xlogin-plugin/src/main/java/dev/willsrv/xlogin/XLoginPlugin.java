package dev.willsrv.xlogin;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;

public final class XLoginPlugin extends JavaPlugin {

    private PlayerStore store;
    private AuthManager authManager;
    private AuthListener authListener;
    private ChatModerator chatModerator;
    private int maxAttempts;
    private int minPasswordLength;
    private int maxPasswordLength;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        FileConfiguration config = getConfig();
        maxAttempts = Math.max(1, config.getInt("max-attempts", 3));
        minPasswordLength = Math.max(1, config.getInt("min-password-length", 4));
        maxPasswordLength = Math.max(minPasswordLength, config.getInt("max-password-length", 64));

        store = new PlayerStore(this);
        authManager = new AuthManager(this);
        authListener = new AuthListener(this);
        chatModerator = new ChatModerator();

        try {
            store.load();
        } catch (IOException e) {
            getSLF4JLogger().error("No se pudo cargar el almacen de jugadores", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        Bukkit.getPluginManager().registerEvents(authListener, this);
        Bukkit.getPluginManager().registerEvents(chatModerator, this);

        registerCommand("register");
        registerCommand("login");
        registerCommand("unregister");

        org.bukkit.command.PluginCommand modCommand = getCommand("mod");
        if (modCommand != null) {
            modCommand.setExecutor(chatModerator);
            modCommand.setTabCompleter(chatModerator);
        }

        getSLF4JLogger().info("xLogin habilitado: dialogos para 1.21.6+, chat para versiones anteriores.");
    }

    private void registerCommand(String name) {
        PluginCommand command = getCommand(name);
        if (command != null) {
            command.setExecutor(new AuthCommands(this));
        }
    }

    @Override
    public void onDisable() {
        getSLF4JLogger().info("xLogin deshabilitado.");
    }

    PlayerStore store() {
        return store;
    }

    AuthManager authManager() {
        return authManager;
    }

    int getMaxAttempts() {
        return maxAttempts;
    }

    int getMinPasswordLength() {
        return minPasswordLength;
    }

    int getMaxPasswordLength() {
        return maxPasswordLength;
    }

    boolean supportsDialogs(org.bukkit.entity.Player player) {
        return authListener.supportsDialogs(player);
    }
}

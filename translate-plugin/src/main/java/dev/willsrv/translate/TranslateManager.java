package dev.willsrv.translate;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TranslateManager {

    private final TranslatePlugin plugin;
    private final File dataFile;
    private final Map<UUID, Lang> langMap = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> enabledMap = new ConcurrentHashMap<>();

    // config values
    private int suffixDurationSeconds = 45;
    private boolean translateEnabledByDefault = true;

    public TranslateManager(TranslatePlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "players.yml");
    }

    public void load() {
        plugin.saveDefaultConfig();
        suffixDurationSeconds = plugin.getConfig().getInt("suffix-duration-seconds", 45);
        translateEnabledByDefault = plugin.getConfig().getBoolean("translate-enabled-by-default", true);

        if (!dataFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(dataFile);
        for (String key : cfg.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                String code = cfg.getString(key + ".lang");
                Lang lang = Lang.fromCode(code);
                if (lang != null) langMap.put(uuid, lang);
                boolean enabled = cfg.getBoolean(key + ".enabled", translateEnabledByDefault);
                enabledMap.put(uuid, enabled);
            } catch (IllegalArgumentException ignored) {}
        }
        plugin.getSLF4JLogger().info("Translate: cargados {} jugadores", langMap.size());
    }

    public void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<UUID, Lang> e : langMap.entrySet()) {
            String id = e.getKey().toString();
            cfg.set(id + ".lang", e.getValue().code());
            boolean enabled = enabledMap.getOrDefault(e.getKey(), translateEnabledByDefault);
            cfg.set(id + ".enabled", enabled);
        }
        // also save enabled for players that only have enabled but no lang?
        for (Map.Entry<UUID, Boolean> e : enabledMap.entrySet()) {
            if (!langMap.containsKey(e.getKey())) {
                cfg.set(e.getKey().toString() + ".enabled", e.getValue());
            }
        }
        try {
            plugin.getDataFolder().mkdirs();
            cfg.save(dataFile);
        } catch (IOException ex) {
            plugin.getSLF4JLogger().error("No se pudo guardar players.yml", ex);
        }
    }

    public void saveAsync() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::save);
    }

    public Lang getLang(Player p) {
        Lang l = langMap.get(p.getUniqueId());
        if (l != null) return l;
        // Try auto detect from client locale as suggestion but don't persist yet
        try {
            String locale = p.getLocale(); // Paper API
            Lang detected = Lang.fromCode(locale);
            if (detected != null) return detected;
        } catch (Throwable ignored) {}
        return Lang.defaultLang();
    }

    public boolean hasChosenLang(Player p) {
        return langMap.containsKey(p.getUniqueId());
    }

    public String getLangCode(Player p) {
        return getLang(p).code();
    }

    public void setLang(Player p, Lang lang) {
        langMap.put(p.getUniqueId(), lang);
        // default enabled if not set
        enabledMap.putIfAbsent(p.getUniqueId(), translateEnabledByDefault);
        saveAsync();
    }

    public boolean isTranslateEnabled(Player p) {
        return enabledMap.getOrDefault(p.getUniqueId(), translateEnabledByDefault);
    }

    public void setTranslateEnabled(Player p, boolean enabled) {
        enabledMap.put(p.getUniqueId(), enabled);
        saveAsync();
    }

    public int getSuffixDurationSeconds() { return suffixDurationSeconds; }

    public void reloadConfigValues() {
        plugin.reloadConfig();
        suffixDurationSeconds = plugin.getConfig().getInt("suffix-duration-seconds", 45);
        translateEnabledByDefault = plugin.getConfig().getBoolean("translate-enabled-by-default", true);
    }
}

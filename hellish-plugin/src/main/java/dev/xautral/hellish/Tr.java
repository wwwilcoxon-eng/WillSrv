package dev.xautral.hellish;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import java.io.File;

public final class Tr {
    private Tr() {}
    public static boolean isEnglish(Player p) {
        if (p == null) return false;
        try {
            File f = new File("plugins/Translate/players.yml");
            if (f.exists()) {
                YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
                String code = cfg.getString(p.getUniqueId().toString() + ".lang");
                if (code != null) return "en".equalsIgnoreCase(code);
            }
        } catch (Throwable ignored) {}
        try { String loc = p.getLocale(); if (loc != null) return loc.toLowerCase().startsWith("en"); } catch (Throwable ignored) {}
        return false;
    }
    public static String t(Player p, String es, String en) { return isEnglish(p) ? en : es; }
}

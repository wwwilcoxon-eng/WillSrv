package dev.willsrv.translate;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * API pública para que otros plugins consulten el idioma del jugador
 * sin depender directamente del TranslatePlugin en compilación.
 * Usa reflexión/file fallback para evitar NoClassDefFound si Translate no está.
 *
 * Otros plugins deben copiar el helper I18n o usar reflexión para llamar aquí.
 */
public final class TranslateAPI {

    private TranslateAPI() {}

    /** Obtiene código de idioma del jugador (es, en, pt...) */
    public static String getLangCode(Player player) {
        if (player == null) return "es";
        // Intenta via plugin cargado
        try {
            Plugin pl = Bukkit.getPluginManager().getPlugin("Translate");
            if (pl instanceof TranslatePlugin tp && tp.getManager() != null) {
                return tp.getManager().getLangCode(player);
            }
        } catch (Throwable ignored) {}
        // Fallback: lee directamente players.yml
        try {
            File f = new File("plugins/Translate/players.yml");
            if (f.exists()) {
                YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
                String code = cfg.getString(player.getUniqueId().toString() + ".lang");
                if (code != null) {
                    Lang l = Lang.fromCode(code);
                    if (l != null) return l.code();
                    return code;
                }
            }
        } catch (Throwable ignored) {}
        // Fallback: locale del cliente
        try {
            String locale = player.getLocale();
            Lang l = Lang.fromCode(locale);
            if (l != null) return l.code();
            if (locale != null && locale.toLowerCase().startsWith("en")) return "en";
        } catch (Throwable ignored) {}
        return "es";
    }

    public static boolean isEnglish(Player player) {
        return "en".equalsIgnoreCase(getLangCode(player));
    }

    public static boolean isSpanish(Player player) {
        String c = getLangCode(player);
        return "es".equalsIgnoreCase(c);
    }

    /** Traduce eligiendo cadena según idioma del viewer */
    public static String tr(Player viewer, String es, String en) {
        return isEnglish(viewer) ? en : es;
    }

    public static String tr(Player viewer, String es, String en, String pt, String fr, String de) {
        String code = getLangCode(viewer);
        return switch (code.toLowerCase()) {
            case "en" -> en;
            case "pt" -> pt != null ? pt : en;
            case "fr" -> fr != null ? fr : en;
            case "de" -> de != null ? de : en;
            default -> es;
        };
    }

    /** Devuelve Lang enum o default */
    public static Lang getLang(Player player) {
        String code = getLangCode(player);
        Lang l = Lang.fromCode(code);
        return l != null ? l : Lang.defaultLang();
    }

    public static boolean isTranslateEnabled(Player player) {
        try {
            Plugin pl = Bukkit.getPluginManager().getPlugin("Translate");
            if (pl instanceof TranslatePlugin tp && tp.getManager() != null) {
                return tp.getManager().isTranslateEnabled(player);
            }
        } catch (Throwable ignored) {}
        return true;
    }
}

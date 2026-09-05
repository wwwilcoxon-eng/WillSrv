package dev.willsrv.translate;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

/**
 * Mensajes bilingües del propio plugin Translate.
 * Usa TranslateAPI para decidir idioma del receptor.
 */
public final class TranslateMessages {

    private TranslateMessages() {}

    public static boolean isEnglish(Player p) { return TranslateAPI.isEnglish(p); }

    public static Component prefix(Player viewer) {
        return Component.text("[" + tr(viewer, "Translate", "Translate") + "] ", NamedTextColor.GOLD);
    }

    public static String tr(Player p, String es, String en) { return TranslateAPI.tr(p, es, en); }

    // Mensajes comunes
    public static Component onlyPlayers(Player p) {
        return Component.text(tr(p, "Solo jugadores pueden usar /translate", "Only players can use /translate"), NamedTextColor.RED);
    }
    public static Component mustChooseCountry(Player p) {
        return Component.text(tr(p, "Debes elegir tu país primero: /translate select", "You must choose your country first: /translate select"), NamedTextColor.RED);
    }
    public static Component langNotRecognized(Player p, String code) {
        return Component.text(tr(p, "Idioma no reconocido: " + code, "Language not recognized: " + code), NamedTextColor.RED);
    }
    public static Component usageLang(Player p) {
        return Component.text(tr(p, "Usa: /translate lang <es|en|pt|fr|de|it|ru|...>", "Use: /translate lang <es|en|pt|fr|de|it|ru|...>"), NamedTextColor.GRAY);
    }
    public static Component translationOn(Player p, Lang lang) {
        String msg = tr(p,
                "Traducción ACTIVADA → " + lang.nativeName() + " " + lang.flag(),
                "Translation ENABLED → " + lang.nativeName() + " " + lang.flag());
        return Component.text(msg, NamedTextColor.GREEN);
    }
    public static Component translationOff(Player p) {
        return Component.text(tr(p, "Traducción DESACTIVADA", "Translation DISABLED"), NamedTextColor.RED);
    }
    public static Component willSeeOriginal(Player p) {
        return Component.text(tr(p, "Verás los mensajes originales sin traducir.", "You will see original messages without translation."), NamedTextColor.GRAY);
    }
    public static Component useToDisable(Player p) {
        return Component.text(tr(p, "Usa /translate off para desactivar.", "Use /translate off to disable."), NamedTextColor.GRAY);
    }
    public static Component langChanged(Player p, Lang lang) {
        String msg = tr(p, "✔ Idioma cambiado a ", "✔ Language changed to ") + lang.flag() + " " + lang.nativeName() + " " + lang.displaySuffix();
        return Component.text(msg, NamedTextColor.GREEN);
    }
    public static Component chatsWillTranslate(Player p, Lang lang) {
        return Component.text(tr(p, "Los chats se traducirán a " + lang.nativeName(), "Chats will be translated to " + lang.nativeName()), NamedTextColor.GRAY);
    }
}

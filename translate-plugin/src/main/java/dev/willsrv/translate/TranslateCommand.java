package dev.willsrv.translate;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public final class TranslateCommand implements CommandExecutor, TabCompleter {

    private final TranslateManager manager;
    private final CountryGui gui;
    private final SuffixManager suffixManager;

    public TranslateCommand(TranslateManager manager, CountryGui gui, SuffixManager suffixManager) {
        this.manager = manager;
        this.gui = gui;
        this.suffixManager = suffixManager;
    }

    private boolean en(Player p) { return TranslateAPI.isEnglish(p); }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can use /translate / Solo jugadores pueden usar /translate");
            return true;
        }
        boolean isEn = en(player);

        if (args.length == 0) {
            boolean current = manager.isTranslateEnabled(player);
            manager.setTranslateEnabled(player, !current);
            String onOff = !current ? (isEn ? "ON ✔" : "ON ✔") : (isEn ? "OFF ✘" : "OFF ✘");
            player.sendMessage(Component.text("§8[§6Translate§8] " + (isEn ? "Translation: " : "Traducción: "), NamedTextColor.GRAY)
                    .append(Component.text(onOff, !current ? NamedTextColor.GREEN : NamedTextColor.RED, TextDecoration.BOLD)));
            player.sendMessage(Component.text((isEn ? "Current language: " : "Idioma actual: "), NamedTextColor.GRAY)
                    .append(Component.text(manager.getLang(player).displayNameFull(), NamedTextColor.YELLOW))
                    .append(Component.text(" " + manager.getLang(player).displaySuffix(), NamedTextColor.GRAY)));
            if (!current) {
                player.sendMessage(Component.text(isEn ? "Chats will be translated to " + manager.getLang(player).nativeName() : "Los chats se traducirán a " + manager.getLang(player).nativeName(), NamedTextColor.GRAY));
            }
            sendHelp(player);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "on", "enable", "enabled", "activar", "si" -> {
                manager.setTranslateEnabled(player, true);
                player.sendMessage(TranslateMessages.translationOn(player, manager.getLang(player)));
                player.sendMessage(Component.text(isEn ? "Use /translate off to disable." : "Usa /translate off para desactivar.", NamedTextColor.GRAY));
            }
            case "off", "disable", "disabled", "desactivar", "no" -> {
                manager.setTranslateEnabled(player, false);
                player.sendMessage(TranslateMessages.translationOff(player));
                player.sendMessage(Component.text(isEn ? "You will see original messages." : "Verás los mensajes originales sin traducir.", NamedTextColor.GRAY));
            }
            case "auto" -> {
                // Detecta del cliente y lo aplica
                try {
                    String locale = player.getLocale();
                    Lang detected = Lang.fromCode(locale);
                    if (detected != null) {
                        manager.setLang(player, detected);
                        suffixManager.applyTemporarySuffix(player);
                        player.sendMessage(Component.text((isEn ? "Auto-detected: " : "Auto-detectado: ") + detected.flag() + " " + detected.nativeName() + " " + detected.displaySuffix(), NamedTextColor.GREEN));
                    } else {
                        player.sendMessage(Component.text(isEn ? "Could not detect language from client (" + locale + ")" : "No se pudo detectar idioma del cliente (" + locale + ")", NamedTextColor.RED));
                    }
                } catch (Throwable t) {
                    player.sendMessage(Component.text("Error detecting locale", NamedTextColor.RED));
                }
            }
            case "select", "gui", "menu", "pais", "country", "idioma", "lang", "language", "choose" -> {
                if (args.length >= 2) {
                    String code = args[1];
                    Lang lang = Lang.fromCode(code);
                    if (lang == null) {
                        player.sendMessage(TranslateMessages.langNotRecognized(player, code));
                        player.sendMessage(TranslateMessages.usageLang(player));
                        listLangs(player);
                        return true;
                    }
                    manager.setLang(player, lang);
                    player.sendMessage(TranslateMessages.langChanged(player, lang));
                    suffixManager.applyTemporarySuffix(player);
                    // after change, ask toggle
                    boolean enabled = manager.isTranslateEnabled(player);
                    Component prompt = Component.text(isEn ? "Translation for " + lang.nativeName() + " is " : "Traducción para " + lang.nativeName() + " está ", NamedTextColor.GRAY)
                            .append(Component.text(enabled ? "ON" : "OFF", enabled ? NamedTextColor.GREEN : NamedTextColor.RED));
                    player.sendMessage(prompt);
                    Component btns = Component.text("  ")
                            .append(Component.text("[ON]", NamedTextColor.GREEN, TextDecoration.BOLD).clickEvent(ClickEvent.runCommand("/translate on")))
                            .append(Component.text(" "))
                            .append(Component.text("[OFF]", NamedTextColor.RED, TextDecoration.BOLD).clickEvent(ClickEvent.runCommand("/translate off")));
                    player.sendMessage(btns);
                    return true;
                }
                gui.open(player);
            }
            case "help", "?", "ayuda" -> sendHelp(player);
            case "status", "info", "estado" -> {
                Lang lang = manager.getLang(player);
                boolean enabled = manager.isTranslateEnabled(player);
                player.sendMessage(Component.text("§8§m-----------------------------", NamedTextColor.DARK_GRAY));
                player.sendMessage(Component.text(isEn ? "  Translate - Status" : "  Translate - Estado", NamedTextColor.GOLD));
                player.sendMessage(Component.text((isEn ? " Language: " : " Idioma: ") + "§f" + lang.displayNameFull() + " " + lang.displaySuffix(), NamedTextColor.GRAY));
                player.sendMessage(Component.text((isEn ? " Translation: " : " Traducción: ") + (enabled ? "§aON" : "§cOFF"), enabled ? NamedTextColor.GREEN : NamedTextColor.RED));
                player.sendMessage(Component.text((isEn ? " Suffix lasts: " : " Sufijo dura: ") + "§e" + manager.getSuffixDurationSeconds() + "s", NamedTextColor.GRAY));
                player.sendMessage(Component.text("§8§m-----------------------------", NamedTextColor.DARK_GRAY));
                Component toggle = Component.text("  ")
                        .append(Component.text(enabled ? "[OFF]" : "[ON]", enabled ? NamedTextColor.RED : NamedTextColor.GREEN, TextDecoration.BOLD)
                                .clickEvent(ClickEvent.runCommand("/translate " + (enabled ? "off" : "on")))
                                .hoverEvent(Component.text(isEn ? "Click to " + (enabled ? "disable" : "enable") : "Click para " + (enabled ? "desactivar" : "activar"))))
                        .append(Component.text("  "))
                        .append(Component.text(isEn ? "[CHANGE COUNTRY]" : "[CAMBIAR PAÍS]", NamedTextColor.YELLOW, TextDecoration.BOLD)
                                .clickEvent(ClickEvent.runCommand("/translate select"))
                                .hoverEvent(Component.text(isEn ? "Open selector" : "Abrir selector")));
                player.sendMessage(toggle);
            }
            case "reload" -> {
                if (!player.hasPermission("translate.admin")) {
                    player.sendMessage(Component.text(isEn ? "No permission." : "Sin permiso.", NamedTextColor.RED));
                    return true;
                }
                manager.reloadConfigValues();
                TranslationService.clearCache();
                player.sendMessage(Component.text(isEn ? "Translate config reloaded." : "Translate config recargada.", NamedTextColor.GREEN));
            }
            default -> {
                Lang lang = Lang.fromCode(sub);
                if (lang != null) {
                    manager.setLang(player, lang);
                    player.sendMessage(TranslateMessages.langChanged(player, lang));
                    suffixManager.applyTemporarySuffix(player);
                } else {
                    player.sendMessage(Component.text(isEn ? "Usage: /translate <on|off|select|lang> [language]" : "Uso: /translate <on|off|select|lang> [idioma]", NamedTextColor.RED));
                    sendHelp(player);
                }
            }
        }
        return true;
    }

    private void sendHelp(Player p) {
        boolean isEn = en(p);
        p.sendMessage(Component.text("§8§m-----------------------------", NamedTextColor.DARK_GRAY));
        if (isEn) {
            p.sendMessage(Component.text(" §6/translate on §7- Enable translation", NamedTextColor.GRAY));
            p.sendMessage(Component.text(" §6/translate off §7- Disable translation", NamedTextColor.GRAY));
            p.sendMessage(Component.text(" §6/translate auto §7- Auto-detect from client", NamedTextColor.GRAY));
            p.sendMessage(Component.text(" §6/translate select §7- Choose your country/language", NamedTextColor.GRAY));
            p.sendMessage(Component.text(" §6/translate lang <es|en|pt|...>", NamedTextColor.GRAY).append(Component.text(" §7- Change language directly", NamedTextColor.GRAY)));
            p.sendMessage(Component.text(" §6/translate status §7- Show your settings", NamedTextColor.GRAY));
        } else {
            p.sendMessage(Component.text(" §6/translate on §7- Activa traducción", NamedTextColor.GRAY));
            p.sendMessage(Component.text(" §6/translate off §7- Desactiva traducción", NamedTextColor.GRAY));
            p.sendMessage(Component.text(" §6/translate auto §7- Auto-detectar del cliente", NamedTextColor.GRAY));
            p.sendMessage(Component.text(" §6/translate select §7- Elige tu país/idioma", NamedTextColor.GRAY));
            p.sendMessage(Component.text(" §6/translate lang <es|en|pt|...>", NamedTextColor.GRAY).append(Component.text(" §7- Cambia idioma directo", NamedTextColor.GRAY)));
            p.sendMessage(Component.text(" §6/translate status §7- Ver tu configuración", NamedTextColor.GRAY));
        }
        Component line = Component.text(isEn ? "§7TAB: " : "§7TAB: ", NamedTextColor.GRAY)
                .append(Component.text("[ON]", NamedTextColor.GREEN).clickEvent(ClickEvent.runCommand("/translate on")))
                .append(Component.text(" "))
                .append(Component.text("[OFF]", NamedTextColor.RED).clickEvent(ClickEvent.runCommand("/translate off")))
                .append(Component.text(" "))
                .append(Component.text(isEn ? "[COUNTRY]" : "[PAIS]", NamedTextColor.YELLOW).clickEvent(ClickEvent.runCommand("/translate select")));
        p.sendMessage(line);
        p.sendMessage(Component.text("§8§m-----------------------------", NamedTextColor.DARK_GRAY));
    }

    private void listLangs(Player p) {
        StringBuilder sb = new StringBuilder(p != null && en(p) ? "Languages: " : "Idiomas: ");
        for (Lang l : Lang.values()) sb.append(l.code()).append(", ");
        p.sendMessage(Component.text(sb.toString(), NamedTextColor.GRAY));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            for (String s : List.of("on", "off", "auto", "select", "lang", "status", "help")) {
                if (s.startsWith(prefix)) out.add(s);
            }
            for (Lang l : Lang.values()) {
                if (l.code().startsWith(prefix) || l.countryCode().toLowerCase().startsWith(prefix)) out.add(l.code());
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("lang")) {
            String prefix = args[1].toLowerCase();
            for (Lang l : Lang.values()) {
                if (l.code().startsWith(prefix)) out.add(l.code());
                if (l.countryCode().toLowerCase().startsWith(prefix)) out.add(l.countryCode().toLowerCase());
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("select")) {
            String prefix = args[1].toLowerCase();
            for (Lang l : Lang.values()) {
                if (l.code().startsWith(prefix)) out.add(l.code());
            }
        }
        return out;
    }
}

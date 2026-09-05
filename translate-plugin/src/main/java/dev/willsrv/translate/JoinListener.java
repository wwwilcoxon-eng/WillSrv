package dev.willsrv.translate;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class JoinListener implements Listener {

    private final TranslatePlugin plugin;
    private final TranslateManager manager;
    private final SuffixManager suffixManager;
    private final CountryGui gui;

    public JoinListener(TranslatePlugin plugin, TranslateManager manager, SuffixManager suffixManager, CountryGui gui) {
        this.plugin = plugin;
        this.manager = manager;
        this.suffixManager = suffixManager;
        this.gui = gui;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;

            if (!manager.hasChosenLang(player)) {
                sendCountryPrompt(player);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline() && !manager.hasChosenLang(player)) {
                        gui.open(player);
                    }
                }, 20L);
            } else {
                Lang lang = manager.getLang(player);
                suffixManager.applyTemporarySuffix(player);
                boolean enabled = manager.isTranslateEnabled(player);
                boolean en = TranslateAPI.isEnglish(player);
                // Bienvenida bilingüe
                String welcome = en ? "Welcome back " : "Bienvenido ";
                String suffixLine = en ? "Suffix " : "Sufijo ";
                String transLine = en ? "Translation: " : "Traducción: ";
                player.sendMessage(Component.text("§8[§6Translate§8] §7" + welcome, NamedTextColor.GRAY)
                        .append(Component.text(player.getName(), NamedTextColor.YELLOW))
                        .append(Component.text(" " + lang.displaySuffix() + " §7(" + lang.nativeName() + ")", NamedTextColor.GRAY)));
                player.sendMessage(Component.text("§7" + transLine + (enabled ? "§aON" : "§cOFF") + " §7— ", NamedTextColor.GRAY)
                        .append(Component.text(en ? "use " : "usa ", NamedTextColor.GRAY))
                        .append(Component.text("/translate on|off", NamedTextColor.YELLOW).clickEvent(ClickEvent.runCommand("/translate " + (enabled ? "off" : "on"))).hoverEvent(Component.text(en ? "Click to toggle" : "Click para cambiar")) )
                        .append(Component.text(" §7— ", NamedTextColor.GRAY))
                        .append(Component.text(en ? "change country" : "cambiar país", NamedTextColor.YELLOW).clickEvent(ClickEvent.runCommand("/translate select")).hoverEvent(Component.text("/translate select"))));
                // Pregunta explícita de activar/desactivar según idioma
                sendTogglePrompt(player, lang, enabled);

                for (Player online : Bukkit.getOnlinePlayers()) {
                    if (online.equals(player)) continue;
                    boolean viewerEn = TranslateAPI.isEnglish(online);
                    String joined = viewerEn ? " joined " : " se unió ";
                    online.sendMessage(Component.text("§8[§6Translate§8] §e" + player.getName() + " §7" + joined + lang.flag() + " " + lang.displaySuffix(), NamedTextColor.GRAY));
                }
            }
        }, 10L);

        if (manager.hasChosenLang(player)) {
            Lang lang = manager.getLang(player);
            event.joinMessage(Component.text("§8[§a+§8] §f" + player.getName() + " " + lang.displaySuffix() + " §7" + (TranslateAPI.isEnglish(player) ? "joined" : "se unió"), NamedTextColor.GRAY));
        } else {
            event.joinMessage(Component.text("§8[§a+§8] §f" + player.getName() + " §7" + (TranslateAPI.isEnglish(player) ? "joined" : "se unió") + " §8[§e?§8]", NamedTextColor.GRAY));
        }
    }

    private void sendCountryPrompt(Player player) {
        String locale = "es";
        try { locale = player.getLocale(); } catch (Throwable ignored) {}
        Lang detected = Lang.fromCode(locale);
        String sug = detected != null ? detected.nativeName() + " " + detected.flag() : "Español 🇪🇸";
        boolean en = detected != null && "en".equalsIgnoreCase(detected.code());

        player.sendMessage(Component.text("§8§m--------------------------------", NamedTextColor.DARK_GRAY));
        if (en) {
            player.sendMessage(Component.text("  §6§lWelcome! §7Choose your country", NamedTextColor.GOLD, TextDecoration.BOLD));
            player.sendMessage(Component.text("  §7Your suffix §f[COUNTRY] §7will show for §e" + manager.getSuffixDurationSeconds() + "s", NamedTextColor.GRAY));
            player.sendMessage(Component.text("  §7when you join, and chat will be translated.", NamedTextColor.GRAY));
        } else {
            player.sendMessage(Component.text("  §6§l¡Bienvenido! §7Selecciona tu país", NamedTextColor.GOLD, TextDecoration.BOLD));
            player.sendMessage(Component.text("  §7Tu sufijo §f[PAIS] §7se mostrará por §e" + manager.getSuffixDurationSeconds() + "s", NamedTextColor.GRAY));
            player.sendMessage(Component.text("  §7cuando te unas, y el chat se traducirá.", NamedTextColor.GRAY));
        }
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("  §7" + (en ? "Detected: " : "Detectado: ") + "§f" + sug + " §8(" + locale + ")", NamedTextColor.GRAY));
        Component btn = Component.text("  ")
                .append(Component.text(en ? "[🇺🇸 Choose Country]" : "[🇪🇸 Elegir País]", NamedTextColor.GREEN, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/translate select"))
                        .hoverEvent(Component.text(en ? "Click to open selector" : "Click para abrir selector")))
                .append(Component.text("  "))
                .append(Component.text(en ? "[Help]" : "[Ayuda]", NamedTextColor.YELLOW)
                        .clickEvent(ClickEvent.runCommand("/translate help")));
        player.sendMessage(btn);
        player.sendMessage(Component.text(en ? "§7 Also use §e/translate select §7or §e/pais" : "§7 También usa §e/translate select §7o §e/pais", NamedTextColor.GRAY));
        player.sendMessage(Component.text("§8§m--------------------------------", NamedTextColor.DARK_GRAY));
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1.1f);

        // Pregunta traducir on/off inmediatamente después
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && !manager.hasChosenLang(player)) {
                // aún sin elegir, no preguntar toggle hasta que elija
                return;
            }
            if (player.isOnline()) sendTogglePrompt(player, manager.getLang(player), manager.isTranslateEnabled(player));
        }, 30L);
    }

    /** Pregunta si quiere activar/desactivar traducción según su idioma */
    public void sendTogglePrompt(Player player, Lang lang, boolean currentlyEnabled) {
        boolean en = "en".equalsIgnoreCase(lang.code()) || TranslateAPI.isEnglish(player);
        player.sendMessage(Component.text("§8§m-----------------------------", NamedTextColor.DARK_GRAY));
        if (en) {
            player.sendMessage(Component.text("  §eDo you want chat translation?", NamedTextColor.YELLOW));
            player.sendMessage(Component.text("  §7Your language: §f" + lang.nativeName() + " " + lang.flag() + " §7→ chats will be translated to §f" + lang.nativeName(), NamedTextColor.GRAY));
            player.sendMessage(Component.text("  §7Current: " + (currentlyEnabled ? "§aON" : "§cOFF"), currentlyEnabled ? NamedTextColor.GREEN : NamedTextColor.RED));
        } else {
            player.sendMessage(Component.text("  §e¿Quieres activar la traducción del chat?", NamedTextColor.YELLOW));
            player.sendMessage(Component.text("  §7Tu idioma: §f" + lang.nativeName() + " " + lang.flag() + " §7→ los chats se traducirán a §f" + lang.nativeName(), NamedTextColor.GRAY));
            player.sendMessage(Component.text("  §7Actual: " + (currentlyEnabled ? "§aON" : "§cOFF"), currentlyEnabled ? NamedTextColor.GREEN : NamedTextColor.RED));
        }
        Component buttons = Component.text("  ")
                .append(Component.text(en ? "[✔ ON]" : "[✔ ACTIVAR]", NamedTextColor.GREEN, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/translate on"))
                        .hoverEvent(Component.text(en ? "Enable translation" : "Activar traducción")))
                .append(Component.text("  "))
                .append(Component.text(en ? "[✘ OFF]" : "[✘ DESACTIVAR]", NamedTextColor.RED, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/translate off"))
                        .hoverEvent(Component.text(en ? "Disable translation" : "Desactivar traducción")))
                .append(Component.text("  "))
                .append(Component.text(en ? "[🌐 Change Country]" : "[🌐 Cambiar País]", NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.runCommand("/translate select"))
                        .hoverEvent(Component.text("/translate select")));
        player.sendMessage(buttons);
        player.sendMessage(Component.text(en ? "  §7Tip: use §e/translate on|off §7to toggle anytime" : "  §7Consejo: usa §e/translate on|off §7para cambiar en cualquier momento", NamedTextColor.GRAY));
        player.sendMessage(Component.text("§8§m-----------------------------", NamedTextColor.DARK_GRAY));
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7f, en ? 1.3f : 1.1f);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        suffixManager.onQuit(p);
        if (manager.hasChosenLang(p)) {
            Lang lang = manager.getLang(p);
            boolean en = TranslateAPI.isEnglish(p);
            event.quitMessage(Component.text("§8[§c-§8] §f" + p.getName() + " " + lang.displaySuffix() + " §7" + (en ? "left" : "salió"), NamedTextColor.GRAY));
        }
    }
}

package dev.willsrv.translate;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ChatListener implements Listener {

    private final TranslatePlugin plugin;
    private final TranslateManager manager;

    public ChatListener(TranslatePlugin plugin, TranslateManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAsyncChat(AsyncChatEvent event) {
        Player sender = event.getPlayer();
        Component originalMessage = event.message();
        String plain = PlainTextComponentSerializer.plainText().serialize(originalMessage);
        if (plain.isBlank()) return;

        // Don't translate commands
        if (plain.startsWith("/")) return;

        // Cancel original broadcast, we will handle per-viewer
        event.setCancelled(true);

        String sourceLang = manager.getLangCode(sender);
        Lang senderLangEnum = manager.getLang(sender);
        // Prepare display format: sender name with suffix? Keep sender suffix visible?
        // We'll keep original sender display name but add suffix temporarily if present
        String senderName = sender.getName();
        Lang senderLang = senderLangEnum;

        // Gather viewers: all online players + console
        // But also event has viewers set? We'll use online players
        // Need to do translation async then send sync

        // Group recipients by target lang
        Map<String, List<Player>> grouped = new HashMap<>();
        List<Player> noTranslate = new ArrayList<>();

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(sender)) {
                // sender always sees original?
                // Alternatively sender sees his original, no translation needed
                noTranslate.add(viewer);
                continue;
            }
            if (!manager.isTranslateEnabled(viewer)) {
                noTranslate.add(viewer);
                continue;
            }
            String target = manager.getLangCode(viewer);
            if (target.equalsIgnoreCase(sourceLang)) {
                noTranslate.add(viewer);
            } else {
                grouped.computeIfAbsent(target.toLowerCase(), k -> new ArrayList<>()).add(viewer);
            }
        }

        // Run async translation
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            // Build translated map
            Map<String, String> translatedByLang = new ConcurrentHashMap<>();
            for (String targetLang : grouped.keySet()) {
                String translated = TranslationService.translate(plain, sourceLang, targetLang);
                translatedByLang.put(targetLang, translated);
            }

            // Now send sync
            Bukkit.getScheduler().runTask(plugin, () -> {
                // Format for noTranslate: original
                Component originalFormatted = formatChat(sender, senderLang, plain, false, null);
                for (Player viewer : noTranslate) {
                    viewer.sendMessage(originalFormatted);
                }
                // For each translated group
                for (Map.Entry<String, List<Player>> e : grouped.entrySet()) {
                    String targetLang = e.getKey();
                    String translated = translatedByLang.getOrDefault(targetLang, plain);
                    boolean isTranslated = !translated.equals(plain);
                    // If translation failed and returned same as original, we still mark not translated? Send original
                    for (Player viewer : e.getValue()) {
                        Lang viewerLang = manager.getLang(viewer);
                        Component formatted = formatChat(sender, senderLang, translated, isTranslated, viewerLang);
                        viewer.sendMessage(formatted);
                    }
                }
                // Also send to console (original)
                Bukkit.getConsoleSender().sendMessage(PlainTextComponentSerializer.plainText().serialize(originalFormatted));
                plugin.getSLF4JLogger().info("[Translate] {} ({} -> ...): {}",
                        sender.getName(), sourceLang, plain);
            });
        });
    }

    private Component formatChat(Player sender, Lang senderLang, String message, boolean translated, Lang viewerLang) {
        // Format: [ES] <Sender> message
        // If translated, add indicator
        String senderSuffix = senderLang.displaySuffix(); // contains [ES] flag
        Component base = Component.text()
                .append(Component.text(senderSuffix + " "))
                .append(Component.text("<"))
                .append(Component.text(sender.getName()))
                .append(Component.text("> "))
                .append(Component.text(message))
                .build();
        if (translated && viewerLang != null) {
            // Append subtle tag
            Component tag = Component.text(" §8[Traducido " + senderLang.code().toUpperCase() + "→" + viewerLang.code().toUpperCase() + "]");
            base = base.append(tag);
        }
        return base;
    }
}

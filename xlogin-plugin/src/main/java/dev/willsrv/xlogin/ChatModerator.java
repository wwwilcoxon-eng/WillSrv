package dev.willsrv.xlogin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import io.papermc.paper.event.player.AsyncChatEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Moderacion de chat (/mod on|off, por defecto ON): todo mensaje que contenga
 * IPs, dominios web (.com, .net, etc.), TCP/UDP, Aternos, FalixNodes, Playit
 * u otras referencias de hosting/red sale OFUSCADO (&k) para que nadie pueda
 * leer ni copiar la publicidad.
 */
public final class ChatModerator implements Listener, CommandExecutor, TabCompleter {

    /** IPv4 clasica: 123.45.67.89 */
    private static final String IPV4 =
            "\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b";

    /** Dominios web: algo.com, server.net, playit.gg, etc. */
    private static final String DOMAIN =
            "\\b(?:[a-z0-9-]+\\.)+"
                    + "(com|net|org|io|gg|me|xyz|tk|ml|ga|cf|gq|es|cl|ar|mx|br|pe|co|"
                    + "info|top|online|store|site|app|dev|cc|tv|to|us|eu|ru|cn|jp|uk)\\b";

    /** Palabras de hosting/tuneles/red conocidos. */
    private static final String KEYWORDS =
            "\\b(?:aternos|exaroton|falixnodes?|falix|playit|minehut|hamachi|"
                    + "radmin|zero.?tier|ngrok|portforward(?:ing)?|tcp|udp|"
                    + "mi[- ]?ip|mi[- ]?puerto|forward\\s*puerto)\\b";

    /** Un solo patron combinado para censurar de una pasada. */
    private static final Pattern RULE =
            Pattern.compile("(?i)(" + IPV4 + ")|(" + DOMAIN + ")|(" + KEYWORDS + ")");

    /** Palabras clave buscadas sin separadores (anti "a t e r n o s"). */
    private static final List<String> COMPACT_KEYWORDS = List.of(
            "aternos", "exaroton", "falix", "playit", "minehut",
            "ngrok", "hamachi", "radmin");

    /** Las 3 iniciales de cada servicio (ate, exa, fal, pla...). */
    private static final List<String> KEYWORD_PREFIXES =
            COMPACT_KEYWORDS.stream()
                    .map(k -> k.substring(0, Math.min(3, k.length())))
                    .toList();

    private volatile boolean enabled = true;

    /** Ventana rodante de texto reciente por jugador (anti-evasion). */
    private final java.util.Map<java.util.UUID, StringBuilder> recentBuffer =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<java.util.UUID, Long> windowStarts =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Historial de mensajes recientes para detectar iniciales sueltas. */
    private record MsgPart(String text, long time) {
    }
    private final java.util.Map<java.util.UUID, java.util.concurrent.ConcurrentLinkedDeque<MsgPart>>
            msgHistory = new java.util.concurrent.ConcurrentHashMap<>();
    /** Jugadores marcados por escribir iniciales: todo censurado hasta aqui. */
    private final java.util.Map<java.util.UUID, Long> flaggedUntil =
            new java.util.concurrent.ConcurrentHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    // ---------------- Filtro de chat ----------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!enabled) {
            return;
        }
        Player player = event.getPlayer();
        if (player.hasPermission("xlogin.mod.bypass")) {
            return;
        }
        Component censored = filter(player, event.message());
        if (censored == null) {
            return;
        }
        // Cancelamos el mensaje original y emitimos la version censurada:
        // garantiza que la ofuscacion se muestre SIEMPRE
        event.setCancelled(true);
        Component linea = Component.text("<" + player.getName() + "> ",
                net.kyori.adventure.text.format.NamedTextColor.WHITE)
                .append(censored);
        org.bukkit.Bukkit.broadcast(linea);
        org.bukkit.Bukkit.getLogger().info("[xLogin] Mensaje CENSURADO (ofuscado en el chat) de "
                + player.getName());
    }

    /**
     * Recorre el componente y reemplaza cada coincidencia por su mismo texto
     * con la decoracion OBFUSCATED (&k) aplicada directamente como componente
     * Adventure; asi el cliente siempre lo renderiza ilegible.
     */
    static Component censor(Component component) {
        if (!(component instanceof TextComponent text)) {
            return censorChildren(component);
        }
        String content = text.content();
        Matcher matcher = RULE.matcher(content);
        List<Component> parts = new ArrayList<>();
        int last = 0;
        while (matcher.find()) {
            if (matcher.start() > last) {
                parts.add(Component.text(content.substring(last, matcher.start())));
            }
            parts.add(Component.text(matcher.group())
                    .decoration(TextDecoration.OBFUSCATED, true));
            last = matcher.end();
        }
        if (parts.isEmpty()) {
            // Sin coincidencias: solo seguir con los hijos
            return censorChildren(component);
        }
        if (last < content.length()) {
            parts.add(Component.text(content.substring(last)));
        }
        List<Component> children = new ArrayList<>(text.children());
        children.addAll(0, parts);
        return text.content("").children(children);
    }

    private static Component censorChildren(Component component) {
        boolean changed = false;
        List<Component> newChildren = new ArrayList<>();
        for (Component child : component.children()) {
            Component censored = censor(child);
            if (censored != child) {
                changed = true;
            }
            newChildren.add(censored);
        }
        if (!changed) {
            return component;
        }
        return component.children(newChildren);
    }

    /** Comandos que terminan mostrando texto en el chat. */
    private static final List<String> CHAT_COMMANDS = List.of(
            "msg", "w", "tell", "whisper", "m", "r", "reply",
            "me", "say", "tellraw");

    /**
     * Intercepta /msg, /me, /say, etc. para censurar tambien ahi.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChatCommand(org.bukkit.event.player.PlayerCommandPreprocessEvent event) {
        if (!enabled) {
            return;
        }
        Player player = event.getPlayer();
        if (player.hasPermission("xlogin.mod.bypass")) {
            return;
        }
        String message = event.getMessage();
        String[] parts = message.split(" ", 2);
        if (parts.length < 2) {
            return;
        }
        String root = parts[0].substring(1).toLowerCase(java.util.Locale.ROOT)
                .replace("minecraft:", "");
        if (!CHAT_COMMANDS.contains(root)) {
            return;
        }
        String text = root.equals("say")
                ? message.substring(parts[0].length() + 1)
                : parts[1];
        String target = null;
        boolean isDirectMsg = List.of("msg", "w", "tell", "whisper", "m", "r", "reply")
                .contains(root);
        if (isDirectMsg) {
            String[] sub = text.split(" ", 2);
            if (sub.length < 2) {
                return;
            }
            target = sub[0];
            text = sub[1];
        }

        Component censored = filter(player, Component.text(text));
        if (censored == null) {
            return; // nada sospechoso: el comando sigue normal
        }
        event.setCancelled(true);
        if (isDirectMsg) {
            Player recipient = org.bukkit.Bukkit.getPlayerExact(target == null ? "" : target);
            if (recipient == null) {
                player.sendMessage("§7No se encontro al jugador.");
                return;
            }
            recipient.sendMessage(Component.text("[De " + player.getName() + "] ")
                    .append(censored));
            player.sendMessage(Component.text("[Para " + recipient.getName() + "] ")
                    .append(censored));
        } else if (root.equals("me")) {
            org.bukkit.Bukkit.broadcast(Component.text("* " + player.getName() + " ",
                    net.kyori.adventure.text.format.NamedTextColor.WHITE)
                    .append(censored));
        } else {
            org.bukkit.Bukkit.broadcast(Component.text("[" + player.getName() + "] ",
                    net.kyori.adventure.text.format.NamedTextColor.WHITE)
                    .append(censored));
        }
        org.bukkit.Bukkit.getLogger().info("[xLogin] Mensaje CENSURADO (ofuscado en el chat) de "
                + player.getName());
    }

    /**
     * Devuelve la version censurada del texto, o null si no contiene nada
     * sospechoso. Actualiza tambien la ventana rodante anti-evasion.
     */
    private Component filter(Player player, Component original) {
        long nowMs = System.currentTimeMillis();
        String plain = net.kyori.adventure.text.serializer.plain
                .PlainTextComponentSerializer.plainText().serialize(original);

        // Jugador marcado por escribir iniciales sueltas: todo censurado
        Long flagged = flaggedUntil.get(player.getUniqueId());
        if (flagged != null && flagged > nowMs) {
            return Component.text(plain)
                    .decoration(TextDecoration.OBFUSCATED, true);
        }

        // Historial de mensajes recientes para detectar iniciales sueltas
        java.util.concurrent.ConcurrentLinkedDeque<MsgPart> history = msgHistory.computeIfAbsent(
                player.getUniqueId(), k -> new java.util.concurrent.ConcurrentLinkedDeque<>());
        history.addLast(new MsgPart(plain, nowMs));
        while (!history.isEmpty() && nowMs - history.peekFirst().time() > 30000) {
            history.pollFirst();
        }
        if (history.size() > 20) {
            history.pollFirst();
        }
        // Concatena los ultimos mensajes CORTOS (letras sueltas)
        StringBuilder shortSeq = new StringBuilder();
        for (java.util.Iterator<MsgPart> it = history.descendingIterator();
                it.hasNext(); ) {
            MsgPart part = it.next();
            String compactPart = part.text().replaceAll("[^A-Za-z0-9]", "");
            if (compactPart.length() <= 2) {
                shortSeq.insert(0, compactPart.toLowerCase(java.util.Locale.ROOT));
            } else {
                break; // un mensaje largo corta la secuencia de iniciales
            }
        }
        if (shortSeq.length() >= 3) {
            for (String prefix : KEYWORD_PREFIXES) {
                if (shortSeq.toString().contains(prefix)) {
                    // Escribio las iniciales de un servicio: marcado 60s
                    flaggedUntil.put(player.getUniqueId(), nowMs + 60000);
                    recentBuffer.remove(player.getUniqueId());
                    windowStarts.remove(player.getUniqueId());
                    player.sendMessage("§6[xLogin] §cDetectado intento de evasión. "
                            + "Tus mensajes serán censurados temporalmente.");
                    return Component.text(plain)
                            .decoration(TextDecoration.OBFUSCATED, true);
                }
            }
        }

        Component censored = censor(original);

        long nowMs2 = nowMs;
        StringBuilder window = recentBuffer.computeIfAbsent(player.getUniqueId(),
                k -> new StringBuilder());
        Long windowStart = windowStarts.get(player.getUniqueId());
        if (windowStart == null || nowMs2 - windowStart > 30000) {
            window.setLength(0);
            windowStart = nowMs2;
            windowStarts.put(player.getUniqueId(), windowStart);
        }
        window.append(plain);
        if (window.length() > 400) {
            window.delete(0, window.length() - 400);
        }
        String compactWindow = window.toString()
                .replaceAll("[^A-Za-z0-9]", "")
                .toLowerCase(java.util.Locale.ROOT);
        for (String keyword : COMPACT_KEYWORDS) {
            if (compactWindow.contains(keyword)) {
                censored = Component.text(plain)
                        .decoration(TextDecoration.OBFUSCATED, true);
                window.setLength(0);
                windowStarts.remove(player.getUniqueId());
                break;
            }
        }

        String compactMsg = plain.replaceAll("[^A-Za-z0-9]", "")
                .toLowerCase(java.util.Locale.ROOT);
        for (String keyword : COMPACT_KEYWORDS) {
            if (compactMsg.contains(keyword)) {
                censored = Component.text(plain)
                        .decoration(TextDecoration.OBFUSCATED, true);
                break;
            }
        }

        return censored == original ? null : censored;
    }

    // ---------------- Limpieza ----------------

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        java.util.UUID id = event.getPlayer().getUniqueId();
        recentBuffer.remove(id);
        windowStarts.remove(id);
        msgHistory.remove(id);
        flaggedUntil.remove(id);
    }

    // ---------------- Comando /mod ----------------

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§6[xLogin] §eModeracion de chat: "
                    + (enabled ? "§aACTIVADA" : "§cDESACTIVADA")
                    + "§7. Usa /mod <on|off>");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "on" -> {
                enabled = true;
                sender.sendMessage("§6[xLogin] §aModeracion ACTIVADA: IPs, dominios, "
                        + "TCP, Aternos, FalixNodes, Playit, etc. salen ofuscados.");
            }
            case "off" -> {
                enabled = false;
                sender.sendMessage("§6[xLogin] §cModeracion DESACTIVADA: el chat "
                        + "sale tal cual.");
            }
            default -> sender.sendMessage("§6[xLogin] §cUso: /mod <on|off>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String option : List.of("on", "off")) {
                if (option.startsWith(args[0].toLowerCase())) {
                    out.add(option);
                }
            }
        }
        return out;
    }
}

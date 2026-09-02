package dev.willsrv.xlogin;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;

public final class AuthListener implements Listener {

    private static final int DIALOG_PROTOCOL = 771; // 1.21.6+
    private static final Set<String> ALLOWED_COMMANDS = Set.of("register", "reg", "login", "l", "unregister", "unreg", "desregistrar");

    private final XLoginPlugin plugin;
    private boolean viaChecked;
    private Method viaGetApi;
    private Method viaGetVersion;

    AuthListener(XLoginPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        plugin.authManager().handleJoin(event.getPlayer());
        broadcastVersion(event.getPlayer());
    }

    private void broadcastVersion(Player player) {
        int protocol = protocolVersion(player);
        String name = protocolName(protocol);
        Component message = Msg.info(player.getName()
                + " entró con la versión " + name);
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            online.sendMessage(message);
        }
    }

    private int protocolVersion(Player player) {
        try {
            ensureVia();
            if (viaGetApi != null && viaGetVersion != null) {
                Object api = viaGetApi.invoke(null);
                Object version = viaGetVersion.invoke(api, player.getUniqueId());
                return ((Number) version).intValue();
            }
        } catch (Throwable ignored) {
            // ViaVersion no disponible
        }
        return -1;
    }

    private static String protocolName(int protocol) {
        return switch (protocol) {
            case 340 -> "1.12.2";
            case 404 -> "1.13.2";
            case 498 -> "1.14.4";
            case 578 -> "1.15.2";
            case 754 -> "1.16.5";
            case 757, 758 -> "1.18.2";
            case 761 -> "1.19.4";
            case 765 -> "1.20.4";
            case 766 -> "1.20.6";
            case 767 -> "1.21";
            case 768 -> "1.21.2/3";
            case 769 -> "1.21.4";
            case 770 -> "1.21.5";
            case 771 -> "1.21.6/7";
            case 772 -> "1.21.9";
            case 773 -> "1.21.10";
            case 774 -> "1.21.11";
            case 775 -> "26.1";
            case 776 -> "26.2";
            default -> protocol > 0 ? "desconocida (protocolo " + protocol + ")" : "desconocida";
        };
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.authManager().remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (plugin.authManager().hasPendingSession(uuid)) {
            event.setCancelled(true);
            String text = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(event.message());
            plugin.authManager().handleChatInput(event.getPlayer(), text);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!plugin.authManager().hasPendingSession(event.getPlayer().getUniqueId())) {
            return;
        }
        String root = event.getMessage().split(" ")[0].substring(1).toLowerCase();
        if (!ALLOWED_COMMANDS.contains(root)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Msg.error("Debes iniciar sesión antes de usar comandos."));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (!plugin.authManager().isAuthenticated(uuid)) {
            plugin.authManager().notifyActivity(event.getPlayer());
        }
        if (!plugin.authManager().hasPendingSession(uuid)) {
            return;
        }
        if (event.getFrom().getBlockX() != event.getTo().getBlockX()
                || event.getFrom().getBlockY() != event.getTo().getBlockY()
                || event.getFrom().getBlockZ() != event.getTo().getBlockZ()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player
                && plugin.authManager().hasPendingSession(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player
                && plugin.authManager().hasPendingSession(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player
                && plugin.authManager().hasPendingSession(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (plugin.authManager().hasPendingSession(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player
                && plugin.authManager().hasPendingSession(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (plugin.authManager().hasPendingSession(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (plugin.authManager().hasPendingSession(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    boolean supportsDialogs(Player player) {
        try {
            ensureVia();
            if (viaGetApi != null && viaGetVersion != null) {
                Object api = viaGetApi.invoke(null);
                Object version = viaGetVersion.invoke(api, player.getUniqueId());
                return ((Number) version).intValue() >= DIALOG_PROTOCOL;
            }
        } catch (Throwable ignored) {
            // ViaVersion no disponible o error: se asume cliente nativo moderno
        }
        return true;
    }

    private synchronized void ensureVia() throws ReflectiveOperationException {
        if (viaChecked) {
            return;
        }
        viaChecked = true;
        Class<?> viaClass = Class.forName("com.viaversion.viaversion.api.Via");
        viaGetApi = viaClass.getMethod("getAPI");
        viaGetVersion = viaGetApi.getReturnType().getMethod("getPlayerVersion", UUID.class);
    }
}

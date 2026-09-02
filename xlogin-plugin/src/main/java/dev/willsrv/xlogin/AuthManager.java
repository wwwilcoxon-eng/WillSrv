package dev.willsrv.xlogin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class AuthManager {

    private final XLoginPlugin plugin;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    private static final class Session {
        volatile boolean authenticated;
        volatile int attemptsLeft;
        volatile boolean pendingPrompt;
    }

    AuthManager(XLoginPlugin plugin) {
        this.plugin = plugin;
    }

    void handleJoin(Player player) {
        Session session = new Session();
        sessions.put(player.getUniqueId(), session);

        PlayerStore.StoredPlayer stored = plugin.store().get(player.getUniqueId());
        String curIp = player.getAddress() == null ? "" : player.getAddress().getHostString();
        boolean ipMatches = stored != null && plugin.store().ipMatches(player.getUniqueId(), curIp);
        // Si la IP es privada (playit / codespace NAT 127.x, 10.x, 192.168.x) la consideramos estable y hacemos autologin aunque cambie el último octeto
        boolean isPrivate = isPrivateIp(curIp);
        boolean autoLogin = false;
        if (stored != null) {
            if (ipMatches) autoLogin = true;
            else if (isPrivate) {
                // Para IPs privadas, si ya está registrado, no pedir contraseña (evita el bug del texture pack que cambia IP NAT)
                autoLogin = true;
                plugin.getSLF4JLogger().info("Auto-login por IP privada para {} (IP {} hash {} vs stored {})", player.getName(), curIp, "private", stored.ipHash().substring(0,8));
            }
        }

        if (autoLogin) {
            session.authenticated = true;
            sendLater(player, Msg.exito("Bienvenido de nuevo, " + player.getName() + ". Sesión restaurada."));
            return;
        }

        session.attemptsLeft = plugin.getMaxAttempts();
        session.pendingPrompt = true;
        boolean register = stored == null;
        sendLater(player, Msg.linea(register
                ? "Regístrate para jugar: usa el diálogo o escribe /register <contraseña>"
                : "Inicia sesión para jugar: usa el diálogo o escribe /login <contraseña>"));

        // El diálogo se muestra cuando el cliente termina de cargar (primer
        // paquete de movimiento) o tras 5 segundos de respaldo.
        Bukkit.getScheduler().runTaskLater(plugin, () -> tryPrompt(player), 100L);
    }

    void notifyActivity(Player player) {
        tryPrompt(player);
    }

    private void tryPrompt(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || !session.pendingPrompt || !player.isOnline()) {
            return;
        }
        if (session.authenticated) {
            session.pendingPrompt = false;
            return;
        }
        if (isResourcePackPending(player)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Session s = sessions.get(player.getUniqueId());
                if (s != null) s.pendingPrompt = true;
                tryPrompt(player);
            }, 60L);
            return;
        }
        session.pendingPrompt = false;
        prompt(player);
    }

    private boolean isResourcePackPending(Player player) {
        try {
            java.lang.reflect.Method m = player.getClass().getMethod("getResourcePackStatus");
            Object status = m.invoke(player);
            if (status == null) return false;
            String n = status.toString();
            if (n.equalsIgnoreCase("SUCCESSFULLY_LOADED") || n.equalsIgnoreCase("DECLINED") || n.equalsIgnoreCase("FAILED_DOWNLOAD") || n.equalsIgnoreCase("DISCARDED")) return false;
            return true;
        } catch (Throwable ignored) { return false; }
    }

    void prompt(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.authenticated || !player.isOnline()) {
            return;
        }
        boolean register = !plugin.store().isRegistered(player.getUniqueId());
        if (plugin.supportsDialogs(player)) {
            Dialogs.showAuthDialog(plugin, player, register, session.attemptsLeft);
        } else {
            player.sendMessage(Msg.linea(register
                    ? "Tu versión no soporta diálogos. Escribe en el chat la contraseña que quieras usar,"
                    : "Tu versión no soporta diálogos. Escribe en el chat tu contraseña,"));
            player.sendMessage(Msg.linea("o usa el comando "
                    + (register ? "/register <contraseña>" : "/login <contraseña>") + "."));
        }
    }

    void handleChatInput(Player player, String input) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.authenticated) {
            return;
        }
        processInput(player, session, input);
    }

    void handleCommandInput(Player player, String input) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            player.sendMessage(Msg.error("No tienes una sesión activa. Vuelve a entrar."));
            return;
        }
        if (session.authenticated) {
            player.sendMessage(Msg.error("Ya tienes la sesión iniciada."));
            return;
        }
        processInput(player, session, input);
    }

    private void processInput(Player player, Session session, String input) {
        boolean register = !plugin.store().isRegistered(player.getUniqueId());

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String password = input.trim();
            if (password.isEmpty()) {
                fail(player, session, register, "La contraseña no puede estar vacía.");
                return;
            }
            if (register && password.length() > plugin.getMaxPasswordLength()) {
                fail(player, session, true, "La contraseña es demasiado larga (máx "
                        + plugin.getMaxPasswordLength() + " caracteres).");
                return;
            }
            if (register && password.length() < plugin.getMinPasswordLength()) {
                fail(player, session, true, "La contraseña debe tener al menos "
                        + plugin.getMinPasswordLength() + " caracteres.");
                return;
            }

            PlayerStore.StoredPlayer stored = plugin.store().get(player.getUniqueId());
            if (stored != null) {
                boolean ok = CryptoUtil.verifyPbkdf2(password.toCharArray(), stored.passSalt(), stored.passHash());
                Bukkit.getScheduler().runTask(plugin, () -> finishLogin(player, session, ok));
            } else {
                String ip = player.getAddress() == null ? "" : player.getAddress().getHostString();
                plugin.store().register(player.getUniqueId(), player.getName(), password.toCharArray(), ip);
                Bukkit.getScheduler().runTask(plugin, () -> finishRegister(player, session));
            }
        });
    }

    void handleUnregister(Player player, String input) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String password = input.trim();
            PlayerStore.StoredPlayer stored = plugin.store().get(player.getUniqueId());
            if (stored == null) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        player.sendMessage(Msg.error("No tienes una cuenta registrada.")));
                return;
            }
            boolean ok = !password.isEmpty()
                    && CryptoUtil.verifyPbkdf2(password.toCharArray(), stored.passSalt(), stored.passHash());
            Bukkit.getScheduler().runTask(plugin, () -> finishUnregister(player, ok));
        });
    }

    private void finishUnregister(Player player, boolean ok) {
        if (!player.isOnline()) {
            return;
        }
        Session session = sessions.get(player.getUniqueId());
        boolean pending = session != null && !session.authenticated;

        if (!ok) {
            if (pending && session != null) {
                consumeAttempt(player, session);
            } else {
                player.sendMessage(Msg.error("Contraseña incorrecta."));
            }
            return;
        }

        // Eliminar cuenta y escribir data.json fuera del hilo principal (no bloquear el tick en IO de disco).
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin,
                () -> plugin.store().delete(player.getUniqueId()));
        if (pending && session != null) {
            session.attemptsLeft = plugin.getMaxAttempts();
            player.sendMessage(Msg.exito("Tu registro fue eliminado. Regístrate de nuevo."));
            prompt(player);
        } else {
            player.sendMessage(Msg.exito("Tu registro fue eliminado. La próxima vez que entres tendrás que registrarte."));
        }
    }

    private void fail(Player player, Session session, boolean register, String reason) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            player.sendMessage(Msg.error(reason + " Intenta de nuevo."));
            prompt(player);
        });
    }

    private void finishRegister(Player player, Session session) {
        if (!player.isOnline()) {
            return;
        }
        session.authenticated = true;
        player.sendMessage(Msg.exito("Cuenta registrada con éxito, " + player.getName() + ". Ya puedes jugar."));
    }

    private void finishLogin(Player player, Session session, boolean ok) {
        if (!player.isOnline()) {
            return;
        }
        if (ok) {
            session.authenticated = true;
            player.sendMessage(Msg.exito("Sesión iniciada correctamente. Bienvenido, " + player.getName() + "."));
        } else {
            consumeAttempt(player, session);
        }
    }

    private void consumeAttempt(Player player, Session session) {
        session.attemptsLeft--;
        if (session.attemptsLeft <= 0) {
            player.kick(Component.text("Demasiados intentos fallidos. Conéctate de nuevo para intentarlo.",
                    NamedTextColor.GOLD));
        } else {
            player.sendMessage(Msg.error("Contraseña incorrecta. Intentos restantes: " + session.attemptsLeft));
            prompt(player);
        }
    }

    boolean isAuthenticated(UUID uuid) {
        Session session = sessions.get(uuid);
        return session != null && session.authenticated;
    }

    boolean hasPendingSession(UUID uuid) {
        Session session = sessions.get(uuid);
        return session != null && !session.authenticated;
    }

    void remove(UUID uuid) {
        sessions.remove(uuid);
    }

    private void sendLater(Player player, Component message) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                player.sendMessage(message);
            }
        }, 5L);
    }

    private boolean isPrivateIp(String ip) {
        if (ip == null || ip.isBlank()) return false;
        ip = ip.trim();
        if (ip.startsWith("/")) ip = ip.substring(1);
        // quita puerto si hay
        int colon = ip.lastIndexOf(':');
        if (colon > 0 && ip.indexOf(':') == colon) { // solo un : => IPv4:port
            ip = ip.substring(0, colon);
        } else if (ip.startsWith("[")) {
            int close = ip.indexOf(']');
            if (close > 0) ip = ip.substring(1, close);
        }
        return ip.startsWith("127.") || ip.startsWith("10.") || ip.startsWith("192.168.") || ip.startsWith("172.16.") || ip.startsWith("172.17.") || ip.startsWith("172.18.") || ip.startsWith("172.19.") || ip.startsWith("172.20.") || ip.startsWith("172.21.") || ip.startsWith("172.22.") || ip.startsWith("172.23.") || ip.startsWith("172.24.") || ip.startsWith("172.25.") || ip.startsWith("172.26.") || ip.startsWith("172.27.") || ip.startsWith("172.28.") || ip.startsWith("172.29.") || ip.startsWith("172.30.") || ip.startsWith("172.31.") || ip.equals("::1") || ip.startsWith("fc") || ip.startsWith("fd");
    }
}

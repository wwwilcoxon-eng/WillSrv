package dev.willsrv.xautralmusic;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class XautralMusicPlugin extends JavaPlugin implements Listener {

    private ResourcePackManager packManager;
    private PackHttpServer httpServer;
    private XmeScriptManager xmeManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();

        packManager = new ResourcePackManager(this);
        httpServer = new PackHttpServer(this);
        xmeManager = new XmeScriptManager(this);

        // Genera pack al arrancar si hay música
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                boolean ok = packManager.generatePack();
                // carga XME siempre (aunque no haya pack)
                Bukkit.getScheduler().runTask(this, () -> xmeManager.load());
                if (ok) {
                    httpServer.start();
                    // solo consola — nunca al jugador
                    getSLF4JLogger().info("XautralMusic pack generado: {} ({} tracks) SHA1={} SHA256={}",
                            packManager.getPackFile().getName(), packManager.getLastTrackCount(),
                            packManager.getSha1(), packManager.getSha256());
                    getSLF4JLogger().info("URL pack: {} (host local {}:{})", packManager.getPublicUrl(), getConfig().getString("httpHost"), getConfig().getInt("httpPort"));
                    updateServerProperties();
                } else {
                    getSLF4JLogger().warn("XautralMusic: no hay audio en {}/{} - pon archivos y haz /xm reload", packManager.getMusicFolder(), packManager.getSfxFolder().getName());
                    httpServer.start();
                }
            } catch (Exception e) {
                getSLF4JLogger().error("Error generando pack", e);
            }
        });

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(xmeManager, this);
        var cmd = getCommand("xautralmusic");
        if (cmd != null) {
            MusicCommand exec = new MusicCommand(this);
            cmd.setExecutor(exec);
            cmd.setTabCompleter(exec);
            // alias /xm
            var alias = getCommand("xm");
            if (alias != null) { alias.setExecutor(exec); alias.setTabCompleter(exec); }
        }
        getSLF4JLogger().info("XautralMusic habilitado. Carpeta: {} | HTTP: {}:{}",
                getConfig().getString("musicFolder"), getConfig().getString("httpHost"), getConfig().getInt("httpPort"));
    }

    @Override
    public void onDisable() {
        if (httpServer != null) httpServer.stop();
        getSLF4JLogger().info("XautralMusic deshabilitado.");
    }

    public ResourcePackManager getPackManager() { return packManager; }
    public PackHttpServer getHttpServer() { return httpServer; }

    public void reloadPack() {
        reloadConfig();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                boolean ok = packManager.generatePack();
                Bukkit.getScheduler().runTask(this, () -> xmeManager.load());
                httpServer.restart();
                if (ok) {
                    getSLF4JLogger().info("Pack regenerado ({} tracks) SHA1={} URL={}", packManager.getLastTrackCount(), packManager.getSha1(), packManager.getPublicUrl());
                    getSLF4JLogger().info("(SHA privado — solo consola) SHA256={}", packManager.getSha256());
                    Bukkit.getScheduler().runTask(this, this::updateServerProperties);
                } else {
                    getSLF4JLogger().warn("No hay audio en {}/{}", packManager.getMusicFolder(), packManager.getSfxFolder().getName());
                }
            } catch (Exception e) {
                getSLF4JLogger().error("Error reload pack", e);
            }
        });
    }

    private void updateServerProperties() {
        if (!getConfig().getBoolean("updateServerProperties", true)) return;
        if (!packManager.isPackReady()) return;
        try {
            java.io.File propsFile = locateServerProperties();
            if (propsFile == null || !propsFile.exists()) {
                getSLF4JLogger().warn("No se encontró server.properties para actualizar (buscado en {} y worldContainer)", new java.io.File("server.properties").getAbsolutePath());
                return;
            }
            java.util.Properties props = new java.util.Properties();
            try (java.io.FileInputStream in = new java.io.FileInputStream(propsFile)) { props.load(in); }
            String url = packManager.getPublicUrl();
            String sha1 = packManager.getSha1();
            String prompt = getConfig().getString("prompt", "");
            // strip color codes § para server.properties
            String promptPlain = prompt.replaceAll("§[0-9a-fk-or]", "");
            // Paper espera JSON válido — usamos Gson para convertir a string JSON seguro
            String jsonPrompt;
            try {
                jsonPrompt = new com.google.gson.Gson().toJson(promptPlain);
                // Gson toJson devuelve "\"texto\"" ; StrictJsonParser acepta tanto string JSON como objeto {"text":...}
                // Lo dejamos como string JSON. Alternativa objeto: "{\"text\":\"...\"}"
                // Usamos objeto para soportar colores futuros si se quiere
                String esc = promptPlain.replace("\\", "\\\\").replace("\"", "\\\"");
                jsonPrompt = "{\"text\":\"" + esc + "\"}";
            } catch (Exception ex) {
                jsonPrompt = "\"" + promptPlain.replace("\"","\\\"") + "\"";
            }
            boolean required = getConfig().getBoolean("required", false);
            // Por defecto opcional (usuario lo pidió). Respeta config.
            props.setProperty("resource-pack", url);
            props.setProperty("resource-pack-sha1", sha1);
            props.setProperty("resource-pack-prompt", jsonPrompt);
            props.setProperty("require-resource-pack", String.valueOf(required));
            // resource-pack-id opcional uuid
            if (!props.containsKey("resource-pack-id") || props.getProperty("resource-pack-id","").isBlank()) {
                props.setProperty("resource-pack-id", java.util.UUID.randomUUID().toString());
            }
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(propsFile)) {
                props.store(out, "XautralMusic auto-update " + new java.util.Date());
            }
            getSLF4JLogger().info("server.properties actualizado: resource-pack={} sha1={} (se aplicará al reiniciar)", url, sha1.substring(0,8)+"...");
        } catch (Exception e) {
            getSLF4JLogger().warn("No se pudo actualizar server.properties: {}", e.getMessage());
        }
    }

    private java.io.File locateServerProperties() {
        java.io.File f1 = new java.io.File("server.properties");
        if (f1.exists()) return f1;
        try {
            java.io.File wc = getServer().getWorldContainer();
            java.io.File f2 = new java.io.File(wc, "server.properties");
            if (f2.exists()) return f2;
        } catch (Exception ignored) {}
        try {
            java.io.File f3 = new java.io.File(getDataFolder().getParentFile().getParentFile(), "server.properties");
            if (f3.exists()) return f3;
        } catch (Exception ignored) {}
        return null;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!getConfig().getBoolean("autoApplyOnJoin", true)) return;
        if (!packManager.isPackReady()) return;
        // Si el server ya va a enviar el pack vía server.properties, no dupliques (evita 2 descargas)
        if (isServerPackSame()) {
            getSLF4JLogger().info("Server ya envía resource-pack (server.properties), omitiendo envío por plugin para {}", e.getPlayer().getName());
            return;
        }
        Player p = e.getPlayer();
        // Si xLogin está presente y el jugador aún no está autenticado, espera a que lo esté (evita choque de diálogos)
        if (isXLoginPending(p)) {
            Bukkit.getScheduler().runTaskTimer(this, task -> {
                if (!p.isOnline()) { task.cancel(); return; }
                if (!isXLoginPending(p)) {
                    task.cancel();
                    Bukkit.getScheduler().runTaskLater(this, () -> {
                        if (p.isOnline() && packManager.isPackReady() && !isServerPackSame()) sendPack(p);
                    }, 40L);
                }
            }, 60L, 20L);
            Bukkit.getScheduler().runTaskLater(this, () -> {}, 600L);
            return;
        }
        int delay = getConfig().getInt("applyDelayTicks", 40);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline() && !isXLoginPending(p) && !isServerPackSame()) sendPack(p);
        }, delay);
    }

    private boolean isServerPackSame() {
        try {
            java.io.File f = locateServerProperties();
            if (f == null || !f.exists()) return false;
            java.util.Properties props = new java.util.Properties();
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) { props.load(in); }
            String serverUrl = props.getProperty("resource-pack", "").trim();
            if (serverUrl.isEmpty()) return false;
            String ourUrl = packManager.getPublicUrl();
            // compara sin escapar
            String a = serverUrl.replace("\\:", ":").replace("\\/", "/");
            String b = ourUrl.replace("\\:", ":").replace("\\/", "/");
            return a.equalsIgnoreCase(b);
        } catch (Throwable ignored) { return false; }
    }

    private boolean isXLoginPending(Player p) {
        try {
            org.bukkit.plugin.Plugin xlogin = Bukkit.getPluginManager().getPlugin("xLogin");
            if (xlogin == null) xlogin = Bukkit.getPluginManager().getPlugin("XLogin");
            if (xlogin == null) return false;
            // Intenta AuthManager.isAuthenticated / hasPendingSession vía reflexión para no hard-depender
            java.lang.reflect.Method mStore = xlogin.getClass().getMethod("store");
            Object store = mStore.invoke(xlogin);
            // PlayerStore no tiene isAuthenticated, AuthManager sí
            java.lang.reflect.Method mAuth = xlogin.getClass().getMethod("authManager");
            Object auth = mAuth.invoke(xlogin);
            java.lang.reflect.Method mHasPending = auth.getClass().getMethod("hasPendingSession", java.util.UUID.class);
            boolean pending = (boolean) mHasPending.invoke(auth, p.getUniqueId());
            // Si tiene sesión pendiente => aún no autenticado
            return pending;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public void sendPack(Player p) {
        if (!packManager.isPackReady()) {
            // no spam a jugador
            return;
        }
        String url = packManager.getPublicUrl();
        String sha1 = packManager.getSha1();
        String prompt = getConfig().getString("prompt", "¿Descargar música del servidor?");
        boolean required = getConfig().getBoolean("required", false);
        try {
            p.setResourcePack(url, sha1, required, net.kyori.adventure.text.Component.text(prompt));
            // todo privado solo consola
            getSLF4JLogger().info("Pack enviado a {} ({} tracks) -> {} (prompt privado)", p.getName(), packManager.getLastTrackCount(), url);
        } catch (Throwable t) {
            try { p.setResourcePack(url, sha1); } catch (Throwable ignored) {}
            getSLF4JLogger().warn("Fallback setResourcePack para {}: {}", p.getName(), t.getMessage());
        }
    }

    public void broadcastPack() {
        for (Player p : Bukkit.getOnlinePlayers()) sendPack(p);
    }

    @EventHandler
    public void onResourcePackStatus(PlayerResourcePackStatusEvent e) {
        Player p = e.getPlayer();
        // Solo consola, nunca chat
        getSLF4JLogger().info("[XautralMusic] ResourcePack {} -> {} (hash {})", p.getName(), e.getStatus(), e.getHash());
        if (e.getStatus() == PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD) {
            getSLF4JLogger().warn("Pack falló para {} — URL {} — revisa que http://0.0.0.0:8008 sea accesible vía {} (Codespace ya es https://...-8008.app.github.dev sin Playit)", p.getName(), packManager.getPublicUrl(), packManager.getPublicUrl());
        }
    }
}

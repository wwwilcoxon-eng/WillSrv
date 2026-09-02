package dev.willsrv.xautralmusic;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.concurrent.Executors;

public class PackHttpServer {

    private final JavaPlugin plugin;
    private HttpServer server;

    public PackHttpServer(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized void start() {
        if (server != null) return;
        String host = plugin.getConfig().getString("httpHost", "0.0.0.0");
        int port = plugin.getConfig().getInt("httpPort", 8008);
        try {
            InetSocketAddress addr = new InetSocketAddress(host, port);
            server = HttpServer.create(addr, 0);
            server.createContext("/pack.zip", new PackHandler());
            server.createContext("/", exchange -> {
                String msg = "XautralMusic - pack en /pack.zip\nTracks: " + plugin.getConfig().getString("musicFolder");
                byte[] b = msg.getBytes();
                exchange.sendResponseHeaders(200, b.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(b); }
            });
            server.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "XautralMusic-Http");
                t.setDaemon(true);
                return t;
            }));
            server.start();
            String publicUrl = new ResourcePackManager(plugin).getPublicUrl();
            // No intentes crear otro ResourcePackManager solo para URL, usa el existente si hay
            try { publicUrl = ((XautralMusicPlugin)plugin).getPackManager().getPublicUrl(); } catch (Throwable ignored) {}
            plugin.getSLF4JLogger().info("[XautralMusic] HTTP hosteando pack en http://{}:{}/pack.zip -> público: {}", host.equals("0.0.0.0") ? "0.0.0.0" : host, port, publicUrl);
            String codespace = System.getenv("CODESPACE_NAME");
            if (codespace != null && !codespace.isBlank()) {
                plugin.getSLF4JLogger().info("[XautralMusic] Codespace detectado: pack accesible vía {} sin Playit extra (puerto 8008 ya es público https)", publicUrl);
            } else if (host.equals("0.0.0.0")) {
                plugin.getSLF4JLogger().info("[XautralMusic] Pack local en 0.0.0.0:{} — para jugadores externos pon publicUrl con tu IP/dominio o usa el forwarding de tu host", port);
            }
        } catch (IOException e) {
            plugin.getSLF4JLogger().error("[XautralMusic] No se pudo iniciar HTTP en {}:{} - {}", host, port, e.getMessage());
            plugin.getSLF4JLogger().error("Cambia httpPort en plugins/XautralMusic/config.yml y haz /xm reload");
        }
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    public synchronized void restart() {
        stop();
        start();
    }

    private class PackHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String remote = exchange.getRemoteAddress() != null ? exchange.getRemoteAddress().toString() : "?";
            plugin.getSLF4JLogger().info("[XautralMusic] HTTP {} {} desde {}", method, exchange.getRequestURI(), remote);
            File pack = new File(plugin.getDataFolder(), "generated/pack.zip");
            if (!pack.exists()) {
                String msg = "Pack no generado aún - pon .ogg en " + plugin.getConfig().getString("musicFolder") + " y usa /xm reload";
                byte[] b = msg.getBytes();
                exchange.getResponseHeaders().set("Content-Type", "text/plain");
                exchange.sendResponseHeaders(404, b.length);
                try (OutputStream os = exchange.getResponseBody()) { if (!"HEAD".equalsIgnoreCase(method)) os.write(b); }
                return;
            }
            byte[] data = Files.readAllBytes(pack.toPath());
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"pack.zip\"");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
            exchange.getResponseHeaders().set("Content-Length", String.valueOf(data.length));
            if ("HEAD".equalsIgnoreCase(method)) {
                exchange.sendResponseHeaders(200, -1);
                exchange.getResponseBody().close();
                plugin.getSLF4JLogger().info("[XautralMusic] HEAD {} -> 200 ({} bytes) para {}", exchange.getRequestURI(), data.length, remote);
                return;
            }
            exchange.sendResponseHeaders(200, data.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(data); }
            plugin.getSLF4JLogger().info("[XautralMusic] GET {} -> 200 ({} bytes) para {}", exchange.getRequestURI(), data.length, remote);
        }
    }
}

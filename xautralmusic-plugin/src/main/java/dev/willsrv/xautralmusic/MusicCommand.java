package dev.willsrv.xautralmusic;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MusicCommand implements CommandExecutor, TabCompleter {

    private final XautralMusicPlugin plugin;

    public MusicCommand(XautralMusicPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("xautralmusic.admin")) {
            sender.sendMessage("§cSin permiso.");
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase() : "help";
        switch (sub) {
            case "reload" -> {
                sender.sendMessage("§6[XautralMusic] §7Regenerando pack...");
                plugin.reloadPack();
                // feedback retardado porque es async
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    var m = plugin.getPackManager();
                    if (m.isPackReady()) {
                        sender.sendMessage("§aPack regenerado: §e" + m.getLastTrackCount() + " §atracks §7SHA1 §8" + m.getSha1().substring(0, 8) + "... §7SHA256 §8" + m.getSha256().substring(0, 8) + "...");
                        sender.sendMessage("§7URL: §b" + m.getPublicUrl());
                        sender.sendMessage("§7Carpeta: §e" + m.getMusicFolder().getAbsolutePath());
                    } else {
                        sender.sendMessage("§cNo hay .ogg en " + m.getMusicFolder().getAbsolutePath());
                        sender.sendMessage("§7Pon archivos .ogg ahí y repite /xm reload");
                    }
                }, 200L);
            }
            case "apply", "send" -> {
                if (args.length >= 2) {
                    Player target = plugin.getServer().getPlayer(args[1]);
                    if (target == null) { sender.sendMessage("§cJugador no encontrado: " + args[1]); return true; }
                    plugin.sendPack(target);
                    sender.sendMessage("§aPack enviado a " + target.getName());
                } else {
                    plugin.broadcastPack();
                    sender.sendMessage("§aPack enviado a todos (" + plugin.getServer().getOnlinePlayers().size() + " jugadores)");
                }
            }
            case "status", "info" -> {
                var m = plugin.getPackManager();
                sender.sendMessage("§6§l[XautralMusic] Status");
                sender.sendMessage(" §7Carpeta: §e" + m.getMusicFolder().getAbsolutePath());
                sender.sendMessage(" §7Namespace: §e" + m.getNamespace() + (plugin.getConfig().getString("soundPrefix","").isEmpty()?"":" §7prefix §e"+plugin.getConfig().getString("soundPrefix")));
                sender.sendMessage(" §7Categorías: §e" + String.join(", ", plugin.getConfig().getStringList("categories")));
                try {
                    var oggsR = m.collectOggsForStatus();
                    sender.sendMessage(" §7.ogg encontrados (recursivo): §e" + oggsR.size());
                    for (File f : oggsR) {
                        String rel = m.getMusicFolder().toPath().relativize(f.toPath()).toString().replace('\\','/');
                        String[] info = m.resolveSoundInfoForStatus(f);
                        sender.sendMessage("  §8- §7" + rel + " §8=> §b" + m.getNamespace() + ":" + info[0] + " §8(" + (f.length()/1024) + "KB)");
                    }
                } catch (Exception ex) { sender.sendMessage(" §cError listando: "+ex.getMessage()); }
                sender.sendMessage(" §7Pack: §e" + m.getPackFile().getAbsolutePath() + (m.isPackReady() ? " §a(EXISTE)" : " §c(NO)"));
                if (m.isPackReady()) {
                    sender.sendMessage(" §7SHA1: §8" + m.getSha1());
                    sender.sendMessage(" §7SHA256: §8" + m.getSha256());
                    sender.sendMessage(" §7URL: §b" + m.getPublicUrl());
                    sender.sendMessage(" §7HTTP: §e" + plugin.getConfig().getString("httpHost") + ":" + plugin.getConfig().getInt("httpPort") + "/pack.zip");
                    String example = m.getNamespace() + ":" + (plugin.getConfig().getString("soundPrefix","").isEmpty() ? "" : plugin.getConfig().getString("soundPrefix")+".") + "weapons.shot";
                    sender.sendMessage(" §7Usa: §e/playsound " + example + " master @p");
                }
            }
            case "help", "?" -> sendHelp(sender);
            default -> sendHelp(sender);
        }
        return true;
    }

    private void sendHelp(CommandSender s) {
        s.sendMessage("§6[XautralMusic] §7Comandos:");
        s.sendMessage(" §e/xm reload §7- Escanea carpeta .ogg, genera pack.zip, calcula SHA1/SHA256 y hostea");
        s.sendMessage(" §e/xm apply [jugador] §7- Envía el pack al jugador/todos");
        s.sendMessage(" §e/xm status §7- Muestra carpeta, tracks, hashes y URL");
        s.sendMessage("§7Flujo: pon .ogg en §e" + plugin.getPackManager().getMusicFolder().getAbsolutePath() + " §7→ §e/xm reload §7→ jugadores aceptan pack al entrar");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String c : List.of("reload", "apply", "status", "help")) if (c.startsWith(args[0].toLowerCase())) out.add(c);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("apply")) {
            for (Player p : plugin.getServer().getOnlinePlayers()) if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) out.add(p.getName());
        }
        return out;
    }
}

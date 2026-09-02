package dev.willsrv.restore;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;

/**
 * /restore chunk [mundo x z] — regenera un chunk a su terreno natural.
 * Sin tracking: solo restaura cuando se llama explicitamente (anti-lag).
 */
public final class RestoreCommand implements CommandExecutor, TabCompleter {

    private final RestoreManager manager;

    public RestoreCommand(RestoreManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("restore.admin")) {
            sender.sendMessage("§cSin permiso.");
            return true;
        }
        if (args.length < 1 || !args[0].equalsIgnoreCase("chunk")) {
            sender.sendMessage("§7/restore chunk [mundo] <x> <z>  — restaura el chunk a vanilla");
            sender.sendMessage("§7Si omites mundo/coords usa tu chunk actual.");
            return true;
        }

        World w;
        int cx, cz;
        if (args.length >= 4) {
            w = Bukkit.getWorld(args[1]);
            if (w == null) {
                sender.sendMessage("§cMundo no encontrado: " + args[1]);
                return true;
            }
            try {
                cx = Integer.parseInt(args[2]);
                cz = Integer.parseInt(args[3]);
            } catch (NumberFormatException ex) {
                sender.sendMessage("§cCoordenadas de chunk invalidas (x z enteros).");
                return true;
            }
        } else if (args.length == 3) {
            w = Bukkit.getWorlds().get(0);
            try {
                cx = Integer.parseInt(args[1]);
                cz = Integer.parseInt(args[2]);
            } catch (NumberFormatException ex) {
                sender.sendMessage("§cCoordenadas de chunk invalidas. Uso: /restore chunk [mundo] <x> <z>");
                return true;
            }
        } else if (sender instanceof org.bukkit.entity.Player p) {
            var ch = p.getLocation().getChunk();
            w = ch.getWorld();
            cx = ch.getX();
            cz = ch.getZ();
        } else {
            sender.sendMessage("§cDesde consola: /restore chunk <mundo> <x> <z>");
            return true;
        }

        // Proteccion del spawn: no se restaura el chunk que contiene el spawn.
        int spawnCX = w.getSpawnLocation().getBlockX() >> 4;
        int spawnCZ = w.getSpawnLocation().getBlockZ() >> 4;
        if (cx == spawnCX && cz == spawnCZ) {
            sender.sendMessage("§cNo puedes restaurar el chunk del spawn.");
            return true;
        }

        boolean ok = manager.restoreChunkAt(w, cx, cz);
        sender.sendMessage((ok ? "§a" : "§c") + (ok ? "Chunk restaurado a vanilla (seed del mundo): "
                : "No se pudo restaurar: ") + w.getName() + " " + cx + "," + cz);
        if (ok) {
            sender.sendMessage("§7Terreno, estructuras y bloques regenerados como si nunca se hubiera modificado.");
        } else {
            sender.sendMessage("§cEste Paper (26.2) NO soporta la regeneracion de chunks via API.");
            sender.sendMessage("§7Para restaurar, borra manualmente la region del chunk (carpeta region/) o usa una herramienta tipo WorldEdit.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1 && "chunk".startsWith(args[0].toLowerCase())) {
            out.add("chunk");
        } else if (args.length == 2) {
            for (World w : Bukkit.getWorlds()) {
                if (w.getName().toLowerCase().startsWith(args[1].toLowerCase())) {
                    out.add(w.getName());
                }
            }
        }
        return out;
    }
}
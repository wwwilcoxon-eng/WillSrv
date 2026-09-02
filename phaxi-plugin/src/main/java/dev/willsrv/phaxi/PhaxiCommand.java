package dev.willsrv.phaxi;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class PhaxiCommand implements CommandExecutor, TabCompleter {

    private final PhaxiManager manager;

    public PhaxiCommand(PhaxiManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Solo jugadores.");
            return true;
        }
        if (args.length == 0) {
            sendHelp(player);
            return true;
        }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "set" -> {
                String name = args.length >= 2 ? args[1] : "casa";
                if (!name.matches("^[a-zA-Z0-9_\\-]{1,16}$")) {
                    player.sendMessage("§6[Phaxi] §cNombre invalido. Usa 1-16 letras/numeros/_/-");
                    return true;
                }
                manager.setHome(player, name);
            }
            case "accept" -> {
                manager.handleAccept(player);
            }
            case "deny", "denny", "decline", "cancel" -> {
                manager.handleDeny(player);
            }
            case "send" -> {
                if (args.length < 3) {
                    player.sendMessage("§6[Phaxi] §cUso: /phaxi send <jugador> <here | x y z [mundo]>");
                    return true;
                }
                String targetName = args[1];
                String destArg = args[2];
                String[] extra = new String[args.length - 3];
                System.arraycopy(args, 3, extra, 0, extra.length);
                manager.callSend(player, targetName, destArg, extra);
            }
            case "go" -> {
                String name = args.length >= 2 ? args[1] : null;
                manager.callTaxi(player, name);
            }
            case "list" -> {
                Map<String, Location> homes = manager.getHomes(player);
                if (homes.isEmpty()) {
                    player.sendMessage("§6[Phaxi] §cNo tienes casas. Usa §e/phaxi set [nombre]");
                } else {
                    player.sendMessage("§6[Phaxi] §aTus casas:");
                    for (Map.Entry<String, Location> e : homes.entrySet()) {
                        Location l = e.getValue();
                        player.sendMessage(" §7- §e" + e.getKey() + " §7" + l.getWorld().getName() + " " + String.format("%.0f, %.0f, %.0f", l.getX(), l.getY(), l.getZ()));
                    }
                }
            }
            case "del", "delete", "remove" -> {
                if (args.length < 2) {
                    player.sendMessage("§6[Phaxi] §cUsa §e/phaxi del <nombre>");
                    return true;
                }
                String name = args[1];
                if (manager.delHome(player, name)) {
                    player.sendMessage("§6[Phaxi] §aCasa '" + name + "' eliminada.");
                } else {
                    player.sendMessage("§6[Phaxi] §cNo tienes casa '" + name + "'.");
                }
            }
            case "help", "?" -> sendHelp(player);
            default -> {
                // alias: /phaxi <nombre> = go <nombre> o set si no existe?
                // tratamos como go si es nombre de casa
                if (manager.hasHome(player, sub)) {
                    manager.callTaxi(player, sub);
                } else {
                    sendHelp(player);
                }
            }
        }
        return true;
    }

    private void sendHelp(Player p) {
        p.sendMessage("§6[Phaxi] §7Comandos:");
        p.sendMessage(" §e/phaxi set [nombre] §7- Guarda tu casa actual (default: casa) y muestra coords");
        p.sendMessage(" §e/phaxi go [nombre] §7- Llama Phaxi (1 XP cada 30 bloques, confirma con click)");
        p.sendMessage(" §e/phaxi accept/deny §7- Acepta/deniega coste XP");
        p.sendMessage(" §e/phaxi send <jugador> <here|x y z [mundo]> §7- Envía Phaxi a otro (2 XP cada 35 bloques)");
        p.sendMessage(" §e/phaxi list §7- Lista tus casas");
        p.sendMessage(" §e/phaxi del <nombre> §7- Borra una casa");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("set", "go", "send", "accept", "deny", "list", "del", "help")) {
                if (s.startsWith(args[0].toLowerCase())) out.add(s);
            }
            if (sender instanceof Player p) {
                for (String n : manager.getHomes(p).keySet()) {
                    if (n.startsWith(args[0].toLowerCase())) out.add(n);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("go")) {
            if (sender instanceof Player p) {
                for (String n : manager.getHomes(p).keySet()) {
                    if (n.startsWith(args[1].toLowerCase())) out.add(n);
                }
            }
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("del") || args[0].equalsIgnoreCase("delete"))) {
            if (sender instanceof Player p) {
                for (String n : manager.getHomes(p).keySet()) {
                    if (n.startsWith(args[1].toLowerCase())) out.add(n);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("send")) {
            for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) out.add(p.getName());
        } else if (args.length == 3 && args[0].equalsIgnoreCase("send")) {
            if ("here".startsWith(args[2].toLowerCase())) out.add("here");
        }
        return out;
    }
}

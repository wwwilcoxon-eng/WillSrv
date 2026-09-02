package dev.willsrv.inv;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.List;

/**
 * /ec [jugador] — abre el ender chest de un jugador en modo solo lectura.
 */
public final class EcCommand implements CommandExecutor, TabCompleter {

    static final String TITLE_PREFIX = "Ender Chest de ";

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {
        Player viewer;
        if (sender instanceof Player p) {
            viewer = p;
        } else {
            sender.sendMessage(ChatColor.RED + "Solo los jugadores pueden usar /ec.");
            return true;
        }
        Player target = args.length >= 1
                ? Bukkit.getPlayerExact(args[0])
                : viewer;
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Jugador no encontrado: "
                    + (args.length >= 1 ? args[0] : ""));
            return true;
        }

        Inventory gui = Bukkit.createInventory(null, 27,
                Component.text(TITLE_PREFIX + target.getName()));
        gui.setContents(target.getEnderChest().getContents());

        viewer.openInventory(gui);
        viewer.sendMessage(ChatColor.GRAY + "Viendo el ender chest de "
                + ChatColor.GOLD + target.getName() + ChatColor.GRAY + ".");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String label, String[] args) {
        List<String> out = new ArrayList<>();
        String prefix = args.length > 0 ? args[args.length - 1].toLowerCase() : "";
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().toLowerCase().startsWith(prefix)) {
                out.add(player.getName());
            }
        }
        return out;
    }
}
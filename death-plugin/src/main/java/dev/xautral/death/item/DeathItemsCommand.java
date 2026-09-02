package dev.xautral.death.item;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * /deathitems give [item] [jugador] — con tab completion completa.
 */
public final class DeathItemsCommand implements CommandExecutor, TabCompleter {

    private final AngryWand wand;
    private final dev.xautral.death.lives.TotemOfLife totemOfLife;

    public DeathItemsCommand(AngryWand wand,
                             dev.xautral.death.lives.TotemOfLife totemOfLife) {
        this.wand = wand;
        this.totemOfLife = totemOfLife;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {
        if (args.length < 2 || !args[0].equalsIgnoreCase("give")) {
            sender.sendMessage("§6[Death] §eUso: /deathitems give <item> <jugador>");
            sender.sendMessage("§7Items: §eangry_wand§7, §etotem_of_life");
            return true;
        }
        String itemKey = args[1].toLowerCase();
        ItemStack toGive = null;
        if (itemKey.equals("angry_wand")) {
            toGive = wand.create();
        } else if (itemKey.equals("totem_of_life")) {
            toGive = totemOfLife.create();
        } else {
            sender.sendMessage("§6[Death] §cItem desconocido: " + args[1]);
            return true;
        }
        Player target;
        if (args.length >= 3) {
            target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                sender.sendMessage("§6[Death] §cJugador no encontrado: " + args[2]);
                return true;
            }
        } else if (sender instanceof Player self) {
            target = self;
        } else {
            sender.sendMessage("§6[Death] §cDesde consola indica el jugador: "
                    + "/deathitems give <item> <jugador>");
            return true;
        }

        var leftovers = target.getInventory().addItem(toGive);
        leftovers.values().forEach(rest ->
                target.getWorld().dropItemNaturally(target.getLocation(), rest));
        target.sendMessage("§6[Death] §4Has recibido §l" + itemKey + "§4!");
        if (sender != target) {
            sender.sendMessage("§6[Death] §aEntregada a " + target.getName() + ".");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            if ("give".startsWith(args[0].toLowerCase())) {
                out.add("give");
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            if ("angry_wand".startsWith(args[1].toLowerCase())) {
                out.add("angry_wand");
            }
            if ("totem_of_life".startsWith(args[1].toLowerCase())) {
                out.add("totem_of_life");
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase()
                        .startsWith(args[2].toLowerCase())) {
                    out.add(online.getName());
                }
            }
        }
        return out;
    }
}

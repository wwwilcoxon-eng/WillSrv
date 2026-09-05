package dev.willsrv.xlogin;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

final class AuthCommands implements org.bukkit.command.CommandExecutor {

    private final XLoginPlugin plugin;

    AuthCommands(XLoginPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Msg.error("Solo jugadores pueden usar este comando."));
            return true;
        }
        if (args.length < 1) {
            player.sendMessage(Msg.error("Uso: /" + label + " <contraseña>"));
            return true;
        }
        if (command.getName().equalsIgnoreCase("unregister")) {
            plugin.authManager().handleUnregister(player, args[0]);
        } else {
            plugin.authManager().handleCommandInput(player, args[0]);
        }
        return true;
    }
}

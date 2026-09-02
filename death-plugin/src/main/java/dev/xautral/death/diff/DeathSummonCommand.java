package dev.xautral.death.diff;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * /deathsummon <tipo> [jugador] : invoca jefes y mobs del modo Hellish.
 */
public final class DeathSummonCommand implements CommandExecutor, TabCompleter {

    private static final List<String> OPTIONS = List.of(
            "skeletonboss", "huskboss", "phantom",
            "endphantom", "creeper", "blaze", "breeze");

    private final DiffModeManager manager;
    private final Random random = new Random();

    public DeathSummonCommand(DiffModeManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§6[Death] §eUso: /deathsummon <"
                    + String.join("|", OPTIONS) + "> [jugador]");
            return true;
        }
        Player target;
        if (args.length >= 2) {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage("§6[Death] §cJugador no encontrado: " + args[1]);
                return true;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage("§6[Death] §cDesde consola indica un jugador: "
                    + "/deathsummon <tipo> <jugador>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "skeletonboss" -> {
                manager.summonSkeletonBoss(target);
                sender.sendMessage("§6[Death] §e☠ Jefe Esqueleto invocado cerca de §a"
                        + target.getName() + "§e.");
            }
            case "huskboss" -> {
                manager.summonHuskBoss(target);
                sender.sendMessage("§6[Death] §e☠ Jefe Husk invocado cerca de §a"
                        + target.getName() + "§e.");
            }
            case "phantom" -> summonPhantom(target, false);
            case "endphantom" -> summonPhantom(target, true);
            case "creeper" -> summonCreeper(target);
            case "blaze" -> summonBlaze(target);
            case "breeze" -> summonBreeze(target);
            default -> sender.sendMessage("§6[Death] §cTipo desconocido: " + args[0]
                    + " §7(" + String.join("|", OPTIONS) + ")");
        }
        return true;
    }

    private void summonPhantom(Player target, boolean end) {
        Location loc = target.getLocation().add(
                (random.nextDouble() - 0.5) * 20, 14 + random.nextInt(6),
                (random.nextDouble() - 0.5) * 20);
        if (!loc.getChunk().isLoaded()) {
            return;
        }
        org.bukkit.entity.Phantom phantom = (org.bukkit.entity.Phantom)
                loc.getWorld().spawnEntity(loc, EntityType.PHANTOM);
        phantom.addScoreboardTag("dm_hellish_phantom");
        if (end) {
            phantom.addScoreboardTag("dm_end_phantom");
            phantom.setCustomName("§5End Phantom");
            phantom.setCustomNameVisible(false);
        }
        try {
            phantom.setSize(2);
        } catch (Throwable ignored) {
        }
        phantom.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey(manager.plugin(), "phantom_bites"),
                org.bukkit.persistence.PersistentDataType.INTEGER, 0);
        manager.buffPhantom(phantom);
        manager.markSpawned(phantom);
        phantom.setTarget(target);
        target.sendMessage("§6[Death] §5Phantom Hellish invocado. ¡No le pierdas de vista!");
    }

    private void summonCreeper(Player target) {
        Location loc = target.getLocation().add(
                (random.nextDouble() - 0.5) * 10, 0, (random.nextDouble() - 0.5) * 10);
        if (!loc.getChunk().isLoaded()) {
            return;
        }
        org.bukkit.entity.Creeper creeper = (org.bukkit.entity.Creeper)
                loc.getWorld().spawnEntity(loc, EntityType.CREEPER);
        creeper.setPowered(true);
        var speed = creeper.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null) {
            speed.setBaseValue(speed.getBaseValue() * 1.55);
        }
        manager.markSpawned(creeper);
        creeper.setTarget(target);
        target.sendMessage("§6[Death] §eCreeper de Hellish invocado (cargado y rápido).");
    }

    private void summonBlaze(Player target) {
        Location loc = target.getLocation().add(
                (random.nextDouble() - 0.5) * 8, 0, (random.nextDouble() - 0.5) * 8);
        if (!loc.getChunk().isLoaded()) {
            return;
        }
        org.bukkit.entity.Blaze blaze = (org.bukkit.entity.Blaze)
                loc.getWorld().spawnEntity(loc, EntityType.BLAZE);
        var range = blaze.getAttribute(Attribute.FOLLOW_RANGE);
        if (range != null) {
            range.setBaseValue(25);
        }
        manager.markSpawned(blaze);
        blaze.setTarget(target);
        target.sendMessage("§6[Death] §6Blaze de Hellish invocado.");
    }

    private void summonBreeze(Player target) {
        Location loc = target.getLocation().add(
                (random.nextDouble() - 0.5) * 12, 0, (random.nextDouble() - 0.5) * 12);
        if (!loc.getChunk().isLoaded()) {
            return;
        }
        org.bukkit.entity.Breeze breeze = (org.bukkit.entity.Breeze)
                loc.getWorld().spawnEntity(loc, EntityType.BREEZE);
        breeze.addScoreboardTag("dm_lv5_breeze");
        breeze.setTarget(target);
        var range = breeze.getAttribute(Attribute.FOLLOW_RANGE);
        if (range != null) {
            range.setBaseValue(64);
        }
        manager.markSpawned(breeze);
        target.sendMessage("§6[Death] §3Breeze de Hellish invocado (balas de shulker en espiral).");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            for (String option : OPTIONS) {
                if (option.startsWith(prefix)) {
                    out.add(option);
                }
            }
        } else {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase().startsWith(args[args.length - 1].toLowerCase())) {
                    out.add(player.getName());
                }
            }
        }
        return out;
    }
}
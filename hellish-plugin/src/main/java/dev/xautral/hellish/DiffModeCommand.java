package dev.xautral.hellish;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;

/**
 * /diffmode con gestor de tiempo multi-unidad:
 *   30s  -> 30 segundos
 *   10m  -> 10 minutos
 *   2h   -> 2 horas
 *   1d   -> 1 dia
 *   400t -> 400 ticks
 *   300  -> numero plano = segundos (compatibilidad)
 */
public final class DiffModeCommand implements CommandExecutor, TabCompleter {

    private static final int MIN_SECONDS = 5;
    private static final long MAX_SECONDS = 30L * 24L * 60L * 60L; // 30 dias

    private final DiffModeManager manager;

    public DiffModeCommand(DiffModeManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§6[Death] §eUso: /diffmode <hellish|off> "
                    + "[<num><s|m|h|d|t>§7] §8(§ehellish§8: §7+ [§e1§7|§e2§7|§e3§8])");
            sender.sendMessage("§7Unidades: §es§7 segundos, §em§7 minutos, "
                    + "§eh§7 horas, §ed§7 dias, §et§7 ticks. §8(30s, 10m, 1d, 400t)");
            sender.sendMessage("§7Nivel de peligro §e1§7: base, §e2§7: extra, "
                    + "§e3§7: extremo, §e4§7: infernal, §e5§7: daemon, "
                    + "§e6§7: avengers, §e7§7: daemon. "
                    + "§8Ej: /diffmode hellish 10m 7");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "off" -> manager.stop(true);
            case "hellish", "activateplus" -> start(sender, DiffMode.HELLISH, args, parseHellishLevel(sender, args));
            default -> sender.sendMessage("§6[Death] §cModo desconocido: " + args[0]
                    + " §7(hellish | off)");
        }
        return true;
    }

    private static int parseHellishLevel(CommandSender sender, String[] args) {
        if (args.length < 3) {
            return 1;
        }
        try {
            int level = Integer.parseInt(args[2]);
            if (level >= 1 && level <= 7) {
                return level;
            }
        } catch (NumberFormatException ignored) {
            // abajo
        }
        sender.sendMessage("§6[Death] §cNivel de peligro invalido: " + args[2]
                + " §7(usa 1, 2, 3, 4, 5, 6 o 7)");
        return 1;
    }

    private void start(CommandSender sender, DiffMode mode, String[] args, int level) {
        long seconds = 300;
        if (args.length >= 2) {
            Long parsed = parseTime(args[1]);
            if (parsed == null) {
                sender.sendMessage("§6[Death] §cTiempo invalido: " + args[1]
                        + " §7usa <num><s|m|h|d|t>, ej: 90s, 15m, 2d, 600t");
                return;
            }
            seconds = parsed;
            if (seconds < MIN_SECONDS || seconds > MAX_SECONDS) {
                sender.sendMessage("§6[Death] §cEl tiempo debe estar entre §e" + MIN_SECONDS
                        + "s§c y §e30d§c.");
                return;
            }
        }
        if (!(sender instanceof org.bukkit.entity.Player) && args.length < 2) {
            sender.sendMessage("§6[Death] §cDesde consola indica el tiempo: /diffmode "
                    + args[0].toLowerCase() + " <num><s|m|h|d|t>");
            return;
        }
        manager.start(mode, (int) seconds, level);
    }

    /**
     * Convierte "<num><unidad>" a segundos. Null si es invalido.
     */
    static Long parseTime(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        char last = Character.toLowerCase(raw.charAt(raw.length() - 1));
        String numberPart = raw;
        long multiplier = 1L; // por defecto: segundos

        switch (last) {
            case 's' -> multiplier = 1L;
            case 'm' -> multiplier = 60L;
            case 'h' -> multiplier = 3600L;
            case 'd' -> multiplier = 86_400L;
            case 't' -> multiplier = 1L; // ticks abajo se dividen entre 20
            default -> {
                if (!Character.isDigit(last)) {
                    return null;
                }
                // numero plano: segundos
            }
        }
        boolean isTicks = last == 't';
        if (isTicks || Character.isLetter(last)) {
            numberPart = raw.substring(0, raw.length() - 1);
        }
        if (numberPart.isEmpty()) {
            return null;
        }
        long value;
        try {
            value = Long.parseLong(numberPart);
        } catch (NumberFormatException e) {
            return null;
        }
        if (value < 0) {
            return null;
        }
        return isTicks ? Math.max(1, value / 20L) : value * multiplier;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String option : List.of("hellish", "off")) {
                if (option.startsWith(args[0].toLowerCase())) {
                    out.add(option);
                }
            }
        } else if (args.length == 2 && !args[0].equalsIgnoreCase("off")) {
            String prefix = args[1].toLowerCase();
            for (String time : List.of(
                    "30s", "60s", "300s", "600s", "1200s",
                    "5m", "10m", "30m",
                    "1h", "6h",
                    "1d", "7d", "30d",
                    "100t", "400t", "2000t")) {
                if (time.startsWith(prefix)) {
                    out.add(time);
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("hellish")) {
            String prefix = args[2].toLowerCase();
            for (String lvl : List.of("1", "2", "3", "4", "5", "6", "7")) {
                if (lvl.startsWith(prefix)) {
                    out.add(lvl);
                }
            }
        }
        return out;
    }
}

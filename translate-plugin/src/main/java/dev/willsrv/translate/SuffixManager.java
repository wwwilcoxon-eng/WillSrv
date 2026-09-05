package dev.willsrv.translate;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SuffixManager {

    private final TranslatePlugin plugin;
    private final TranslateManager manager;
    private final Map<UUID, Long> suffixExpiry = new ConcurrentHashMap<>();

    public SuffixManager(TranslatePlugin plugin, TranslateManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public void applyTemporarySuffix(Player player) {
        Lang lang = manager.getLang(player);
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        String teamName = "tr_" + player.getUniqueId().toString().substring(0, 8);
        // Team names max 16? In modern Paper 26, limit is higher but we keep short
        if (teamName.length() > 16) teamName = teamName.substring(0, 16);

        Team team = board.getTeam(teamName);
        if (team == null) {
            try {
                team = board.registerNewTeam(teamName);
            } catch (IllegalArgumentException ex) {
                team = board.getTeam(teamName);
            }
        }
        if (team == null) return;

        Component suffix = Component.text(lang.displaySuffix());
        try {
            team.suffix(suffix);
        } catch (Throwable t) {
            // fallback via deprecated API
            try { team.setSuffix(lang.displaySuffix()); } catch (Throwable ignored) {}
        }
        // Ensure player is in team
        try {
            if (!team.hasEntry(player.getName())) team.addEntry(player.getName());
        } catch (Throwable ignored) {}

        // Also set playerListName for TAB visibility (more immediate)
        Component tabName = Component.text(player.getName()).append(suffix);
        try { player.playerListName(tabName); } catch (Throwable ignored) {}

        // Also set displayName suffix for chat? we keep display via team
        suffixExpiry.put(player.getUniqueId(), System.currentTimeMillis() + manager.getSuffixDurationSeconds() * 1000L);

        int durationTicks = manager.getSuffixDurationSeconds() * 20;
        final Team finalTeam = team;
        final String finalTeamName = teamName;
        Bukkit.getScheduler().runTaskLater(plugin, () -> removeSuffix(player, finalTeam, finalTeamName), durationTicks);

        // Announce to others? Could send actionbar
        // Broadcast small message: player joined with country
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.equals(player)) continue;
            // Send subtle message about suffix?
            // Only 1 time per join to avoid spam, use action bar or chat
        }
    }

    private void removeSuffix(Player player, Team team, String teamName) {
        Long expiry = suffixExpiry.get(player.getUniqueId());
        if (expiry != null && System.currentTimeMillis() < expiry) {
            // still should persist? reschedule? but we scheduled exact duration, so ignore
        }
        try {
            if (team != null) {
                try { team.suffix(Component.empty()); } catch (Throwable ignored) {
                    try { team.setSuffix(""); } catch (Throwable ignored2) {}
                }
                // optionally remove player from team if empty?
                // keep team but remove entry? No, keep to avoid leaking
                if (team.hasEntry(player.getName())) {
                    // do not remove entry immediately, just clear suffix
                }
            }
            // Reset tab name
            if (player.isOnline()) {
                try { player.playerListName(Component.text(player.getName())); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        // cleanup map
        // Keep expiry for reference until next apply
    }

    public void onQuit(Player player) {
        suffixExpiry.remove(player.getUniqueId());
        // don't immediately remove team - let scheduler clean
        try {
            Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
            for (Team t : board.getTeams()) {
                if (t.hasEntry(player.getName()) && t.getName().startsWith("tr_")) {
                    // keep but clear suffix
                    try { t.suffix(Component.empty()); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        try { player.playerListName(null); } catch (Throwable ignored) {}
    }

    public void removeAll() {
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        for (Team t : board.getTeams()) {
            if (t.getName().startsWith("tr_")) {
                try { t.unregister(); } catch (Throwable ignored) {}
            }
        }
    }
}

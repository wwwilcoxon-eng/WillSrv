package dev.willsrv.xautralmusic;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class XmeScriptManager implements Listener {

    private final JavaPlugin plugin;
    private final List<XmeEntry> entries = new ArrayList<>();

    private static class XmeEntry {
        String raw;
        String action; // lower
        String sound; // e.g. minecraft:sfx.grunt_kill
        float volume = 1f;
        float pitch = 1f;
        String playersSel = "all"; // all/@a/@p/@s or player name
        String posSel = "-1,-1,-1"; // coords or @a/@p/@s
        File source;
    }

    public XmeScriptManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        entries.clear();
        File sfxFolder = new File(plugin.getConfig().getString("sfxFolder", "plugins/XautralMusic/sfx"));
        File base = new File(plugin.getConfig().getString("baseFolder", "plugins/XautralMusic"));
        File sfx2 = new File(base, "sfx");
        List<File> search = new ArrayList<>();
        if (sfxFolder.exists()) search.add(sfxFolder);
        if (sfx2.exists() && !sfx2.getAbsolutePath().equals(sfxFolder.getAbsolutePath())) search.add(sfx2);
        // también busca en base/music si hay .xme? pero spec dice SFX
        Set<String> seen = new HashSet<>();
        for (File root : search) {
            if (!root.exists()) continue;
            try (var walk = Files.walk(root.toPath())) {
                walk.filter(p -> p.toString().toLowerCase().endsWith(".xme"))
                        .map(java.nio.file.Path::toFile)
                        .forEach(f -> {
                            if (seen.add(f.getAbsolutePath())) loadFile(f);
                        });
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Error escaneando .xme en {}: {}", root, e.getMessage());
            }
        }
        boolean verbose = plugin.getConfig().getBoolean("verbose", false);
        if (verbose) {
            plugin.getSLF4JLogger().info("[XautralMusic] XME cargados: {} eventos desde {} archivos", entries.size(), seen.size());
            for (XmeEntry e : entries) {
                plugin.getSLF4JLogger().info("  - [{}] {} vol={} pitch={} players={} pos={} ({}:{})", e.action, e.sound, e.volume, e.pitch, e.playersSel, e.posSel, e.source.getName(), e.raw);
            }
        }
        if (plugin.getConfig().getBoolean("generateDatapack", true)) generateDatapack();
    }

    private void loadFile(File f) {
        try {
            List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            for (String line : lines) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#") || t.startsWith("//")) continue;
                XmeEntry e = parseLine(t, f);
                if (e != null) entries.add(e);
                else plugin.getSLF4JLogger().warn("[XME] Línea no parseada en {}: {}", f.getName(), line);
            }
        } catch (Exception e) {
            plugin.getSLF4JLogger().warn("Error leyendo {}: {}", f.getName(), e.getMessage());
        }
    }

    private static final Pattern ACTION_PAT = Pattern.compile("\\[action\\s*[:;,]\\s*([^\\]]+)\\]", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLAYERS_PAT = Pattern.compile("\\[players\\s*[:;,]\\s*([^\\]]+)\\]", Pattern.CASE_INSENSITIVE);
    private static final Pattern POS_PAT = Pattern.compile("\\[pos\\s*[:;,]?\\s*([^\\]]+)\\]", Pattern.CASE_INSENSITIVE);
    // alternative: [pos,-1,-1,-1] -> captures -1,-1,-1 without colon, but above handles colon; we need also handle "[pos,-1,-1,-1]" where there's comma right after pos
    // So POS_PAT already handles "pos," -> group will be "-1,-1,-1"

    private XmeEntry parseLine(String line, File src) {
        Matcher ma = ACTION_PAT.matcher(line);
        if (!ma.find()) return null;
        String action = ma.group(1).trim().toLowerCase();
        // players / pos
        Matcher mp = PLAYERS_PAT.matcher(line);
        String players = mp.find() ? mp.group(1).trim() : "all";
        Matcher mo = POS_PAT.matcher(line);
        String pos = mo.find() ? mo.group(1).trim() : "-1,-1,-1";
        // playsound part
        // find "playsound" keyword
        String lower = line.toLowerCase();
        int idx = lower.indexOf("playsound");
        if (idx < 0) return null;
        String after = line.substring(idx + "playsound".length()).trim();
        // after = "minecraft:sfx.grunt_kill 1 [players:all] [pos:-1,-1,-1]"
        // split, but ignore brackets
        String[] parts = after.split("\\s+");
        String sound = null;
        float vol = 1f, pitch = 1f;
        int pIdx = 0;
        for (String part : parts) {
            if (part.startsWith("[")) break;
            if (sound == null) { sound = part; pIdx = 1; }
            else if (pIdx == 1) {
                try { vol = Float.parseFloat(part); pIdx = 2; } catch (NumberFormatException ex) { break; }
            } else if (pIdx == 2) {
                try { pitch = Float.parseFloat(part); pIdx = 3; } catch (NumberFormatException ex) { break; }
            } else break;
        }
        if (sound == null) return null;
        // normaliza sonidos: si no tiene namespace, prepend del config namespace
        if (!sound.contains(":")) {
            String ns = plugin.getConfig().getString("namespace", "xautral");
            sound = ns + ":" + sound;
        }
        XmeEntry e = new XmeEntry();
        e.raw = line;
        e.action = action;
        e.sound = sound;
        e.volume = vol;
        e.pitch = pitch;
        e.playersSel = players;
        e.posSel = pos;
        e.source = src;
        return e;
    }

    // ── Dispatch ──

    private void dispatch(String action, Player trigger) {
        if (trigger == null) return;
        for (XmeEntry e : entries) {
            if (!e.action.equalsIgnoreCase(action)) continue;
            // también soporta alias con *? por ahora exact
            executeEntry(e, trigger);
        }
    }

    private void dispatchKill(Player killer, Player victim) {
        for (XmeEntry e : entries) if (e.action.equalsIgnoreCase("kill")) executeEntry(e, killer != null ? killer : victim);
    }

    private void executeEntry(XmeEntry e, Player trigger) {
        List<Player> targets = resolvePlayers(e.playersSel, trigger);
        if (targets.isEmpty()) return;
        Location pos = resolvePos(e.posSel, trigger, targets);
        // si pos es null, usa trigger location
        for (Player t : targets) {
            Location playAt = pos != null ? pos : t.getLocation();
            // Si posSel era "-1,-1,-1" significa en cada target
            if (e.posSel.trim().equals("-1,-1,-1") || e.posSel.trim().equals("-1, -1, -1") || e.posSel.equalsIgnoreCase("self")) {
                playAt = t.getLocation();
            }
            try {
                // Paper: playSound con String
                t.playSound(playAt, e.sound, SoundCategory.MASTER, e.volume, e.pitch);
                // también para oír global si es all, cada uno ya lo recibe
            } catch (Throwable ex) {
                // fallback world play
                try {
                    World w = playAt.getWorld();
                    if (w != null) w.playSound(playAt, e.sound, SoundCategory.MASTER, e.volume, e.pitch);
                } catch (Throwable ignore) {}
            }
        }
    }

    private List<Player> resolvePlayers(String sel, Player trigger) {
        sel = sel.trim();
        if (sel.equalsIgnoreCase("all") || sel.equalsIgnoreCase("@a")) {
            return new ArrayList<>(Bukkit.getOnlinePlayers());
        }
        if (sel.equalsIgnoreCase("@p")) {
            // nearest to trigger
            Player nearest = null; double best = Double.MAX_VALUE;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.equals(trigger)) continue;
                double d = p.getLocation().distanceSquared(trigger.getLocation());
                if (d < best) { best = d; nearest = p; }
            }
            if (nearest != null) return List.of(nearest);
            return List.of(trigger);
        }
        if (sel.equalsIgnoreCase("@s") || sel.equalsIgnoreCase("self") || sel.equalsIgnoreCase("@self")) {
            return List.of(trigger);
        }
        if (sel.equalsIgnoreCase("@r")) {
            List<Player> all = new ArrayList<>(Bukkit.getOnlinePlayers());
            if (all.isEmpty()) return List.of();
            Collections.shuffle(all);
            return List.of(all.get(0));
        }
        // nombre específico
        Player named = Bukkit.getPlayerExact(sel);
        if (named != null) return List.of(named);
        // fallback all
        return new ArrayList<>(Bukkit.getOnlinePlayers());
    }

    private Location resolvePos(String sel, Player trigger, List<Player> targets) {
        sel = sel.trim();
        if (sel.equals("-1,-1,-1") || sel.equals("-1, -1, -1") || sel.isEmpty()) {
            return null; // señal para usar cada target loc
        }
        if (sel.equalsIgnoreCase("@a") || sel.equalsIgnoreCase("@p") || sel.equalsIgnoreCase("@s") || sel.equalsIgnoreCase("self") || sel.startsWith("@")) {
            // para pos selector, si es @a/@p usa trigger loc
            return trigger.getLocation();
        }
        // intenta parse x,y,z
        String[] parts = sel.split(",");
        if (parts.length == 3) {
            try {
                double x = Double.parseDouble(parts[0].trim());
                double y = Double.parseDouble(parts[1].trim());
                double z = Double.parseDouble(parts[2].trim());
                World w = trigger.getWorld();
                return new Location(w, x, y, z);
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private void generateDatapack() {
        try {
            File base = new File(plugin.getDataFolder(), "datapack");
            // también intenta world/datapacks/xautral_music
            File worldPack = null;
            try {
                World w = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
                if (w != null) {
                    File worldFolder = w.getWorldFolder();
                    worldPack = new File(worldFolder, "datapacks/xautral_music");
                }
            } catch (Exception ignored) {}

            List<File> dests = new ArrayList<>();
            dests.add(new File(base, "xautral_music"));
            if (worldPack != null) dests.add(worldPack);

            for (File datapackRoot : dests) {
                File dataFunc = new File(datapackRoot, "data/xautral_music/function");
                dataFunc.mkdirs();
                // pack.mcmeta
                File mcmeta = new File(datapackRoot, "pack.mcmeta");
                if (!mcmeta.exists()) {
                    String json = "{\n  \"pack\": {\"pack_format\": 48, \"description\": \"XautralMusic SFX datapack\"},\n  \"id\": \"xautral_music\"\n}\n";
                    Files.writeString(mcmeta.toPath(), json, StandardCharsets.UTF_8);
                }
                // genera un mcfunction por cada .xme file o por cada entry grupal
                Map<String, List<XmeEntry>> byFile = new LinkedHashMap<>();
                for (XmeEntry e : entries) {
                    String key = e.source.getName().replaceAll("\\.xme$", "");
                    byFile.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
                }
                for (Map.Entry<String, List<XmeEntry>> fe : byFile.entrySet()) {
                    File func = new File(dataFunc, fe.getKey() + ".mcfunction");
                    StringBuilder sb = new StringBuilder("# XME auto-generated from " + fe.getKey() + ".xme\n");
                    for (XmeEntry e : fe.getValue()) {
                        // traduce a comando vanilla playsound
                        String players = e.playersSel.equalsIgnoreCase("all") ? "@a" : e.playersSel;
                        if (players.equalsIgnoreCase("all")) players = "@a";
                        String pos = e.posSel;
                        String posStr = "~ ~ ~";
                        if (pos.equals("-1,-1,-1") || pos.equalsIgnoreCase("@s") || pos.equalsIgnoreCase("self")) posStr = "~ ~ ~";
                        else if (pos.contains(",")) {
                            String[] c = pos.split(",");
                            if (c.length == 3) {
                                try {
                                    double x = Double.parseDouble(c[0].trim());
                                    double y = Double.parseDouble(c[1].trim());
                                    double z = Double.parseDouble(c[2].trim());
                                    posStr = x + " " + y + " " + z;
                                } catch (Exception ex) { posStr = "~ ~ ~"; }
                            }
                        } else if (pos.startsWith("@")) posStr = "~ ~ ~";
                        sb.append(String.format("playsound %s master %s %s %.2f %.2f\n", e.sound, players, posStr, e.volume, e.pitch));
                    }
                    Files.writeString(func.toPath(), sb.toString(), StandardCharsets.UTF_8);
                }
                // tag load? no
            }
        } catch (Exception e) {
            plugin.getSLF4JLogger().warn("Error generando datapack XME: {}", e.getMessage());
        }
    }

    // ── Event hooks ──

    @EventHandler
    public void onDeath(PlayerDeathEvent e) { dispatch("die", e.getEntity()); }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent e) {
        if (e.getEntity().getKiller() != null) dispatchKill(e.getEntity().getKiller(), null);
        // también "kill" si es player mata entity
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { dispatch("join", e.getPlayer()); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { dispatch("quit", e.getPlayer()); dispatch("leave", e.getPlayer()); }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) { dispatch("respawn", e.getPlayer()); }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent e) { if (e.isSneaking()) dispatch("sneak", e.getPlayer()); else dispatch("unsneak", e.getPlayer()); }

    @EventHandler
    public void onSprint(PlayerToggleSprintEvent e) { if (e.isSprinting()) dispatch("sprint", e.getPlayer()); else dispatch("unsprint", e.getPlayer()); }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p) dispatch("damage", p);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) { dispatch("interact", e.getPlayer()); }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        // jump detection: y aumenta con vel >0.35 y onGround false -> true
        if (e.getFrom().getY() < e.getTo().getY() && e.getPlayer().getVelocity().getY() > 0.3) {
            // evita spam
            if (Math.random() < 0.3) {} else dispatch("jump", e.getPlayer());
        }
    }
}

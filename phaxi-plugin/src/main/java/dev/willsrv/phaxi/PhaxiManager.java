package dev.willsrv.phaxi;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PhaxiManager implements Listener {

    private final JavaPlugin plugin;
    private final File homesFile;
    // UUID -> Map<name, Location>
    private final Map<UUID, Map<String, Location>> homes = new ConcurrentHashMap<>();
    // Jugador en vuelo activo
    private final Map<UUID, Flight> flights = new ConcurrentHashMap<>();
    // Pendientes de confirmacion
    private final Map<UUID, PendingGo> pendingGo = new ConcurrentHashMap<>();
    private final Map<UUID, PendingSend> pendingSend = new ConcurrentHashMap<>();

    private static final class PendingGo {
        final Location home;
        final String homeName;
        final int costLevels;
        final double distance;
        long timestamp;
        PendingGo(Location home, String homeName, int cost, double dist) { this.home=home; this.homeName=homeName; this.costLevels=cost; this.distance=dist; this.timestamp=System.currentTimeMillis(); }
    }
    private static final class PendingSend {
        final Player target;
        final Location dest;
        final int costLevels;
        final double distance;
        final UUID senderId;
        final String senderName;
        long timestamp;
        PendingSend(Player target, Location dest, int cost, double dist, UUID senderId, String senderName) { this.target=target; this.dest=dest; this.costLevels=cost; this.distance=dist; this.senderId=senderId; this.senderName=senderName; this.timestamp=System.currentTimeMillis(); }
        PendingSend(Player target, Location dest, int cost, double dist) { this(target,dest,cost,dist,target.getUniqueId(),target.getName()); }
    }

    public PhaxiManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.homesFile = new File(plugin.getDataFolder(), "homes.yml");
    }

    // ---------- Persistencia ----------

    public void loadHomes() {
        if (!homesFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(homesFile);
        for (String uuidStr : cfg.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(uuidStr);
                ConfigurationSection sec = cfg.getConfigurationSection(uuidStr);
                if (sec == null) continue;
                Map<String, Location> map = new ConcurrentHashMap<>();
                for (String name : sec.getKeys(false)) {
                    ConfigurationSection h = sec.getConfigurationSection(name);
                    if (h == null) continue;
                    String worldName = h.getString("world");
                    if (worldName == null) continue;
                    World w = Bukkit.getWorld(worldName);
                    if (w == null) continue;
                    double x = h.getDouble("x");
                    double y = h.getDouble("y");
                    double z = h.getDouble("z");
                    float yaw = (float) h.getDouble("yaw");
                    float pitch = (float) h.getDouble("pitch");
                    map.put(name.toLowerCase(), new Location(w, x, y, z, yaw, pitch));
                }
                if (!map.isEmpty()) homes.put(uuid, map);
            } catch (IllegalArgumentException ignored) {}
        }
    }

    public void saveHomes() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<UUID, Map<String, Location>> e : homes.entrySet()) {
            String uuidStr = e.getKey().toString();
            for (Map.Entry<String, Location> h : e.getValue().entrySet()) {
                String path = uuidStr + "." + h.getKey();
                Location loc = h.getValue();
                cfg.set(path + ".world", loc.getWorld().getName());
                cfg.set(path + ".x", loc.getX());
                cfg.set(path + ".y", loc.getY());
                cfg.set(path + ".z", loc.getZ());
                cfg.set(path + ".yaw", loc.getYaw());
                cfg.set(path + ".pitch", loc.getPitch());
            }
        }
        try {
            plugin.getDataFolder().mkdirs();
            cfg.save(homesFile);
        } catch (IOException ex) {
            plugin.getSLF4JLogger().error("No se pudo guardar homes.yml", ex);
        }
    }

    private void saveAsync() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::saveHomes);
    }

    // ---------- API casas ----------

    public void setHome(Player player, String name) {
        name = name.toLowerCase();
        Location loc = player.getLocation().clone();
        homes.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>()).put(name, loc);
        saveAsync();
        if (Tr.isEnglish(player)) {
            player.sendMessage("§6[Phaxi] §aHome '§e" + name + "§a' saved at §e"
                    + loc.getWorld().getName() + " §7" + fmt(loc) + "§a.");
            player.sendMessage("§7Use §e/phaxi go" + (name.equals("casa") ? "" : " " + name) + " §7to call your phantom taxi.");
        } else {
            player.sendMessage("§6[Phaxi] §aCasa '§e" + name + "§a' guardada en §e"
                    + loc.getWorld().getName() + " §7" + fmt(loc) + "§a.");
            player.sendMessage("§7Usa §e/phaxi go" + (name.equals("casa") ? "" : " " + name) + " §7para llamar tu taxi phantom.");
        }
    }

    public boolean hasHome(Player player, String name) {
        Map<String, Location> m = homes.get(player.getUniqueId());
        return m != null && m.containsKey(name.toLowerCase());
    }

    public Location getHome(Player player, String name) {
        Map<String, Location> m = homes.get(player.getUniqueId());
        if (m == null) return null;
        return m.get(name.toLowerCase());
    }

    public Location getDefaultHome(Player player) {
        Map<String, Location> m = homes.get(player.getUniqueId());
        if (m == null || m.isEmpty()) return null;
        // prioridad: "casa", luego "home", luego cualquiera
        if (m.containsKey("casa")) return m.get("casa");
        if (m.containsKey("home")) return m.get("home");
        return m.values().iterator().next();
    }

    public Map<String, Location> getHomes(Player player) {
        return homes.getOrDefault(player.getUniqueId(), Map.of());
    }

    public boolean delHome(Player player, String name) {
        Map<String, Location> m = homes.get(player.getUniqueId());
        if (m == null || !m.containsKey(name.toLowerCase())) return false;
        m.remove(name.toLowerCase());
        if (m.isEmpty()) homes.remove(player.getUniqueId());
        saveAsync();
        return true;
    }

    private static String fmt(Location loc) {
        return String.format("%.0f, %.0f, %.0f", loc.getX(), loc.getY(), loc.getZ());
    }

    // ---------- Vuelo ----------

    public void callTaxi(Player player, String name) {
        Location home;
        if (name != null) {
            home = getHome(player, name);
            if (home == null) {
                player.sendMessage(Tr.t(player, "§6[Phaxi] §cNo tienes casa '" + name + "'. Usa §e/phaxi set " + name + "§c.", "§6[Phaxi] §cYou don't have home '" + name + "'. Use §e/phaxi set " + name + "§c."));
                return;
            }
        } else {
            home = getDefaultHome(player);
            if (home == null) {
                player.sendMessage(Tr.t(player, "§6[Phaxi] §cNo tienes casa. Usa §e/phaxi set [nombre]§c estando en tu casa.", "§6[Phaxi] §cYou have no home. Use §e/phaxi set [name]§c while at your home."));
                return;
            }
            name = getHomeName(player, home);
        }
        if (flights.containsKey(player.getUniqueId())) {
            player.sendMessage(Tr.t(player, "§6[Phaxi] §cYa tienes un Phaxi en camino.", "§6[Phaxi] §cYou already have a Phaxi on the way."));
            return;
        }
        // valida asfixia
        if (isAsphyxiated(player)) {
            player.sendMessage(Tr.t(player, "§cEstás asfixiado dentro de bloques. Muévete a un sitio con aire.", "§cYou are suffocating inside blocks. Move to open air."));
            // intenta igualmente pero avisa
        }
        // asegura destino seguro
        Location safeHome = findSafeAirNear(home, 5);
        if (!isSafeLocation(safeHome)) {
            player.sendMessage(Tr.t(player, "§cTu casa está obstruida (bloques). Libera 2 bloques de aire en " + fmt(home), "§cYour home is obstructed (blocks). Clear 2 air blocks at " + fmt(home)));
        } else {
            home = safeHome;
        }
        if (player.getWorld().getEnvironment() != World.Environment.NORMAL && !player.getWorld().equals(home.getWorld())) {
            player.sendMessage(Tr.t(player, "§6[Phaxi] §eTu taxi vendrá en tu mundo actual y te llevará a §b" + home.getWorld().getName() + "§e.", "§6[Phaxi] §eYour taxi will come in your current world and take you to §b" + home.getWorld().getName() + "§e."));
        }
        double dist = player.getWorld().equals(home.getWorld()) ? player.getLocation().distance(home) : 500 + player.getLocation().distance(new Location(player.getWorld(), home.getX(), player.getLocation().getY(), home.getZ()));
        int cost = calcCostGo(player, home);
        // limpia pendientes previas
        pendingSend.remove(player.getUniqueId());
        pendingGo.put(player.getUniqueId(), new PendingGo(home, name, cost, dist));
        showGoConfirmation(player, name, home, cost, dist);
    }

    private String getHomeName(Player player, Location loc) {
        for (Map.Entry<String, Location> e : getHomes(player).entrySet()) {
            if (e.getValue().equals(loc)) return e.getKey();
        }
        return "casa";
    }

    private void spawnPhaxi(Player player, Location home, String homeName, int cost) { spawnPhaxi(player, home, homeName, cost, player.getUniqueId()); }
    private void spawnPhaxi(Player player, Location home, String homeName, int cost, UUID payerId) {
        // Si el jugador ya tiene un Phaxi activo, cancelar el anterior antes de
        // crear otro (evita phantoms huerfanos que siguen con su tarea).
        Flight existing = flights.get(player.getUniqueId());
        if (existing != null) {
            existing.cancel();
            flights.remove(player.getUniqueId());
        }
        World world = player.getWorld();
        // Spawn "arriba del todo (overworld)" pero dentro de limites jugables
        // Fix: antes usaba maxHeight-4 (~380) fuera del limite de construccion (319) causando spawn invalido -> cancelado
        int highest = world.getHighestBlockYAt(player.getLocation());
        int spawnY = highest + 16 + (int)(Math.random()*8);
        // clamp a limites del mundo
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1; // inclusive limit, pero build limit ~320, usamos maxHeight-1 como techo
        // Para overworld el limite real es 319; si maxHeight es 384, limitamos a 260 para que sea visible
        if (world.getEnvironment() == World.Environment.NORMAL) {
            spawnY = Math.min(spawnY, 220);
            spawnY = Math.max(spawnY, player.getLocation().getBlockY() + 18);
        } else {
            spawnY = Math.min(spawnY, maxY - 10);
        }
        spawnY = Math.min(Math.max(spawnY, minY + 5), maxY - 2);
        // si sigue muy bajo (cueva profunda) asegura +20 sobre jugador
        if (spawnY < player.getLocation().getY() + 12) spawnY = (int)player.getLocation().getY() + 20;

        double angle = Math.random() * Math.PI * 2;
        double dist = 18 + Math.random() * 12;
        Location spawnLoc = new Location(world,
                player.getLocation().getX() + Math.cos(angle) * dist,
                spawnY,
                player.getLocation().getZ() + Math.sin(angle) * dist);
        // evita aparecer entre bloques
        if (spawnLoc.getBlock().getType().isSolid() || spawnLoc.clone().add(0,1,0).getBlock().getType().isSolid()) {
            spawnLoc = findSafeAirNear(spawnLoc, 4);
            spawnLoc.setY(Math.max(spawnLoc.getY(), spawnY));
        }
        // asegurar chunk cargado
        if (!spawnLoc.getChunk().isLoaded()) spawnLoc.getChunk().load();
        plugin.getSLF4JLogger().info("[Phaxi] Spawning taxi for {} at {} (player at {} highest {} ) home {} {}",
                player.getName(), fmt(spawnLoc) + " y=" + spawnY, fmt(player.getLocation()), highest, homeName, fmt(home));

        Phantom phantom;
        try {
            phantom = (Phantom) world.spawnEntity(spawnLoc, org.bukkit.entity.EntityType.PHANTOM);
        } catch (Exception ex) {
            plugin.getSLF4JLogger().error("[Phaxi] Error spawnEntity at " + spawnLoc, ex);
            player.sendMessage("§6[Phaxi] §cError al spawnear phantom: " + ex.getMessage());
            return;
        }
        phantom.setInvulnerable(true);
        phantom.setSilent(false);
        phantom.setCustomName("§5Phaxi §7de §e" + player.getName());
        phantom.setCustomNameVisible(true);
        phantom.setGlowing(true);
        try { phantom.setSize(2); } catch (Throwable ignored) {}
        phantom.addScoreboardTag("phaxi");
        phantom.addScoreboardTag("phaxi_" + player.getUniqueId());
        phantom.setPersistent(true);
        phantom.setRemoveWhenFarAway(false);
        try { phantom.setAI(false); } catch (Throwable ignored) {}
        try { phantom.setGravity(false); } catch (Throwable ignored) {}
        phantom.setCanPickupItems(false);
        phantom.setCollidable(false);
        // SIN noPhysics: el phaxi NO debe atravesar bloques. El movimiento por
        // teleport se comprueba contra bloques solidos en tryAdvance().
        // No debe quemarse ni atacar
        phantom.setTarget(null);
        // Opcional: atributos
        try {
            var attr = phantom.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            if (attr != null) attr.setBaseValue(40.0);
            phantom.setHealth(40.0);
        } catch (Throwable ignored) {}
        // asegurar que no despawnea y no arde
        phantom.setFireTicks(0);
        phantom.setVisualFire(false);

        player.sendMessage("§6[Phaxi] §d¡Phaxi invocado! §7Mira arriba, viene desde §e" + fmt(spawnLoc) + "§7...");
        player.sendMessage("§6[Phaxi] §7Destino: §e" + homeName + " §7en §b" + home.getWorld().getName() + " §7" + fmt(home) + "§7. §aTe montara automaticamente.");
        if (isUnderground(player)) {
            player.sendMessage("§6[Phaxi] §cEstas bajo tierra. §ePhaxi te sacara a la superficie y luego a tu casa.");
        }

        Flight flight = new Flight(player, phantom, home, homeName, cost, payerId);
        flights.put(player.getUniqueId(), flight);
        flight.start();
        plugin.getSLF4JLogger().info("[Phaxi] Flight started for {} id {} cost {} payer {}", player.getName(), phantom.getUniqueId(), cost, payerId);
    }

    private boolean isUnderground(Player player) {
        Location loc = player.getLocation();
        World w = loc.getWorld();
        if (w.getEnvironment() != World.Environment.NORMAL) return false;
        int highest = w.getHighestBlockYAt(loc);
        return loc.getY() < highest - 4;
    }

    private Location findSurfaceExit(Player player) {
        World w = player.getWorld();
        Location pl = player.getLocation();
        int px = pl.getBlockX(), pz = pl.getBlockZ();
        Location best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -16; dx <= 16; dx += 4) for (int dz = -16; dz <= 16; dz += 4) {
            int x = px + dx, z = pz + dz;
            int highest = w.getHighestBlockYAt(x, z);
            if (highest <= pl.getY() + 2) continue;
            org.bukkit.Material top = w.getBlockAt(x, highest, z).getType();
            if (top == org.bukkit.Material.AIR || top == org.bukkit.Material.CAVE_AIR) continue;
            if (!w.getBlockAt(x, highest + 1, z).getType().isAir()) continue;
            Location surf = new Location(w, x + 0.5, highest + 1.6, z + 0.5);
            double d = pl.distance(surf);
            if (d < bestDist) { bestDist = d; best = surf; }
        }
        if (best != null) return best;
        int highest = w.getHighestBlockYAt(pl);
        return new Location(w, pl.getX(), highest + 1.6, pl.getZ());
    }

    // ---------- Helpers XP / Seguro ----------
    private boolean isSafeLocation(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        World w = loc.getWorld();
        int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
        Material feet = w.getBlockAt(x, y, z).getType();
        Material head = w.getBlockAt(x, y+1, z).getType();
        Material below = w.getBlockAt(x, y-1, z).getType();
        // no asfixiado: cabeza y pies deben ser aire o no solido, y abajo solido
        boolean feetOk = feet.isAir() || !feet.isSolid() || feet == Material.WATER;
        boolean headOk = head.isAir() || !head.isSolid();
        if (!feetOk || !headOk) return false;
        if (below.isAir()) return false;
        return true;
    }

    private Location findSafeAirNear(Location origin, int radius) {
        World w = origin.getWorld();
        if (isSafeLocation(origin)) return origin.clone();
        Location best = null; double bestD = Double.MAX_VALUE;
        for (int dx=-radius; dx<=radius; dx++) for(int dy=-3; dy<=5; dy++) for(int dz=-radius; dz<=radius; dz++) {
            Location cand = origin.clone().add(dx, dy, dz);
            if (!isSafeLocation(cand)) continue;
            double d = origin.distanceSquared(cand);
            if (d < bestD) { bestD = d; best = cand; }
        }
        return best != null ? best : origin.clone();
    }

    private boolean isAsphyxiated(Player p) {
        Location eye = p.getEyeLocation();
        Material m = eye.getBlock().getType();
        return m.isSolid() && m.isOccluding();
    }

    private int calcCostGo(Player p, Location home) {
        double dist;
        if (!p.getWorld().equals(home.getWorld())) {
            dist = p.getLocation().distance(new Location(p.getWorld(), home.getX(), p.getLocation().getY(), home.getZ())) + 500;
        } else {
            dist = p.getLocation().distance(home);
        }
        return Math.max(1, (int)Math.ceil(dist / 35.0));
    }

    private int calcCostSend(Player sender, Location dest, Player target) {
        double dist;
        if (!target.getWorld().equals(dest.getWorld())) {
            dist = target.getLocation().distance(new Location(target.getWorld(), dest.getX(), target.getLocation().getY(), dest.getZ())) + 500;
        } else {
            dist = target.getLocation().distance(dest);
        }
        return Math.max(2, (int)Math.ceil(dist / 35.0) * 2);
    }

    private void showGoConfirmation(Player p, String homeName, Location home, int cost, double dist) {
        int have = p.getLevel();
        Component msg = Component.text("§6[Phaxi] §eCoste: ", NamedTextColor.GOLD)
            .append(Component.text(String.format("%.0f bloques → %d niveles XP", dist, cost), NamedTextColor.YELLOW))
            .append(Component.text(" | Tienes: " + have + " niveles", NamedTextColor.GRAY));
        p.sendMessage(msg);
        if (have < cost) {
            p.sendMessage(Component.text("§cNo tienes suficiente XP. Te faltan " + (cost - have) + " niveles.", NamedTextColor.RED));
            return;
        }
        Component buttons = Component.text("  ")
            .append(Component.text("[✓ ACEPTAR]", NamedTextColor.GREEN, TextDecoration.BOLD).clickEvent(ClickEvent.runCommand("/phaxi accept")).hoverEvent(Component.text("Click para aceptar y gastar " + cost + " niveles")))
            .append(Component.text("  "))
            .append(Component.text("[✗ DENEGAR]", NamedTextColor.RED, TextDecoration.BOLD).clickEvent(ClickEvent.runCommand("/phaxi deny")).hoverEvent(Component.text("Click para cancelar")));
        p.sendMessage(buttons);
        p.sendMessage(Component.text("§7O usa §e/phaxi accept §7o §e/phaxi deny", NamedTextColor.GRAY));
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, 1.2f);
    }

    private void showSendConfirmation(Player sender, Player target, Location dest, int cost, double dist) {
        int have = sender.getLevel();
        if (have < cost) {
            sender.sendMessage(Component.text("§cXP insuficiente. Te faltan " + (cost-have) + " niveles para enviar a " + target.getName(), NamedTextColor.RED));
            target.sendMessage(Component.text("§c" + sender.getName() + " intentó enviarte Phaxi pero le falta XP (" + have + "/" + cost + ")", NamedTextColor.RED));
            pendingSend.remove(target.getUniqueId());
            return;
        }
        target.sendMessage(Component.text("§6[Phaxi] §e" + sender.getName() + " quiere enviarte un Phaxi → " + fmt(dest) + " (" + dest.getWorld().getName() + ")", NamedTextColor.GOLD));
        target.sendMessage(Component.text(String.format("§7Distancia %.0f → coste %d niveles (paga %s, él tiene %d) | Te llevará a %s", dist, cost, sender.getName(), have, dest.getWorld().getName().equals(target.getWorld().getName()) ? "aquí" : fmt(dest)), NamedTextColor.YELLOW));
        Component buttons = Component.text("  ")
            .append(Component.text("[✓ ACEPTAR]", NamedTextColor.GREEN, TextDecoration.BOLD).clickEvent(ClickEvent.runCommand("/phaxi accept")).hoverEvent(Component.text("Aceptar - Phaxi vendrá a llevarte")))
            .append(Component.text("  "))
            .append(Component.text("[✗ DENEGAR]", NamedTextColor.RED, TextDecoration.BOLD).clickEvent(ClickEvent.runCommand("/phaxi deny")).hoverEvent(Component.text("Rechazar")));
        target.sendMessage(buttons);
        target.sendMessage(Component.text("§7/phaxi accept / deny (30s) | Phaxi aparecerá sobre ti y te llevará", NamedTextColor.GRAY));
        target.playSound(target.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.9f, 1.2f);
        sender.playSound(sender.getLocation(), Sound.BLOCK_NOTE_BLOCK_HARP, 0.8f, 1.1f);
    }

    public void handleAccept(Player p) {
        PendingGo go = pendingGo.remove(p.getUniqueId());
        PendingSend send = pendingSend.remove(p.getUniqueId());
        if (go != null) {
            if (System.currentTimeMillis() - go.timestamp > 30000) { p.sendMessage("§cConfirmación expirada (30s). Usa de nuevo /phaxi go."); return; }
            if (p.getLevel() < go.costLevels) { p.sendMessage("§cYa no tienes XP suficiente."); return; }
            p.setLevel(p.getLevel() - go.costLevels);
            p.sendMessage("§a" + go.costLevels + " niveles descontados. Invocando Phaxi...");
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.0f);
            spawnPhaxi(p, go.home, go.homeName, go.costLevels);
            return;
        }
        if (send != null) {
            Player sender = Bukkit.getPlayer(send.senderId);
            if (sender == null || !sender.isOnline()) {
                p.sendMessage("§cEl remitente ya no está online.");
                return;
            }
            if (System.currentTimeMillis() - send.timestamp > 30000) {
                p.sendMessage("§cSolicitud expirada (30s).");
                sender.sendMessage("§cTu envío a " + p.getName() + " expiró.");
                return;
            }
            if (sender.getLevel() < send.costLevels) {
                p.sendMessage("§c" + sender.getName() + " ya no tiene XP suficiente (" + sender.getLevel() + "/" + send.costLevels + ").");
                sender.sendMessage("§cTe faltan " + (send.costLevels - sender.getLevel()) + " niveles para enviar a " + p.getName());
                return;
            }
            sender.setLevel(sender.getLevel() - send.costLevels);
            sender.sendMessage("§aEnviando Phaxi a " + p.getName() + " (-" + send.costLevels + " niveles)");
            sender.playSound(sender.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.0f);
            p.sendMessage("§aHas aceptado Phaxi de " + sender.getName() + " → " + fmt(send.dest) + "!");
            p.playSound(p.getLocation(), Sound.ENTITY_PHANTOM_FLAP, 1f, 1.2f);
            spawnPhaxi(p, send.dest, "envio de " + sender.getName(), send.costLevels, sender.getUniqueId());
            return;
        }
        // busca si p es remitente de un send pendiente
        for (Map.Entry<UUID, PendingSend> e : new HashMap<>(pendingSend).entrySet()) {
            if (e.getValue().senderId.equals(p.getUniqueId())) {
                PendingSend found = e.getValue();
                UUID targetId = e.getKey();
                pendingSend.remove(targetId);
                Player target = found.target;
                if (target == null || !target.isOnline()) { p.sendMessage("§cDestinatario offline."); return; }
                if (p.getLevel() < found.costLevels) { p.sendMessage("§cXP insuficiente."); return; }
                if (System.currentTimeMillis() - found.timestamp > 30000) { p.sendMessage("§cEnvío expirado."); return; }
                p.setLevel(p.getLevel() - found.costLevels);
                p.sendMessage("§aEnviando Phaxi a " + target.getName() + " (-" + found.costLevels + " niveles)");
                target.sendMessage("§6[Phaxi] §e" + p.getName() + " te envía un Phaxi a " + fmt(found.dest) + "!");
                spawnPhaxi(target, found.dest, "envio de " + p.getName(), found.costLevels, p.getUniqueId());
                target.playSound(target.getLocation(), Sound.ENTITY_PHANTOM_FLAP, 1f, 1.0f);
                return;
            }
        }
        p.sendMessage("§7Nada que aceptar. Usa /phaxi go o espera una solicitud de send.");
    }

    public void handleDeny(Player p) {
        boolean had = false;
        if (pendingGo.remove(p.getUniqueId()) != null) had = true;
        PendingSend removed = pendingSend.remove(p.getUniqueId());
        if (removed != null) {
            had = true;
            // notifica al remitente si el destinatario deniega
            Player sender = Bukkit.getPlayer(removed.senderId);
            if (sender != null && !sender.equals(p)) {
                sender.sendMessage("§7" + p.getName() + " rechazó tu envío Phaxi.");
                sender.playSound(sender.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            }
            p.sendMessage("§7Has rechazado Phaxi de " + removed.senderName);
        }
        // si p es remitente y quiere cancelar un send pendiente donde él es sender
        if (!had) {
            for (Map.Entry<UUID, PendingSend> e : new HashMap<>(pendingSend).entrySet()) {
                if (e.getValue().senderId.equals(p.getUniqueId())) {
                    pendingSend.remove(e.getKey());
                    Player target = e.getValue().target;
                    if (target != null && target.isOnline()) {
                        target.sendMessage("§7" + p.getName() + " canceló el envío Phaxi.");
                    }
                    p.sendMessage("§7Envío a " + e.getValue().target.getName() + " cancelado.");
                    had = true;
                    break;
                }
            }
        }
        if (had) {
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.7f);
        } else {
            p.sendMessage("§7Nada que denegar.");
        }
    }

    public void callSend(Player sender, String targetName, String destArg, String[] extra) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) { sender.sendMessage("§cJugador no encontrado: " + targetName); return; }
        Location dest;
        double dist;
        if (destArg.equalsIgnoreCase("here")) {
            dest = sender.getLocation().clone();
        } else {
            // intenta parsear como x y z o x y z mundo?
            // destArg es primer coord x, extra[0]=y, extra[1]=z, extra[2]=mundo opcional
            try {
                double x = Double.parseDouble(destArg);
                double y = Double.parseDouble(extra[0]);
                double z = Double.parseDouble(extra[1]);
                World w = sender.getWorld();
                if (extra.length >= 3) {
                    World ww = Bukkit.getWorld(extra[2]);
                    if (ww != null) w = ww;
                }
                dest = new Location(w, x, y, z);
            } catch (Exception ex) {
                sender.sendMessage("§cUso: /phaxi send <jugador> here | <x> <y> <z> [mundo]");
                return;
            }
        }
        dest = findSafeAirNear(dest, 3);
        dist = target.getWorld().equals(dest.getWorld()) ? target.getLocation().distance(dest) : target.getLocation().distance(new Location(target.getWorld(), dest.getX(), target.getLocation().getY(), dest.getZ())) + 500;
        int cost = calcCostSend(sender, dest, target);
        // limpia y guarda pendiente para el DESTINATARIO (él debe aceptar)
        pendingGo.remove(target.getUniqueId());
        pendingSend.remove(target.getUniqueId());
        pendingSend.put(target.getUniqueId(), new PendingSend(target, dest, cost, dist, sender.getUniqueId(), sender.getName()));
        // avisa a ambos
        sender.sendMessage(Component.text("§eSolicitud enviada a " + target.getName() + " (" + String.format("%.0f", dist) + "b → " + cost + " niveles, pagas tú)", NamedTextColor.YELLOW));
        showSendConfirmation(sender, target, dest, cost, dist);
    }

    public void cancelAll() {
        for (Flight f : flights.values()) f.cancel();
        flights.clear();
    }

    // ---------- Listeners ----------

    @EventHandler
    public void onCombust(EntityCombustEvent event) {
        if (event.getEntity().getScoreboardTags().contains("phaxi")) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onTarget(EntityTargetEvent event) {
        if (event.getEntity().getScoreboardTags().contains("phaxi")) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDismount(EntityDismountEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Entity vehicle = event.getDismounted();
        if (!vehicle.getScoreboardTags().contains("phaxi")) return;
        Location vloc = vehicle.getLocation().clone();
        Flight flight = flights.get(player.getUniqueId());
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (vehicle.isValid()) {
                try {
                    vehicle.getWorld().spawnParticle(org.bukkit.Particle.CLOUD, vloc.clone().add(0,0.7,0), 15, 0.35,0.25,0.35, 0.03);
                    vehicle.getWorld().spawnParticle(org.bukkit.Particle.SOUL, vloc.clone().add(0,0.8,0), 10, 0.3,0.2,0.3, 0.03);
                    vehicle.getWorld().spawnParticle(org.bukkit.Particle.END_ROD, vloc.clone().add(0,1,0), 8, 0.25,0.25,0.25, 0.06);
                    vehicle.getWorld().playSound(vloc, Sound.ENTITY_PHANTOM_FLAP, 0.85f, 0.7f);
                    vehicle.getWorld().playSound(vloc, Sound.BLOCK_BEACON_DEACTIVATE, 0.9f, 0.9f);
                    vehicle.getWorld().playSound(vloc, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 0.6f);
                } catch (Throwable ignored) {}
                vehicle.remove();
                player.sendMessage("§6[Phaxi] §7Te desmontaste (Shift). §dPhaxi se desvanece épicamente.");
            }
        });
        if (flight != null) {
            flight.cancel();
            flights.remove(player.getUniqueId());
            pendingGo.remove(player.getUniqueId());
            pendingSend.remove(player.getUniqueId());
        }
    }

    // ---------- Clase Flight ----------

    private final class Flight {
        final Player player;
        final Phantom phantom;
        final Location home;
        final String homeName;
        final int costLevels;
        final UUID payerId;
        BukkitTask task;
        enum State { APPROACHING, CARRYING }
        State state = State.APPROACHING;
        int ticks = 0;
        double boost = 0;
        boolean reachedSurface = false;
        double lastDist = Double.MAX_VALUE;
        int stuckTicks = 0;
        int carryingSince = -1;
        int detourStuck = 0;
        Location detourTarget = null;
        int detourTicks = 0;
        Location prev = null;
        Location cachedSurfaceExit = null; // cache del punto de salida de cueva (relativo al jugador)
        int surfaceExitTick = -1;
        int cachedHomeY = Integer.MIN_VALUE; // cache de getHighestBlockYAt(home) (home no cambia)
        int cachedHomeYTick = -1;

        Flight(Player player, Phantom phantom, Location home, String homeName, int cost, UUID payerId) {
            this.player = player;
            this.phantom = phantom;
            this.home = home;
            this.homeName = homeName;
            this.costLevels = cost;
            this.payerId = payerId != null ? payerId : player.getUniqueId();
        }
        Flight(Player player, Phantom phantom, Location home, String homeName, int cost) { this(player, phantom, home, homeName, cost, player.getUniqueId()); }
        Flight(Player player, Phantom phantom, Location home, String homeName) { this(player, phantom, home, homeName, 0, player.getUniqueId()); }

        private void refundAndCancel(String reason) {
            if (costLevels > 0) {
                Player payer = Bukkit.getPlayer(payerId);
                if (payer != null && payer.isOnline()) {
                    int before = payer.getLevel();
                    payer.setLevel(before + costLevels);
                    payer.sendMessage(Component.text("§a[Phaxi] §eRuta falló (" + reason + "). Te devuelvo §6" + costLevels + " §eniveles. §7Ahora tienes " + payer.getLevel(), NamedTextColor.GREEN));
                    payer.playSound(payer.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 0.8f);
                    try { payer.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, payer.getLocation().clone().add(0,1,0), 10, 0.3,0.4,0.3, 0.05); } catch (Throwable ignored) {}
                    if (!payer.equals(player)) player.sendMessage(Component.text("§7Ruta cancelada, XP devuelta a " + payer.getName(), NamedTextColor.GRAY));
                } else {
                    plugin.getSLF4JLogger().info("[Phaxi] Refund pendiente {} niveles a offline {} por {}", costLevels, payerId, reason);
                    // guarda pendiente offline? por ahora log
                }
                plugin.getSLF4JLogger().info("[Phaxi] Refund {} niveles a {} (rider {}) por error: {}", costLevels, payerId, player.getName(), reason);
            } else {
                player.sendMessage(Component.text("§c[Phaxi] Ruta falló: " + reason, NamedTextColor.RED));
            }
            cancel();
            flights.remove(player.getUniqueId());
        }

        void start() {
            task = new BukkitRunnable() {
                @Override
                public void run() {
                    tick();
                }
            }.runTaskTimer(plugin, 0L, 1L);
        }

        void cancel() {
            if (task != null) task.cancel();
            if (phantom.isValid()) phantom.remove();
        }

        /** Bloque solido que impide el paso del phantom (pies o cabeza). */
        private boolean blocked(Location l) {
            return l.getBlock().getType().isSolid()
                    || l.clone().add(0, 1, 0).getBlock().getType().isSolid();
        }

        /**
         * Avanza hacia la direccion evitando atravesar bloques. En vez de saltar
         * de golpe al destino (lo que atravesaba paredes o "bloqueaba" la ruta de
         * golpe al caer el destino sobre una boveda), mueve en SEGMENTOS pequenos
         * (0.6 bloques) y a lo largo del camino sube/baja gradualmente hasta 6
         * bloques para sortear obstaculos. Devuelve false solo si un segmento
         * esta REALMENTE bloqueado (ni subir ni bajar deja pasar).
         */
        private boolean tryAdvance(Vector dir, double step, Location from) {
            double remaining = step;
            Location cur = from.clone();
            while (remaining > 0.0001) {
                double m = Math.min(remaining, 0.6);
                remaining -= m;
                Location next = cur.clone().add(dir.clone().multiply(m));
                boolean placed = false;
                // Intenta la posicion exacta (preserva componente Y de dir)
                // Si está bloqueada, sube hasta 6 o baja hasta 3 para sortear
                if (!blocked(next)) {
                    cur = next;
                    placed = true;
                } else {
                    for (int climb = 1; climb <= 6 && !placed; climb++) {
                        Location cand = next.clone().add(0, climb, 0);
                        if (!blocked(cand)) {
                            cur = cand;
                            placed = true;
                        }
                    }
                    for (int drop = 1; drop <= 3 && !placed; drop++) {
                        Location cand = next.clone().add(0, -drop, 0);
                        if (!blocked(cand)) {
                            cur = cand;
                            placed = true;
                        }
                    }
                }
                if (!placed) {
                    // Segmento realmente bloqueado: no avanzar este tick
                    return false;
                }
            }
            Vector look = dir.clone(); look.setY(-look.getY());
            cur.setDirection(look);
            try {
                phantom.setVelocity(dir.clone().multiply(step));
                phantom.teleport(cur);
                phantom.setRotation(cur.getYaw(), cur.getPitch());
            } catch (Throwable ex) {
                phantom.setVelocity(dir.clone().multiply(step));
            }
            return true;
        }

        private Vector horizontalPerp(Vector dir) {
            Vector h = new Vector(-dir.getZ(), 0, dir.getX());
            if (h.lengthSquared() < 1e-8) h = new Vector(1, 0, 0);
            return h.normalize();
        }

        private boolean isPathMostlyClear(Location from, Location to, int samples) {
            Vector d = to.toVector().subtract(from.toVector());
            double len = d.length();
            if (len < 1e-6) return true;
            d.normalize();
            for (int i = 1; i <= samples; i++) {
                double t = (len * i) / (samples + 1);
                Location p = from.clone().add(d.clone().multiply(t));
                if (blocked(p)) return false;
            }
            return true;
        }

        private Location computeDetour(Location from, Location target, Vector dir) {
            Vector perp = horizontalPerp(dir);
            double h = from.getY();
            // candidatos: lateral izq/der, diagonal, arriba
            Location[] cands = new Location[]{
                    from.clone().add(perp.clone().multiply(10)).add(0, 3, 0),
                    from.clone().add(perp.clone().multiply(-10)).add(0, 3, 0),
                    from.clone().add(dir.clone().multiply(6)).add(perp.clone().multiply(8)).add(0, 4, 0),
                    from.clone().add(dir.clone().multiply(6)).add(perp.clone().multiply(-8)).add(0, 4, 0),
                    from.clone().add(0, 10, 0),
                    from.clone().add(dir.clone().multiply(8)).add(0, 6, 0),
            };
            Location best = null;
            double bestScore = Double.MAX_VALUE;
            for (Location c : cands) {
                Location safe = findSafeAirNear(c, 4);
                // asegura altura no bajo suelo
                if (safe.getBlock().getType().isSolid()) continue;
                if (blocked(safe)) continue;
                if (!isPathMostlyClear(from, safe, 4)) continue;
                double d1 = from.distance(safe);
                double d2 = safe.distance(target);
                double score = d1 * 0.6 + d2;
                // prefiere que se acerque al destino
                if (score < bestScore) { bestScore = score; best = safe; }
            }
            if (best != null) {
                // asegura no muy lejos
                if (from.distance(best) > 28) return null;
            }
            return best;
        }

        void tick() {
            ticks++;
            // boost cada 25s (500 ticks) +3 velocidad si tarda
            if (ticks % 500 == 0 && ticks > 0) {
                boost += 3.0;
                player.sendMessage("§6[Phaxi] §e¡Phaxi acelera! +3 velocidad (boost total +" + (int)boost + ") " + (ticks/20) + "s");
                try {
                    phantom.getWorld().playSound(phantom.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.9f, 1.1f);
                    phantom.getWorld().playSound(phantom.getLocation(), Sound.ENTITY_PHANTOM_FLAP, 1f, 1.4f);
                    phantom.getWorld().spawnParticle(org.bukkit.Particle.FIREWORK, phantom.getLocation().clone().add(0,0.8,0), 12, 0.25,0.25,0.25, 0.06);
                    phantom.getWorld().spawnParticle(Particle.END_ROD, phantom.getLocation().clone().add(0,0.6,0), 8, 0.2,0.2,0.2, 0.08);
                } catch (Throwable ignored) {}
            }
            if (!player.isOnline()) {
                plugin.getSLF4JLogger().info("[Phaxi] Player {} offline, cancelling flight", player.getName());
                if (state == State.APPROACHING && costLevels > 0) refundAndCancel("offline durante recogida");
                else { cancel(); flights.remove(player.getUniqueId()); }
                return;
            }
            if (!phantom.isValid()) {
                plugin.getSLF4JLogger().warn("[Phaxi] Phantom invalid for {} ticks={} loc={}", player.getName(), ticks, phantom.getLocation());
                if (state == State.APPROACHING && costLevels > 0) refundAndCancel("phantom invalido en recogida");
                else { cancel(); flights.remove(player.getUniqueId()); player.sendMessage("§6[Phaxi] §cPhaxi se perdio (phantom invalido). Intenta de nuevo."); }
                return;
            }
            // Inmune al sol y no atacable cada tick
            phantom.setFireTicks(0);
            phantom.setVisualFire(false);
            try { phantom.setTarget(null); } catch (Throwable ignored) {}
            // asegurar chunk
            if (!phantom.getLocation().getChunk().isLoaded()) phantom.getLocation().getChunk().load();

            if (state == State.APPROACHING) {
                Location pLoc = player.getLocation().add(0, 1, 0);
                // analisis de ruta cueva: si estas bajo tierra, el phaxi va al techo de la cueva primero
                // (cacheado: el punto de salida depende del jugador, que apenas se mueve durante la ruta)
                Location surfaceExit;
                if (isUnderground(player)) {
                    if (cachedSurfaceExit == null || ticks - surfaceExitTick > 200) {
                        cachedSurfaceExit = findSurfaceExit(player);
                        surfaceExitTick = ticks;
                    }
                    surfaceExit = cachedSurfaceExit;
                } else {
                    surfaceExit = null;
                }
                Location target = pLoc;
                boolean viaSurface = false;
                if (surfaceExit != null && !reachedSurface) {
                    if (phantom.getLocation().distance(surfaceExit) > 3.5) {
                        target = surfaceExit;
                        viaSurface = true;
                    } else {
                        reachedSurface = true;
                    }
                }
                Location phLoc = phantom.getLocation();
                double dist = phLoc.distance(target);
                // trazos vanilla detras
                try { phantom.getWorld().spawnParticle(org.bukkit.Particle.CLOUD, phLoc.clone().add(0,0.4,0), 1, 0.14,0.07,0.14, 0.008); } catch (Throwable ignored) {}
                // Actualiza lastDist solo para debug, el atasco real se detecta tras tryAdvance
                lastDist = dist;
                // flap cada 24t mas vanilla
                if (ticks % 24 == 0) {
                    try {
                        phantom.getWorld().playSound(phLoc, Sound.ENTITY_PHANTOM_FLAP, 0.42f, 1.18f + (float)(Math.random()*0.15));
                        phantom.getWorld().spawnParticle(org.bukkit.Particle.CLOUD, phLoc.clone().add(0,0.3,0), 1, 0.16,0.09,0.16, 0.01);
                        phantom.getWorld().spawnParticle(org.bukkit.Particle.WHITE_SMOKE, phLoc.clone().add(0,0.5,0), 1, 0.08,0.06,0.08, 0.005);
                    } catch (Throwable ignored) {}
                }
                // bob vanilla suave
                double bob = Math.sin(ticks * 0.18) * 0.12;
                if (viaSurface) bob += 0.08;
                if (dist < 3.2 && !viaSurface) {
                    if (phantom.getPassengers().isEmpty() && !player.isInsideVehicle()) {
                        Location mountLoc = findSafeAirNear(pLoc.clone().add(0, 1.15, 0), 2);
                        Vector look = pLoc.toVector().subtract(phLoc.toVector());
                        look.setY(-look.getY());
                        mountLoc.setDirection(look);
                        try { phantom.teleport(mountLoc); } catch (Throwable ignored) { phantom.teleport(pLoc.clone().add(0,1.5,0)); }
                        boolean mounted = false;
                        try { mounted = phantom.addPassenger(player); } catch (Throwable ignore) {}
                        if (mounted) {
                            state = State.CARRYING;
                            carryingSince = ticks;
                        } else {
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                if (phantom.isValid() && !player.isInsideVehicle() && phantom.getPassengers().isEmpty() && phantom.addPassenger(player)) {
                                    state = State.CARRYING;
                                    carryingSince = ticks;
                                }
                            });
                            // Mantén APPROACHING este tick, el próximo hará retry si aún no monta
                            // No cambies state aún para evitar cancel prematuro en CARRYING
                            return;
                        }
                        player.sendMessage("§6[Phaxi] §a¡Montado! §7Llevandote a §e" + homeName + " §7" + fmt(home) + " §7(SHIFT para bajar)");
                        phantom.getWorld().playSound(phantom.getLocation(), Sound.ENTITY_PHANTOM_FLAP, 1f, 1.2f);
                        phantom.getWorld().playSound(phantom.getLocation(), Sound.ENTITY_PHANTOM_BITE, 0.6f, 1.1f);
                        if (surfaceExit != null) player.sendMessage("§7Ruta cueva analizada → te sacará por " + String.format("%.0f,%.0f,%.0f", surfaceExit.getX(), surfaceExit.getY(), surfaceExit.getZ()));
                    } else if (ticks > 900) {
                        // no puede montar (esta en otro vehiculo u ocupado): evita hover infinito
                        refundAndCancel("no puede montar (en otro vehículo)");
                        return;
                    }
                } else {
                    // Si hay detour activo, ir hacia él
                    Location effective = target;
                    boolean isDetour = false;
                    if (detourTarget != null) {
                        if (phLoc.distance(detourTarget) < 3.5) {
                            detourTarget = null; detourTicks = 0; stuckTicks = 0; detourStuck = 0;
                        } else {
                            effective = detourTarget;
                            isDetour = true;
                            detourTicks++;
                            if (detourTicks > 180) { detourTarget = null; detourTicks = 0; }
                            else if (detourTicks % 30 == 0) {
                                try { phantom.getWorld().spawnParticle(Particle.END_ROD, phLoc.clone().add(0,0.5,0), 4, 0.15,0.15,0.15, 0.02); } catch (Throwable ignored) {}
                            }
                        }
                    }
                    Vector dir = effective.toVector().subtract(phLoc.toVector());
                    double len = dir.length();
                    if (len > 0) {
                        dir.normalize();
                        double base = isDetour ? 1.15 : (viaSurface ? 1.0 : 1.35);
                        double step = Math.min(Math.max(0.45, base + boost), len);
                        boolean advanced = tryAdvance(dir, step, phLoc.clone());
                        double moved;
                        try {
                            moved = prev == null ? step : phantom.getLocation().distance(prev);
                        } catch (Throwable e) {
                            moved = step;
                        }
                        if (!advanced || (moved < 0.15 && len > 2)) {
                            stuckTicks++; detourStuck++;
                            // Si lleva 10 ticks bloqueado sin detour, busca rodear
                            if (!isDetour && detourTarget == null && detourStuck > 10) {
                                Location d = computeDetour(phLoc, target, dir);
                                if (d != null) {
                                    detourTarget = d;
                                    detourTicks = 0;
                                    detourStuck = 0;
                                    stuckTicks = 0;
                                    player.sendMessage("§6[Phaxi] §eTrayecto cortado, buscando rodeo... §8(" + String.format("%.0f,%.0f,%.0f", d.getX(), d.getY(), d.getZ()) + ")");
                                    try { phantom.getWorld().playSound(phLoc, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7f, 1.5f); } catch (Throwable ignored) {}
                                }
                            }
                        } else {
                            stuckTicks = 0;
                            if (!isDetour) detourStuck = 0;
                        }
                        prev = phantom.getLocation().clone();
                        if (stuckTicks > 36 && !isDetour) {
                            // si ni con detour avanza, refund
                            if (detourTarget != null) {
                                // intenta otro detour antes de rendirse
                                Location d2 = computeDetour(phLoc, target, dir);
                                if (d2 != null && (detourTarget == null || d2.distance(detourTarget) > 3)) {
                                    detourTarget = d2; detourTicks = 0; stuckTicks = 0; detourStuck = 0;
                                    return;
                                }
                            }
                            refundAndCancel("ruta bloqueada (" + String.format("%.1f", dist) + "b)");
                            return;
                        }
                        if (isDetour && stuckTicks > 48) {
                            detourTarget = null; detourTicks = 0; stuckTicks = 0; detourStuck = 0;
                        }
                    }
                    if (ticks > 900) {
                        refundAndCancel("timeout recogida " + (viaSurface ? "cueva" : "abierto") + " dist=" + String.format("%.1f", dist));
                        return;
                    }
                }
            } else if (state == State.CARRYING) {
                // Si el jugador ya no es pasajero (se bajo con shift ya manejado), cancelar
                // Gracia de 20 ticks tras montar para que el pasajero se sincronice
                if (!phantom.getPassengers().contains(player)) {
                    if (carryingSince != -1 && ticks - carryingSince < 20) return;
                    // onDismount ya limpiara, pero por si acaso
                    cancel();
                    flights.remove(player.getUniqueId());
                    return;
                }
                Location phLoc = phantom.getLocation();
                // Determinar destino: si cross-world, volamos a home en su mundo pero necesitamos teleport
                World destWorld = home.getWorld();
                if (!phantom.getWorld().equals(destWorld)) {
                    // sube verticalmente con teleport hasta max
                    if (phLoc.getY() < phantom.getWorld().getMaxHeight() - 12) {
                        Location up = phLoc.clone().add(0, 1.1, 0);
                        phantom.teleport(up);
                        return;
                    } else {
                        // teleport ambos a home world arriba
                        Location destSpawn = home.clone().add(0, 28, 0);
                        destSpawn.setWorld(destWorld);
                        // asegura chunk
                        if (!destSpawn.getChunk().isLoaded()) destSpawn.getChunk().load();
                        phantom.teleport(destSpawn);
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (!phantom.getPassengers().contains(player) && player.isOnline() && phantom.isValid()) {
                                phantom.addPassenger(player);
                            }
                        });
                        player.sendMessage("§6[Phaxi] §7Cambiando de dimension a §b" + destWorld.getName() + "§7...");
                    }
                    return;
                }

                // Vuelo normal mismo mundo: waypoint elevado para salir de cueva
                Location surfaceHome = home.clone();
                if (cachedHomeY == Integer.MIN_VALUE || ticks - cachedHomeYTick > 100) {
                    cachedHomeY = home.getWorld().getHighestBlockYAt(home);
                    cachedHomeYTick = ticks;
                }
                surfaceHome.setY(Math.max(home.getY() + 14, cachedHomeY + 16));

                Location waypoint;
                double distToHomeXZ = Math.hypot(phLoc.getX() - home.getX(), phLoc.getZ() - home.getZ());
                if (distToHomeXZ > 14) {
                    waypoint = surfaceHome;
                } else {
                    waypoint = home.clone().add(0, 1.2, 0);
                }

                double dist = phLoc.distance(waypoint);
                if (dist < 3.5 && distToHomeXZ < 14) {
                    if (dist < 2.1) {
                        // llegada vanilla: desmonta, parpadea y se va volando
                        phantom.removePassenger(player);
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            player.teleportAsync(home).thenRun(() -> {
                                player.sendMessage("§6[Phaxi] §a¡Has llegado a §e" + homeName + " §7" + fmt(home) + "§a!");
                                try {
                                    World hw = home.getWorld();
                                    hw.playSound(home, Sound.ENTITY_PHANTOM_DEATH, 0.9f, 0.75f);
                                    hw.playSound(home, Sound.ENTITY_PHANTOM_FLAP, 1f, 1.0f);
                                    hw.playSound(home, Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.3f);
                                    hw.playSound(home, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.5f);
                                    hw.playSound(home, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.4f);
                                    hw.spawnParticle(org.bukkit.Particle.CLOUD, home.clone().add(0,1,0), 18, 0.5,0.35,0.5, 0.04);
                                    hw.spawnParticle(org.bukkit.Particle.END_ROD, home.clone().add(0,1.2,0), 12, 0.35,0.35,0.35, 0.08);
                                    hw.spawnParticle(org.bukkit.Particle.SOUL, home.clone().add(0,0.8,0), 14, 0.4,0.25,0.4, 0.04);
                                    hw.spawnParticle(org.bukkit.Particle.TOTEM_OF_UNDYING, home.clone().add(0,1,0), 8, 0.4,0.4,0.4, 0.12);
                                    hw.spawnParticle(org.bukkit.Particle.FIREWORK, home.clone().add(0,1.5,0), 10, 0.3,0.3,0.3, 0.08);
                                } catch (Throwable ignored) {}
                            });
                        });
                        // despedida vanilla: se eleva y se aleja (pitch fix)
                        Location depart = phLoc.clone().add(0, 8, 0);
                        depart.setDirection(new Vector((Math.random()-0.5)*0.6, -0.9, (Math.random()-0.5)*0.6));
                        phantom.teleport(depart);
                        phantom.setVelocity(new Vector(0, 0.65, 0));
                        Bukkit.getScheduler().runTaskLater(plugin, () -> {
                            if (phantom.isValid()) {
                                phantom.getWorld().spawnParticle(org.bukkit.Particle.CLOUD, phantom.getLocation(), 10, 0.3,0.2,0.3, 0.02);
                                phantom.getWorld().playSound(phantom.getLocation(), Sound.ENTITY_PHANTOM_FLAP, 0.7f, 0.8f);
                            }
                        }, 6L);
                        Bukkit.getScheduler().runTaskLater(plugin, () -> {
                            if (phantom.isValid()) phantom.remove();
                            cancel();
                            flights.remove(player.getUniqueId());
                        }, 40L);
                        return;
                    } else {
                        Vector down = waypoint.toVector().subtract(phLoc.toVector()).normalize();
                        double step = Math.min(1.0, waypoint.distance(phLoc));
                        tryAdvance(down, step, phLoc.clone());
                        prev = phantom.getLocation().clone();
                        return;
                    }
                }
                // Detour en CARRYING también si el waypoint está bloqueado
                Location effWp = waypoint;
                if (detourTarget != null) {
                    if (phLoc.distance(detourTarget) < 3.5) { detourTarget = null; detourTicks = 0; }
                    else {
                        effWp = detourTarget;
                        detourTicks++;
                        if (detourTicks > 180) detourTarget = null;
                    }
                }
                Vector dir = effWp.toVector().subtract(phLoc.toVector());
                double len = dir.length();
                if (len > 0) {
                    double base = dist > 28 ? 1.25 : 0.85;
                    if (detourTarget != null) base = 1.15;
                    double step = Math.min(Math.max(0.45, base + boost), len);
                    Vector unit = dir.normalize();
                    boolean advanced = tryAdvance(unit, step, phLoc.clone());
                    double moved;
                    try {
                        moved = prev == null ? step : phantom.getLocation().distance(prev);
                    } catch (Throwable e) {
                        moved = step;
                    }
                    if (!advanced || moved < 0.15) {
                        stuckTicks++; detourStuck++;
                        if (detourTarget == null && detourStuck > 10) {
                            Location d = computeDetour(phLoc, waypoint, unit);
                            if (d != null) {
                                detourTarget = d; detourTicks = 0; detourStuck = 0; stuckTicks = 0;
                                player.sendMessage("§6[Phaxi] §eRodeando obstáculo en vuelo... §8(" + String.format("%.0f,%.0f,%.0f", d.getX(), d.getY(), d.getZ()) + ")");
                            }
                        }
                    } else {
                        stuckTicks = 0;
                        if (detourTarget == null) detourStuck = 0;
                    }
                    prev = phantom.getLocation().clone();
                    if (stuckTicks > 36) {
                        if (detourTarget != null) { detourTarget = null; stuckTicks = 0; detourStuck = 0; }
                        else {
                            player.sendMessage("§6[Phaxi] §cVuelo bloqueado, teleportando a casa...");
                            phantom.removePassenger(player);
                            player.teleportAsync(home);
                            cancel();
                            flights.remove(player.getUniqueId());
                        }
                        return;
                    }
                }
                if (ticks % 24 == 0) {
                    try {
                        phantom.getWorld().spawnParticle(org.bukkit.Particle.CLOUD, phLoc.clone().add(0,0.25,0), 1, 0.12,0.08,0.12, 0.01);
                        if (ticks % 48 ==0) phantom.getWorld().playSound(phLoc, Sound.ENTITY_PHANTOM_FLAP, 0.3f, 1.12f);
                    } catch (Throwable ignored) {}
                }
                if (ticks > 1600) {
                    player.sendMessage("§6[Phaxi] §cVuelo demorado, teleportando a casa...");
                    phantom.removePassenger(player);
                    player.teleportAsync(home);
                    // despedida
                    phantom.setVelocity(new Vector(0,0.5,0));
                    Bukkit.getScheduler().runTaskLater(plugin, () -> { if(phantom.isValid()) phantom.remove(); cancel(); flights.remove(player.getUniqueId()); }, 30L);
                }
            }
        }
    }
}

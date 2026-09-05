package dev.xautral.death;

import dev.xautral.death.chest.DeathChest;
import dev.xautral.death.head.DeathHeadSpawner;
import dev.xautral.death.store.PendingStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public final class DeathListener implements Listener {

    private static final int KICK_DELAY_TICKS = 100; // 5 segundos

    private final DeathPlugin plugin;
    private final PendingStore store;
    private final DeathChest chest;
    private final DeathHeadSpawner heads;

    DeathListener(DeathPlugin plugin, PendingStore store, DeathChest chest,
                  DeathHeadSpawner heads) {
        this.plugin = plugin;
        this.store = store;
        this.chest = chest;
        this.heads = heads;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        Location loc = player.getLocation();

        // Aviso a operadores/admins con la ubicacion exacta de la muerte
        String coords = String.format("%.0f, %.0f, %.0f (%s)",
                loc.getX(), loc.getY(), loc.getZ(), loc.getWorld().getName());
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (online != player && (online.isOp()
                    || online.hasPermission("death.admin"))) {
                String deathMsg = Tr.isEnglish(online) ? "§6[Death] §e" + player.getName() + " §7died at §b" + coords : "§6[Death] §e" + player.getName() + " §7murió en §b" + coords;
                online.sendMessage(deathMsg);
            }
        }
        plugin.getSLF4JLogger().info("[Death] " + player.getName()
                + " murió en " + coords);

        // Recolectar todos los items (incluye armadura) y borrarlos de la muerte
        // Los slots BLOQUEADOS (marcador STRUCTURE_VOID de Hellish) NO se
        // transfieren al cofre: siguen bloqueados en el inventario del jugador.
        // Se detecta por propiedades del item (sin depender del plugin Hellish).
        List<ItemStack> items = new ArrayList<>();
        int dropsOriginales = 0;
        for (ItemStack item : event.getDrops()) {
            if (item != null && !item.getType().isAir()) {
                if (!isSlotMarker(item)) {
                    items.add(item);
                }
            }
            dropsOriginales++;
        }
        event.getDrops().clear();
        if (event.getKeepInventory()) {
            var inv = player.getInventory();
            for (ItemStack item : inv.getContents()) {
                if (item != null && !item.getType().isAir()) {
                    if (!isSlotMarker(item)) {
                        items.add(item);
                    }
                }
            }
            inv.clear();
        }
        plugin.getSLF4JLogger().info("Muerte de {}: drops={} keepInventory={} itemsACofrar={}",
                player.getName(), dropsOriginales, event.getKeepInventory(), items.size());

        Location chestLoc = null;
        if (!items.isEmpty()) {
            chestLoc = chest.place(loc.getBlock(), items);
            if (chestLoc != null) {
                store.markChestLocation(player.getUniqueId(), chestLoc);
            }
        }

        // Sonido del tridente al invocar trueno: uno en pitch 2 y otro en pitch 0
        loc.getWorld().playSound(loc,
                org.bukkit.Sound.ITEM_TRIDENT_THUNDER, 1.0f, 2.0f);
        loc.getWorld().playSound(loc,
                org.bukkit.Sound.ITEM_TRIDENT_THUNDER, 1.0f, 0.0f);

        String nearDeathLog = event.deathMessage() != null
                ? LegacyComponentSerializer.legacySection()
                        .serialize(event.deathMessage()) : player.getName() + " murió";
        for (Player nearby : loc.getWorld().getNearbyEntities(loc, 30, 30, 30,
                p -> p instanceof Player && p != player && ((Player) p).isOnline())
                .stream().map(p -> (Player) p).toList()) {
            nearby.sendActionBar(net.kyori.adventure.text.Component.text(
                    "§4§l☠ §c" + nearDeathLog));
        }

        // Fantasma espectador: guardar estado exacto y restaurarlo tras cualquier
        // respawn automatico para que nunca acabe en el spawn antes del kick.
        Location deathLocation = loc.clone();
        org.bukkit.util.Vector deathVelocity = player.getVelocity();
        store.markPending(player.getUniqueId(), player.getGameMode());
        store.markDeathLocation(player.getUniqueId(), deathLocation);
        player.setGameMode(GameMode.SPECTATOR);

        // La cabeza flota donde estaba la del jugador hasta el kick
        heads.spawn(player);

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (player.getLocation().getBlockX() == deathLocation.getBlockX()
                    && player.getLocation().getBlockY() == deathLocation.getBlockY()
                    && player.getLocation().getBlockZ() == deathLocation.getBlockZ()) {
                return;
            }
            restoreDeathSpot(player, deathLocation, deathVelocity);
        }, 2L);

        // Kick a los 5 segundos con el log de muerte crudo tal cual sale en chat
        String deathLog = LegacyComponentSerializer.legacySection()
                .serialize(event.deathMessage());
        Location chestSnap = chestLoc != null ? chestLoc.clone() : null;

        // Sistema de vidas: resta 1 vida (o mas via asesino), y si llega a 0
        // PERMABANEA temporalmente (con su propio kick). Devuelve vidas restantes.
        int remainingLives = plugin.lives().handleDeath(
                player, player.getKiller(), deathLog);

        // "Wither" solo si el jugador acumuló más de 10 muertes totales.
        if (plugin.lives().totalDeaths(player.getUniqueId()) > 10) {
            loc.getWorld().playSound(loc, org.bukkit.Sound.ENTITY_WITHER_DEATH,
                    6.0f, 0.7f);
            loc.getWorld().playSound(loc, org.bukkit.Sound.BLOCK_BEACON_DEACTIVATE,
                    5.0f, 0.6f);
        }

        // Aviso de vidas restantes en chat (tambien por privado al jugador).
        String livesMsg = Tr.t(player, "§6[Vidas] §7Vidas restantes: §c" + remainingLives, "§6[Lives] §7Lives left: §c" + remainingLives);
        player.sendMessage(livesMsg);
        if (plugin.lives().isPermaBanned(player.getName())) {
            // Ya lo kickeo LivesManager con el mensaje PERMABANEADO; no duplicar.
            return;
        }

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            heads.remove(player.getUniqueId());
            if (player.isOnline()) {
                Component message = Component.text()
                        .append(Component.text("Death", NamedTextColor.GOLD))
                        .append(Component.newline())
                        .append(Component.newline())
                        .append(Component.text(deathLog))
                        .append(Component.newline())
                        .append(Component.newline())
                        .append(Component.text(livesMsg))
                        .build();
                if (chestSnap != null) {
                    String chestMsg = Tr.t(player, "§7Tu cofre está en §b" + coordsOf(chestSnap), "§7Your chest is at §b" + coordsOf(chestSnap));
                    message = message.append(Component.newline())
                            .append(Component.newline())
                            .append(Component.text(chestMsg));
                }
                player.kick(message);
            }
        }, KICK_DELAY_TICKS);
    }

    @EventHandler
    public void onPostRespawn(
            com.destroystokyo.paper.event.player.PlayerPostRespawnEvent event) {
        Player player = event.getPlayer();
        Location chestLoc = store.takeChestLocation(player.getUniqueId());
        if (chestLoc != null) {
            String chestMsg = Tr.t(player, "§6[Death] §eHas muerto. §7Tu cofre con tus items está en §b" + coordsOf(chestLoc) + "§7.", "§6[Death] §eYou died. §7Your chest with your items is at §b" + coordsOf(chestLoc) + "§7.");
            player.sendMessage(chestMsg);
        }
        Location deathLocation = store.takeDeathLocation(player.getUniqueId());
        if (deathLocation == null) {
            return;
        }
        org.bukkit.util.Vector velocity = player.getVelocity();
        // Un tick despues del respawn para que ningun paquete de posicion del
        // cliente lo sobreescriba
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                restoreDeathSpot(player, deathLocation, velocity);
            }
        });
    }

    private void restoreDeathSpot(Player player, Location deathLocation,
                                  org.bukkit.util.Vector velocity) {
        player.teleport(deathLocation);
        player.setVelocity(velocity);
        player.setFallDistance(0.0f);
    }

    private static String coordsOf(Location l) {
        return String.format("%.0f, %.0f, %.0f (%s)",
                l.getX(), l.getY(), l.getZ(), l.getWorld().getName());
    }

    /**
     * Detecta el marcador de slot sellado de Hellish (STRUCTURE_VOID llamado
     * "Slot Bloqueado") por sus propiedades, sin depender del plugin Hellish.
     */
    private static boolean isSlotMarker(ItemStack item) {
        if (item == null || item.getType() != org.bukkit.Material.STRUCTURE_VOID
                || !item.hasItemMeta()) {
            return false;
        }
        var meta = item.getItemMeta();
        return meta != null && meta.hasDisplayName();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        heads.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Location chestLoc = store.takeChestLocation(player.getUniqueId());
        if (chestLoc != null) {
            player.sendMessage("§6[Death] §eNo olvides recoger tu cofre. §7Está en §b"
                    + coordsOf(chestLoc) + "§7.");
        }
        store.takeDeathLocation(player.getUniqueId());
        GameMode previous = store.takePending(player.getUniqueId());
        if (previous == null) {
            return;
        }
        player.setGameMode(previous);
        Location respawn = resolveRespawnLocation(player);
        Location safe = findSafeLocation(respawn);
        if (safe == null) safe = respawn;
        plugin.getSLF4JLogger().info("Respawn de {}: solicitado={}, resuelto={} safe={} mundoActual={}", player.getName(), player.getRespawnLocation(), respawn, safe, player.getWorld().getName());
        Location target = safe;
        // Asegura chunk cargado antes de teleport
        if (target.getWorld() != null && !target.getChunk().isLoaded()) {
            target.getChunk().load();
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                // Usa teleportAsync para cross-dimension seguro
                player.teleportAsync(target).thenRun(() -> player.setFallDistance(0));
            }
        });
    }

    private Location resolveRespawnLocation(Player player) {
        // 1) Respawn personal (cama / ancla / spawnpoint) - global
        Location respawn = player.getRespawnLocation();
        if (respawn != null && respawn.getWorld() != null) {
            return respawn.clone();
        }
        // Fallback cama legacy si existe
        try {
            Location bed = player.getBedSpawnLocation();
            if (bed != null && bed.getWorld() != null) return bed.clone();
            if (bed != null) {
                // bed world null -> intenta resolver por nombre si se puede
                World w = org.bukkit.Bukkit.getWorld("world");
                if (w != null) {
                    Location loc = new Location(w, bed.getX(), bed.getY(), bed.getZ(), bed.getYaw(), bed.getPitch());
                    return loc;
                }
            }
        } catch (Throwable ignored) {}
        // 2) Fallback a overworld, NO al mundo actual (evita mandar a inicio de dimension)
        World overworld = org.bukkit.Bukkit.getWorld("world");
        if (overworld == null) {
            overworld = org.bukkit.Bukkit.getWorlds().stream()
                    .filter(w -> w.getEnvironment() == World.Environment.NORMAL)
                    .findFirst().orElse(player.getWorld());
        }
        if (overworld == null) overworld = player.getWorld();
        return overworld.getSpawnLocation().clone();
    }

    private Location findSafeLocation(Location loc) {
        if (loc == null || loc.getWorld() == null) return loc;
        World w = loc.getWorld();
        // Si la ubicacion es segura (aire para pies/cabeza y solido debajo), devolver tal cual
        if (isSafeForRespawn(w, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ())) {
            return loc.clone();
        }
        // Busca el bloque mas alto seguro cercano
        int x = loc.getBlockX(), z = loc.getBlockZ();
        int y = w.getHighestBlockYAt(x, z);
        // Si highest es aire (void), usa y original +1
        if (y <= w.getMinHeight()) y = loc.getBlockY();
        for (int dy = 2; dy >= -2; dy--) {
            int ny = y + dy;
            if (isSafeForRespawn(w, x, ny, z)) {
                Location safe = new Location(w, x + 0.5, ny, z + 0.5, loc.getYaw(), loc.getPitch());
                return safe;
            }
        }
        // Busca en radio 3
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
            int nx = x + dx, nz = z + dz;
            int ny = w.getHighestBlockYAt(nx, nz);
            if (isSafeForRespawn(w, nx, ny, nz)) {
                return new Location(w, nx + 0.5, ny, nz + 0.5, loc.getYaw(), loc.getPitch());
            }
            if (isSafeForRespawn(w, nx, ny+1, nz)) {
                return new Location(w, nx + 0.5, ny+1, nz + 0.5, loc.getYaw(), loc.getPitch());
            }
        }
        // Ultimo recurso: spawn del overworld ajustado
        return loc.clone();
    }

    private boolean isSafeForRespawn(World w, int x, int y, int z) {
        org.bukkit.Material feet = w.getBlockAt(x, y, z).getType();
        org.bukkit.Material head = w.getBlockAt(x, y+1, z).getType();
        org.bukkit.Material below = w.getBlockAt(x, y-1, z).getType();
        boolean feetOk = feet.isAir() || !feet.isSolid();
        boolean headOk = head.isAir() || !head.isSolid();
        boolean belowOk = below.isSolid() && !below.isAir();
        // Evita lava/fuego
        if (below == org.bukkit.Material.LAVA || below == org.bukkit.Material.FIRE) return false;
        if (feet == org.bukkit.Material.LAVA || head == org.bukkit.Material.LAVA) return false;
        return feetOk && headOk && belowOk;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(org.bukkit.event.player.PlayerRespawnEvent event) {
        // Asegura que el respawn normal (sin kick) tambien vaya a spawnpoint correcto y seguro
        Location current = event.getRespawnLocation();
        Location resolved = resolveRespawnLocation(event.getPlayer());
        // Si el evento ya tiene un respawn personal valido, lo dejamos, pero lo hacemos seguro
        if (current != null && current.getWorld() != null && current.distance(resolved) > 2) {
            // Si difiere mucho, es que el evento eligio dimension start en lugar de spawnpoint
            plugin.getSLF4JLogger().warn("RespawnEvent corrigiendo {} de {} a {}", event.getPlayer().getName(), current, resolved);
            Location safe = findSafeLocation(resolved);
            event.setRespawnLocation(safe != null ? safe : resolved);
        } else if (current != null) {
            Location safe = findSafeLocation(current);
            if (safe != null && !safe.equals(current)) event.setRespawnLocation(safe);
        }
    }
}

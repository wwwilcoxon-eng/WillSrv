package dev.xautral.death.lives;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.BanEntry;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Totem of Life: sustituye al Resurrecto Bíblico como mecanismo de revivir a
 * jugadores PERMABANEADOS por perder todas sus vidas.
 *
 * Se craftea con un totem de inmortalidad en el centro y bloques de oro a los
 * lados. Click derecho abre una interfaz con la lista de caídos y su motivo;
 * al elegir pide confirmacion, consume el totem y revive (con efectos epicos,
 * teleport al spawn de quien revive y MOTD durante 5 minutos).
 */
public final class TotemOfLife implements Listener {

    private final JavaPlugin plugin;
    private final LivesManager lives;
    private final NamespacedKey key;
    private final Map<UUID, String> confirm = new HashMap<>();

    public TotemOfLife(JavaPlugin plugin, LivesManager lives) {
        this.plugin = plugin;
        this.lives = lives;
        this.key = new NamespacedKey(plugin, "totem_of_life");
    }

    public NamespacedKey key() {
        return key;
    }

    public ItemStack create() {
        ItemStack item = new ItemStack(Material.TOTEM_OF_UNDYING);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("§d§lTotem of Life"));
            meta.lore(List.of(
                    Component.text("§7Totem sagrado para revivir"),
                    Component.text("§7a jugadores §4PERMABANEADOS§7."),
                    Component.text(" "),
                    Component.text("§eClick derecho §7para ver los caídos")));
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer()
                    .set(key, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    public boolean isTotem(ItemStack item) {
        if (item == null || item.getType() != Material.TOTEM_OF_UNDYING
                || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta m = item.getItemMeta();
        return m != null
                && m.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    public void registerRecipe() {
        ShapedRecipe recipe = new ShapedRecipe(key, create());
        recipe.shape("GGG", "GTG", "GGG");
        recipe.setIngredient('G', Material.GOLD_BLOCK);
        recipe.setIngredient('T', Material.TOTEM_OF_UNDYING);
        Bukkit.addRecipe(recipe);
        plugin.getSLF4JLogger()
                .info("Totem of Life recipe registrado (totem + bloques de oro)");
    }

    private List<BanEntry> permaBanned() {
        List<BanEntry> out = new ArrayList<>();
        for (BanEntry be : Bukkit.getBanList(BanList.Type.NAME).getBanEntries()) {
            if (be.getReason() != null
                    && be.getReason().startsWith(LivesManager.BAN_PREFIX)) {
                out.add(be);
            }
        }
        return out;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR
                && e.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = e.getItem();
        if (!isTotem(item)) {
            return;
        }
        e.setCancelled(true);
        openList(e.getPlayer());
        e.getPlayer().getWorld()
                .playSound(e.getPlayer().getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1f, 1f);
    }

    private void openList(Player p) {
        List<BanEntry> bans = permaBanned();
        Inventory inv = Bukkit.createInventory(null, 54,
                Component.text("§dTotem of Life §7- Caídos"));
        int slot = 0;
        for (BanEntry be : bans) {
            if (slot >= 45) {
                break;
            }
            String name = be.getTarget();
            String deathLog = be.getReason().substring(LivesManager.BAN_PREFIX.length());
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta m = (SkullMeta) head.getItemMeta();
            if (m != null) {
                m.displayName(Component.text("§e" + name));
                m.lore(List.of(
                        Component.text("§7Muerte: §f" + deathLog),
                        Component.text("§7Vidas: §c0"),
                        Component.text(" "),
                        Component.text("§aClick para revivir")));
                try {
                    m.setOwningPlayer(Bukkit.getOfflinePlayer(name));
                } catch (Throwable ignored) {
                }
                head.setItemMeta(m);
            }
            inv.setItem(slot++, head);
        }
        if (slot == 0) {
            ItemStack info = new ItemStack(Material.BARRIER);
            ItemMeta m = info.getItemMeta();
            if (m != null) {
                m.displayName(Component.text("§a¡Nadie PERMABANEADO!"));
                m.lore(List.of(Component.text("§7No hay jugadores caídos.")));
                info.setItemMeta(m);
            }
            inv.setItem(22, info);
        }
        for (int i = 45; i < 54; i++) {
            inv.setItem(i, new ItemStack(Material.RED_STAINED_GLASS_PANE));
        }
        p.openInventory(inv);
    }

    private void openConfirm(Player p, String name) {
        confirm.put(p.getUniqueId(), name);
        Inventory inv = Bukkit.createInventory(null, 27,
                Component.text("§dConfirmar revivir §7- " + name));
        ItemStack yes = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
        ItemMeta ym = yes.getItemMeta();
        if (ym != null) {
            ym.displayName(Component.text("§a§lCONFIRMAR"));
            ym.lore(List.of(Component.text("§7Consumirá tu Totem of Life")));
            yes.setItemMeta(ym);
        }
        ItemStack no = new ItemStack(Material.RED_STAINED_GLASS_PANE);
        ItemMeta nm = no.getItemMeta();
        if (nm != null) {
            nm.displayName(Component.text("§c§lCANCELAR"));
            no.setItemMeta(nm);
        }
        inv.setItem(11, yes);
        inv.setItem(15, no);
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fm = filler.getItemMeta();
        if (fm != null) {
            fm.displayName(Component.text(" "));
            filler.setItemMeta(fm);
        }
        for (int i = 0; i < 27; i++) {
            if (inv.getItem(i) == null) {
                inv.setItem(i, filler);
            }
        }
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) {
            return;
        }
        String rawTitle = PlainTextComponentSerializer.plainText()
                .serialize(e.getView().title());
        // IMPORTANTE: solo bloqueamos las GUI de este item. Sin este gate,
        // se cancelaria TODO click en CUALQUIER inventario (cofres, propio,
        // crafting) de todos los jugadores, dejando el inventario bloqueado.
        boolean isList = rawTitle.startsWith("Totem of Life");
        boolean isConfirm = rawTitle.startsWith("Confirmar revivir");
        if (!isList && !isConfirm) {
            return;
        }
        e.setCancelled(true);
        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()
                || !clicked.getItemMeta().hasDisplayName()) {
            return;
        }

        if (isConfirm) {
            if (clicked.getType() == Material.LIME_STAINED_GLASS_PANE) {
                String name = confirm.remove(p.getUniqueId());
                if (name != null) {
                    revive(p, name);
                }
            }
            p.closeInventory();
            return;
        }

        // Lista de caidos
        if (clicked.getType() != Material.PLAYER_HEAD) {
            return;
        }
        String name = PlainTextComponentSerializer.plainText()
                .serialize(clicked.getItemMeta().displayName())
                .replaceAll("§[0-9a-fk-or]", "").trim();
        if (name.isEmpty()) {
            return;
        }
        openConfirm(p, name);
    }

    private void revive(Player p, String name) {
        BanEntry be = Bukkit.getBanList(BanList.Type.NAME).getBanEntry(name);
        if (be == null || be.getReason() == null
                || !be.getReason().startsWith(LivesManager.BAN_PREFIX)) {
            p.sendMessage("§c" + name + " ya no está permabaneado.");
            return;
        }
        ItemStack totem = findTotem(p);
        if (totem == null) {
            p.sendMessage("§cNecesitas un §dTotem of Life §cen tu inventario.");
            return;
        }
        totem.setAmount(totem.getAmount() - 1);

        lives.onRevive(p, name);

        p.closeInventory();
        Bukkit.broadcast(Component.text("§d§l[Totem of Life] §a" + p.getName()
                + " §7ha resucitado a §e" + name + " §7!"));
        for (Player pl : Bukkit.getOnlinePlayers()) {
            pl.playSound(pl.getLocation(), Sound.ITEM_TOTEM_USE, 1.5f, 1f);
            pl.playSound(pl.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1f, 1.4f);
            pl.playSound(pl.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            pl.spawnParticle(org.bukkit.Particle.TOTEM_OF_UNDYING,
                    pl.getLocation().clone().add(0, 1, 0), 40, 0.5, 0.5, 0.5, 0.2);
        }
        plugin.getSLF4JLogger().info("{} resucito a {} con Totem of Life",
                p.getName(), name);
    }

    private ItemStack findTotem(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (isTotem(it)) {
                return it;
            }
        }
        return null;
    }
}

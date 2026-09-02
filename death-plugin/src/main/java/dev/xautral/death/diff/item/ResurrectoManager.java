package dev.xautral.death.diff.item;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
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
import java.util.List;

public final class ResurrectoManager implements Listener {

    private final JavaPlugin plugin;
    private final NamespacedKey key;

    public ResurrectoManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "resurrecto_biblico");
    }

    public NamespacedKey key() { return key; }

    public ItemStack createItem() {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta meta = book.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("§d§lResurrecto Bíblico").color(NamedTextColor.LIGHT_PURPLE));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("§7Libro sagrado para revivir"));
            lore.add(Component.text("§7a los caídos sin corazones"));
            lore.add(Component.text(" "));
            lore.add(Component.text("§eClick derecho §7para ver baneados"));
            lore.add(Component.text("§7por LifeSteal / Slots"));
            meta.lore(lore);
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte)1);
            book.setItemMeta(meta);
        }
        return book;
    }

    public boolean isResurrecto(ItemStack item) {
        if (item == null || item.getType() != Material.ENCHANTED_BOOK || !item.hasItemMeta()) return false;
        var meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    public void registerRecipe() {
        ShapedRecipe recipe = new ShapedRecipe(key, createItem());
        recipe.shape("EEE","EBE","EEE");
        recipe.setIngredient('E', Material.EMERALD);
        recipe.setIngredient('B', Material.ENCHANTED_BOOK);
        Bukkit.addRecipe(recipe);
        plugin.getSLF4JLogger().info("Resurrecto Bíblico recipe registrado (esmeraldas + libro encantado)");
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = e.getItem();
        if (!isResurrecto(item)) return;
        e.setCancelled(true);
        openGui(e.getPlayer());
        e.getPlayer().getWorld().playSound(e.getPlayer().getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1f);
        e.getPlayer().getWorld().spawnParticle(org.bukkit.Particle.ENCHANT, e.getPlayer().getLocation().clone().add(0,1,0), 15, 0.4,0.4,0.4, 0.5);
    }

    private void openGui(Player p) {
        var bans = Bukkit.getBanList(org.bukkit.BanList.Type.NAME).getBanEntries();
        // filtra solo LifeSteal / slots? muestra todos pero prioriza LifeSteal
        List<org.bukkit.BanEntry> list = new ArrayList<>(bans);
        // Si hay baneados por slots, también mostrará Hellish slot bans que guardamos como ban con reason "Slots bloqueados"
        Inventory inv = Bukkit.createInventory(null, 54, Component.text("§dResurrecto Bíblico §7- Baneados"));
        int slot = 0;
        for (var entry : list) {
            if (slot >= 45) break;
            String name = entry.getTarget();
            String reason = entry.getReason() != null ? entry.getReason() : "Desconocido";
            // Solo muestra LifeSteal / Slots, pero si hay pocos, muestra todos
            boolean isLifeSteal = reason.toLowerCase().contains("corazon") || reason.toLowerCase().contains("lifesteal") || reason.toLowerCase().contains("slot");
            // Si hay muchos baneados y no es LifeSteal, salta a menos que sea pocos
            if (list.size() > 10 && !isLifeSteal) continue;
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            var meta = (SkullMeta) head.getItemMeta();
            if (meta != null) {
                meta.displayName(Component.text("§e" + name));
                List<Component> lore = new ArrayList<>();
                lore.add(Component.text("§7Razón: §f" + reason));
                lore.add(Component.text("§7Fuente: §f" + (entry.getSource() != null ? entry.getSource() : "Desconocido")));
                if (entry.getExpiration() != null) lore.add(Component.text("§7Expira: §f" + entry.getExpiration()));
                lore.add(Component.text(" "));
                lore.add(Component.text("§aClick para resucitar §7(desbanear)"));
                lore.add(Component.text("§7Coste: §dResurrecto Bíblico"));
                meta.lore(lore);
                // intenta set owner
                try { meta.setOwningPlayer(Bukkit.getOfflinePlayer(name)); } catch (Throwable ignored) {}
                head.setItemMeta(meta);
            }
            inv.setItem(slot++, head);
        }
        if (slot == 0) {
            ItemStack info = new ItemStack(Material.BARRIER);
            var m = info.getItemMeta();
            if (m != null) {
                m.displayName(Component.text("§a¡Nadie baneado!"));
                m.lore(List.of(Component.text("§7No hay jugadores baneados por LifeSteal/Slots")));
                info.setItemMeta(m);
            }
            inv.setItem(22, info);
        }
        // botones decorativos
        ItemStack close = new ItemStack(Material.RED_STAINED_GLASS_PANE);
        var cm = close.getItemMeta();
        if (cm != null) { cm.displayName(Component.text("§cCerrar")); close.setItemMeta(cm); }
        for (int i=45;i<54;i++) inv.setItem(i, close);
        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.8f, 1f);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!e.getView().title().toString().contains("Resurrecto")) return;
        e.setCancelled(true);
        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR || clicked.getType() == Material.RED_STAINED_GLASS_PANE) {
            if (clicked != null && clicked.getType() == Material.RED_STAINED_GLASS_PANE) p.closeInventory();
            return;
        }
        if (clicked.getType() != Material.PLAYER_HEAD) return;
        var meta = clicked.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return;
        String name = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(meta.displayName()).replace("§e","").trim();
        // plain text may include color codes, strip
        name = name.replaceAll("§[0-9a-fk-or]", "").trim();
        // Intenta desbanear
        var banList = Bukkit.getBanList(org.bukkit.BanList.Type.NAME);
        if (!banList.isBanned(name)) {
            p.sendMessage("§c" + name + " ya no está baneado.");
            return;
        }
        // Consume un Resurrecto del inventario
        ItemStack resurrecto = null;
        int slotIdx = -1;
        for (int i=0;i<p.getInventory().getSize();i++) {
            var it = p.getInventory().getItem(i);
            if (isResurrecto(it)) { resurrecto = it; slotIdx = i; break; }
        }
        if (resurrecto == null) {
            p.sendMessage("§cNecesitas un §dResurrecto Bíblico §cen tu inventario.");
            return;
        }
        // consume uno
        resurrecto.setAmount(resurrecto.getAmount() - 1);
        if (resurrecto.getAmount() <= 0) p.getInventory().setItem(slotIdx, null);
        banList.pardon(name);
        // Si era baneo por slots, también limpia locks
        try {
            var store = new dev.xautral.death.store.SlotLockStore(plugin);
            // no tenemos acceso directo, pero intentamos limpiar archivo
            // Por ahora solo desbaneo
        } catch (Throwable ignored) {}
        p.closeInventory();
        Bukkit.broadcast(Component.text("§d§l[Resurrecto] §a" + p.getName() + " §7ha resucitado a §e" + name + " §7!"));
        Bukkit.getOnlinePlayers().forEach(pl -> {
            pl.playSound(pl.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.2f);
            pl.playSound(pl.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 0.8f);
            pl.spawnParticle(org.bukkit.Particle.TOTEM_OF_UNDYING, pl.getLocation().clone().add(0,1,0), 10, 0.4,0.4,0.4, 0.1);
        });
        plugin.getSLF4JLogger().info("{} resucito a {} via Resurrecto Biblico", p.getName(), name);
    }
}

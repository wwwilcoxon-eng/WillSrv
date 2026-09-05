package dev.willsrv.translate;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

public final class CountryGui implements Listener {

    private final TranslatePlugin plugin;
    private final TranslateManager manager;
    private final SuffixManager suffixManager;
    private final Map<UUID, Inventory> openGuis = new HashMap<>();
    private final Map<Integer, Lang> slotToLang = new HashMap<>();

    private static final String GUI_TITLE = "§8» §6Selecciona tu País §8«";

    public CountryGui(TranslatePlugin plugin, TranslateManager manager, SuffixManager suffixManager) {
        this.plugin = plugin;
        this.manager = manager;
        this.suffixManager = suffixManager;
    }

    public void open(Player player) {
        int size = 54;
        Inventory inv = Bukkit.createInventory(null, size, Component.text(GUI_TITLE));

        // Prepare list
        List<Lang> langs = Arrays.asList(Lang.values());
        // Sort maybe by popularity? Keep enum order
        slotToLang.clear();
        // Fill border
        ItemStack border = createPane(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 9; i++) inv.setItem(i, border);
        for (int i = 45; i < 54; i++) inv.setItem(i, border);
        inv.setItem(9, border); inv.setItem(17, border);
        inv.setItem(18, border); inv.setItem(26, border);
        inv.setItem(27, border); inv.setItem(35, border);
        inv.setItem(36, border); inv.setItem(44, border);

        int[] slots = new int[]{10,11,12,13,14,15,16, 19,20,21,22,23,24,25, 28,29,30,31,32,33,34, 37,38,39,40,41,42,43};
        int idx = 0;
        for (Lang lang : langs) {
            if (idx >= slots.length) break;
            int slot = slots[idx++];
            ItemStack item = createLangItem(lang, manager.getLang(player) == lang);
            inv.setItem(slot, item);
            slotToLang.put(slot, lang);
        }
        // Auto-detect suggestion
        try {
            String locale = player.getLocale();
            Lang detected = Lang.fromCode(locale);
            if (detected != null) {
                // highlight detected?
                // Add lore?
            }
        } catch (Throwable ignored) {}

        // Info item at bottom center
        ItemStack info = new ItemStack(Material.BOOK);
        ItemMeta meta = info.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("§e¿Para qué es esto?", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text("§7Selecciona tu país/idioma para", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("§7traducir el chat automáticamente.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("§7Se mostrará §f[PAIS] §7en tu sufijo", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("§7por §e" + manager.getSuffixDurationSeconds() + "s§7 cada vez que entres.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("§7Usa §e/translate on|off §7para activar.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
            ));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            info.setItemMeta(meta);
        }
        inv.setItem(49, info);

        player.openInventory(inv);
        openGuis.put(player.getUniqueId(), inv);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7f, 1.2f);
    }

    private ItemStack createLangItem(Lang lang, boolean selected) {
        ItemStack item = new ItemStack(lang.icon());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            Component name = Component.text(lang.flag() + " " + lang.nativeName(), selected ? NamedTextColor.GREEN : NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, selected);
            meta.displayName(name);
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("§7País: §f" + lang.countryName() + " (" + lang.countryCode() + ")", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("§7Idioma: §f" + lang.code(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
            if (selected) {
                lore.add(Component.text("§a✔ Seleccionado actualmente", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
            } else {
                lore.add(Component.text("§e▶ Click para seleccionar", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
            }
            lore.add(Component.text("§8Sufijo: " + lang.displaySuffix(), NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            if (selected) meta.setEnchantmentGlintOverride(true);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createPane(Material mat, String name) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name));
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;
        Inventory top = player.getOpenInventory().getTopInventory();
        if (!openGuis.containsKey(player.getUniqueId())) return;
        if (!top.equals(openGuis.get(player.getUniqueId()))) return;
        e.setCancelled(true);
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= top.getSize()) return;
        Lang lang = slotToLang.get(slot);
        if (lang == null) return;

        // select
        manager.setLang(player, lang);
        player.closeInventory();
        player.sendMessage(Component.text("§a✔ Has seleccionado ", NamedTextColor.GREEN)
                .append(Component.text(lang.flag() + " " + lang.nativeName() + " §7(" + lang.countryCode() + ")", NamedTextColor.YELLOW))
                .append(Component.text(" §8[" + lang.code() + "]", NamedTextColor.GRAY)));
        player.sendMessage(Component.text("§7Sufijo: " + lang.displaySuffix() + " §7se mostrará por §e" + manager.getSuffixDurationSeconds() + "s §7al entrar.", NamedTextColor.GRAY));
        player.sendMessage(Component.text("§7Traducción: " + (manager.isTranslateEnabled(player) ? "§aON" : "§cOFF") + " §7(usa §e/translate on|off§7)", NamedTextColor.GRAY));
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.1f);
        // apply suffix immediately
        suffixManager.applyTemporarySuffix(player);
        // Show to all: announce join with country? optional
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.equals(player)) continue;
            online.sendMessage(Component.text("§8[§6Translate§8] §e" + player.getName() + " §7es de " + lang.flag() + " " + lang.countryName() + " " + lang.displaySuffix(), NamedTextColor.GRAY));
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        openGuis.remove(p.getUniqueId());
    }

    public boolean isGui(Inventory inv) {
        return openGuis.containsValue(inv);
    }
}

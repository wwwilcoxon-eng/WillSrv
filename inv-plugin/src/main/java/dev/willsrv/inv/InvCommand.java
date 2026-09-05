package dev.willsrv.inv;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * /inv [jugador] — abre una vista de solo lectura del inventario del jugador:
 * armadura, manos, inventario principal, hotbar.
 */
public final class InvCommand implements CommandExecutor, TabCompleter {

    static final String TITLE_PREFIX = "Inventario de ";
    static final String TITLE_PREFIX_ES = "Inventario de ";
    static final String TITLE_PREFIX_EN = "Inventory of ";

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {
        Player viewer;
        if (sender instanceof Player p) {
            viewer = p;
        } else {
            sender.sendMessage("§cOnly players can use /inv. / Solo jugadores pueden usar /inv.");
            return true;
        }
        Player target = args.length >= 1
                ? Bukkit.getPlayerExact(args[0])
                : viewer;
        if (target == null) {
            String msg = Tr.isEnglish(viewer) ? "§cPlayer not found: " : "§cJugador no encontrado: ";
            sender.sendMessage(msg + (args.length >= 1 ? args[0] : ""));
            return true;
        }

        PlayerInventory source = target.getInventory();
        String prefix = Tr.isEnglish(viewer) ? TITLE_PREFIX_EN : TITLE_PREFIX_ES;
        Inventory gui = Bukkit.createInventory(null, 54,
                Component.text(prefix + target.getName()));
        // Storage contiene hotbar (0-8) + inventario principal (9-35) = 36 slots.
        // Usamos getStorageContents para evitar depender de getContents que en
        // Paper 26.x cambia de tamaño (43) y provocaba Index 43 OOB en logs.
        ItemStack[] storage = source.getStorageContents();
        ItemStack[] armor = source.getArmorContents(); // [0]=botas, [1]=piernas, [2]=pechera, [3]=casco
        if (armor == null) armor = new ItemStack[4];
        if (storage == null) storage = new ItemStack[0];

        // Fila superior: armadura (casco..botas) + manos + cursor  (defensivo contra arrays cortos)
        gui.setItem(0, armor.length > 3 ? armor[3] : null); // casco
        gui.setItem(1, armor.length > 2 ? armor[2] : null); // pechera
        gui.setItem(2, armor.length > 1 ? armor[1] : null); // piernas
        gui.setItem(3, armor.length > 0 ? armor[0] : null); // botas
        try { gui.setItem(4, source.getItemInOffHand()); } catch (Throwable ignored) { gui.setItem(4, null); }
        try { gui.setItem(5, source.getItemInMainHand()); } catch (Throwable ignored) { gui.setItem(5, null); }
        try { gui.setItem(6, target.getItemOnCursor()); } catch (Throwable ignored) { gui.setItem(6, null); }

        // Inventario principal: storage[9-35] → GUI slots 18-44 (con bounds check)
        for (int i = 9; i <= 35 && i < storage.length; i++) {
            int guiSlot = 9 + i; // 18..44
            if (guiSlot < gui.getSize()) {
                gui.setItem(guiSlot, storage[i]);
            }
        }
        // Hotbar: storage[0-8] → GUI slots 45-53
        for (int i = 0; i <= 8 && i < storage.length; i++) {
            int guiSlot = 45 + i;
            if (guiSlot < gui.getSize()) {
                gui.setItem(guiSlot, storage[i]);
            }
        }
        // Separadores
        fill(gui, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17);

        viewer.openInventory(gui);
        viewer.sendMessage(Tr.t(viewer, "§7Viendo el inventario de §e" + target.getName() + "§7.", "§7Viewing inventory of §e" + target.getName() + "§7."));
        return true;
    }

    static void fill(Inventory gui, int... slots) {
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(" "));
            pane.setItemMeta(meta);
        }
        for (int slot : slots) {
            gui.setItem(slot, pane);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String label, String[] args) {
        List<String> out = new ArrayList<>();
        String prefix = args.length > 0 ? args[args.length - 1].toLowerCase() : "";
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().toLowerCase().startsWith(prefix)) {
                out.add(player.getName());
            }
        }
        return out;
    }
}
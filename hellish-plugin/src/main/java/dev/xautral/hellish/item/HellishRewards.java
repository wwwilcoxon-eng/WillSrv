package dev.xautral.hellish.item;

import dev.xautral.hellish.util.AdvancementUtil;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Recompensas de Hellish: manzanas, totems y logros con soporte de idioma.
 */
public final class HellishRewards {

    public static final String ADV_NO_DEATH = "Full No-Death";
    public static final String ADV_NO_HIT = "Full No-Hit Perfect";
    public static final String ADV_ALL_BOSSES = "JungleSMP All Bosses (No Death)";
    public static final String ADV_NORMALITY = "De vuelta a la normalidad";

    private HellishRewards() {
    }

    public static void giveApples(Player player) {
        player.getInventory().addItem(new ItemStack(Material.GOLDEN_APPLE, 32));
        player.getInventory().addItem(new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 6));
    }

    /** Totem stackeable (hasta 6) que suelta el Jefe Husk. */
    public static ItemStack stackableTotem(NamespacedKey key) {
        ItemStack item = new ItemStack(Material.TOTEM_OF_UNDYING);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(net.kyori.adventure.text.Component.text("§6Totem del Jefe"));
            meta.setMaxStackSize(6);
            meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

}

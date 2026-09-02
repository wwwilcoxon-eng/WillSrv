package dev.xautral.death.item;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/**
 * La Angry Wand ("Vara del Odio"): click izquierdo o derecho sobre una
 * entidad y luego sobre otra hace que se ODIENTEN y peleen hasta la muerte.
 */
public final class AngryWand {

    private final NamespacedKey key;

    public AngryWand(NamespacedKey key) {
        this.key = key;
    }

    public ItemStack create() {
        ItemStack item = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("§4§lAngry Wand"));
            meta.setEnchantmentGlintOverride(true);
            meta.lore(List.of(
                    Component.text("§7Toca a una entidad: §bVictima A"),
                    Component.text("§7Toca a otra: §cVictima B"),
                    Component.text("§4¡Se odian al instante"),
                    Component.text("§4y pelean hasta la muerte!"),
                    Component.text("§8Apunta a una en guerra para ver"),
                    Component.text("§8a su enemigo brillar 5s.")));
            meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    public boolean isWand(ItemStack item) {
        if (item == null || item.getType() != Material.BLAZE_ROD || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer()
                .has(key, PersistentDataType.BYTE);
    }
}

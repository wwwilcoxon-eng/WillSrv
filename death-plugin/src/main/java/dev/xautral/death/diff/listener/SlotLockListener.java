package dev.xautral.death.diff.listener;

import dev.xautral.death.diff.DiffMode;
import dev.xautral.death.diff.DiffModeManager;
import dev.xautral.death.store.SlotLockStore;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Bloquea la interaccion con los slots sellados y maneja el uso del
 * Dodecaedro Angelical (click derecho = libera el ultimo slot sellado).
 * El marcador "Slot Bloqueado" (STRUCTURE VOID) no se puede mover, duplicar,
 * soltar, recoger, intercambiar de mano ni usar en el mundo.
 */
public final class SlotLockListener implements Listener {

    private final DiffModeManager manager;
    private final SlotLockStore store;
    private final NamespacedKey dodecaedroKey;

    public SlotLockListener(DiffModeManager manager, SlotLockStore store,
                            NamespacedKey dodecaedroKey) {
        this.manager = manager;
        this.store = store;
        this.dodecaedroKey = dodecaedroKey;
    }

    /**
     * El sellado de slots es una mecanica del evento Hellish: fuera de un
     * Hellish activo NUNCA se bloquea la interaccion con el inventario,
     * aunque queden datos/marcadores heredados de una partida anterior.
     */
    private boolean hellishActive() {
        return manager.isActive(DiffMode.HELLISH);
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!hellishActive()) {
            return;
        }
        // Nunca tocar un marcador: ni moverlo, ni duplicarlo, ni soltarlo
        if (SlotLockStore.isMarker(event.getCursor())
                || SlotLockStore.isMarker(event.getCurrentItem())) {
            event.setCancelled(true);
            return;
        }
        boolean bottom = event.getClickedInventory()
                == event.getView().getBottomInventory();
        if (bottom) {
            // Slot de inventario/armadura/ofhand sellado (0-40)
            if (store.isInvLocked(player.getUniqueId(), event.getSlot())) {
                event.setCancelled(true);
                return;
            }
            // Mesa de crafteo 2x2 del inventario (raw 1-4 en vista propia)
            int raw = event.getRawSlot();
            if (raw >= 1 && raw <= 4
                    && store.isCraftLocked(player.getUniqueId(), raw)) {
                event.setCancelled(true);
                return;
            }
        }
        // Teclas numericas: ni hacia sellado ni desde sellado
        if (event.getClick() == ClickType.NUMBER_KEY
                && event.getHotbarButton() >= 0
                && (store.isInvLocked(player.getUniqueId(), event.getHotbarButton())
                        || store.isInvLocked(player.getUniqueId(), event.getSlot()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!hellishActive()) {
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        for (int raw : event.getRawSlots()) {
            if (SlotLockStore.isMarker(event.getOldCursor())
                    || SlotLockStore.isMarker(event.getNewItems().get(raw))) {
                event.setCancelled(true);
                return;
            }
            if (raw < topSize) {
                continue;
            }
            int idx = raw - topSize;
            // Vista del propio inventario: crafteo 2x2 = idx 1-4
            if (idx >= 1 && idx <= 4
                    && store.isCraftLocked(player.getUniqueId(), idx)) {
                event.setCancelled(true);
                return;
            }
            if (store.isInvLocked(player.getUniqueId(), idx)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (!hellishActive()) {
            return;
        }
        if (store.isInvLocked(event.getPlayer().getUniqueId(), 40)
                || SlotLockStore.isMarker(event.getMainHandItem())
                || SlotLockStore.isMarker(event.getOffHandItem())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(
                    "§4[Hellish] §cNo puedes intercambiar esa mano.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!hellishActive()) {
            return;
        }
        if (SlotLockStore.isMarker(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(
                    "§4[Hellish] §cEse slot esta sellado, no puedes soltar el bloque.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(PlayerAttemptPickupItemEvent event) {
        if (!hellishActive()) {
            return;
        }
        if (SlotLockStore.isMarker(event.getItem().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || item.getType() == Material.AIR) {
            return;
        }
        // Un marcador sellado nunca se usa ni se coloca en el mundo
        if (SlotLockStore.isMarker(item)) {
            event.setCancelled(true);
            return;
        }
        if (item.getType() != Material.DIAMOND) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.getPersistentDataContainer()
                .has(dodecaedroKey, PersistentDataType.BYTE)) {
            return;
        }
        Player player = event.getPlayer();
        event.setCancelled(true);
        if (store.unlockLast(player)) {
            item.setAmount(item.getAmount() - 1);
            // Logro: De vuelta a la normalidad (con soporte de idioma)
            dev.xautral.death.diff.util.AdvancementUtil.grant(
                    player, dev.xautral.death.diff.util.AdvancementUtil.NORMALITY);
        } else {
            player.sendMessage("§6[Death] §7No tienes slots sellados que liberar.");
        }
    }
}
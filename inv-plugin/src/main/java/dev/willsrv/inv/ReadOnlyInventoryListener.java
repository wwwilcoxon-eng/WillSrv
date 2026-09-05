package dev.willsrv.inv;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * Las GUI de /inv y /ec son solo lectura: cancela cualquier interaccion.
 */
public final class ReadOnlyInventoryListener implements Listener {

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        if (title.startsWith(InvCommand.TITLE_PREFIX) || title.startsWith(InvCommand.TITLE_PREFIX_EN)
                || title.startsWith(EcCommand.TITLE_PREFIX) || title.startsWith(EcCommand.TITLE_PREFIX_EN)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        String title = event.getView().getTitle();
        if (title.startsWith(InvCommand.TITLE_PREFIX) || title.startsWith(InvCommand.TITLE_PREFIX_EN)
                || title.startsWith(EcCommand.TITLE_PREFIX) || title.startsWith(EcCommand.TITLE_PREFIX_EN)) {
            event.setCancelled(true);
        }
    }
}
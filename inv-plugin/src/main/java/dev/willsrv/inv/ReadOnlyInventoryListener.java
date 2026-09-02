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
        if (event.getView().getTitle().startsWith(InvCommand.TITLE_PREFIX)
                || event.getView().getTitle().startsWith(EcCommand.TITLE_PREFIX)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTitle().startsWith(InvCommand.TITLE_PREFIX)
                || event.getView().getTitle().startsWith(EcCommand.TITLE_PREFIX)) {
            event.setCancelled(true);
        }
    }
}
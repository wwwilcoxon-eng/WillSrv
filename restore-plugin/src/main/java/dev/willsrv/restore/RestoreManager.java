package dev.willsrv.restore;

import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Restore ya NO trackea chunks (eso causaba mucho lag: cada chunk, cada
 * bloqueo/rotura, cada movimiento escribia en un mapa que crecia sin parar
 * y se persistia en disco).
 *
 * Ahora solo restaura un chunk a su terreno natural (vanilla, con la seed del
 * mundo) cuando se ejecuta /restore chunk de forma explicita.
 */
public final class RestoreManager {

    private final JavaPlugin plugin;

    public RestoreManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Restaura el chunk a su terreno natural usando la seed del mundo,
     * eliminando toda modificacion de jugadores. Solo /restore chunk.
     *
     * Nota: en Paper 26.2 la regeneracion de chunks via API no esta soportada
     * (regenerateChunk lanza "Not supported in this Minecraft version!"). Aqui
     * lo detectamos y devolvemos false sin llenar el log de warnings.
     */
    public boolean restoreChunkAt(World w, int cx, int cz) {
        if (w == null) {
            return false;
        }
        try {
            boolean ok = w.regenerateChunk(cx, cz);
            plugin.getSLF4JLogger().info(
                    "Restore: chunk {} {} {} restaurado a vanilla (ok={})",
                    w.getName(), cx, cz, ok);
            return ok;
        } catch (Throwable ex) {
            // No spamear: solo aviso sencillo para que el admin sepa.
            plugin.getSLF4JLogger().info(
                    "Restore: la regeneracion de chunks no esta soportada en este Paper ({}), chunk {},{},{}",
                    ex.getClass().getSimpleName(), w.getName(), cx, cz);
            return false;
        }
    }
}
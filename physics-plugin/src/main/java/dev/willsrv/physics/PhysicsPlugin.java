package dev.willsrv.physics;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Physics: fisica de puertas estilo Garry's Mod.
 *
 * Al correr (o ir a mayor velocidad) y chocar contra una puerta de madera,
 * el puerta se rompe y su BlockDisplay sale volando, rebotando y rotando
 * contra el suelo con un sistema de gravedad y friccion propios, simulando
 * que cae con fisicas reales. No muestra ningun mensaje ni tell. Suena el
 * sonido de romper una puerta. Con Shift + click derecho puedes recoger la
 * puerta caida como item para volver a colocarla.
 */
public final class PhysicsPlugin extends JavaPlugin {

    private PhysicsManager manager;

    @Override
    public void onEnable() {
        manager = new PhysicsManager(this);
        manager.start();
        getLogger().info("Physics habilitado.");
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.stop();
        }
    }
}
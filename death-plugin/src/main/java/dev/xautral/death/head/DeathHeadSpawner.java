package dev.xautral.death.head;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Skull;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class DeathHeadSpawner {

    private final Map<UUID, FallingBlock> heads = new HashMap<>();

    public void spawn(Player player) {
        Location eye = player.getEyeLocation();
        Block block = eye.getBlock();

        // Capturar un BlockState de calavera con el dueño (skin) sin colocar
        // el bloque en el mundo: es lo que hace visible la cabeza al cliente
        Material previous = block.getType();
        block.setType(Material.PLAYER_HEAD);
        Skull skullState = (Skull) block.getState();
        skullState.setOwningPlayer(player);
        block.setType(previous);

        // Sin gravedad: con gravedad caia y se descartaba al tocar suelo en
        // menos de un tick, por eso nunca se veia. Flota hasta el kick.
        FallingBlock head = eye.getWorld().spawn(eye, FallingBlock.class, falling -> {
            falling.setBlockData(Material.PLAYER_HEAD.createBlockData());
            falling.setBlockState(skullState);
            falling.setDropItem(false);
            falling.setCancelDrop(true);
            falling.setHurtEntities(false);
            falling.setGlowing(true);
            falling.setGravity(false);
            falling.setPersistent(false);
        });
        heads.put(player.getUniqueId(), head);
    }

    public void remove(UUID uuid) {
        FallingBlock head = heads.remove(uuid);
        if (head != null && head.isValid()) {
            head.remove();
        }
    }
}

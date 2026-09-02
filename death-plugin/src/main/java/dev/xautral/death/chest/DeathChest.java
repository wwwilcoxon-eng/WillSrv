package dev.xautral.death.chest;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public final class DeathChest {

    private final org.slf4j.Logger logger;

    public DeathChest(org.slf4j.Logger logger) {
        this.logger = logger;
    }

    public Location place(Block feet, List<ItemStack> items) {
        List<ItemStack> compacted = compact(items);
        logger.info("Colocando cofre de muerte en " + feet.getWorld().getName()
                + " " + feet.getLocation() + " (" + compacted.size() + " stacks)");

        Block first = feet;
        first.setType(Material.CHEST);

        // IMPORTANTE: en Paper el inventario del estado del bloque escribe
        // directo al mundo. Llamar update() despues de llenarlo SOBREESCRIBE
        // el cofre con el snapshot vacio capturado antes, borrando el loot.
        if (compacted.size() <= 27) {
            fill(first, compacted);
            verify(first);
            return first.getLocation();
        }

        // Cofre doble: para que vanilla lo fusione en uno solo nunca deben
        // quedar frente a frente; misma orientacion y tipos LEFT/RIGHT.
        Block second = first.getRelative(BlockFace.EAST);
        if (!second.getType().isAir() && second.getType() != Material.CHEST) {
            second = first.getRelative(BlockFace.NORTH);
            applyData(second, BlockFace.EAST, org.bukkit.block.data.type.Chest.Type.LEFT);
            applyData(first, BlockFace.EAST, org.bukkit.block.data.type.Chest.Type.RIGHT);
        } else {
            applyData(second, BlockFace.SOUTH, org.bukkit.block.data.type.Chest.Type.LEFT);
            applyData(first, BlockFace.SOUTH, org.bukkit.block.data.type.Chest.Type.RIGHT);
        }

        int limitA = Math.min(27, compacted.size());
        fill(first, compacted.subList(0, limitA));
        fill(second, compacted.subList(27, Math.min(54, compacted.size())));
        verify(first);
        verify(second);

        if (compacted.size() > 54) {
            World world = first.getWorld();
            Location dropLoc = first.getLocation().add(0.5, 0.5, 0.5);
            for (ItemStack leftover : compacted.subList(54, compacted.size())) {
                world.dropItemNaturally(dropLoc, leftover);
            }
        }
        return first.getLocation();
    }

    private void applyData(Block block, BlockFace facing,
                           org.bukkit.block.data.type.Chest.Type type) {
        org.bukkit.block.data.type.Chest data =
                (org.bukkit.block.data.type.Chest) Material.CHEST.createBlockData();
        data.setFacing(facing);
        data.setType(type);
        block.setBlockData(data);
    }

    private void fill(Block block, List<ItemStack> stacks) {
        if (block.getState() instanceof Chest chest) {
            chest.getBlockInventory().setContents(stacks.toArray(new ItemStack[0]));
        }
    }

    private void verify(Block chestBlock) {
        Runnable check = () -> {
            if (chestBlock.getState() instanceof Chest chest) {
                long count = java.util.Arrays.stream(chest.getBlockInventory().getContents())
                        .filter(item -> item != null && !item.getType().isAir())
                        .count();
                logger.info("Verificacion cofre en " + chestBlock.getLocation()
                        + ": " + count + " items reales en el mundo");
            } else {
                logger.info("Verificacion cofre en " + chestBlock.getLocation()
                        + ": el bloque ya NO es un cofre (" + chestBlock.getType() + ")");
            }
        };
        check.run();
        org.bukkit.Bukkit.getScheduler().runTaskLater(
                org.bukkit.Bukkit.getPluginManager().getPlugin("Death"), check, 40L);
    }

    private List<ItemStack> compact(List<ItemStack> items) {
        List<ItemStack> result = new ArrayList<>();
        for (ItemStack item : items) {
            int amount = item.getAmount();
            int max = item.getMaxStackSize();
            while (amount > 0 && max > 1) {
                boolean merged = false;
                for (ItemStack target : result) {
                    if (target.getAmount() < max && target.isSimilar(item)) {
                        int move = Math.min(max - target.getAmount(), amount);
                        target.setAmount(target.getAmount() + move);
                        amount -= move;
                        merged = true;
                        if (amount == 0) {
                            break;
                        }
                    }
                }
                if (!merged && amount > 0) {
                    ItemStack copy = item.clone();
                    copy.setAmount(Math.min(amount, max));
                    result.add(copy);
                    amount -= copy.getAmount();
                    if (max == 1) {
                        break;
                    }
                    continue;
                }
                if (amount <= 0) {
                    break;
                }
            }
            if (max == 1 && amount > 0) {
                ItemStack copy = item.clone();
                copy.setAmount(amount);
                result.add(copy);
            }
        }
        return result;
    }
}

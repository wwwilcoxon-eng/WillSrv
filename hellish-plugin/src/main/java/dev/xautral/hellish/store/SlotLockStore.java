package dev.xautral.hellish.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Slots bloqueados por muerte durante Hellish. El bloqueo es ALEATORIO entre:
 * inventario y armadura (nunca la mano principal actual), la mesa de crafteo
 * 2x2 del inventario y, en el peor de los casos, el cursor.
 * Cada slot sellado muestra un BLOQUE DE STRUCTURE VOID ENCANTADO llamado
 * "Slot Bloqueado" para identificarlo. Solo se libera con un Dodecaedro.
 */
public final class SlotLockStore {

    /** Codigos: "0"-"39" inventario/armadura, "c1"-"c4" crafteo, "cursor". */
    private static final Type TYPE = new TypeToken<Map<String, List<String>>>() {
    }.getType();

    private final JavaPlugin plugin;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<UUID, List<String>> locked = new HashMap<>();
    private final Random random = new Random();
    private Path file;

    public SlotLockStore(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        file = plugin.getDataFolder().toPath().resolve("hellish_locks.json");
        try {
            if (!Files.exists(file)) {
                return;
            }
            Map<String, List<String>> loaded =
                    gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), TYPE);
            if (loaded != null) {
                loaded.forEach((k, v) -> locked.put(UUID.fromString(k), v));
            }
        } catch (IOException e) {
            plugin.getSLF4JLogger().error("No se pudo cargar hellish_locks.json "
                    + "(si el formato antiguo no es compatible, borra el archivo)", e);
        }
    }

    /**
     * Bloquea un slot ALEATORIO: 70% inventario/armadura (excepto la mano
     * principal actual), 20% mesa de crafteo del inventario, 10% cursor
     * (peor caso). Tira lo que hubiera dentro y coloca el marcador.
     */
    public void lockNextSlot(Player player) {
        List<String> slots = locked.computeIfAbsent(player.getUniqueId(),
                k -> new ArrayList<>());
        String code = pickRandomCode(player, slots);
        if (code == null) {
            player.sendMessage("§4[Hellish] §cNo queda nada mas que sellar...");
            return;
        }
        slots.add(code);
        save();

        // Si era un slot numerico, tira su contenido
        Integer invSlot = invIndex(code);
        if (invSlot != null) {
            ItemStack content = player.getInventory().getItem(invSlot);
            if (content != null && !content.getType().isAir()) {
                player.getInventory().setItem(invSlot, null);
                player.getWorld().dropItemNaturally(player.getLocation(), content);
            }
        } else if (code.startsWith("cursor")) {
            ItemStack onCursor = player.getItemOnCursor();
            if (onCursor != null && !onCursor.getType().isAir()) {
                player.setItemOnCursor(null);
                player.getWorld().dropItemNaturally(player.getLocation(), onCursor);
            }
        }

        player.sendMessage("§4[Hellish] §cTu muerte ha sellado tu §e"
                + slotName(code) + "§c para siempre...");
        decorate(player);
    }

    /** Eleccion aleatoria ponderada respetando lo ya sellado. */
    private String pickRandomCode(Player player, List<String> already) {
        int roll = random.nextInt(100);
        if (roll < 10 && !already.contains("cursor")) {
            return "cursor"; // peor caso: el cursor
        }
        if (roll < 30) {
            // Mesa de crafteo 2x2 del inventario: c1..c4
            List<String> free = new ArrayList<>();
            for (int i = 1; i <= 4; i++) {
                String code = "c" + i;
                if (!already.contains(code)) {
                    free.add(code);
                }
            }
            if (!free.isEmpty()) {
                return free.get(random.nextInt(free.size()));
            }
        }
        // Inventario/armadura: nunca la mano principal actual (hotbar seleccionada)
        int held = player.getInventory().getHeldItemSlot();
        List<Integer> free = new ArrayList<>();
        for (int i = 0; i <= 8; i++) {
            if (i != held && !already.contains(String.valueOf(i))) {
                free.add(i);
            }
        }
        for (int i = 36; i <= 40; i++) {
            if (!already.contains(String.valueOf(i))) {
                free.add(i);
            }
        }
        if (free.isEmpty()) {
            // Respaldo: cualquier slot de almacenamiento libre
            for (int i = 9; i <= 35; i++) {
                if (!already.contains(String.valueOf(i))) {
                    free.add(i);
                }
            }
        }
        if (free.isEmpty()) {
            return null;
        }
        return String.valueOf(free.get(random.nextInt(free.size())));
    }

    /** Libera el ultimo slot bloqueado (el mas reciente). */
    public boolean unlockLast(Player player) {
        List<String> slots = locked.get(player.getUniqueId());
        if (slots == null || slots.isEmpty()) {
            return false;
        }
        String freed = slots.remove(slots.size() - 1);
        save();
        decorate(player);
        player.sendMessage("§6[Death] §bEl Dodecaedro Angelical libero tu §e"
                + slotName(freed) + "§b.");
        return true;
    }

    /**
     * Quita TODOS los bloqueos de TODOS los jugadores y retira los marcadores
     * (STRUCTURE VOID) de sus inventarios. Se usa al terminar el modo Hellish
     * para que los slots no queden sellados para siempre tras el evento.
     */
    public void clearAll() {
        if (locked.isEmpty()) {
            return;
        }
        locked.clear();
        save();
        for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
            org.bukkit.inventory.Inventory inv = p.getInventory();
            boolean changed = false;
            for (int i = 0; i < inv.getSize(); i++) {
                ItemStack it = inv.getItem(i);
                if (isMarker(it)) {
                    inv.setItem(i, null);
                    changed = true;
                }
            }
            ItemStack cursor = p.getItemOnCursor();
            if (isMarker(cursor)) {
                p.setItemOnCursor(null);
                changed = true;
            }
            if (changed) {
                p.updateInventory();
            }
        }
    }

    public boolean hasLocks(UUID uuid) {
        List<String> slots = locked.get(uuid);
        return slots != null && !slots.isEmpty();
    }

    /** ¿Esta sellado ese slot de inventario (0-40)? */
    public boolean isInvLocked(UUID uuid, int slot) {
        return isLocked(uuid, String.valueOf(slot));
    }

    /** ¿Esta sellado el slot de crafteo 2x2 del inventario (raw 1-4)? */
    public boolean isCraftLocked(UUID uuid, int rawSlot) {
        return isLocked(uuid, "c" + rawSlot);
    }

    public boolean isLocked(UUID uuid, String code) {
        List<String> slots = locked.get(uuid);
        return slots != null && slots.contains(code);
    }

    public boolean isCursorLocked(UUID uuid) {
        return isLocked(uuid, "cursor");
    }

    /**
     * Barrido: tira objetos reales dentro de slots sellados y coloca el
     * marcador de STRUCTURE VOID encantado en los vacios.
     */
    public void sweepLockedSlots(Player player) {
        List<String> slots = locked.get(player.getUniqueId());
        if (slots == null || slots.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (String code : slots) {
            Integer invSlot = invIndex(code);
            if (invSlot == null) {
                continue;
            }
            ItemStack content = player.getInventory().getItem(invSlot);
            if (content != null && !content.getType().isAir()
                    && !isMarker(content)) {
                // Objeto real dentro del slot sellado: fuera
                player.getInventory().setItem(invSlot, null);
                player.getWorld().dropItemNaturally(player.getLocation(), content);
                changed = true;
            }
            if (content == null || content.getType().isAir()) {
                player.getInventory().setItem(invSlot, markerItem());
                changed = true;
            }
        }
        // Cursor sellado: no puede llevar nada
        if (slots.contains("cursor")) {
            ItemStack onCursor = player.getItemOnCursor();
            if (onCursor != null && !onCursor.getType().isAir()) {
                player.setItemOnCursor(null);
                player.getWorld().dropItemNaturally(player.getLocation(), onCursor);
            }
        }
        if (changed) {
            player.updateInventory();
        }
    }

    /** Marcador visible: STRUCTURE VOID encantado llamado "Slot Bloqueado". */
    public static ItemStack markerItem() {
        ItemStack item = new ItemStack(Material.STRUCTURE_VOID);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("§4Slot Bloqueado"));
            meta.setEnchantmentGlintOverride(true);
            item.setItemMeta(meta);
        }
        return item;
    }

    public static boolean isMarker(ItemStack item) {
        if (item == null || item.getType() != Material.STRUCTURE_VOID
                || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasDisplayName();
    }

    private void decorate(Player player) {
        // Aplica el marcador de inmediato tras sellar/liberar
        sweepLockedSlots(player);
    }

    private void save() {
        try {
            Files.writeString(file, gson.toJson(locked, TYPE), StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getSLF4JLogger().error("No se pudo guardar hellish_locks.json", e);
        }
    }

    private static Integer invIndex(String code) {
        try {
            if (!code.startsWith("c")) {
                return Integer.parseInt(code);
            }
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    private static String slotName(String code) {
        if (code.equals("cursor")) {
            return "CURSOR (peor suerte)";
        }
        if (code.startsWith("c")) {
            return "mesa de crafteo " + code.substring(1);
        }
        int slot;
        try {
            slot = Integer.parseInt(code);
        } catch (NumberFormatException e) {
            return "slot desconocido";
        }
        if (slot == 40) {
            return "mano secundaria";
        }
        if (slot >= 36) {
            return "armadura";
        }
        if (slot <= 8) {
            return "hotbar " + (slot + 1);
        }
        return "inventario";
    }
}

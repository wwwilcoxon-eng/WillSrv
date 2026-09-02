package dev.willsrv.weapons;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ItemEdit: /ie o /itemedit abre un dialog para editar el item en tu mano:
 * nombre, lore, item model, brillo, irrompibilidad, atributos y
 * encantamientos (subir/bajar/quitar).
 */
public final class WeaponsPlugin extends JavaPlugin {

    private static final LegacyComponentSerializer AMPERSAND =
            LegacyComponentSerializer.legacyAmpersand();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(
                new CrossbowAmmoListener(this), this);
        getSLF4JLogger().info("ItemEdit listo: /ie o /itemedit");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Solo jugadores pueden usar este comando.");
            return true;
        }
        openMainMenu(player);
        return true;
    }

    // ---------------- Helpers ----------------

    private ItemStack handItem(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType().isAir()) {
            return null;
        }
        return item;
    }

    /** Crea un boton con callback que recibe (player, respuesta del dialog). */
    private ActionButton button(String label,
                                DialogHandler handler) {
        DialogAction action = null;
        if (handler != null) {
            action = DialogAction.customClick((view, audience) -> {
                if (audience instanceof Player player) {
                    handler.run(player, view);
                }
            }, ClickCallback.Options.builder().build());
        }
        return ActionButton.create(Component.text(label), null, 150, action);
    }

    @FunctionalInterface
    private interface DialogHandler {
        void run(Player player, DialogResponseView view);
    }

    // ---------------- Menu principal ----------------

    void openMainMenu(Player player) {
        ItemStack item = handItem(player);
        if (item == null) {
            player.sendMessage("§cTen un item en la mano principal.");
            return;
        }
        ItemMeta meta = item.getItemMeta();

        String currentName = "";
        if (meta.hasDisplayName()) {
            currentName = AMPERSAND.serialize(meta.displayName());
        }
        String currentModel = item.getType().getKey().getNamespace() + ":"
                + item.getType().getKey().getKey();
        NamespacedKey modelKey = meta.getItemModel();
        if (modelKey != null) {
            currentModel = modelKey.getNamespace() + ":" + modelKey.getKey();
        }

        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Component.text(
                "§7Item: §f" + item.getType().name())));
        if (meta.hasDisplayName()) {
            body.add(DialogBody.plainMessage(Component.text("§7Nombre: ")
                    .append(meta.displayName())));
        }
        if (meta.hasLore()) {
            Component lorePreview = Component.text("§7Lore actual:");
            for (Component line : meta.lore()) {
                lorePreview = lorePreview.append(Component.newline())
                        .append(Component.text(" §8- ")).append(line);
            }
            body.add(DialogBody.plainMessage(lorePreview));
        }
        if (meta.hasEnchants()) {
            StringBuilder enchants = new StringBuilder();
            for (var entry : meta.getEnchants().entrySet()) {
                enchants.append(entry.getKey().getKey().getKey())
                        .append(" ").append(entry.getValue()).append(", ");
            }
            body.add(DialogBody.plainMessage(Component.text(
                    "§7Encantamientos: §b"
                            + enchants.substring(0, enchants.length() - 2))));
        } else {
            body.add(DialogBody.plainMessage(Component.text(
                    "§7Encantamientos: §8ninguno")));
        }

        // Lore completo multilinea: cada linea del cuadro = linea de lore
        String currentLore = "";
        if (meta.hasLore()) {
            List<String> lines = new ArrayList<>();
            for (Component line : meta.lore()) {
                lines.add(AMPERSAND.serialize(line));
            }
            currentLore = String.join("\n", lines);
        }

        List<DialogInput> inputs = List.of(
                DialogInput.text("name", Component.text("Nombre (&colores)"))
                        .initial(currentName)
                        .maxLength(64)
                        .width(300)
                        .build(),
                DialogInput.text("lore",
                        Component.text("Lore completo (una linea por fila, &colores)"))
                        .initial(currentLore)
                        .maxLength(512)
                        .width(300)
                        .multiline(TextDialogInput.MultilineOptions.create(8, 120))
                        .build(),
                DialogInput.text("model", Component.text("Item Model"))
                        .initial(currentModel)
                        .maxLength(128)
                        .width(300)
                        .build(),
                DialogInput.bool("glint", Component.text("Brillo mágico"))
                        .initial(meta.hasEnchantmentGlintOverride()
                                ? Boolean.TRUE.equals(meta.getEnchantmentGlintOverride()) : false)
                        .build(),
                DialogInput.bool("unbreakable", Component.text("Irrompible"))
                        .initial(meta.isUnbreakable())
                        .build(),
                DialogInput.singleOption("attribute",
                        Component.text("Atributo"), List.of(
                                option("attack_damage", "Daño de ataque", true),
                                option("attack_speed", "Velocidad de ataque", false),
                                option("max_health", "Vida máxima", false),
                                option("armor", "Armadura", false),
                                option("armor_toughness", "Dureza", false),
                                option("movement_speed", "Velocidad", false),
                                option("knockback_resistance", "Retroceso", false)))
                        .width(250)
                        .build(),
                DialogInput.numberRange("attr_amount",
                        Component.text("Cantidad del atributo"), -10f, 20f)
                        .step(0.5f)
                        .initial(1f)
                        .width(300)
                        .build());

        List<ActionButton> actions = List.of(
                button("✔ Aplicar cambios", (p, view) -> {
                    applyBasics(p, view);
                    openMainMenu(p);
                }),
                button("Quitar atributos", (p, view) -> {
                    ItemStack hand = handItem(p);
                    if (hand == null) {
                        return;
                    }
                    ItemMeta m = hand.getItemMeta();
                    m.setAttributeModifiers(com.google.common.collect.ImmutableMultimap.of());
                    hand.setItemMeta(m);
                    p.sendMessage("§aAtributos borrados.");
                    openMainMenu(p);
                }),
                button("Encantamientos...", (p, view) -> openEnchantsMenu(p)),
                button("Cerrar", null));

        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text("§dEditor de Items"))
                        .canCloseWithEscape(true)
                        .body(body)
                        .inputs(inputs)
                        .build())
                .type(DialogType.multiAction(actions).build()));
        player.showDialog(dialog);
    }

    private SingleOptionDialogInput.OptionEntry option(String id, String label,
                                                       boolean initial) {
        return SingleOptionDialogInput.OptionEntry.create(id,
                Component.text(label), initial);
    }

    private Attribute attributeFrom(String id) {
        return switch (id) {
            case "attack_damage" -> Attribute.ATTACK_DAMAGE;
            case "attack_speed" -> Attribute.ATTACK_SPEED;
            case "max_health" -> Attribute.MAX_HEALTH;
            case "armor" -> Attribute.ARMOR;
            case "armor_toughness" -> Attribute.ARMOR_TOUGHNESS;
            case "movement_speed" -> Attribute.MOVEMENT_SPEED;
            case "knockback_resistance" -> Attribute.KNOCKBACK_RESISTANCE;
            default -> null;
        };
    }

    private void applyBasics(Player player, DialogResponseView view) {
        ItemStack item = handItem(player);
        if (item == null) {
            return;
        }
        ItemMeta meta = item.getItemMeta();

        String name = view.getText("name");
        if (name != null && !name.isBlank()) {
            meta.displayName(AMPERSAND.deserialize(name));
        } else {
            meta.displayName(null);
        }

        String loreText = view.getText("lore");
        if (loreText != null) {
            List<Component> lore = new ArrayList<>();
            for (String line : loreText.split("\n")) {
                if (!line.isBlank()) {
                    lore.add(AMPERSAND.deserialize(line));
                }
            }
            meta.lore(lore.isEmpty() ? null : lore);
        }

        String model = view.getText("model");
        if (model != null && !model.isBlank()) {
            try {
                Key key = Key.key(model.toLowerCase(Locale.ROOT));
                meta.setItemModel(new NamespacedKey(key.namespace(), key.value()));
            } catch (Exception e) {
                player.sendMessage("§cItem model invalido: " + model);
            }
        } else {
            meta.setItemModel(null);
        }

        Boolean glint = view.getBoolean("glint");
        if (glint != null) {
            meta.setEnchantmentGlintOverride(glint);
        }
        Boolean unbreakable = view.getBoolean("unbreakable");
        if (unbreakable != null) {
            meta.setUnbreakable(unbreakable);
        }

        String attrId = view.getText("attribute");
        Float amount = view.getFloat("attr_amount");
        if (attrId != null && amount != null && amount != 0f) {
            Attribute attr = attributeFrom(attrId);
            if (attr != null) {
                // Quita primero cualquier modificador anterior del editor
                var existing = meta.getAttributeModifiers();
                if (existing != null) {
                    for (AttributeModifier old : existing.get(attr)) {
                        try {
                            meta.removeAttributeModifier(attr, old);
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                }
                try {
                    meta.addAttributeModifier(attr, new AttributeModifier(
                            new NamespacedKey(this, "ie_" + attrId), amount,
                            AttributeModifier.Operation.ADD_NUMBER,
                            EquipmentSlotGroup.ANY));
                } catch (IllegalArgumentException e) {
                    player.sendMessage("§cNo se pudo aplicar el atributo: "
                            + e.getMessage());
                }
            }
        }

        item.setItemMeta(meta);
        player.sendMessage("§aPropiedades aplicadas.");
    }

    // ---------------- Menu de encantamientos ----------------

    void openEnchantsMenu(Player player) {
        ItemStack item = handItem(player);
        if (item == null) {
            player.sendMessage("§cTen un item en la mano principal.");
            return;
        }

        ItemMeta meta = item.getItemMeta();
        List<SingleOptionDialogInput.OptionEntry> options = new ArrayList<>();
        List<String> currentEnchants = new ArrayList<>();
        for (var entry : meta.getEnchants().entrySet()) {
            currentEnchants.add(entry.getKey().getKey().getKey()
                    + " " + entry.getValue());
        }
        currentEnchants.sort(String.CASE_INSENSITIVE_ORDER);
        List<Enchantment> all = new ArrayList<>();
        for (Enchantment ench : RegistryAccess.registryAccess()
                .getRegistry(RegistryKey.ENCHANTMENT)) {
            all.add(ench);
        }
        all.sort(java.util.Comparator.comparing(
                (Enchantment e) -> e.getKey().getKey()));
        boolean first = true;
        for (Enchantment enchantment : all) {
            NamespacedKey key = enchantment.getKey();
            int level = item.getEnchantmentLevel(enchantment);
            String label = key.getKey()
                    + (level > 0 ? " §7[Nv. " + level + "]" : "");
            options.add(SingleOptionDialogInput.OptionEntry.create(
                    key.getNamespace() + ":" + key.getKey(),
                    Component.text(label), first));
            first = false;
        }

        Component summary;
        if (currentEnchants.isEmpty()) {
            summary = Component.text("§7Encantamientos actuales: §8ninguno");
        } else {
            summary = Component.text("§7Encantamientos actuales: §b"
                    + String.join("§7, §b", currentEnchants));
        }

        List<DialogInput> inputs = List.of(
                DialogInput.singleOption("enchant",
                        Component.text("Encantamiento"), options)
                        .width(300)
                        .build(),
                DialogInput.numberRange("level",
                        Component.text("Nivel"), 0f, 10f)
                        .step(1f)
                        .initial(1f)
                        .width(300)
                        .build());

        List<ActionButton> actions = List.of(
                button("Aplicar nivel", (p, view) -> {
                    applyEnchant(p, view, 0);
                    openEnchantsMenu(p);
                }),
                button("+1", (p, view) -> {
                    applyEnchant(p, view, 1);
                    openEnchantsMenu(p);
                }),
                button("-1", (p, view) -> {
                    applyEnchant(p, view, -1);
                    openEnchantsMenu(p);
                }),
                button("Quitar encantamiento", (p, view) -> {
                    Enchantment ench = selectedEnchant(view);
                    ItemStack hand = handItem(p);
                    if (ench == null || hand == null) {
                        return;
                    }
                    ItemMeta m = hand.getItemMeta();
                    m.removeEnchant(ench);
                    hand.setItemMeta(m);
                    p.sendMessage("§aEncantamiento quitado.");
                    openEnchantsMenu(p);
                }),
                button("Quitar TODOS", (p, view) -> {
                    ItemStack hand = handItem(p);
                    if (hand == null) {
                        return;
                    }
                    ItemMeta m = hand.getItemMeta();
                    m.getEnchants().keySet().forEach(m::removeEnchant);
                    hand.setItemMeta(m);
                    p.sendMessage("§aEncantamientos borrados.");
                    openEnchantsMenu(p);
                }),
                button("Volver", (p, view) -> openMainMenu(p)));

        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text("§bEncantamientos"))
                        .canCloseWithEscape(true)
                        .body(List.of(DialogBody.plainMessage(summary)))
                        .inputs(inputs)
                        .build())
                .type(DialogType.multiAction(actions).build()));
        player.showDialog(dialog);
    }

    private Enchantment selectedEnchant(DialogResponseView view) {
        String id = view.getText("enchant");
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            Key key = Key.key(id.toLowerCase(Locale.ROOT));
            return RegistryAccess.registryAccess()
                    .getRegistry(RegistryKey.ENCHANTMENT).get(key);
        } catch (Exception e) {
            return null;
        }
    }

    /** delta 0 = fijar el nivel elegido; +/-1 = relativo al nivel actual. */
    private void applyEnchant(Player player, DialogResponseView view, int delta) {
        Enchantment enchant = selectedEnchant(view);
        ItemStack item = handItem(player);
        if (enchant == null || item == null) {
            return;
        }
        Float level = view.getFloat("level");
        int target;
        if (delta == 0) {
            target = level == null ? 1 : Math.round(level);
        } else {
            target = item.getEnchantmentLevel(enchant) + delta;
        }
        if (target <= 0) {
            ItemMeta meta = item.getItemMeta();
            meta.removeEnchant(enchant);
            item.setItemMeta(meta);
            player.sendMessage("§aEncantamiento quitado.");
            return;
        }
        ItemMeta meta = item.getItemMeta();
        meta.addEnchant(enchant, target, true);
        item.setItemMeta(meta);
        player.sendMessage("§a" + enchant.getKey().getKey()
                + " " + target + " aplicado.");
    }
}

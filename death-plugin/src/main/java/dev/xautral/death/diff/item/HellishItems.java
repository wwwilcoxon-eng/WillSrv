package dev.xautral.death.diff.item;

import dev.xautral.death.util.TimeUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

public final class HellishItems {

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacySection();

    private HellishItems() {
    }

    public static ItemStack vidrioRoto(NamespacedKey key) {
        ItemStack item = new ItemStack(Material.GLASS);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(LEGACY.deserialize("§bVidrio Roto"));
            meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    public static ItemStack dodecaedro(NamespacedKey key) {
        ItemStack item = new ItemStack(Material.DIAMOND);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(LEGACY.deserialize("§b§lDodecaedro §f§lAngelical"));
            meta.setEnchantmentGlintOverride(true);
            meta.lore(List.of(
                    LEGACY.deserialize("§7Un diamante de las estrellas que"),
                    LEGACY.deserialize("§7rompe los sellos de Hellish."),
                    LEGACY.deserialize(""),
                    LEGACY.deserialize("§dClick derecho: §7libera un slot sellado."),
                    LEGACY.deserialize("§8Crafteable con 8 bloques de"),
                    LEGACY.deserialize("§8diamante y armadura de nautilo.")));
            meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    public static ItemStack totemDefinitivo(NamespacedKey key) {
        ItemStack item = new ItemStack(Material.TOTEM_OF_UNDYING);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(LEGACY.deserialize("§6Totem Definitivo"));
            meta.lore(List.of(
                    LEGACY.deserialize("§5Nunca falla al activarse."),
                    LEGACY.deserialize("§7Recompensa por sobrevivir Hellish")));
            meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** % de fallo del totem normal segun nivel: 40% (1-2), 75% (3). */
    public static int totemFailPercent(int level) {
        return level >= 3 ? 75 : 40;
    }

    public static ItemStack book(int durationSeconds, int level) {
        ItemStack item = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) item.getItemMeta();
        if (meta != null) {
            meta.setTitle("Hellish");
            meta.setAuthor("Death");
            meta.addPages(
                    LEGACY.deserialize("§8» §4§lHELLISH§r\n\n"
                            + "§7Duración: §b" + TimeUtil.format(durationSeconds) + "\n\n"
                            + "§c• Undead no se queman al sol\n"
                            + "§c• Esqueletos: puntería perfecta, ballestas, huyen a montar arañas\n"
                            + "§c• Wither skeletons detectan a 25 bloques y cambian a arco"),
                    LEGACY.deserialize("§c• Piglins zombis: hierro completo con hacha\n"
                            + "§c• Zombies: 50% hacha hierro, velocidad y cota de malla\n"
                            + "§c• Zombies y monstruos: escudos, elytras y cargas de viento\n"
                            + "§c• Cerca de trial chambers usan mazo\n"
                            + "§c• Creepers: 20% cargados, corren y explotan a través de paredes"),
                    LEGACY.deserialize("§c• Phantoms: aparecen tras 1 día sin dormir, más agresivos y montados por esqueletos\n"
                            + "§c• End Phantoms: aparecen en el End, atacan 2 veces, se teletransportan si les apuntas o al ser atacados\n"
                            + "§c• Ayudan a esqueletos heridos\n"
                            + "§c• Slimes y magma cubes hasta tamaño 7"),
                    LEGACY.deserialize("§c• Ghasts en el End que se teletransportan\n"
                            + "§c• Ghasts Nether: bolas de fuego en espiral con bolitas pequeñas\n"
                            + "§c• Raids con ilusionistas\n"
                            + "§c• Blazes lanzan una bola de ghast cada 5 disparos\n"
                            + "§c• Tus totems tienen §4" + totemFailPercent(level)
                            + "%§c de fallar\n"),
                    LEGACY.deserialize("§c• Golem nieve: agresivo contra todos (incl. jugadores), bola nieve → 10 balas shulker teledirigidas en aro\n"
                            + "§c• Golem hierro: hostil contra todos salvo aldeanos\n"
                            + "§c• Ahogados: 3 tridentes Channeling V Impaling V que vuelven\n"
                            + "§c• Shulkers: 15 balas a los lados\n\n"
                            + "§6Recompensa:\n"
                            + "§eTotem Definitivo §7(nunca falla)"));
            if (level >= 2) {
                meta.addPages(
                        LEGACY.deserialize("§4§l> NIVEL 2 <\n\n"
                                + "§c• Creepers: más rápidos y te persiguen.\n"
                                + "§c• Si te escondes o huyes, explotan en el acto.\n"
                                + "§c• La explosión de un creeper lanza §e4 TNT§c alrededor.\n"
                                + "§c• Esqueletos: §550%§c de disparar una bola de fuego explosiva.\n"
                                + "§c• Flechas teledirigidas del boss se desvanecen al impactar.\n"),
                        LEGACY.deserialize("§4§l> NIVEL 2 <\n\n"
                                + "§c• Blazes disparan desde hasta §e25 bloques§c.\n"
                                + "§c• Bolas de fuego destructivas atraviesan §e1 bloque§c\n"
                                + "§c  para alcanzar al jugador que se esconde.\n"
                                + "§7Dificultad extra del modo Hellish, nivel 2."));
            }
            if (level >= 3) {
                meta.addPages(
                        LEGACY.deserialize("§4§l> NIVEL 3 <\n\n"
                                + "§c• En el §5Nether§c, creepers potenciados e §e invisibles§c\n"
                                + "§c  aparecen a §e30 bloques§c de ti, con gran explosión.\n"
                                + "§c• Los §egast del Nether§c explotan muy fuerte al morir.\n"
                                + "§c• Tus totems solo tienen §425%§c de funcionar.\n"),
                        LEGACY.deserialize("§4§l> NIVEL 3 <\n\n"
                                + "§c• El §6Totem Definitivo§c se debilita:\n"
                                + "§c  al §565%§c pasa a tener solo §7 50%§c de éxito.\n"
                                + "§c• Cada minuto, §euna patrulla de pillagers§c\n"
                                + "§c  te caza en el Overworld con flechas\n"
                                + "§c  que lanzan §5balas de shulker§c.\n"
                                + "§7Dificultad extrema del modo Hellish, nivel 3."));
            }
            if (level >= 4) {
                meta.addPages(
                        LEGACY.deserialize("§4§l> NIVEL 4 <\n\n"
                                + "§c• Los esqueletos del §5Nether§c disparan\n"
                                + "§c  flechas §econ TNT§c (daño en masa,\n"
                                + "§c  sin destruir) y flechas que lanzan\n"
                                + "§c  bolas de fuego en espiral.\n"
                                + "§c• Phantoms: aparecen de día, hasta 3,\n"
                                + "§c  no se queman y te dan §e4 mordidas§c.\n"),
                        LEGACY.deserialize("§4§l> NIVEL 4 <\n\n"
                                + "§c• La §eLluvia es ÁCIDA§c: te quema.\n"
                                + "§c  ¡Te avisaremos antes de que caiga!\n"
                                + "§c• Pollos, vacas, cerdos y aldeanos\n"
                                + "§c  son §ehostiles§c y te hacen daño.\n"
                                + "§7Dificultad infernal del modo Hellish, nivel 4."));
            }
            if (level >= 5) {
                meta.addPages(
                        LEGACY.deserialize("§4§l> NIVEL 5 <\n\n"
                                + "§c• El §3Breeze§c aparece si pasas mucho\n"
                                + "§c  tiempo en la superficie: lanza\n"
                                + "§c  balas de shulker en espiral, sin ruta.\n"
                                + "§c• La §eLluvia Ácida§c derrite la madera.\n"),
                        LEGACY.deserialize("§4§l> NIVEL 5 <\n\n"
                                + "§c• Una §aTribu de Husk§c te persigue\n"
                                + "§c  sin dejarte (hachas + cota de malla).\n"
                                + "§c• ¡Si los matas, desatan una §7trampa\n"
                                + "§c  de esqueletos§c que también te sigue,\n"
                                + "§c  con totems!\n"
                                + "§c• TODOS los mobs hostiles llevan un\n"
                                + "§c  §bTOTEM§c en su mano secundaria.\n"
                                + "§7Dificultad daemon del modo Hellish, nivel 5."));
            }
            if (level >= 6) {
                meta.addPages(
                        LEGACY.deserialize("§4§l> NIVEL 6 <\n\n"
                                + "§c• Zombies, esqueletos, wither skeletons\n"
                                + "§c  y piglins zombis llevan §earmadura§c y\n"
                                + "§c  tienen §550%§c de llevar un §bescudo§c\n"
                                + "§c  que alternan (hasta §e2s§c, se rompe).\n"
                                + "§c• Si estás desarmado o descansando,\n"
                                + "§c  bajan el escudo.\n"
                                + "§7Dificultad avengers del modo Hellish, nivel 6."),
                        LEGACY.deserialize("§4§l> NIVEL 6 <\n\n"
                                + "§c• Piglins zombis: §esiempre enojados§c,\n"
                                + "§c  armadura §6de oro §5Prot IV§c y espadas\n"
                                + "§c  con §eEmpuje§c e §eIrrompibilidad§c.\n"
                                + "§c  20% de que suelten la armadura.\n"
                                + "§c• Wither skeletons usan §ehachas de hierro§c.\n"
                                + "§c• Hay §5Ender Blazes§c en el End: silenciosos,\n"
                                + "§c  partículas en espiral, bolas de dragón\n"
                                + "§c  y se teletransportan hacia ti.\n"
                                + "§7Sin jefes ni lag: modo optimizado."));
            }
            if (level >= 7) {
                meta.addPages(
                        LEGACY.deserialize("§4§l> NIVEL 7 <\n\n"
                                + "§c• Wither skeletons se §ealejan§c y te\n"
                                + "§c  disparan §eflechas de fuego§c con\n"
                                + "§c  arco §ePunch§c. Si te acercas lo\n"
                                + "§c  suficiente, dejan de huir y te\n"
                                + "§c  cazan con hachas §eHierro Filo V§c\n"
                                + "§c  (5% de drop) y son más rápidos.\n"
                                + "§7Dificultad daemon del modo Hellish, nivel 7."),
                        LEGACY.deserialize("§4§l> NIVEL 7 <\n\n"
                                + "§c• Hay §dCreepers Supernova§c que vuelan\n"
                                + "§c  y aparecen si estás §einactivo§c (solos,\n"
                                + "§c  sin que te detecten) tras §e3 min§c.\n"
                                + "§c  Saben que eres su víctima y explotan\n"
                                + "§c  entre ~5-15 bloques de ti.\n"
                                + "§c• Todos los creepers recuerdan tu último\n"
                                + "§c  lugar, te ven a través de paredes y\n"
                                + "§c  explotan al estar a la mitad de su\n"
                                + "§c  radio de explosión.\n"
                                + "§7Nivel máximo del modo Hellish."));
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    public static void updateTotemLores(World world, NamespacedKey definitiveKey, int level) {
        Component warning = LEGACY.deserialize("§c⚠ " + totemFailPercent(level)
                + "% de fallar durante Hellish");
        for (Player player : world.getPlayers()) {
            ItemStack[] storage = player.getInventory().getContents();
            boolean changed = false;
            for (int i = 0; i < storage.length; i++) {
                ItemStack item = storage[i];
                if (item == null || item.getType() != Material.TOTEM_OF_UNDYING) {
                    continue;
                }
                ItemMeta meta = item.getItemMeta();
                if (meta == null
                        || meta.getPersistentDataContainer()
                                .has(definitiveKey, PersistentDataType.BYTE)
                        || (meta.hasLore() && !meta.lore().isEmpty())) {
                    continue;
                }
                List<Component> lore = meta.hasLore() && meta.lore() != null
                        ? new ArrayList<>(meta.lore())
                        : new ArrayList<>();
                lore.add(warning);
                meta.lore(lore);
                item.setItemMeta(meta);
                changed = true;
            }
            if (changed) {
                player.updateInventory();
            }
        }
    }

    public static void give(Player player, ItemStack item) {
        var leftovers = player.getInventory().addItem(item);
        if (!leftovers.isEmpty()) {
            player.getWorld().dropItemNaturally(player.getLocation(), item);
        }
    }
}

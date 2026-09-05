package dev.xautral.hellish.util;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Logros VANILLA reales: se genera un datapack en el mundo con los avances
 * (criterio impossible, otorgados por comando), por lo que aparecen en la
 * interfaz de logros del cliente con el toast autentico y anuncio en chat.
 */
public final class AdvancementUtil {

    public record Def(String key, String iconId, String frame,
                      String titleEs, String titleEn, String descEs, String descEn) {
    }

    public static final Def NO_DEATH = new Def("full_no_death",
            "minecraft:totem_of_undying", "task",
            "Full No-Death", "Full No-Death",
            "Sobreviviste Hellish sin morir",
            "You survived Hellish without dying");

    public static final Def NO_HIT = new Def("full_no_hit_perfect",
            "minecraft:enchanted_golden_apple", "challenge",
            "Full No-Hit Perfect", "Full No-Hit Perfect",
            "No recibiste ni un solo daño en todo Hellish",
            "You took no damage at all during Hellish");

    public static final Def ALL_BOSSES = new Def("all_bosses_no_death",
            "minecraft:skeleton_skull", "challenge",
            "JungleSMP All Bosses (No Death)", "JungleSMP All Bosses (No Death)",
            "Mataste a todos los jefes sin morir ni una vez",
            "You killed every boss without dying once");

    public static final Def NORMALITY = new Def("back_to_normality",
            "minecraft:diamond", "task",
            "De vuelta a la normalidad", "Back to Normality",
            "Usaste un Dodecaedro Angelical",
            "You used an Angelic Dodecahedron");

    private static final Def[] ALL = {NO_DEATH, NO_HIT, ALL_BOSSES, NORMALITY};

    private static final NamespacedKey PROBE = NamespacedKey.fromString("death:full_no_death");

    private static boolean datapackReady;
    private static JavaPlugin host;

    private AdvancementUtil() {
    }

    /**
     * Escribe el datapack de avances en el primer mundo (una sola vez) y
    * deja el datapack listo para que el servidor lo cargue en el siguiente arranque.
    * No fuerza Bukkit.reloadData(), porque esa operación bloquea el hilo principal.
     */
    public static void ensureDatapack(JavaPlugin plugin) {
        host = plugin;
        try {
            Path base = Bukkit.getWorlds().get(0).getWorldFolder().toPath()
                    .resolve("datapacks").resolve("death_advancements");
            boolean changed = false;

            Path meta = base.resolve("pack.mcmeta");
            if (!Files.exists(meta)) {
                Files.createDirectories(meta.getParent());
                // supported_formats amplio para compatibilidad con versiones nuevas
                Files.writeString(meta, """
                        {
                          "pack": {
                            "pack_format": 90,
                            "supported_formats": {"min_inclusive": 45, "max_inclusive": 9999},
                            "description": "Death plugin - Hellish advancements"
                          }
                        }""", StandardCharsets.UTF_8);
                changed = true;
            }
            for (Def def : ALL) {
                // Carpeta moderna "advancement"; tambien escribimos "advancements"
                // legacy para maximizar compatibilidad de carga
                for (String folder : new String[]{"advancement", "advancements"}) {
                    Path json = base.resolve("data").resolve("death")
                            .resolve(folder).resolve(def.key() + ".json");
                    if (!Files.exists(json)) {
                        Files.createDirectories(json.getParent());
                        Files.writeString(json, buildJson(def), StandardCharsets.UTF_8);
                        changed = true;
                    }
                }
            }

            // Ya esta registrado en la partida: nada que hacer
            if (Bukkit.getAdvancement(PROBE) != null) {
                datapackReady = true;
                return;
            }
            plugin.getSLF4JLogger().info("Datapack de logros de Hellish instalado; se cargara en el proximo reinicio.");
        } catch (IOException e) {
            plugin.getSLF4JLogger().error("No se pudo instalar el datapack de logros", e);
        }
    }

    private static String buildJson(Def def) {
        return """
                {
                  "display": {
                    "icon": {"id": "%s"},
                    "title": {"translate": "", "fallback": "%s"},
                    "description": {"text": "%s"},
                    "frame": "%s",
                    "announce_to_chat": true,
                    "show_toast": true,
                    "hidden": false
                  },
                  "criteria": {
                    "granted": {"trigger": "minecraft:impossible"}
                  },
                  "requirements": [["granted"]]
                }""".formatted(def.iconId(), def.titleEs(),
                escape(def.descEs()), def.frame());
    }

    private static String escape(String s) {
        return s.replace("\"", "\\\"");
    }

    /**
     * Otorga el avance REAL via comando (toast vanilla + anuncio en chat) y
     * manda ademas una linea localizada al jugador. Si el datapack aun no
     * esta cargado, reintenta el toast en segundo plano (la linea de chat
     * siempre se muestra ya).
     */
    public static void grant(Player player, Def def) {
        boolean spanish = player.locale().getLanguage().startsWith("es")
                || Locale.getDefault().getLanguage().startsWith("es")
                && player.locale().getLanguage().isEmpty();
        String title = spanish ? def.titleEs() : def.titleEn();
        String desc = spanish ? def.descEs() : def.descEn();
        String frame = def.frame().equals("challenge")
                ? "§b¡Desafío completado!" : "§e¡Avance conseguido!";
        player.sendMessage("§6[Death] " + frame + " §f" + title + " §7- " + desc);
        scheduleRealGrant(player, def, 0);
    }

    private static void scheduleRealGrant(Player player, Def def, int attempt) {
        if (host == null || !player.isOnline()) {
            return;
        }
        NamespacedKey key = NamespacedKey.fromString("death:" + def.key());
        if (Bukkit.getAdvancement(key) != null) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "advancement grant " + player.getName() + " only " + key);
            return;
        }
        if (attempt < 6) {
            long delay = attempt == 0 ? 60L : 120L * attempt;
            Bukkit.getScheduler().runTaskLater(host,
                    () -> scheduleRealGrant(player, def, attempt + 1), delay);
        }
    }
}
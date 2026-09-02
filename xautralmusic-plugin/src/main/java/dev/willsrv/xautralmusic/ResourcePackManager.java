package dev.willsrv.xautralmusic;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ResourcePackManager {

    private final JavaPlugin plugin;
    private File packFile;
    private String sha1 = "";
    private String sha256 = "";
    private int lastTrackCount = 0;
    private String publicUrl = "";

    private static final Set<String> AUDIO_EXTS = Set.of("ogg","wav","wave","mp3","mpga","flac","aiff","aif","m4a","aac","wma","opus","oga");
    private static final Set<String> OGG_EXTS = Set.of("ogg","oga","opus");

    public ResourcePackManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public File getBaseFolder() {
        String base = plugin.getConfig().getString("baseFolder", "plugins/XautralMusic");
        return new File(base);
    }
    public File getMusicFolder() {
        String path = plugin.getConfig().getString("musicFolder", "plugins/XautralMusic/music");
        return new File(path);
    }
    public File getSfxFolder() {
        String path = plugin.getConfig().getString("sfxFolder", "plugins/XautralMusic/sfx");
        return new File(path);
    }

    public File getPackFile() {
        if (packFile == null) {
            File dir = new File(plugin.getDataFolder(), "generated");
            dir.mkdirs();
            packFile = new File(dir, "pack.zip");
        }
        return packFile;
    }

    public String getSha1() { return sha1; }
    public String getSha256() { return sha256; }
    public int getLastTrackCount() { return lastTrackCount; }
    public String getPublicUrl() { return publicUrl; }
    public boolean isPackReady() { return getPackFile().exists() && !sha1.isEmpty(); }
    public String getNamespace() {
        String ns = plugin.getConfig().getString("namespace", "xautral");
        if (ns == null || ns.isBlank()) ns = "xautral";
        return ns.toLowerCase().replaceAll("[^a-z0-9_.-]", "_");
    }
    public String getSoundPrefix() {
        String p = plugin.getConfig().getString("soundPrefix", "");
        if (p == null) p = "";
        return p.toLowerCase().replaceAll("[^a-z0-9_.-]", "_");
    }

    public boolean generatePack() throws Exception {
        File base = getBaseFolder();
        File musicFolder = getMusicFolder();
        File sfxFolder = getSfxFolder();
        base.mkdirs();
        musicFolder.mkdirs();
        sfxFolder.mkdirs();

        List<String> categories = getCategories();
        boolean autoCreate = plugin.getConfig().getBoolean("autoCreateCategoryFolders", true);
        if (autoCreate) {
            for (String cat : categories) {
                File f = new File(base, cat);
                if (!f.exists()) f.mkdirs();
                // también dentro de musicFolder/sfxFolder por compat
                File m = new File(musicFolder, cat);
                if (!m.exists() && categories.contains(cat)) {
                    // solo crea si es sub de musicFolder y no duplica base
                }
            }
            // asegura music y sfx existen
            musicFolder.mkdirs();
            sfxFolder.mkdirs();
        }

        // recolecta audios de base/music + base/sfx + musicFolder + sfxFolder (evita duplicados)
        List<File> audios = collectAudios();
        // filtra .xme no es audio

        if (audios.isEmpty()) {
            File readmeM = new File(musicFolder, "README.txt");
            if (!readmeM.exists()) {
                String ns = getNamespace();
                String pref = getSoundPrefix();
                String example = pref.isEmpty() ? ns : ns + ":" + pref;
                Files.writeString(readmeM.toPath(),
                        "Pon música aquí (.ogg/.wav/.mp3/.flac se convierten auto a ogg vorbis)\n"
                                + "Ej: theme.ogg => " + example + ".music.theme\n"
                                + "Usa /playsound " + example + ".music.theme master @p\n", StandardCharsets.UTF_8);
            }
            File readmeS = new File(sfxFolder, "README.txt");
            if (!readmeS.exists()) {
                String ns = getNamespace();
                String pref = getSoundPrefix();
                String example = pref.isEmpty() ? ns : ns + ":" + pref;
                Files.writeString(readmeS.toPath(),
                        "Pon SFX aquí (.ogg/.wav/.mp3... → ogg vorbis)\n"
                                + "Ej: sfx/grunt_kill.ogg => " + example + ".sfx.grunt_kill\n"
                                + "Crea eventos .xme en esta carpeta:\n"
                                + "  execute when [action:die] run playsound " + example + ".sfx.grunt_kill 1 [players:all] [pos:-1,-1,-1]\n"
                                + "Acciones: die, kill, join, quit, respawn, sneak, sprint, jump, damage, interact\n"
                                + "Players: all/@a/@p/@s  Pos: -1,-1,-1 (=en jugador) o x,y,z o @a/@p\n"
                                + "Luego /xm reload\n", StandardCharsets.UTF_8);
            }
            String indexName = plugin.getConfig().getString("indexFile", "index.yml");
            File indexFile = new File(base, indexName);
            if (!indexFile.exists() && !categories.isEmpty()) {
                YamlConfiguration idx = new YamlConfiguration();
                idx.set("extensions", categories);
                idx.set("namespace", getNamespace());
                idx.set("soundPrefix", getSoundPrefix());
                try { idx.save(indexFile); } catch (Exception ignored) {}
            }
            File pack = getPackFile();
            if (pack.exists()) {
                // mantiene pack viejo?
            }
            publicUrl = buildPublicUrl();
            return false;
        }

        audios.sort(Comparator.comparing(f -> getBaseRelative(f)));

        File pack = getPackFile();
        pack.getParentFile().mkdirs();

        int packFormat = plugin.getConfig().getInt("packFormat", 34);
        String description = plugin.getConfig().getString("packDescription", "XautralMusic");
        String namespace = getNamespace();
        String prefix = getSoundPrefix();
        boolean useSub = plugin.getConfig().getBoolean("useSubfoldersAsCategories", true);

        Map<String, String> soundKeyToPath = new LinkedHashMap<>();
        // tmp dir para conversiones
        File tmpDir = new File(plugin.getDataFolder(), "generated/tmp");
        tmpDir.mkdirs();

        // Deduplica por assetPath: si hay .ogg y .wav con mismo nombre, prioriza .ogg original
        Map<String, ConvertedAudio> dedup = new LinkedHashMap<>();
        for (File audio : audios) {
            ConvertedAudio ca = convertAudio(audio, tmpDir);
            if (ca == null) continue;
            String[] info = resolveSoundInfo(ca.original, getBaseFolder(), namespace, prefix, useSub);
            ca.soundKey = info[0];
            ca.soundPath = info[1];
            String assetPath = "assets/" + namespace + "/sounds/" + ca.soundPath + ".ogg";
            ConvertedAudio existing = dedup.get(assetPath);
            if (existing != null) {
                boolean existingIsOgg = existing.original.getName().toLowerCase().endsWith(".ogg");
                boolean newIsOgg = ca.original.getName().toLowerCase().endsWith(".ogg");
                if (newIsOgg && !existingIsOgg) {
                    plugin.getSLF4JLogger().warn("Duplicado {}: {} y {} mapean a {} — priorizando .ogg {}", assetPath, existing.original.getName(), ca.original.getName(), assetPath, ca.original.getName());
                    // limpia tmp anterior si era temp
                    if (existing.isTemp) try { existing.oggFile.delete(); } catch (Exception ignored) {}
                    dedup.put(assetPath, ca);
                } else {
                    plugin.getSLF4JLogger().warn("Duplicado {}: {} y {} mapean a mismo asset — ignorando {}", assetPath, existing.original.getName(), ca.original.getName(), ca.original.getName());
                    if (ca.isTemp) try { ca.oggFile.delete(); } catch (Exception ignored) {}
                }
                continue;
            }
            dedup.put(assetPath, ca);
        }
        List<ConvertedAudio> converted = new ArrayList<>(dedup.values());

        lastTrackCount = converted.size();
        if (converted.isEmpty()) {
            publicUrl = buildPublicUrl();
            return false;
        }

        try (FileOutputStream fos = new FileOutputStream(pack);
             ZipOutputStream zos = new ZipOutputStream(fos)) {

            String mcmeta = """
                    {
                      "pack": {
                        "pack_format": %d,
                        "description": "%s",
                        "supported_formats": [34, 48]
                      }
                    }
                    """.formatted(packFormat, escapeJson(description));
            addZipEntry(zos, "pack.mcmeta", mcmeta.getBytes(StandardCharsets.UTF_8));

            for (ConvertedAudio ca : converted) {
                String assetPath = "assets/" + namespace + "/sounds/" + ca.soundPath + ".ogg";
                byte[] data = Files.readAllBytes(ca.oggFile.toPath());
                addZipEntry(zos, assetPath, data);
                soundKeyToPath.put(ca.soundKey, ca.soundPath);
            }

            StringBuilder sb = new StringBuilder("{\n");
            int i = 0;
            for (Map.Entry<String, String> e : soundKeyToPath.entrySet()) {
                sb.append("  \"").append(escapeJson(e.getKey())).append("\": {\n");
                sb.append("    \"sounds\": [\"").append(escapeJson(e.getValue())).append("\"]\n");
                sb.append("  }");
                if (++i < soundKeyToPath.size()) sb.append(",");
                sb.append("\n");
            }
            sb.append("}\n");
            addZipEntry(zos, "assets/" + namespace + "/sounds.json", sb.toString().getBytes(StandardCharsets.UTF_8));
        }

        // limpia tmp oggs generados que son conversiones (no borra originales .ogg)
        for (ConvertedAudio ca : converted) {
            if (ca.isTemp) {
                try { ca.oggFile.delete(); } catch (Exception ignored) {}
            }
        }

        byte[] bytes = Files.readAllBytes(pack.toPath());
        sha1 = hex(MessageDigest.getInstance("SHA-1").digest(bytes));
        sha256 = hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        publicUrl = buildPublicUrl();

        plugin.getSLF4JLogger().info("[XautralMusic] Pack: {} tracks ns={} prefix={} -> {} bytes SHA1={} SHA256={}", lastTrackCount, namespace, prefix.isEmpty()?"(none)":prefix, bytes.length, sha1, sha256);
        for (ConvertedAudio ca : converted) {
            plugin.getSLF4JLogger().info("  - {} => {}:{} ({})", getBaseRelative(ca.original), namespace, ca.soundKey, ca.soundPath);
        }
        return true;
    }

    private static class ConvertedAudio {
        File original;
        File oggFile;
        boolean isTemp = false;
        String soundKey;
        String soundPath;
    }

    private ConvertedAudio convertAudio(File input, File tmpDir) {
        String name = input.getName().toLowerCase();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')+1) : "";
        boolean isOgg = OGG_EXTS.contains(ext);
        boolean autoConvert = plugin.getConfig().getBoolean("autoConvertAudio", true);
        ConvertedAudio out = new ConvertedAudio();
        out.original = input;
        if (isOgg) {
            out.oggFile = input;
            out.isTemp = false;
            return out;
        }
        if (!autoConvert) {
            plugin.getSLF4JLogger().warn("Ignorando {} (no es ogg y autoConvertAudio=false)", input.getName());
            return null;
        }
        // intenta convertir con ffmpeg
        String base = input.getName().substring(0, input.getName().lastIndexOf('.'));
        File tmpOgg = new File(tmpDir, base + "_" + Math.abs(input.getAbsolutePath().hashCode()) + ".ogg");
        // si ya existe y es más nuevo que input, reusar
        if (tmpOgg.exists() && tmpOgg.lastModified() > input.lastModified()) {
            out.oggFile = tmpOgg;
            out.isTemp = true;
            return out;
        }
        String ffmpeg = plugin.getConfig().getString("ffmpegPath", "ffmpeg");
        int quality = plugin.getConfig().getInt("vorbisQuality", 5);
        int rate = plugin.getConfig().getInt("sampleRate", 48000);
        // ffmpeg -y -i input -c:a libvorbis -q:a 5 -ar 48000 output
        List<String> cmd = List.of(ffmpeg, "-y", "-i", input.getAbsolutePath(), "-c:a", "libvorbis", "-q:a", String.valueOf(quality), "-ar", String.valueOf(rate), tmpOgg.getAbsolutePath());
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = p.waitFor();
            if (exit == 0 && tmpOgg.exists() && tmpOgg.length() > 0) {
                plugin.getSLF4JLogger().info("Convertido {} ({}KB) -> {} ({}KB) via ffmpeg", input.getName(), input.length()/1024, tmpOgg.getName(), tmpOgg.length()/1024);
                out.oggFile = tmpOgg;
                out.isTemp = true;
                return out;
            } else {
                plugin.getSLF4JLogger().warn("ffmpeg falló para {} (exit {}): {}", input.getName(), exit, output.isEmpty()?"no output":output.substring(0, Math.min(400, output.length())));
                // fallback: intenta con -c:a libvorbis sin quality
                List<String> cmd2 = List.of(ffmpeg, "-y", "-i", input.getAbsolutePath(), "-c:a", "libvorbis", tmpOgg.getAbsolutePath());
                ProcessBuilder pb2 = new ProcessBuilder(cmd2);
                pb2.redirectErrorStream(true);
                Process p2 = pb2.start();
                String out2 = new String(p2.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                int exit2 = p2.waitFor();
                if (exit2 == 0 && tmpOgg.exists()) {
                    out.oggFile = tmpOgg; out.isTemp = true; return out;
                } else {
                    plugin.getSLF4JLogger().warn("Segundo intento ffmpeg falló: {}", out2.substring(0, Math.min(400, out2.length())));
                    return null;
                }
            }
        } catch (IOException | InterruptedException e) {
            plugin.getSLF4JLogger().warn("No se pudo ejecutar ffmpeg ({}) para {}: {} - instala ffmpeg o convierte manual a ogg", ffmpeg, input.getName(), e.getMessage());
            return null;
        }
    }

    private List<String> getCategories() {
        String indexName = plugin.getConfig().getString("indexFile", "index.yml");
        File idx = new File(getBaseFolder(), indexName);
        if (!idx.exists()) idx = new File(getMusicFolder(), indexName);
        if (!idx.exists()) idx = new File(getSfxFolder(), indexName);
        if (idx.exists()) {
            try {
                YamlConfiguration cfg = YamlConfiguration.loadConfiguration(idx);
                List<String> fromIdx = cfg.getStringList("extensions");
                if (fromIdx.isEmpty()) fromIdx = cfg.getStringList("categories");
                if (!fromIdx.isEmpty()) return fromIdx;
            } catch (Exception ignored) {}
        }
        List<String> fromCfg = plugin.getConfig().getStringList("categories");
        if (!fromCfg.isEmpty()) return fromCfg;
        return List.of("music","sfx");
    }

    private List<File> collectAudios() throws IOException {
        List<File> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (File root : List.of(getBaseFolder(), getMusicFolder(), getSfxFolder())) {
            if (!root.exists()) continue;
            try (var stream = Files.walk(root.toPath())) {
                stream.filter(p -> {
                    String n = p.getFileName().toString().toLowerCase();
                    if (n.endsWith(".xme") || n.equals("index.yml") || n.equals("readme.txt")) return false;
                    int dot = n.lastIndexOf('.');
                    if (dot < 0) return false;
                    String ext = n.substring(dot+1);
                    return AUDIO_EXTS.contains(ext);
                }).map(Path::toFile).forEach(f -> {
                    String key = f.getAbsolutePath();
                    if (seen.add(key)) out.add(f);
                });
            }
        }
        // si base es plugins/XautralMusic, ya incluye music y sfx; evita duplicados ya con seen
        return out;
    }

    private String getBaseRelative(File f) {
        try {
            Path base = getBaseFolder().toPath();
            if (f.toPath().startsWith(base)) return base.relativize(f.toPath()).toString().replace('\\','/');
            Path m = getMusicFolder().toPath();
            if (f.toPath().startsWith(m)) return m.relativize(f.toPath()).toString().replace('\\','/');
        } catch (Exception ignored) {}
        return f.getName();
    }

    private String[] resolveSoundInfo(File ogg, File root, String namespace, String prefix, boolean useSub) {
        // usa baseFolder como root para relative
        File base = getBaseFolder();
        Path relPath;
        try {
            if (ogg.toPath().startsWith(base.toPath())) relPath = base.toPath().relativize(ogg.toPath());
            else relPath = root.toPath().relativize(ogg.toPath());
        } catch (Exception e) {
            relPath = Path.of(ogg.getName());
        }
        String relStr = relPath.toString().replace('\\','/');
        // quita extensión
        int dot = relStr.lastIndexOf('.');
        if (dot >= 0) relStr = relStr.substring(0, dot);
        // relStr ej: "music/theme" o "sfx/grunt_kill" o "weapons/shot" (legacy)
        // si empieza con music/ o sfx/, preserva
        // Si useSub false, solo base name
        String folder = "";
        String baseName = relStr;
        int slash = relStr.lastIndexOf('/');
        if (slash >= 0) {
            folder = relStr.substring(0, slash);
            baseName = relStr.substring(slash+1);
        }
        String safeBase = baseName.toLowerCase().replaceAll("[^a-z0-9_.-]", "_");
        String safeFolder = folder.toLowerCase().replaceAll("[^a-z0-9_/.-]", "_").replaceAll("^/+", "").replaceAll("/+$", "");

        // Si es archivo en raíz de base (ej musicFolder/theme.ogg con relStr "theme"), folder=""
        // Para compat, si folder == "music" o "sfx" y useSub true, mantenlo como categoría
        String categoryPath = "";
        String categoryKey = "";
        if (useSub && !safeFolder.isEmpty()) {
            categoryPath = safeFolder;
            categoryKey = safeFolder.replace('/', '.');
        }

        String soundPath;
        String soundKey;
        if (!prefix.isEmpty()) {
            soundPath = prefix + (categoryPath.isEmpty() ? "" : "/" + categoryPath) + "/" + safeBase;
            soundKey = prefix + (categoryKey.isEmpty() ? "" : "." + categoryKey) + "." + safeBase;
        } else {
            soundPath = (categoryPath.isEmpty() ? "" : categoryPath + "/") + safeBase;
            soundKey = (categoryKey.isEmpty() ? "" : categoryKey + ".") + safeBase;
        }
        soundPath = soundPath.replaceAll("//+","/").replaceAll("^/","");
        soundKey = soundKey.replaceAll("\\.+",".").replaceAll("^\\.","");
        return new String[]{soundKey, soundPath};
    }

    public List<File> collectOggsForStatus() throws IOException { return collectAudios(); }
    public String[] resolveSoundInfoForStatus(File f) {
        return resolveSoundInfo(f, getBaseFolder(), getNamespace(), getSoundPrefix(), plugin.getConfig().getBoolean("useSubfoldersAsCategories", true));
    }

    private String buildPublicUrl() {
        String cfg = plugin.getConfig().getString("publicUrl", "");
        if (cfg != null && !cfg.isBlank()) return cfg.trim();
        
        // Si hay config de GitHub Releases, intenta subir y usar esa URL
        if (plugin.getConfig().getBoolean("githubRelease.enabled", false)) {
            String githubUrl = tryUploadToGitHubRelease();
            if (githubUrl != null && !githubUrl.isBlank()) {
                plugin.getSLF4JLogger().info("[XautralMusic] Pack subido a GitHub Release: {}", githubUrl);
                return githubUrl;
            }
        }
        
        int port = plugin.getConfig().getInt("httpPort", 8008);
        // Codespace: https://<name>-8008.app.github.dev/pack.zip
        try {
            String codespace = System.getenv("CODESPACE_NAME");
            String domain = System.getenv("GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN");
            if (codespace != null && !codespace.isBlank() && domain != null && !domain.isBlank()) {
                return "https://" + codespace + "-" + port + "." + domain + "/pack.zip";
            }
            // Fallback: intenta IP pública
            String sip = plugin.getServer().getIp();
            if (sip != null && !sip.isBlank()) return "http://" + sip + ":" + port + "/pack.zip";
            String host = java.net.InetAddress.getLocalHost().getHostAddress();
            if (host.equals("127.0.0.1") || host.equals("0.0.0.0") || host.startsWith("127.")) {
                // en Codespace sin env, intenta localhost pero avisa
                return "http://localhost:" + port + "/pack.zip";
            }
            return "http://" + host + ":" + port + "/pack.zip";
        } catch (Exception ignored) {
            return "http://localhost:" + port + "/pack.zip";
        }
    }

    private String tryUploadToGitHubRelease() {
        try {
            String repo = plugin.getConfig().getString("githubRelease.repo", "");
            // Lee token de variable de entorno GITHUB_TOKEN (el secret del Codespace)
            String token = System.getenv("GITHUB_TOKEN");
            if (token == null || token.isBlank()) {
                token = plugin.getConfig().getString("githubRelease.token", "");
            }
            if (repo.isBlank() || token == null || token.isBlank()) {
                plugin.getSLF4JLogger().warn("[XautralMusic] githubRelease.enabled=true pero falta repo o token (GITHUB_TOKEN env var)");
                return null;
            }
            
            File packFile = getPackFile();
            if (!packFile.exists()) {
                plugin.getSLF4JLogger().warn("[XautralMusic] No hay pack.zip para subir");
                return null;
            }
            
            String tagName = "xautralmusic-pack";
            String releaseName = "XautralMusic Resource Pack";
            String assetName = "pack.zip";
            
            // GitHub API: crea/actualiza release y sube asset
            String apiBase = "https://api.github.com/repos/" + repo;
            String authHeader = "Bearer " + token;
            
            // 1. Obtén o crea el release
            String releaseUrl = apiBase + "/releases/tags/" + tagName;
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            
            // GET release
            java.net.http.HttpRequest getReq = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(releaseUrl))
                .header("Authorization", authHeader)
                .header("Accept", "application/vnd.github+json")
                .GET()
                .build();
            
            java.net.http.HttpResponse<String> getResp = client.send(getReq, java.net.http.HttpResponse.BodyHandlers.ofString());
            String uploadUrl = null;
            String releaseId = null;
            
            if (getResp.statusCode() == 200) {
                // Release existe, obtén upload_url y release_id
                String json = getResp.body();
                uploadUrl = extractJsonField(json, "upload_url").replace("{?name,label}", "");
                releaseId = extractJsonField(json, "id");
                plugin.getSLF4JLogger().info("[XautralMusic] Release existente encontrado: {}", releaseId);
            } else if (getResp.statusCode() == 404) {
                // Crear release
                String createJson = "{\"tag_name\":\"" + tagName + "\",\"name\":\"" + releaseName + "\",\"draft\":false,\"prerelease\":false}";
                java.net.http.HttpRequest createReq = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(apiBase + "/releases"))
                    .header("Authorization", authHeader)
                    .header("Accept", "application/vnd.github+json")
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(createJson))
                    .build();
                java.net.http.HttpResponse<String> createResp = client.send(createReq, java.net.http.HttpResponse.BodyHandlers.ofString());
                if (createResp.statusCode() == 201) {
                    String json = createResp.body();
                    uploadUrl = extractJsonField(json, "upload_url").replace("{?name,label}", "");
                    releaseId = extractJsonField(json, "id");
                    plugin.getSLF4JLogger().info("[XautralMusic] Release creado: {}", releaseId);
                } else {
                    plugin.getSLF4JLogger().error("[XautralMusic] Error creando release: {} {}", createResp.statusCode(), createResp.body());
                    return null;
                }
            } else {
                plugin.getSLF4JLogger().error("[XautralMusic] Error obteniendo release: {} {}", getResp.statusCode(), getResp.body());
                return null;
            }
            
            if (uploadUrl == null) {
                plugin.getSLF4JLogger().error("[XautralMusic] No se obtuvo upload_url");
                return null;
            }
            
            // 2. Sube el asset (pack.zip)
            byte[] packData = Files.readAllBytes(getPackFile().toPath());
            String assetUrl = uploadUrl + "?name=" + java.net.URLEncoder.encode("pack.zip", StandardCharsets.UTF_8);
            java.net.http.HttpRequest uploadReq = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(assetUrl))
                .header("Authorization", authHeader)
                .header("Accept", "application/vnd.github+json")
                .header("Content-Type", "application/zip")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(packData))
                .build();
            
            java.net.http.HttpResponse<String> uploadResp = client.send(uploadReq, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (uploadResp.statusCode() == 201) {
                // Construye URL de descarga directamente (más fiable que parsear JSON)
                String downloadUrl = "https://github.com/" + repo + "/releases/download/" + tagName + "/pack.zip";
                plugin.getSLF4JLogger().info("[XautralMusic] Asset subido: {}", downloadUrl);
                return downloadUrl;
            } else {
                // Si el asset ya existe, bórralo y reintenta
                if (uploadResp.statusCode() == 422 && uploadResp.body().contains("already_exists")) {
                    plugin.getSLF4JLogger().info("[XautralMusic] Asset ya existe, borrando y reintentando...");
                    // Borra asset existente (necesitaría asset_id, simplificamos: usa tag único por build)
                    // Por simplicidad, usa tag con timestamp
                    return tryUploadToGitHubReleaseWithUniqueTag();
                }
                plugin.getSLF4JLogger().error("[XautralMusic] Error subiendo asset: {} {}", uploadResp.statusCode(), uploadResp.body());
                return null;
            }
        } catch (Exception e) {
            plugin.getSLF4JLogger().error("[XautralMusic] Error subiendo a GitHub Release", e);
            return null;
        }
    }
    
    private String tryUploadToGitHubReleaseWithUniqueTag() {
        try {
            String repo = plugin.getConfig().getString("githubRelease.repo", "");
            String token = System.getenv("GITHUB_TOKEN");
            if (token == null || token.isBlank()) {
                token = plugin.getConfig().getString("githubRelease.token", "");
            }
            if (repo.isBlank() || token == null || token.isBlank()) return null;
            
            String tagName = "xautralmusic-pack-" + System.currentTimeMillis();
            String releaseName = "XautralMusic Resource Pack " + System.currentTimeMillis();
            
            String apiBase = "https://api.github.com/repos/" + repo;
            String authHeader = "Bearer " + token;
            
            String createJson = "{\"tag_name\":\"" + tagName + "\",\"name\":\"" + releaseName + "\",\"draft\":false,\"prerelease\":false}";
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest createReq = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(apiBase + "/releases"))
                .header("Authorization", authHeader)
                .header("Accept", "application/vnd.github+json")
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(createJson))
                .build();
            java.net.http.HttpResponse<String> createResp = client.send(createReq, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (createResp.statusCode() != 201) {
                plugin.getSLF4JLogger().error("[XautralMusic] Error creando release único: {} {}", createResp.statusCode(), createResp.body());
                return null;
            }
            String json = createResp.body();
            String uploadUrl = extractJsonField(json, "upload_url").replace("{?name,label}", "");
            
            byte[] packData = Files.readAllBytes(getPackFile().toPath());
            String assetUrl = uploadUrl + "?name=" + java.net.URLEncoder.encode("pack.zip", StandardCharsets.UTF_8);
            java.net.http.HttpRequest uploadReq = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(assetUrl))
                .header("Authorization", authHeader)
                .header("Accept", "application/vnd.github+json")
                .header("Content-Type", "application/zip")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(packData))
                .build();
            java.net.http.HttpResponse<String> uploadResp = client.send(uploadReq, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (uploadResp.statusCode() == 201) {
                String downloadUrl = "https://github.com/" + repo + "/releases/download/" + tagName + "/pack.zip";
                plugin.getSLF4JLogger().info("[XautralMusic] Asset subido (tag único): {}", downloadUrl);
                return downloadUrl;
            }
            plugin.getSLF4JLogger().error("[XautralMusic] Error subiendo asset único: {} {}", uploadResp.statusCode(), uploadResp.body());
            return null;
        } catch (Exception e) {
            plugin.getSLF4JLogger().error("[XautralMusic] Error en upload único", e);
            return null;
        }
    }
    
    private String extractJsonField(String json, String... fields) {
        try {
            com.google.gson.JsonElement elem = com.google.gson.JsonParser.parseString(json);
            for (String field : fields) {
                if (elem.isJsonObject() && elem.getAsJsonObject().has(field)) {
                    elem = elem.getAsJsonObject().get(field);
                } else {
                    return "";
                }
            }
            if (elem.isJsonPrimitive()) return elem.getAsJsonPrimitive().getAsString();
            return elem.toString().replace("\"", "");
        } catch (Exception e) {
            return "";
        }
    }

    private static void addZipEntry(ZipOutputStream zos, String name, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(name);
        zos.putNextEntry(e);
        zos.write(data);
        zos.closeEntry();
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

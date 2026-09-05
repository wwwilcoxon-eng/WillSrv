package dev.willsrv.translate;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Servicio de traducción HTTP.
 * Usa MyMemory (gratis sin key) como proveedor por defecto.
 * Fallback: devuelve texto original si falla o no hay internet.
 * Cachea resultados para no spamear la API.
 */
public final class TranslationService {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    // cache: key = source|target|text  -> translated
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();
    private static final int MAX_CACHE = 2000;

    private TranslationService() {}

    public static String translate(String text, String sourceLang, String targetLang) {
        if (text == null || text.isBlank()) return text;
        if (sourceLang.equalsIgnoreCase(targetLang)) return text;
        // filter commands? skip if starts with /
        if (text.startsWith("/")) return text;

        String key = sourceLang.toLowerCase() + "|" + targetLang.toLowerCase() + "|" + text;
        String cached = CACHE.get(key);
        if (cached != null) return cached;

        String result = translateMyMemory(text, sourceLang, targetLang);
        if (result != null && !result.isBlank()) {
            if (CACHE.size() > MAX_CACHE) CACHE.clear();
            CACHE.put(key, result);
            return result;
        }
        // fallback mock: if no internet, return original (no prefix to avoid spam)
        return text;
    }

    private static String translateMyMemory(String text, String source, String target) {
        try {
            String q = URLEncoder.encode(text, StandardCharsets.UTF_8);
            String url = "https://api.mymemory.translated.net/get?q=" + q + "&langpair=" + source + "|" + target;
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(4))
                    .header("User-Agent", "WillSrv-Translate/1.0")
                    .GET().build();
            HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;
            String body = resp.body();
            // Simple JSON parse without library: extract "translatedText":"..."
            // Example: {"responseData":{"translatedText":"Hello" ...}
            int idx = body.indexOf("\"translatedText\"");
            if (idx == -1) return null;
            int colon = body.indexOf(':', idx);
            int firstQuote = body.indexOf('"', colon + 1);
            int secondQuote = findClosingQuote(body, firstQuote + 1);
            if (firstQuote == -1 || secondQuote == -1) return null;
            String translated = body.substring(firstQuote + 1, secondQuote);
            // unescape json
            translated = translated.replace("\\\"", "\"").replace("\\n", " ").replace("\\r", "").replace("\\/", "/").replace("\\\\", "\\").replace("\\'", "'");
            // MyMemory sometimes returns with extra quotes?
            translated = unescapeUnicode(translated);
            // If translation is same as input or empty, treat as failure?
            if (translated.isBlank()) return null;
            return translated.trim();
        } catch (IOException | InterruptedException e) {
            // no internet or timeout
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static int findClosingQuote(String s, int start) {
        boolean escaped = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\') { escaped = true; continue; }
            if (c == '"') return i;
        }
        return -1;
    }

    private static String unescapeUnicode(String s) {
        // handle unicode escapes
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 5 < s.length() && s.charAt(i + 1) == 'u') {
                String hex = s.substring(i + 2, i + 6);
                try {
                    int code = Integer.parseInt(hex, 16);
                    sb.append((char) code);
                    i += 5;
                } catch (NumberFormatException e) {
                    sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static void clearCache() { CACHE.clear(); }
}

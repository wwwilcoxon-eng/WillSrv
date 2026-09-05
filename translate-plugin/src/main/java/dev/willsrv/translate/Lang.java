package dev.willsrv.translate;

import org.bukkit.Material;

public enum Lang {
    ES("es", "ES", "España", "Español", "§a", "🇪🇸", Material.RED_BANNER),
    EN("en", "US", "United States", "English", "§b", "🇺🇸", Material.WHITE_BANNER),
    PT("pt", "BR", "Brasil", "Português", "§2", "🇧🇷", Material.GREEN_BANNER),
    FR("fr", "FR", "France", "Français", "§9", "🇫🇷", Material.BLUE_BANNER),
    DE("de", "DE", "Deutschland", "Deutsch", "§e", "🇩🇪", Material.YELLOW_BANNER),
    IT("it", "IT", "Italia", "Italiano", "§a", "🇮🇹", Material.LIME_BANNER),
    RU("ru", "RU", "Россия", "Русский", "§c", "🇷🇺", Material.RED_BANNER),
    PL("pl", "PL", "Polska", "Polski", "§f", "🇵🇱", Material.WHITE_BANNER),
    TR("tr", "TR", "Türkiye", "Türkçe", "§c", "🇹🇷", Material.RED_BANNER),
    JA("ja", "JP", "日本", "日本語", "§d", "🇯🇵", Material.WHITE_BANNER),
    KO("ko", "KR", "한국", "한국어", "§9", "🇰🇷", Material.LIGHT_BLUE_BANNER),
    ZH("zh", "CN", "中国", "中文", "§c", "🇨🇳", Material.RED_BANNER),
    AR("ar", "SA", "السعودية", "العربية", "§2", "🇸🇦", Material.GREEN_BANNER),
    NL("nl", "NL", "Nederland", "Nederlands", "§6", "🇳🇱", Material.ORANGE_BANNER),
    SV("sv", "SE", "Sverige", "Svenska", "§e", "🇸🇪", Material.YELLOW_BANNER),
    NO("no", "NO", "Norge", "Norsk", "§c", "🇳🇴", Material.RED_BANNER),
    HI("hi", "IN", "India", "हिन्दी", "§6", "🇮🇳", Material.ORANGE_BANNER),
    RO("ro", "RO", "România", "Română", "§e", "🇷🇴", Material.YELLOW_BANNER);

    private final String code; // ISO 639-1
    private final String countryCode;
    private final String countryName;
    private final String nativeName;
    private final String color;
    private final String flag;
    private final Material icon;

    Lang(String code, String countryCode, String countryName, String nativeName, String color, String flag, Material icon) {
        this.code = code;
        this.countryCode = countryCode;
        this.countryName = countryName;
        this.nativeName = nativeName;
        this.color = color;
        this.flag = flag;
        this.icon = icon;
    }

    public String code() { return code; }
    public String countryCode() { return countryCode; }
    public String countryName() { return countryName; }
    public String nativeName() { return nativeName; }
    public String color() { return color; }
    public String flag() { return flag; }
    public Material icon() { return icon; }

    public String displaySuffix() {
        return " §8[" + color + countryCode + "§8] " + flag;
    }

    public String displayNameFull() {
        return flag + " " + nativeName + " §7(" + countryCode + " - " + code + ")";
    }

    public static Lang fromCode(String code) {
        if (code == null) return null;
        String c = code.toLowerCase().trim();
        // accept en_us, en-us, en
        if (c.contains("_")) c = c.substring(0, c.indexOf('_'));
        if (c.contains("-")) c = c.substring(0, c.indexOf('-'));
        for (Lang l : values()) {
            if (l.code.equalsIgnoreCase(c) || l.countryCode.equalsIgnoreCase(code) || l.name().equalsIgnoreCase(code)) return l;
        }
        return null;
    }

    public static Lang fromLocale(String locale) {
        if (locale == null) return ES;
        return fromCode(locale) != null ? fromCode(locale) : ES;
    }

    public static Lang defaultLang() { return ES; }
}

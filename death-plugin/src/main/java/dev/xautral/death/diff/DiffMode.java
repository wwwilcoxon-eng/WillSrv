package dev.xautral.death.diff;

import org.bukkit.boss.BarColor;

public enum DiffMode {

    HELLISH("Hellish", BarColor.PURPLE);

    private final String display;
    private final BarColor color;

    DiffMode(String display, BarColor color) {
        this.display = display;
        this.color = color;
    }

    public String display() {
        return display;
    }

    public BarColor color() {
        return color;
    }
}

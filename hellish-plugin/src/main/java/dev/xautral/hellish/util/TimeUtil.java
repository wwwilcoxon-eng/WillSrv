package dev.xautral.hellish.util;

public final class TimeUtil {

    private TimeUtil() {
    }

    public static String format(int totalSeconds) {
        return (totalSeconds / 60) + ":" + String.format("%02d", totalSeconds % 60);
    }
}

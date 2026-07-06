package eu.lixko.jarband.recording;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class WeeklyRotation {
    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("YYYY-'w'ww", Locale.ROOT).withZone(ZoneOffset.UTC);

    private WeeklyRotation() {}

    public static String key(long unixMillis) {
        return FORMAT.format(Instant.ofEpochMilli(unixMillis));
    }
}

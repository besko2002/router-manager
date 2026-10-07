package com.example.routermanager.router;

/** Numeric parsing that never throws: the router is free to send junk, we are not free to crash. */
public final class Numbers {

    private Numbers() {
    }

    public static Long parseLongOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException ignored) {
            // Some fields carry a decimal ("299.91" in the MAC table) or a unit suffix.
            try {
                return (long) Double.parseDouble(text);
            } catch (NumberFormatException alsoIgnored) {
                return null;
            }
        }
    }

    public static long parseLong(String raw, long fallback) {
        Long value = parseLongOrNull(raw);
        return value == null ? fallback : value;
    }
}

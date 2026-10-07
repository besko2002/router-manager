package com.example.routermanager.router;

import java.util.Locale;

/** MAC normalisation: lower-case, colon-separated, so one device is one row forever. */
public final class MacAddresses {

    private MacAddresses() {
    }

    /**
     * Normalises {@code 02-00-5E-00-00-01}, {@code 02005e000001} and {@code 02:00:5e:00:00:01} to
     * {@code 02:00:5e:00:00:01}. Anything that is not 12 hex digits is returned trimmed and
     * lower-cased, so odd router output is still stored rather than dropped.
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return "";
        }
        String hex = text.replaceAll("[^0-9a-f]", "");
        if (hex.length() != 12) {
            return text;
        }
        StringBuilder out = new StringBuilder(17);
        for (int i = 0; i < 12; i += 2) {
            if (i > 0) {
                out.append(':');
            }
            out.append(hex, i, i + 2);
        }
        return out.toString();
    }

    public static boolean isValid(String raw) {
        return raw != null && raw.trim().replaceAll("[^0-9a-fA-F]", "").length() == 12;
    }
}

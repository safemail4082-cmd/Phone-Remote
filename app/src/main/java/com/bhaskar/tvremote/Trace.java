package com.bhaskar.tvremote;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Small in-memory log shown in the diagnostics screen and in error messages. */
final class Trace {
    private static final StringBuilder sb = new StringBuilder();
    private static final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.US);

    static synchronized void add(String s) {
        sb.append(fmt.format(new Date())).append(' ').append(s).append('\n');
        if (sb.length() > 8000) sb.delete(0, sb.length() - 6000);
    }

    static synchronized void clear() { sb.setLength(0); }

    static synchronized String tail(int lines) {
        String[] all = sb.toString().split("\n");
        StringBuilder out = new StringBuilder();
        for (int i = Math.max(0, all.length - lines); i < all.length; i++) {
            if (!all[i].isEmpty()) out.append(all[i]).append('\n');
        }
        return out.length() == 0 ? "(nothing yet)" : out.toString().trim();
    }
}

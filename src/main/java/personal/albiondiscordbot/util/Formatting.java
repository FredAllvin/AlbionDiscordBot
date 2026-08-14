package personal.albiondiscordbot.util;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Shared display helpers. */
public final class Formatting {

    private static final DecimalFormat THOUSANDS =
            new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.US));

    private Formatting() {
    }

    /** {@code 1500000 -> "1,500,000"} */
    public static String silver(long amount) {
        return THOUSANDS.format(amount);
    }

    /** Compact form for tight embed fields: {@code 12400000 -> "12.4m"} */
    public static String compact(long amount) {
        long abs = Math.abs(amount);
        if (abs >= 1_000_000_000L) {
            return trimZero(amount / 1_000_000_000.0) + "b";
        }
        if (abs >= 1_000_000L) {
            return trimZero(amount / 1_000_000.0) + "m";
        }
        if (abs >= 1_000L) {
            return trimZero(amount / 1_000.0) + "k";
        }
        return Long.toString(amount);
    }

    private static String trimZero(double value) {
        String s = String.format(Locale.US, "%.1f", value);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    /**
     * Escapes text for inclusion in generated HTML.
     *
     * <p>Not optional: Discord display names and Albion character names are
     * user-controlled, and the balance report is a file someone opens in a browser. A
     * display name containing a script tag would otherwise be stored XSS.
     */
    public static String escapeHtml(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length() + 16);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /** Neutralises Discord markdown so names cannot break embed formatting. */
    public static String escapeMarkdown(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("([*_`~\\\\|>])", "\\\\$1");
    }
}

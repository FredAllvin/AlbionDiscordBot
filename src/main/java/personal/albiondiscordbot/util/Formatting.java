package personal.albiondiscordbot.util;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Collection;
import java.util.Locale;

/** Shared display helpers. */
public final class Formatting {

    private static final DecimalFormat THOUSANDS =
            new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.US));

    /** Characters held back in {@link #mentions} for the "…and N more" tail. */
    private static final int TAIL_RESERVE = 24;

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

    /**
     * A run of user mentions for a message <strong>body</strong>.
     *
     * <p>The body specifically, because Discord only notifies people for mentions in
     * message content — the identical {@code <@id>} inside an embed renders as a name
     * and pings nobody. Anything whose job is to tell members their silver moved has to
     * put them here rather than in the embed that describes it.
     *
     * <p>Message content caps at 2000 characters and a CTA can credit far more people
     * than fit, so the list is cut to {@code budget} characters and says how many names
     * it left out. Everyone is still credited; only the ping is truncated.
     */
    public static String mentions(Collection<Long> discordUserIds, int budget) {
        StringBuilder out = new StringBuilder();
        int listed = 0;
        for (Long userId : discordUserIds) {
            String next = "<@" + userId + "> ";
            // Reserve room for the tail, or a list that only just fits loses the count
            // of the people it dropped.
            if (out.length() + next.length() + TAIL_RESERVE > budget) {
                break;
            }
            out.append(next);
            listed++;
        }
        int remaining = discordUserIds.size() - listed;
        if (remaining > 0) {
            out.append("…and ").append(remaining).append(" more");
        }
        return out.toString().trim();
    }

    /** Neutralises Discord markdown so names cannot break embed formatting. */
    public static String escapeMarkdown(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("([*_`~\\\\|>])", "\\\\$1");
    }
}

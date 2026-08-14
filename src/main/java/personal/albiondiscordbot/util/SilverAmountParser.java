package personal.albiondiscordbot.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import personal.albiondiscordbot.discord.CommandException;

/**
 * Parses silver amounts the way players actually type them.
 *
 * <p>Accepts {@code 1m}, {@code 1.5m}, {@code 500k}, {@code 1kk} (the Albion idiom for
 * a million), {@code 1,000,000}, {@code 1 000 000} and {@code 1000000}. The caller is
 * expected to echo the parsed value back, so a typo is visible before it matters.
 */
public final class SilverAmountParser {

    /** Above this, an amount is almost certainly a typo rather than a real payout. */
    public static final long MAX_AMOUNT = 1_000_000_000_000L;

    private static final Pattern PATTERN =
            Pattern.compile("^(\\d+(?:\\.\\d+)?)\\s*(kk|k|m|b)?$", Pattern.CASE_INSENSITIVE);

    private SilverAmountParser() {
    }

    /**
     * @return the amount in silver, always strictly positive
     * @throws CommandException with a user-facing message if the input is unusable
     */
    public static long parse(String input) {
        if (input == null || input.isBlank()) {
            throw new CommandException("Enter an amount, for example `1m`, `500k` or `1000000`.");
        }

        String cleaned = input.trim().replace(",", "").replace("_", "").replaceAll("\\s+", "");
        if (cleaned.startsWith("-")) {
            throw new CommandException("Amount must be positive. To take silver away, use `/balance remove`.");
        }

        Matcher matcher = PATTERN.matcher(cleaned);
        if (!matcher.matches()) {
            throw new CommandException(
                    "`%s` is not a valid amount. Try `1m`, `1.5m`, `500k` or `1000000`.".formatted(input));
        }

        BigDecimal value = new BigDecimal(matcher.group(1));
        String suffix = matcher.group(2);
        if (suffix != null) {
            value = value.multiply(BigDecimal.valueOf(multiplierFor(suffix)));
        }

        // Silver is integral; 1.5m is fine but 0.0001m is not meaningful.
        long amount = value.setScale(0, RoundingMode.DOWN).longValueExact();

        if (amount <= 0) {
            throw new CommandException("Amount must be greater than zero.");
        }
        if (amount > MAX_AMOUNT) {
            throw new CommandException(
                    "That amount looks like a typo (%s). The maximum is %s."
                            .formatted(Formatting.silver(amount), Formatting.silver(MAX_AMOUNT)));
        }
        return amount;
    }

    private static long multiplierFor(String suffix) {
        return switch (suffix.toLowerCase(Locale.ROOT)) {
            case "k" -> 1_000L;
            // "kk" is Albion shorthand for a million, not a thousand thousand-suffix
            case "m", "kk" -> 1_000_000L;
            case "b" -> 1_000_000_000L;
            default -> 1L;
        };
    }
}

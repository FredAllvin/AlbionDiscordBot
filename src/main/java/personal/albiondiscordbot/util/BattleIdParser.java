package personal.albiondiscordbot.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;
import personal.albiondiscordbot.discord.CommandException;

/**
 * Reads the battle ids an officer names for a CTA.
 *
 * <p>One CTA is often several battles: the fight breaks off, everyone reforms, and Albion
 * opens a new battle id. The killboard the guild actually reads — albionbb — merges them
 * into one page at {@code /battles/417352406,417352999}, so the comma-separated form is
 * what officers already have in front of them. Accepted here verbatim, along with the
 * whole pasted URL, because retyping ids by hand is how a wrong fight gets paid.
 *
 * <p>Order is kept and duplicates are dropped, so naming the same fight twice is
 * harmless rather than a way to pay someone twice.
 */
public final class BattleIdParser {

    /**
     * More than this is a paste accident rather than a CTA. The cap also bounds the API
     * calls a backfill can trigger and keeps the ledger note describing the split
     * inside its 512-character column.
     */
    public static final int MAX_BATTLES = 20;

    private static final Pattern SEPARATORS = Pattern.compile("[,;\\s]+");

    private BattleIdParser() {
    }

    /**
     * @return the ids in the order given, deduplicated, never empty
     * @throws CommandException with a user-facing message if the input is unusable
     */
    public static List<Long> parse(String input) {
        if (input == null || input.isBlank()) {
            throw new CommandException("Give at least one battle id, e.g. `417352406`.");
        }

        // A pasted killboard link is the common case: keep only the last path segment,
        // which is where both albionbb and the official killboard put the ids.
        String cleaned = input.trim();
        int query = cleaned.indexOf('?');
        if (query >= 0) {
            cleaned = cleaned.substring(0, query);
        }
        int fragment = cleaned.indexOf('#');
        if (fragment >= 0) {
            cleaned = cleaned.substring(0, fragment);
        }
        while (cleaned.endsWith("/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        int lastSlash = cleaned.lastIndexOf('/');
        if (lastSlash >= 0) {
            cleaned = cleaned.substring(lastSlash + 1);
        }

        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(parseAll(cleaned, input)));

        if (ids.isEmpty()) {
            throw new CommandException(
                    "`%s` has no battle ids in it. Use the number from the killboard post, e.g. `417352406`."
                            .formatted(input));
        }
        if (ids.size() > MAX_BATTLES) {
            throw new CommandException(
                    "That is %d battles. %d is the most one split can merge — if a CTA really ran that "
                                    .formatted(ids.size(), MAX_BATTLES)
                            + "long, split the fights nobody attended twice into their own `/split-cta`.");
        }
        return List.copyOf(ids);
    }

    private static List<Long> parseAll(String cleaned, String original) {
        List<Long> ids = new ArrayList<>();
        for (String token : SEPARATORS.split(cleaned)) {
            if (token.isBlank()) {
                continue;
            }
            long id;
            try {
                id = Long.parseLong(token);
            } catch (NumberFormatException e) {
                throw new CommandException(
                        "`%s` is not a battle id. Use the number from the killboard post, e.g. `417352406` "
                                        .formatted(token)
                                + "— or paste several as `417352406,417352999`.");
            }
            if (id <= 0) {
                throw new CommandException("`%s` is not a battle id.".formatted(token));
            }
            ids.add(id);
        }
        if (ids.isEmpty() && !cleaned.isBlank()) {
            throw new CommandException(
                    "`%s` is not a battle id. Use the number from the killboard post, e.g. `417352406`."
                            .formatted(original));
        }
        return ids;
    }
}

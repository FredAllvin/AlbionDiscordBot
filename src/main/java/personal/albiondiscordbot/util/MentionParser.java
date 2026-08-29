package personal.albiondiscordbot.util;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads user mentions back out of the raw text of a STRING option.
 *
 * <p>Slash commands cannot declare a variadic user option, so commands that take several
 * members — {@code /balance add}, {@code /balance remove}, {@code /role add} — put them in
 * one string and let JDA resolve the mentions. JDA resolves those <em>only</em> against
 * the {@code resolved} map Discord sends with the interaction: {@code
 * InteractionMentions.matchMember} looks the id up there and returns null if it is absent,
 * never falling back to the guild's member cache. Mention text Discord did not resolve
 * itself — pasted in, or carried along by re-running an older command — therefore matches
 * the pattern, renders in the channel like any other mention, and silently resolves to
 * nobody.
 *
 * <p>This is how a caller notices. Comparing what was typed against what resolved turns a
 * silent partial result into a refusal that names who went missing.
 */
public final class MentionParser {

    /** Both forms Discord sends; {@code <@!id>} is the older nickname variant. */
    private static final Pattern USER_MENTION = Pattern.compile("<@!?(\\d+)>");

    private MentionParser() {
    }

    /**
     * Every user id mentioned in {@code raw}, in the order typed, each once.
     *
     * <p>Ids stay as text. A mention can carry more digits than a {@code long} holds, and
     * parsing is not the job here — noticing one went missing is, and an id too long to be
     * real is still an id the caller typed and expects an answer about.
     */
    public static Set<String> userIds(String raw) {
        Set<String> ids = new LinkedHashSet<>();
        if (raw == null) {
            return ids;
        }
        Matcher matcher = USER_MENTION.matcher(raw);
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        return ids;
    }

    /**
     * Ids mentioned in {@code raw} that are missing from {@code resolvedIds}, in the order
     * they were typed.
     *
     * <p>An empty result means every mention resolved and the caller may proceed. A
     * non-empty one means the command reached fewer people than were named, which for
     * anything touching money has to stop the whole command rather than pay a subset.
     */
    public static List<String> unresolved(String raw, Collection<String> resolvedIds) {
        Set<String> resolved = Set.copyOf(resolvedIds);
        return userIds(raw).stream().filter(id -> !resolved.contains(id)).toList();
    }
}

package personal.albiondiscordbot.discord;

import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;

/**
 * Handles clicks on a family of buttons, identified by the first segment of the
 * button's custom id.
 *
 * <p>Custom ids are colon-separated: {@code <prefix>:<action>:<arg>…}. All state a
 * handler needs lives in that string rather than in server memory, so a pending
 * confirmation survives a bot restart and nothing has to be expired or garbage
 * collected. Discord caps custom ids at 100 characters, which is the budget.
 */
public interface ButtonHandler {

    /** First segment of the custom ids this handler owns. */
    String prefix();

    /**
     * @param args every colon-separated segment after the prefix, so {@code args[0]} is
     *     the action
     */
    void handle(ButtonInteractionEvent event, String[] args, CommandContext context) throws Exception;

    /** Whether the clicker must hold the staff role (admins always pass). */
    default boolean staffOnly() {
        return true;
    }
}

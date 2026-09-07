package personal.albiondiscordbot.discord;

import java.util.List;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

/**
 * One slash command. Implementations are Spring beans; {@link CommandRegistry}
 * discovers them all and dispatches by name, so adding a command means adding a
 * {@code @Component} and nothing else.
 *
 * <p>{@link #execute} runs on a worker thread after the interaction has already been
 * deferred, so implementations reply through {@code event.getHook()} and may block.
 */
public interface SlashCommand {

    /** Top-level command name, without the leading slash. */
    String name();

    /** The Discord-side definition: description, options, subcommands. */
    SlashCommandData definition();

    void execute(SlashCommandInteractionEvent event, CommandContext context) throws Exception;

    /**
     * Suggestions for an option declared with {@code setAutoComplete(true)}.
     *
     * <p>Runs off the gateway thread the way {@link #execute} does, but under a far
     * harder deadline: Discord throws the reply away after 3 seconds and an autocomplete
     * <strong>cannot be deferred</strong>, so nothing here may call the Albion API or
     * anything else that is allowed to hang. It also fires on every keystroke, so it
     * should read and not write.
     *
     * <p>At most {@link net.dv8tion.jda.api.interactions.commands.build.OptionData#MAX_CHOICES}
     * are shown. Returning none is a valid answer — the caller can still type the value
     * by hand — so this is the right response to anything that goes wrong as well.
     */
    default List<Command.Choice> autocomplete(CommandAutoCompleteInteractionEvent event) {
        return List.of();
    }

    /** Whether the caller must hold the configured staff role (admins always pass). */
    default boolean staffOnly() {
        return false;
    }

    /** Whether the command needs {@code /setup} to have been run first. */
    default boolean requiresSetup() {
        return true;
    }

    /**
     * Whether the reply is visible only to the caller.
     *
     * <p>Takes the event because one command can need both answers. {@code /balance add}
     * is guild news and {@code /balance remove} is a correction; {@code /balance stats}
     * is told which it is by an option.
     *
     * <p>This decides the <em>deferral</em>, which is what actually settles it: the first
     * message sent through the hook fills the placeholder the deferral put in the
     * channel, and a later {@code setEphemeral} on that message cannot make it more or
     * less private than the deferral already was. Getting it right here is the only way
     * to get it right at all.
     */
    default boolean ephemeral(SlashCommandInteractionEvent event) {
        return true;
    }
}

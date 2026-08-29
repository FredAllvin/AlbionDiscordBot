package personal.albiondiscordbot.discord;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
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

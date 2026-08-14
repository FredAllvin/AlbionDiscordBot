package personal.albiondiscordbot.discord;

/**
 * A failure whose message is safe and useful to show the user — bad input, missing
 * permissions, a character that is not in the guild. Anything else is logged and
 * reported generically.
 */
public class CommandException extends RuntimeException {

    public CommandException(String message) {
        super(message);
    }

    public CommandException(String message, Throwable cause) {
        super(message, cause);
    }
}

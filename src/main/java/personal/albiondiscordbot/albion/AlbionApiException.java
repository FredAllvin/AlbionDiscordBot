package personal.albiondiscordbot.albion;

/** The Albion API could not be reached or returned something unusable. */
public class AlbionApiException extends RuntimeException {

    public AlbionApiException(String message) {
        super(message);
    }

    public AlbionApiException(String message, Throwable cause) {
        super(message, cause);
    }
}

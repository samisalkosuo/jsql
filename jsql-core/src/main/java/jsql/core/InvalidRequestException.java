package jsql.core;

/**
 * The request cannot be fulfilled because of what the caller asked for (unknown table, statement
 * not allowed in read-only mode, unsupported operation). The message is meant to be shown to the
 * caller. Database errors are reported as {@link java.sql.SQLException} instead.
 */
public class InvalidRequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidRequestException(String message) {
        super(message);
    }
}

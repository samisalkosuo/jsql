package jsql.core;

/**
 * A statement or metadata call ran longer than its deadline and was stopped. It is an
 * {@link InvalidRequestException} (the caller can fix it by narrowing the request or raising the
 * timeout), but it has its own type so that callers can report it as a timeout.
 */
public class QueryTimeoutException extends InvalidRequestException {

    private static final long serialVersionUID = 1L;

    public QueryTimeoutException(String message) {
        super(message);
    }

    public QueryTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}

package jsql.mcp;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Database credentials for one request. The password is never part of {@link #toString()}.
 */
public record Credentials(String user, String password) {

    /** The request cannot be authenticated: no credentials, or an Authorization header we cannot use. */
    public static class CredentialsException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public CredentialsException(String message) {
            super(message);
        }
    }

    /**
     * Credentials for a request: the Basic Authorization header if there is one, otherwise the
     * fallback (from the server configuration).
     *
     * <p>
     * A header with another scheme is rejected instead of ignored, so that a client that tries to
     * authenticate in a way we do not support does not silently get the fallback account.
     *
     * @param authorization value of the Authorization header, null or blank if absent
     * @param fallback      credentials from configuration, null if not configured
     * @throws CredentialsException if there is nothing usable
     */
    public static Credentials resolve(String authorization, Credentials fallback) {
        if (authorization == null || authorization.isBlank()) {
            if (fallback == null) {
                throw new CredentialsException(
                        "No database credentials. Send an 'Authorization: Basic <base64 of user:password>' header"
                                + " (in the MCP client configuration, under headers), or ask the administrator to"
                                + " configure a default account on the server.");
            }
            return fallback;
        }
        return fromBasicHeader(authorization.trim());
    }

    private static Credentials fromBasicHeader(String header) {
        int space = header.indexOf(' ');
        String scheme = space < 0 ? header : header.substring(0, space);
        if (!scheme.equalsIgnoreCase("Basic")) {
            throw new CredentialsException("Unsupported Authorization scheme. Only 'Basic' is supported:"
                    + " 'Authorization: Basic <base64 of user:password>'.");
        }
        String encoded = space < 0 ? "" : header.substring(space + 1).trim();
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new CredentialsException("The Basic Authorization header is not valid base64.");
        }
        int colon = decoded.indexOf(':');
        if (colon < 0) {
            throw new CredentialsException("The Basic Authorization header must contain 'user:password'.");
        }
        String user = decoded.substring(0, colon);
        if (user.isEmpty()) {
            throw new CredentialsException("The user name in the Basic Authorization header is empty.");
        }
        return new Credentials(user, decoded.substring(colon + 1));
    }

    @Override
    public String toString() {
        return "Credentials[user=" + user + ", password=***]";
    }
}

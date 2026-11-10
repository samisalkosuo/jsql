package jsql.mcp;

import java.util.regex.Pattern;

/**
 * Configuration for one named JDBC connection.
 */
public record ConnectionConfig(String name, String jdbcUrl, Credentials defaultCredentials, boolean insecure,
        boolean readOnly, boolean isDefault) {

    private static final Pattern KEY_VALUE_SECRET = Pattern
            .compile("(?i)(password|pwd|passwd|secret|token|sslpassword)=([^;&]*)");
    private static final Pattern ORACLE_USER_PASSWORD = Pattern
            .compile("(?i)(jdbc:oracle:[a-z]+:)([^/@\\s]*)/[^@\\s]*@");

    public String redactedJdbcUrl() {
        if (jdbcUrl == null) {
            return "";
        }
        String s = KEY_VALUE_SECRET.matcher(jdbcUrl).replaceAll("$1=***");
        return ORACLE_USER_PASSWORD.matcher(s).replaceAll("$1$2/***@");
    }
}

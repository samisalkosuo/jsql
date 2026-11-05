package jsql.core;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * Everything needed to open a JDBC connection.
 *
 * @param jdbcUrl  JDBC URL
 * @param user     user name
 * @param password user password
 * @param insecure accept all TLS/SSL certificates (supported for some drivers only)
 */
public record ConnectionSettings(String jdbcUrl, String user, String password, boolean insecure) {

    /**
     * Builds the JDBC connection properties, including driver specific ones.
     *
     * @param warnings receives warning messages (for example when insecure has no effect for the driver)
     */
    public Properties toProperties(Consumer<String> warnings) {
        Properties properties = new Properties();
        properties.put("user", user);
        properties.put("password", password);
        if (jdbcUrl.contains("db2")) {
            properties.put("retreiveMessagesFromServerOnGetMessage", "true");
        }
        if (insecure) {
            applyInsecureSsl(properties, warnings);
        }
        return properties;
    }

    /**
     * Opens a new connection. The caller must close it.
     *
     * @param warnings receives warning messages
     */
    public Connection connect(Consumer<String> warnings) throws SQLException {
        return DriverManager.getConnection(jdbcUrl, toProperties(warnings));
    }

    // Adds driver specific properties that disable server certificate validation.
    private void applyInsecureSsl(Properties properties, Consumer<String> warnings) {
        String url = jdbcUrl.toLowerCase(Locale.ROOT);
        if (url.startsWith("jdbc:presto:") || url.startsWith("jdbc:trino:")) {
            properties.putIfAbsent("SSL", "true");
            properties.putIfAbsent("SSLVerification", "NONE");
        } else if (url.startsWith("jdbc:sqlserver:")) {
            properties.putIfAbsent("encrypt", "true");
            properties.putIfAbsent("trustServerCertificate", "true");
        } else if (url.startsWith("jdbc:postgresql:")) {
            properties.putIfAbsent("ssl", "true");
            properties.putIfAbsent("sslfactory", "org.postgresql.ssl.NonValidatingFactory");
        } else if (url.startsWith("jdbc:mysql:")) {
            properties.putIfAbsent("sslMode", "REQUIRED");
        } else if (url.startsWith("jdbc:mariadb:")) {
            properties.putIfAbsent("sslMode", "trust");
        } else {
            warnings.accept("WARNING: -k has no effect for this JDBC URL.");
        }
    }
}

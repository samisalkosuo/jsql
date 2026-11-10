package jsql.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import jsql.core.QueryLimits;

/**
 * Server configuration, read from environment variables.
 *
 * <pre>
 * JSQL_JDBC_URL          required  JDBC URL of the one database this server talks to
 * JSQL_USER_NAME         optional  default account, used when a request has no Authorization header
 * JSQL_USER_PASSWORD     optional  password of the default account (required with JSQL_USER_NAME)
 * JSQL_INSECURE          false     accept any TLS certificate (supported drivers only)
 * JSQL_READ_ONLY         true      reject statements that are not reads (safety net, not security)
 * JSQL_MAX_ROWS          1000      rows returned by one call
 * JSQL_QUERY_TIMEOUT     60        statement timeout in seconds, 0 = none
 * JSQL_MAX_VALUE_LENGTH  1000      characters returned per value
 * JSQL_POOL_SIZE         5         connections per pool (one pool per distinct account)
 * JSQL_POOL_IDLE_TTL     600       seconds without use after which a pool is closed
 * JSQL_MAX_POOLS         20        pools (distinct accounts) open at the same time
 * JSQL_CONNECT_TIMEOUT   30        seconds to wait for a connection
 * </pre>
 *
 * The HTTP port is configured with JSQL_HTTP_PORT (default 9080) in the Liberty server.xml.
 */
public final class McpConfig {

    private final Map<String, ConnectionConfig> connections;
    private final String defaultConnectionName;
    private final QueryLimits limits;
    private final int poolSize;
    private final int poolIdleTtlSeconds;
    private final int maxPools;
    private final int connectTimeoutSeconds;
    private final String exportDir;
    private final int maxExportRows;
    private final String savedQueriesDir;
    private final String annotationsFile;

    private McpConfig(Map<String, ConnectionConfig> connections, String defaultConnectionName, QueryLimits limits,
            int poolSize, int poolIdleTtlSeconds, int maxPools, int connectTimeoutSeconds, String exportDir,
            int maxExportRows, String savedQueriesDir, String annotationsFile) {
        this.connections = Map.copyOf(connections);
        this.defaultConnectionName = defaultConnectionName;
        this.limits = limits;
        this.poolSize = poolSize;
        this.poolIdleTtlSeconds = poolIdleTtlSeconds;
        this.maxPools = maxPools;
        this.connectTimeoutSeconds = connectTimeoutSeconds;
        this.exportDir = exportDir;
        this.maxExportRows = maxExportRows;
        this.savedQueriesDir = savedQueriesDir;
        this.annotationsFile = annotationsFile;
    }

    /**
     * Reads and validates the configuration.
     *
     * @throws ConfigException with all problems found
     */
    public static McpConfig fromEnvironment(Map<String, String> env) {
        List<String> problems = new ArrayList<>();

        Map<String, ConnectionConfig> connections = new java.util.LinkedHashMap<>();
        String defaultName = null;

        String primaryUrl = text(env, "JSQL_JDBC_URL");
        if (primaryUrl != null) {
            if (!primaryUrl.startsWith("jdbc:")) {
                problems.add("JSQL_JDBC_URL must start with 'jdbc:'");
            }
            String user = text(env, "JSQL_USER_NAME");
            String password = env.get("JSQL_USER_PASSWORD");
            Credentials defaults = null;
            if (user != null && password == null) {
                problems.add("JSQL_USER_PASSWORD must be set together with JSQL_USER_NAME");
            } else if (user == null && password != null) {
                problems.add("JSQL_USER_NAME must be set together with JSQL_USER_PASSWORD");
            } else if (user != null) {
                defaults = new Credentials(user, password);
            }
            boolean insecure = bool(env, "JSQL_INSECURE", false, problems);
            boolean readOnly = boolWithFallback(env, "JSQL_READ_ONLY", "READ_ONLY", true, problems);
            ConnectionConfig primary = new ConnectionConfig("default", primaryUrl, defaults, insecure, readOnly, true);
            connections.put("default", primary);
            defaultName = "default";
        }

        // Discover named connections: JSQL_CONNECTION_<NAME>_URL or JSQL_CONNECTIONS
        Pattern connUrlPattern = Pattern.compile("(?i)^JSQL_CONNECTION_([A-Za-z0-9_]+)_URL$");
        for (Map.Entry<String, String> e : env.entrySet()) {
            var m = connUrlPattern.matcher(e.getKey());
            if (m.matches()) {
                String connName = m.group(1).toLowerCase(Locale.ROOT);
                String cUrl = e.getValue() == null ? null : e.getValue().trim();
                if (cUrl == null || !cUrl.startsWith("jdbc:")) {
                    problems.add("JSQL_CONNECTION_" + m.group(1).toUpperCase(Locale.ROOT) + "_URL must start with 'jdbc:'");
                    continue;
                }
                String cUser = text(env, "JSQL_CONNECTION_" + m.group(1).toUpperCase(Locale.ROOT) + "_USER");
                String cPassword = env.get("JSQL_CONNECTION_" + m.group(1).toUpperCase(Locale.ROOT) + "_PASSWORD");
                Credentials cCreds = null;
                if (cUser != null && cPassword == null) {
                    problems.add("JSQL_CONNECTION_" + m.group(1).toUpperCase(Locale.ROOT) + "_PASSWORD must be set with USER");
                } else if (cUser == null && cPassword != null) {
                    problems.add("JSQL_CONNECTION_" + m.group(1).toUpperCase(Locale.ROOT) + "_USER must be set with PASSWORD");
                } else if (cUser != null) {
                    cCreds = new Credentials(cUser, cPassword);
                }
                boolean cInsecure = bool(env, "JSQL_CONNECTION_" + m.group(1).toUpperCase(Locale.ROOT) + "_INSECURE", false, problems);
                boolean cReadOnly = bool(env, "JSQL_CONNECTION_" + m.group(1).toUpperCase(Locale.ROOT) + "_READ_ONLY", true, problems);
                boolean isDef = defaultName == null;
                ConnectionConfig cc = new ConnectionConfig(connName, cUrl, cCreds, cInsecure, cReadOnly, isDef);
                connections.put(connName, cc);
                if (isDef) {
                    defaultName = connName;
                }
            }
        }

        if (connections.isEmpty()) {
            problems.add("JSQL_JDBC_URL is required");
        }

        int maxRows = integer(env, "JSQL_MAX_ROWS", 1000, 1, 100_000, problems);
        int queryTimeout = integer(env, "JSQL_QUERY_TIMEOUT", 60, 0, 3600, problems);
        int maxQueryTimeout = integer(env, "JSQL_MAX_QUERY_TIMEOUT", 3600, 0, 86_400, problems);
        int maxValueLength = integer(env, "JSQL_MAX_VALUE_LENGTH", 1000, 1, 100_000, problems);
        int poolSize = integer(env, "JSQL_POOL_SIZE", 5, 1, 100, problems);
        int idleTtl = integer(env, "JSQL_POOL_IDLE_TTL", 600, 1, 86_400, problems);
        int maxPools = integer(env, "JSQL_MAX_POOLS", 20, 1, 1000, problems);
        int connectTimeout = integer(env, "JSQL_CONNECT_TIMEOUT", 30, 1, 300, problems);

        String exportDir = text(env, "JSQL_EXPORT_DIR");
        if (exportDir == null) {
            exportDir = text(env, "EXPORT_DIR");
        }
        if (exportDir == null) {
            exportDir = "export";
        }
        int maxExportRows = integer(env, "JSQL_MAX_EXPORT_ROWS", 100_000, 1, 10_000_000, problems);

        String savedQueriesDir = text(env, "JSQL_SAVED_QUERIES_DIR");
        if (savedQueriesDir == null) {
            savedQueriesDir = text(env, "SAVED_QUERIES_DIR");
        }
        if (savedQueriesDir == null) {
            savedQueriesDir = "saved-queries";
        }

        String annotationsFile = text(env, "JSQL_ANNOTATIONS_FILE");
        if (annotationsFile == null) {
            annotationsFile = text(env, "ANNOTATIONS_FILE");
        }
        if (annotationsFile == null) {
            annotationsFile = "annotations.json";
        }

        if (!problems.isEmpty()) {
            throw new ConfigException(problems);
        }
        return new McpConfig(connections, defaultName,
                new QueryLimits(maxRows, queryTimeout, maxValueLength, maxQueryTimeout), poolSize, idleTtl, maxPools,
                connectTimeout, exportDir, maxExportRows, savedQueriesDir, annotationsFile);
    }

    private static String text(Map<String, String> env, String name) {
        String v = env.get(name);
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static boolean bool(Map<String, String> env, String name, boolean defaultValue, List<String> problems) {
        String v = text(env, name);
        if (v == null) {
            return defaultValue;
        }
        String lower = v.toLowerCase(Locale.ROOT);
        if (lower.equals("true")) {
            return true;
        }
        if (lower.equals("false")) {
            return false;
        }
        problems.add(name + " must be true or false, but is '" + v + "'");
        return defaultValue;
    }

    private static boolean boolWithFallback(Map<String, String> env, String name, String fallbackName,
            boolean defaultValue, List<String> problems) {
        String v = text(env, name);
        if (v == null) {
            v = text(env, fallbackName);
        }
        if (v == null) {
            return defaultValue;
        }
        String lower = v.toLowerCase(Locale.ROOT);
        if (lower.equals("true")) {
            return true;
        }
        if (lower.equals("false")) {
            return false;
        }
        problems.add(name + " must be true or false, but is '" + v + "'");
        return defaultValue;
    }

    private static int integer(Map<String, String> env, String name, int defaultValue, int min, int max,
            List<String> problems) {
        String v = text(env, name);
        if (v == null) {
            return defaultValue;
        }
        try {
            int n = Integer.parseInt(v);
            if (n < min || n > max) {
                problems.add(name + " must be between " + min + " and " + max + ", but is " + n);
                return defaultValue;
            }
            return n;
        } catch (NumberFormatException e) {
            problems.add(name + " must be a whole number, but is '" + v + "'");
            return defaultValue;
        }
    }

    public ConnectionConfig defaultConnection() {
        return connections.get(defaultConnectionName);
    }

    public ConnectionConfig connection(String name) {
        if (name == null || name.isBlank() || name.equalsIgnoreCase("default")) {
            return defaultConnection();
        }
        String lower = name.toLowerCase(Locale.ROOT).trim();
        ConnectionConfig cc = connections.get(lower);
        if (cc != null) {
            return cc;
        }
        throw new jsql.core.InvalidRequestException("Unknown connection: '" + name + "'. Available connections: "
                + String.join(", ", connectionNames()));
    }

    public List<ConnectionConfig> connections() {
        return List.copyOf(connections.values());
    }

    public List<String> connectionNames() {
        return connections.values().stream().map(ConnectionConfig::name).toList();
    }

    public String jdbcUrl() {
        ConnectionConfig def = defaultConnection();
        return def != null ? def.jdbcUrl() : null;
    }

    /** The account used when a request has no Authorization header; null if not configured. */
    public Credentials defaultCredentials() {
        ConnectionConfig def = defaultConnection();
        return def != null ? def.defaultCredentials() : null;
    }

    public boolean insecure() {
        ConnectionConfig def = defaultConnection();
        return def != null && def.insecure();
    }

    public boolean readOnly() {
        ConnectionConfig def = defaultConnection();
        return def != null && def.readOnly();
    }

    public QueryLimits limits() {
        return limits;
    }

    public int poolSize() {
        return poolSize;
    }

    public int poolIdleTtlSeconds() {
        return poolIdleTtlSeconds;
    }

    public int maxPools() {
        return maxPools;
    }

    public int connectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public String exportDir() {
        return exportDir;
    }

    public int maxExportRows() {
        return maxExportRows;
    }

    public String savedQueriesDir() {
        return savedQueriesDir;
    }

    public String annotationsFile() {
        return annotationsFile;
    }

    /** The JDBC URL with passwords removed, safe to log and to return to clients. */
    public String redactedJdbcUrl() {
        ConnectionConfig def = defaultConnection();
        return def != null ? def.redactedJdbcUrl() : "";
    }

    @Override
    public String toString() {
        ConnectionConfig def = defaultConnection();
        return "McpConfig[connections=" + connectionNames() + ", defaultConnection="
                + (def == null ? "none" : def.name()) + ", jdbcUrl=" + redactedJdbcUrl() + ", defaultUser="
                + (defaultCredentials() == null ? "none" : defaultCredentials().user()) + ", readOnly=" + readOnly()
                + ", limits=" + limits + ", poolSize=" + poolSize + ", poolIdleTtl=" + poolIdleTtlSeconds
                + "s, maxPools=" + maxPools + ", connectTimeout=" + connectTimeoutSeconds + "s]";
    }
}

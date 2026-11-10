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
 * JSQL_MAX_QUERY_TIMEOUT 3600      largest timeout_seconds a caller may ask for (larger values are cut), 0 = no limit
 * JSQL_POOL_SIZE         10        connections per pool (one pool per distinct account)
 * JSQL_POOL_IDLE_TTL     600       seconds without use after which a pool is closed
 * JSQL_MAX_POOLS         20        pools (distinct accounts) open at the same time
 * JSQL_CONNECT_TIMEOUT   30        seconds to wait for a connection from the pool
 * JSQL_POOL_MAX_LIFETIME  1800      seconds after which a pooled connection is replaced
 * JSQL_POOL_IDLE_TIMEOUT  600       seconds after which an unused pooled connection is closed
 * JSQL_POOL_LEAK_DETECTION 0        log a warning when a connection is held longer than this many seconds, 0 = off
 * JSQL_WATCHDOG_INTERVAL  30        seconds between watchdog runs
 * JSQL_WATCHDOG_GRACE     60        seconds an operation may exceed JSQL_MAX_QUERY_TIMEOUT before the watchdog stops it
 * JSQL_ADMIN_TOOLS_ENABLED false    register list_running_queries, cancel_query and reset_pool (also ADMIN_TOOLS_ENABLED)
 * JSQL_DESCRIBE_INCLUDE_INDEXES false  describe_table returns indexes unless include_indexes says otherwise (also DESCRIBE_INCLUDE_INDEXES)
 * JSQL_MAX_BATCH_QUERIES  10        queries accepted by one execute_batch call
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
    private final int maxLifetimeSeconds;
    private final int idleTimeoutSeconds;
    private final int leakDetectionSeconds;
    private final int watchdogIntervalSeconds;
    private final int watchdogGraceSeconds;
    private final boolean adminToolsEnabled;
    private final boolean describeIncludeIndexes;
    private final int maxBatchQueries;
    private final String exportDir;
    private final int maxExportRows;
    private final String savedQueriesDir;
    private final String annotationsFile;

    private McpConfig(Map<String, ConnectionConfig> connections, String defaultConnectionName, QueryLimits limits,
            int poolSize, int poolIdleTtlSeconds, int maxPools, int connectTimeoutSeconds, int maxLifetimeSeconds,
            int idleTimeoutSeconds, int leakDetectionSeconds, int watchdogIntervalSeconds, int watchdogGraceSeconds,
            boolean adminToolsEnabled, boolean describeIncludeIndexes, int maxBatchQueries, String exportDir,
            int maxExportRows, String savedQueriesDir, String annotationsFile) {
        this.connections = Map.copyOf(connections);
        this.defaultConnectionName = defaultConnectionName;
        this.limits = limits;
        this.poolSize = poolSize;
        this.poolIdleTtlSeconds = poolIdleTtlSeconds;
        this.maxPools = maxPools;
        this.connectTimeoutSeconds = connectTimeoutSeconds;
        this.maxLifetimeSeconds = maxLifetimeSeconds;
        this.idleTimeoutSeconds = idleTimeoutSeconds;
        this.leakDetectionSeconds = leakDetectionSeconds;
        this.watchdogIntervalSeconds = watchdogIntervalSeconds;
        this.watchdogGraceSeconds = watchdogGraceSeconds;
        this.adminToolsEnabled = adminToolsEnabled;
        this.describeIncludeIndexes = describeIncludeIndexes;
        this.maxBatchQueries = maxBatchQueries;
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

        boolean insecure = bool(env, "JSQL_INSECURE", false, problems);
        boolean readOnly = boolWithFallback(env, "JSQL_READ_ONLY", "READ_ONLY", true, problems);
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
        int poolSize = integer(env, "JSQL_POOL_SIZE", 10, 1, 100, problems);
        int idleTtl = integer(env, "JSQL_POOL_IDLE_TTL", 600, 1, 86_400, problems);
        int maxPools = integer(env, "JSQL_MAX_POOLS", 20, 1, 1000, problems);
        int connectTimeout = integer(env, "JSQL_CONNECT_TIMEOUT", 30, 1, 300, problems);
        int maxLifetime = integer(env, "JSQL_POOL_MAX_LIFETIME", 1800, 30, 86_400, problems);
        int idleTimeout = integer(env, "JSQL_POOL_IDLE_TIMEOUT", 600, 10, 86_400, problems);
        if (idleTimeout >= maxLifetime) {
            problems.add("JSQL_POOL_IDLE_TIMEOUT (" + idleTimeout + ") must be smaller than JSQL_POOL_MAX_LIFETIME ("
                    + maxLifetime + ")");
        }
        int leakDetection = integer(env, "JSQL_POOL_LEAK_DETECTION", 0, 0, 3600, problems);
        if (leakDetection == 1) {
            problems.add("JSQL_POOL_LEAK_DETECTION must be 0 (off) or at least 2, but is 1");
        }
        int watchdogInterval = integer(env, "JSQL_WATCHDOG_INTERVAL", 30, 1, 3600, problems);
        int watchdogGrace = integer(env, "JSQL_WATCHDOG_GRACE", 60, 0, 3600, problems);
        boolean adminTools = boolWithFallback(env, "JSQL_ADMIN_TOOLS_ENABLED", "ADMIN_TOOLS_ENABLED", false, problems);
        boolean describeIndexes = boolWithFallback(env, "JSQL_DESCRIBE_INCLUDE_INDEXES", "DESCRIBE_INCLUDE_INDEXES",
                false, problems);
        int maxBatch = integer(env, "JSQL_MAX_BATCH_QUERIES", 10, 1, 100, problems);

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
                connectTimeout, maxLifetime, idleTimeout, leakDetection, watchdogInterval, watchdogGrace, adminTools,
                describeIndexes, maxBatch, exportDir, maxExportRows, savedQueriesDir, annotationsFile);
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

    public int maxLifetimeSeconds() {
        return maxLifetimeSeconds;
    }

    public int idleTimeoutSeconds() {
        return idleTimeoutSeconds;
    }

    /** 0 means leak detection is off. */
    public int leakDetectionSeconds() {
        return leakDetectionSeconds;
    }

    public int watchdogIntervalSeconds() {
        return watchdogIntervalSeconds;
    }

    public int watchdogGraceSeconds() {
        return watchdogGraceSeconds;
    }

    public boolean adminToolsEnabled() {
        return adminToolsEnabled;
    }

    public boolean describeIncludeIndexes() {
        return describeIncludeIndexes;
    }

    public int maxBatchQueries() {
        return maxBatchQueries;
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

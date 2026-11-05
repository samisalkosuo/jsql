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
 * JSQL_READ_ONLY         false     reject statements that are not reads (safety net, not security)
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

    private static final Pattern KEY_VALUE_SECRET = Pattern
            .compile("(?i)(password|pwd|passwd|secret|token|sslpassword)=([^;&]*)");
    private static final Pattern ORACLE_USER_PASSWORD = Pattern.compile("(?i)(jdbc:oracle:[a-z]+:)([^/@\\s]*)/[^@\\s]*@");

    private final String jdbcUrl;
    private final Credentials defaultCredentials;
    private final boolean insecure;
    private final boolean readOnly;
    private final QueryLimits limits;
    private final int poolSize;
    private final int poolIdleTtlSeconds;
    private final int maxPools;
    private final int connectTimeoutSeconds;

    private McpConfig(String jdbcUrl, Credentials defaultCredentials, boolean insecure, boolean readOnly,
            QueryLimits limits, int poolSize, int poolIdleTtlSeconds, int maxPools, int connectTimeoutSeconds) {
        this.jdbcUrl = jdbcUrl;
        this.defaultCredentials = defaultCredentials;
        this.insecure = insecure;
        this.readOnly = readOnly;
        this.limits = limits;
        this.poolSize = poolSize;
        this.poolIdleTtlSeconds = poolIdleTtlSeconds;
        this.maxPools = maxPools;
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    /**
     * Reads and validates the configuration.
     *
     * @throws ConfigException with all problems found
     */
    public static McpConfig fromEnvironment(Map<String, String> env) {
        List<String> problems = new ArrayList<>();

        String url = text(env, "JSQL_JDBC_URL");
        if (url == null) {
            problems.add("JSQL_JDBC_URL is required");
        } else if (!url.startsWith("jdbc:")) {
            problems.add("JSQL_JDBC_URL must start with 'jdbc:'");
        }

        String user = text(env, "JSQL_USER_NAME");
        String password = env.get("JSQL_USER_PASSWORD"); // an empty password is allowed
        Credentials defaults = null;
        if (user != null && password == null) {
            problems.add("JSQL_USER_PASSWORD must be set together with JSQL_USER_NAME");
        } else if (user == null && password != null) {
            problems.add("JSQL_USER_NAME must be set together with JSQL_USER_PASSWORD");
        } else if (user != null) {
            defaults = new Credentials(user, password);
        }

        boolean insecure = bool(env, "JSQL_INSECURE", problems);
        boolean readOnly = bool(env, "JSQL_READ_ONLY", problems);
        int maxRows = integer(env, "JSQL_MAX_ROWS", 1000, 1, 100_000, problems);
        int queryTimeout = integer(env, "JSQL_QUERY_TIMEOUT", 60, 0, 3600, problems);
        int maxValueLength = integer(env, "JSQL_MAX_VALUE_LENGTH", 1000, 1, 100_000, problems);
        int poolSize = integer(env, "JSQL_POOL_SIZE", 5, 1, 100, problems);
        int idleTtl = integer(env, "JSQL_POOL_IDLE_TTL", 600, 1, 86_400, problems);
        int maxPools = integer(env, "JSQL_MAX_POOLS", 20, 1, 1000, problems);
        int connectTimeout = integer(env, "JSQL_CONNECT_TIMEOUT", 30, 1, 300, problems);

        if (!problems.isEmpty()) {
            throw new ConfigException(problems);
        }
        return new McpConfig(url, defaults, insecure, readOnly, new QueryLimits(maxRows, queryTimeout, maxValueLength),
                poolSize, idleTtl, maxPools, connectTimeout);
    }

    private static String text(Map<String, String> env, String name) {
        String v = env.get(name);
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static boolean bool(Map<String, String> env, String name, List<String> problems) {
        String v = text(env, name);
        if (v == null) {
            return false;
        }
        String lower = v.toLowerCase(Locale.ROOT);
        if (lower.equals("true")) {
            return true;
        }
        if (lower.equals("false")) {
            return false;
        }
        problems.add(name + " must be true or false, but is '" + v + "'");
        return false;
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

    public String jdbcUrl() {
        return jdbcUrl;
    }

    /** The account used when a request has no Authorization header; null if not configured. */
    public Credentials defaultCredentials() {
        return defaultCredentials;
    }

    public boolean insecure() {
        return insecure;
    }

    public boolean readOnly() {
        return readOnly;
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

    /** The JDBC URL with passwords removed, safe to log and to return to clients. */
    public String redactedJdbcUrl() {
        String s = KEY_VALUE_SECRET.matcher(jdbcUrl).replaceAll("$1=***");
        return ORACLE_USER_PASSWORD.matcher(s).replaceAll("$1$2/***@");
    }

    @Override
    public String toString() {
        return "McpConfig[jdbcUrl=" + redactedJdbcUrl() + ", defaultUser="
                + (defaultCredentials == null ? "none" : defaultCredentials.user()) + ", insecure=" + insecure
                + ", readOnly=" + readOnly + ", limits=" + limits + ", poolSize=" + poolSize + ", poolIdleTtl="
                + poolIdleTtlSeconds + "s, maxPools=" + maxPools + ", connectTimeout=" + connectTimeoutSeconds
                + "s]";
    }
}

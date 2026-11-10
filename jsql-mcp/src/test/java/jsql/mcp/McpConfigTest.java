package jsql.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class McpConfigTest {

    private static Map<String, String> env(String... keyValues) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put(keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    private static String problems(Map<String, String> env) {
        try {
            McpConfig.fromEnvironment(env);
            fail("expected ConfigException");
            return null;
        } catch (ConfigException e) {
            return e.getMessage();
        }
    }

    @Test
    public void defaults() {
        McpConfig c = McpConfig.fromEnvironment(env("JSQL_JDBC_URL", "jdbc:postgresql://h/db"));
        assertEquals("jdbc:postgresql://h/db", c.jdbcUrl());
        assertNull(c.defaultCredentials());
        assertFalse(c.insecure());
        assertTrue(c.readOnly());
        assertEquals(1000, c.limits().maxRows());
        assertEquals(60, c.limits().queryTimeoutSeconds());
        assertEquals(1000, c.limits().maxValueLength());
        assertEquals(10, c.poolSize());
        assertEquals(600, c.poolIdleTtlSeconds());
        assertEquals(20, c.maxPools());
        assertEquals(30, c.connectTimeoutSeconds());
    }

    @Test
    public void allValues() {
        McpConfig c = McpConfig.fromEnvironment(env("JSQL_JDBC_URL", " jdbc:h2:mem:x ", "JSQL_USER_NAME", "u",
                "JSQL_USER_PASSWORD", "p", "JSQL_INSECURE", "TRUE", "JSQL_READ_ONLY", "true", "JSQL_MAX_ROWS", "50",
                "JSQL_QUERY_TIMEOUT", "0", "JSQL_MAX_VALUE_LENGTH", "200", "JSQL_POOL_SIZE", "3",
                "JSQL_POOL_IDLE_TTL", "30", "JSQL_MAX_POOLS", "4", "JSQL_CONNECT_TIMEOUT", "7"));
        assertEquals("jdbc:h2:mem:x", c.jdbcUrl());
        assertEquals("u", c.defaultCredentials().user());
        assertEquals("p", c.defaultCredentials().password());
        assertTrue(c.insecure());
        assertTrue(c.readOnly());
        assertEquals(50, c.limits().maxRows());
        assertEquals(0, c.limits().queryTimeoutSeconds());
        assertEquals(200, c.limits().maxValueLength());
        assertEquals(3, c.poolSize());
        assertEquals(30, c.poolIdleTtlSeconds());
        assertEquals(4, c.maxPools());
        assertEquals(7, c.connectTimeoutSeconds());
    }

    @Test
    public void blankValuesCountAsNotSet() {
        McpConfig c = McpConfig.fromEnvironment(env("JSQL_JDBC_URL", "jdbc:h2:mem:x", "JSQL_MAX_ROWS", " ",
                "JSQL_READ_ONLY", "", "JSQL_USER_NAME", " "));
        assertEquals(1000, c.limits().maxRows());
        assertTrue(c.readOnly());
        assertNull(c.defaultCredentials());
    }

    @Test
    public void readOnlyCanBeDisabledViaReadOnlyOrJsqlReadOnly() {
        McpConfig c1 = McpConfig.fromEnvironment(env("JSQL_JDBC_URL", "jdbc:h2:mem:x", "JSQL_READ_ONLY", "false"));
        assertFalse(c1.readOnly());
        McpConfig c2 = McpConfig.fromEnvironment(env("JSQL_JDBC_URL", "jdbc:h2:mem:x", "READ_ONLY", "false"));
        assertFalse(c2.readOnly());
    }

    @Test
    public void emptyPasswordIsAllowedForDefaultAccount() {
        McpConfig c = McpConfig.fromEnvironment(env("JSQL_JDBC_URL", "jdbc:h2:mem:x", "JSQL_USER_NAME", "sa",
                "JSQL_USER_PASSWORD", ""));
        assertEquals("", c.defaultCredentials().password());
    }

    @Test
    public void urlIsRequiredAndMustBeJdbc() {
        assertTrue(problems(env()).contains("JSQL_JDBC_URL is required"));
        assertTrue(problems(env("JSQL_JDBC_URL", "postgresql://h/db")).contains("must start with 'jdbc:'"));
    }

    @Test
    public void defaultAccountNeedsBothParts() {
        assertTrue(problems(env("JSQL_JDBC_URL", "jdbc:h2:mem:x", "JSQL_USER_NAME", "u"))
                .contains("JSQL_USER_PASSWORD must be set together"));
        assertTrue(problems(env("JSQL_JDBC_URL", "jdbc:h2:mem:x", "JSQL_USER_PASSWORD", "p"))
                .contains("JSQL_USER_NAME must be set together"));
    }

    @Test
    public void allProblemsAreReportedTogether() {
        String p = problems(env("JSQL_INSECURE", "yes", "JSQL_MAX_ROWS", "abc", "JSQL_POOL_SIZE", "0",
                "JSQL_QUERY_TIMEOUT", "-1"));
        assertTrue(p, p.contains("JSQL_JDBC_URL is required"));
        assertTrue(p, p.contains("JSQL_INSECURE must be true or false, but is 'yes'"));
        assertTrue(p, p.contains("JSQL_MAX_ROWS must be a whole number, but is 'abc'"));
        assertTrue(p, p.contains("JSQL_POOL_SIZE must be between 1 and 100, but is 0"));
        assertTrue(p, p.contains("JSQL_QUERY_TIMEOUT must be between 0 and 3600, but is -1"));
    }

    @Test
    public void passwordsAreRemovedFromTheUrlAndNeverInToString() {
        McpConfig pg = McpConfig.fromEnvironment(env("JSQL_JDBC_URL",
                "jdbc:postgresql://h/db?user=bob&password=hunter2&ssl=true", "JSQL_USER_NAME", "svc",
                "JSQL_USER_PASSWORD", "topsecret"));
        assertEquals("jdbc:postgresql://h/db?user=bob&password=***&ssl=true", pg.redactedJdbcUrl());
        assertFalse(pg.toString().contains("hunter2"));
        assertFalse(pg.toString().contains("topsecret"));
        assertTrue(pg.toString().contains("defaultUser=svc"));

        McpConfig sqlserver = McpConfig
                .fromEnvironment(env("JSQL_JDBC_URL", "jdbc:sqlserver://h:1433;databaseName=d;password=hunter2;encrypt=true"));
        assertEquals("jdbc:sqlserver://h:1433;databaseName=d;password=***;encrypt=true", sqlserver.redactedJdbcUrl());

        McpConfig oracle = McpConfig.fromEnvironment(env("JSQL_JDBC_URL", "jdbc:oracle:thin:scott/tiger@//h:1521/svc"));
        assertEquals("jdbc:oracle:thin:scott/***@//h:1521/svc", oracle.redactedJdbcUrl());

        McpConfig plain = McpConfig.fromEnvironment(env("JSQL_JDBC_URL", "jdbc:db2://h:50000/sample"));
        assertEquals("jdbc:db2://h:50000/sample", plain.redactedJdbcUrl());
    }
}

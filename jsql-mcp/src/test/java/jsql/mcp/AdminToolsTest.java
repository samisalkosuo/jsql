package jsql.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.StringReader;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mcpjava.server.tools.ToolResponse;

import io.openliberty.mcp.tools.ToolCallException;
import io.openliberty.mcp.tools.ToolManager;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jsql.core.InvalidRequestException;

public class AdminToolsTest {

    private static final String URL = "jdbc:h2:mem:admintoolstest";

    private Connection keepAlive;
    private ServerState state;

    @Before
    public void setUp() throws Exception {
        keepAlive = DriverManager.getConnection(URL, "sa", "");
    }

    @After
    public void tearDown() throws Exception {
        if (state != null) {
            state.stop();
        }
        keepAlive.close();
    }

    private AdminTools admin(boolean enabled, ToolManager manager) {
        Map<String, String> env = new HashMap<>();
        env.put("JSQL_JDBC_URL", URL);
        env.put("JSQL_USER_NAME", "sa");
        env.put("JSQL_USER_PASSWORD", "");
        env.put("JSQL_POOL_SIZE", "3");
        if (enabled) {
            env.put("ADMIN_TOOLS_ENABLED", "true");
        }
        state = new ServerState();
        state.initialize(McpConfig.fromEnvironment(env));
        AdminTools tools = new AdminTools();
        tools.state = state;
        tools.toolManager = manager;
        return tools;
    }

    /** Records which tools are registered; the fluent definition API is answered by a proxy. */
    private static final class RecordingManager {
        final List<String> names = new ArrayList<>();
        final Map<String, Function<ToolManager.ToolArguments, ToolResponse>> handlers = new HashMap<>();
        final ToolManager manager;

        @SuppressWarnings("unchecked")
        RecordingManager() {
            manager = (ToolManager) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] { ToolManager.class }, (proxy, method, args) -> {
                        if (method.getName().equals("newTool")) {
                            String name = (String) args[0];
                            names.add(name);
                            return Proxy.newProxyInstance(getClass().getClassLoader(),
                                    new Class<?>[] { ToolManager.ToolDefinition.class }, (p, m, a) -> {
                                        if (m.getName().equals("setHandler")) {
                                            handlers.put(name, (Function<ToolManager.ToolArguments, ToolResponse>) a[0]);
                                        }
                                        return m.getReturnType().isInstance(p) ? p : null;
                                    });
                        }
                        return null;
                    });
        }
    }

    @Test
    public void adminToolsAreNotRegisteredByDefault() {
        RecordingManager rec = new RecordingManager();
        AdminTools tools = admin(false, rec.manager);
        assertFalse(state.config().adminToolsEnabled());
        assertFalse(tools.registerTools());
        assertTrue("tool list has no admin tools", rec.names.isEmpty());
    }

    @Test
    public void adminToolsAreRegisteredWhenTheFlagIsTrue() {
        RecordingManager rec = new RecordingManager();
        AdminTools tools = admin(true, rec.manager);
        assertTrue(tools.registerTools());
        assertEquals(List.of("list_running_queries", "cancel_query", "reset_pool"), rec.names);
        assertEquals(3, rec.handlers.size());
    }

    @Test
    public void adminToolsAreOffForAnInvalidConfiguration() {
        RecordingManager rec = new RecordingManager();
        state = new ServerState(); // not initialised; the environment of the test has no JSQL_JDBC_URL
        AdminTools tools = new AdminTools();
        tools.state = state;
        tools.toolManager = rec.manager;
        assertFalse(tools.registerTools());
        assertTrue(rec.names.isEmpty());
    }

    @Test
    public void listCancelAndResetWork() throws Exception {
        AdminTools tools = admin(true, new RecordingManager().manager);
        assertEquals(0, json(tools.listRunningQueries()).getJsonArray("queries").size());

        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch started = new CountDownLatch(1);
            DbAccess db = new DbAccess();
            db.state = state;
            Future<String> call = caller.submit(() -> db.run(DbAccess.Call.sql("execute_query", "select slow", null), null,
                    null, conn -> {
                        started.countDown();
                        try {
                            Thread.sleep(60_000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new java.sql.SQLException("interrupted", e);
                        }
                        return "never";
                    }));
            assertTrue(started.await(5, TimeUnit.SECONDS));

            JsonObject running = json(tools.listRunningQueries()).getJsonArray("queries").getJsonObject(0);
            assertEquals("execute_query", running.getString("tool"));
            assertEquals("select slow", running.getString("sql"));
            String id = running.getString("query_id");

            assertFalse(json(tools.cancelQuery("unknown")).getBoolean("cancelled"));
            assertTrue(json(tools.cancelQuery(id)).getBoolean("cancelled"));
            try {
                call.get(10, TimeUnit.SECONDS);
                fail("expected the call to be cancelled");
            } catch (java.util.concurrent.ExecutionException e) {
                assertTrue(String.valueOf(e.getCause()), e.getCause() instanceof ToolCallException);
                assertTrue(e.getCause().getMessage().contains("cancelled"));
            }
            assertEquals(0, state.pools().status().get(0).active());
        } finally {
            caller.shutdownNow();
        }

        JsonObject reset = json(tools.resetPool(null, false));
        assertFalse(reset.getBoolean("force"));
        assertNotNull(reset.getJsonArray("before"));
        JsonObject forced = json(tools.resetPool("default", true));
        assertTrue(forced.getBoolean("force"));
        assertEquals(0, forced.getJsonArray("after").size());

        // the pool opens again on the next request
        DbAccess db = new DbAccess();
        db.state = state;
        assertEquals("ok", db.run(DbAccess.Call.of("probe", null), null, null, conn -> "ok"));
    }

    @Test
    public void resetPoolRejectsUnknownConnectionAndCancelNeedsAnId() {
        AdminTools tools = admin(true, new RecordingManager().manager);
        try {
            tools.resetPool("nope", false);
            fail();
        } catch (InvalidRequestException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("Unknown connection"));
        }
        try {
            tools.cancelQuery(" ");
            fail();
        } catch (InvalidRequestException e) {
            assertTrue(e.getMessage().contains("query_id"));
        }
    }

    private static JsonObject json(String text) {
        return Json.createReader(new StringReader(text)).readObject();
    }
}

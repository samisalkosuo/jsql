package jsql.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import jsql.core.QueryTimeoutException;
import jsql.mcp.Operations.CancelledException;
import jsql.mcp.Operations.HistoryEntry;
import jsql.mcp.Operations.Spec;
import jsql.mcp.PoolManager.PoolStatus;

public class OperationsTest {

    private static final String URL = "jdbc:h2:mem:operationstest";

    private final Credentials admin = new Credentials("sa", "");
    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private Connection keepAlive;
    private McpConfig config;
    private PoolManager pools;
    private Operations operations;

    @Before
    public void setUp() throws Exception {
        keepAlive = DriverManager.getConnection(URL, "sa", "");
        Map<String, String> env = new HashMap<>();
        env.put("JSQL_JDBC_URL", URL);
        env.put("JSQL_POOL_SIZE", "3");
        env.put("JSQL_CONNECT_TIMEOUT", "2");
        env.put("JSQL_MAX_QUERY_TIMEOUT", "10");
        env.put("JSQL_WATCHDOG_GRACE", "5");
        config = McpConfig.fromEnvironment(env);
        pools = new PoolManager(config);
        operations = new Operations(config, clock::get, false);
    }

    @After
    public void tearDown() throws Exception {
        operations.close();
        pools.close();
        keepAlive.close();
    }

    private <T> T run(String tool, long deadlineMillis, PoolManager.ConnectionWork<T> work) throws SQLException {
        Spec spec = new Spec(tool, "default", "select 1", deadlineMillis);
        return pools.withConnection(config.defaultConnection(), admin,
                (connection, lease) -> operations.execute(spec, connection, lease, work));
    }

    // stands in for a database call that does not return; ends with an SQLException when interrupted
    private static void block() throws SQLException {
        try {
            Thread.sleep(60_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("interrupted", e);
        }
    }

    private PoolStatus onlyPool() {
        List<PoolStatus> status = pools.status();
        assertEquals(1, status.size());
        return status.get(0);
    }

    @Test
    public void quickWorkReturnsItsResultAndIsRecorded() throws Exception {
        String result = run("probe", 5000, conn -> {
            Operations.recordRows(7);
            return "done";
        });
        assertEquals("done", result);
        HistoryEntry h = operations.history(10).get(0);
        assertEquals("probe", h.tool());
        assertEquals("ok", h.state());
        assertEquals(Integer.valueOf(7), h.rowCount());
        assertNotNull("only a hash of the SQL is kept", h.sqlHash());
        assertEquals(0, operations.running().size());
    }

    @Test
    public void workErrorsPassThroughUnchanged() {
        try {
            run("probe", 5000, conn -> {
                throw new SQLException("boom", "42000");
            });
            fail();
        } catch (SQLException e) {
            assertEquals("42000", e.getSQLState());
        }
        assertEquals("error", operations.history(1).get(0).state());
    }

    @Test
    public void deadlineStopsStuckWorkAndFreesTheConnection() throws Exception {
        long start = System.nanoTime();
        try {
            run("describe_table", 300, conn -> {
                block(); // a metadata call that never returns
                return "never";
            });
            fail("expected timeout");
        } catch (QueryTimeoutException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("error_type: timeout"));
        }
        assertTrue("returned promptly", TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 10);
        assertEquals(0, onlyPool().active());
        assertEquals("timeout", operations.history(1).get(0).state());
        assertEquals("timeout", operations.history(1).get(0).errorType());
        // the pool still works
        assertEquals("ok", run("probe", 5000, conn -> "ok"));
    }

    @Test
    public void twentyConsecutiveTimeoutsLeakNoConnections() throws Exception {
        for (int i = 0; i < 20; i++) {
            try {
                run("probe", 100, conn -> {
                    block();
                    return null;
                });
                fail("expected timeout");
            } catch (QueryTimeoutException expected) {
                // expected
            }
        }
        PoolStatus status = onlyPool();
        assertEquals("active connections", 0, status.active());
        assertEquals("waiting threads", 0, status.waiting());
        assertTrue("total " + status.total(), status.total() <= status.maxPoolSize());
        assertEquals("ok", run("probe", 5000, conn -> "ok"));
        assertEquals(0, operations.running().size());
    }

    @Test
    public void administratorCanCancelARunningOperation() throws Exception {
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch started = new CountDownLatch(1);
            Future<String> call = caller.submit(() -> run("slow_tool", 0, conn -> {
                started.countDown();
                block();
                return "never";
            }));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            List<Operations.Running> running = operations.running();
            assertEquals(1, running.size());
            assertEquals("slow_tool", running.get(0).tool());

            assertFalse(operations.cancel("no-such-id", "test"));
            assertTrue(operations.cancel(running.get(0).queryId(), "cancelled by administrator"));
            try {
                call.get(10, TimeUnit.SECONDS);
                fail("expected cancellation");
            } catch (java.util.concurrent.ExecutionException e) {
                assertTrue(String.valueOf(e.getCause()), e.getCause() instanceof CancelledException);
                assertTrue(e.getCause().getMessage().contains("cancelled"));
            }
            assertEquals("cancelled", operations.history(1).get(0).state());
            assertEquals(0, onlyPool().active());
        } finally {
            caller.shutdownNow();
        }
    }

    @Test
    public void watchdogStopsOperationsPastTheLimitAndReclaimsTheConnection() throws Exception {
        // limit = max query timeout 10s + grace 5s = 15s, measured with the fake clock
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch started = new CountDownLatch(1);
            Future<String> call = caller.submit(() -> run("hung_tool", 0, conn -> {
                started.countDown();
                block();
                return "never";
            }));
            assertTrue(started.await(5, TimeUnit.SECONDS));

            clock.addAndGet(TimeUnit.SECONDS.toNanos(14));
            operations.sweep();
            assertEquals("not yet over the limit", 1, operations.running().size());

            clock.addAndGet(TimeUnit.SECONDS.toNanos(2));
            operations.sweep();
            try {
                call.get(10, TimeUnit.SECONDS);
                fail("expected the watchdog to stop the operation");
            } catch (java.util.concurrent.ExecutionException e) {
                assertTrue(String.valueOf(e.getCause()), e.getCause() instanceof CancelledException);
                assertTrue(e.getCause().getMessage(), e.getCause().getMessage().contains("watchdog"));
            }
            assertEquals(0, operations.running().size());
            assertEquals(0, onlyPool().active());
        } finally {
            caller.shutdownNow();
        }
    }

    @Test
    public void historyIsNewestFirstAndLimited() throws Exception {
        for (int i = 0; i < 5; i++) {
            String tool = "t" + i;
            run(tool, 5000, conn -> "x");
        }
        List<HistoryEntry> last = operations.history(3);
        assertEquals(3, last.size());
        assertEquals("t4", last.get(0).tool());
        assertEquals("t2", last.get(2).tool());
    }
}

package jsql.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
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

import jsql.mcp.PoolManager.TooManyAccountsException;

public class PoolManagerTest {

    // No DB_CLOSE_DELAY here: H2 only lets admin users set it. The keepAlive connection keeps the database open.
    private static final String URL = "jdbc:h2:mem:pooltest";

    private final Credentials admin = new Credentials("sa", "");
    private final Credentials alice = new Credentials("alice", "pw1");
    private final Credentials bob = new Credentials("bob", "pw2");
    private final Credentials carol = new Credentials("carol", "pw3");

    private final AtomicLong clockNanos = new AtomicLong(1_000_000_000L);
    private PoolManager pools;
    private Connection keepAlive;

    private McpConfig config(int maxPools, int idleTtlSeconds) {
        Map<String, String> env = new HashMap<>();
        env.put("JSQL_JDBC_URL", URL);
        env.put("JSQL_MAX_POOLS", String.valueOf(maxPools));
        env.put("JSQL_POOL_IDLE_TTL", String.valueOf(idleTtlSeconds));
        env.put("JSQL_POOL_SIZE", "2");
        env.put("JSQL_CONNECT_TIMEOUT", "1");
        return McpConfig.fromEnvironment(env);
    }

    private void start(int maxPools, int idleTtlSeconds) {
        pools = new PoolManager(config(maxPools, idleTtlSeconds), clockNanos::get, false);
    }

    @Before
    public void createUsers() throws Exception {
        // keeps the in-memory database alive and defines the accounts
        keepAlive = java.sql.DriverManager.getConnection(URL, "sa", "");
        try (Statement st = keepAlive.createStatement()) {
            st.execute("create user if not exists alice password 'pw1'");
            st.execute("create user if not exists bob password 'pw2'");
            st.execute("create user if not exists carol password 'pw3'");
        }
    }

    @After
    public void tearDown() throws Exception {
        if (pools != null) {
            pools.close();
        }
        try (Statement st = keepAlive.createStatement()) {
            st.execute("drop user if exists alice");
            st.execute("drop user if exists bob");
            st.execute("drop user if exists carol");
        }
        keepAlive.close();
    }

    private static String currentUser(PoolManager p, Credentials c) throws SQLException {
        return p.withConnection(c, conn -> {
            try (Statement st = conn.createStatement(); var rs = st.executeQuery("select current_user")) {
                rs.next();
                return rs.getString(1);
            }
        });
    }

    private void advanceSeconds(long seconds) {
        clockNanos.addAndGet(TimeUnit.SECONDS.toNanos(seconds));
    }

    @Test
    public void connectsAsTheRequestedUser() throws Exception {
        start(5, 600);
        assertEquals("ALICE", currentUser(pools, alice));
        assertEquals("BOB", currentUser(pools, bob));
        assertEquals(2, pools.poolCount());
    }

    @Test
    public void samePoolIsReusedForSameAccount() throws Exception {
        start(5, 600);
        currentUser(pools, alice);
        currentUser(pools, alice);
        currentUser(pools, new Credentials("alice", "pw1"));
        assertEquals(1, pools.poolCount());
    }

    @Test
    public void wrongPasswordFailsAndLeavesNoPool() throws Exception {
        start(5, 600);
        try {
            currentUser(pools, new Credentials("alice", "wrong"));
            fail();
        } catch (SQLException e) {
            // the driver's own error
            assertTrue(e.getMessage(), e.getMessage().toLowerCase().contains("wrong user name or password"));
        }
        assertEquals(0, pools.poolCount());
        // and the right password still works afterwards
        assertEquals("ALICE", currentUser(pools, alice));
    }

    @Test
    public void passwordChangeGivesNewPoolAndOldOneExpires() throws Exception {
        start(5, 600);
        currentUser(pools, alice);
        try (Statement st = keepAlive.createStatement()) {
            st.execute("alter user alice set password 'newpw'");
        }
        assertEquals("ALICE", currentUser(pools, new Credentials("alice", "newpw")));
        assertEquals(2, pools.poolCount());

        advanceSeconds(601);
        pools.reapIdle();
        // both are idle now; the one with the old password is no longer needed
        assertEquals(0, pools.poolCount());
    }

    @Test
    public void idlePoolsAreClosedAfterTtl() throws Exception {
        start(5, 600);
        currentUser(pools, alice);
        advanceSeconds(599);
        pools.reapIdle();
        assertEquals(1, pools.poolCount());

        currentUser(pools, alice); // use resets the idle time
        advanceSeconds(599);
        pools.reapIdle();
        assertEquals(1, pools.poolCount());

        advanceSeconds(2);
        pools.reapIdle();
        assertEquals(0, pools.poolCount());
        assertEquals("ALICE", currentUser(pools, alice)); // recreated on demand
    }

    @Test
    public void leastRecentlyUsedIdlePoolIsEvictedWhenFull() throws Exception {
        start(2, 600);
        currentUser(pools, alice);
        advanceSeconds(1);
        currentUser(pools, bob);
        advanceSeconds(1);
        currentUser(pools, alice); // alice is now more recent than bob
        advanceSeconds(1);
        currentUser(pools, carol); // needs room: bob goes
        assertEquals(2, pools.poolCount());

        // bob is gone, alice and carol remain: using them does not change the count
        currentUser(pools, alice);
        currentUser(pools, carol);
        assertEquals(2, pools.poolCount());
    }

    @Test
    public void poolInUseIsNeverEvictedAndNewAccountIsRefused() throws Exception {
        start(1, 600);
        CountDownLatch inUse = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> aliceWork = executor.submit(() -> pools.withConnection(alice, conn -> {
                inUse.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                try (Statement st = conn.createStatement(); var rs = st.executeQuery("select current_user")) {
                    rs.next();
                    return rs.getString(1);
                }
            }));
            assertTrue(inUse.await(10, TimeUnit.SECONDS));

            advanceSeconds(10_000); // far beyond the TTL
            pools.reapIdle();
            assertEquals("a pool in use must survive the reaper", 1, pools.poolCount());

            try {
                currentUser(pools, bob);
                fail();
            } catch (TooManyAccountsException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("Too many different database accounts"));
            }

            release.countDown();
            assertEquals("ALICE", aliceWork.get(10, TimeUnit.SECONDS)); // the running query was not aborted
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        // now alice is idle, bob can take the place
        assertEquals("BOB", currentUser(pools, bob));
        assertEquals(1, pools.poolCount());
    }

    @Test
    public void concurrentFirstUseCreatesOnePool() throws Exception {
        start(5, 600);
        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            java.util.List<Future<String>> results = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(executor.submit(() -> {
                    go.await();
                    return currentUser(pools, alice);
                }));
            }
            go.countDown();
            for (Future<String> f : results) {
                assertEquals("ALICE", f.get(20, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, pools.poolCount());
    }

    @Test
    public void connectionsAreReturnedToThePool() throws Exception {
        start(5, 600); // pool size 2
        for (int i = 0; i < 10; i++) {
            currentUser(pools, alice); // would time out after 2 calls if connections leaked
        }
        assertEquals(1, pools.poolCount());
    }

    @Test
    public void closeClosesEverythingAndRefusesNewWork() throws Exception {
        start(5, 600);
        currentUser(pools, alice);
        pools.close();
        assertEquals(0, pools.poolCount());
        try {
            currentUser(pools, bob);
            fail();
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("shutting down"));
        }
    }

    @Test
    public void keyDoesNotCollideOnUserPasswordBoundary() {
        assertNotEquals(PoolManager.keyOf(new Credentials("ab", "c")), PoolManager.keyOf(new Credentials("a", "bc")));
        assertEquals(PoolManager.keyOf(alice), PoolManager.keyOf(new Credentials("alice", "pw1")));
        assertTrue(PoolManager.keyOf(alice).matches("[0-9a-f]{64}"));
    }

    @Test
    public void adminAccountWorks() throws Exception {
        start(5, 600);
        assertEquals("SA", currentUser(pools, admin));
    }
}

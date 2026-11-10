package jsql.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import jsql.core.ConnectionSettings;

/**
 * One connection pool per distinct account (user and password).
 *
 * <ul>
 * <li>Pools are created on first use. The credentials are checked with a plain connection first, so
 * a wrong password gives the database's own error quickly and no pool is left behind.
 * <li>A pool that has not been used for the idle time is closed. When the maximum number of pools
 * is reached, the least recently used idle pool is closed to make room. A pool that is in use is
 * never closed for either reason, because that would abort running queries.
 * <li>A user who changes password simply gets a new pool; the old one expires.
 * <li>If a pool cannot get a connection any more (for example the password was changed in the
 * database), it is dropped, so the next request checks the credentials again.
 * </ul>
 */
public final class PoolManager implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(PoolManager.class.getName());

    /** Work to do with a pooled connection. The connection is returned to the pool afterwards. */
    public interface ConnectionWork<T> {
        T apply(Connection connection) throws SQLException;
    }

    /** Work that also gets a handle to throw its connection away instead of returning it to the pool. */
    public interface LeasedWork<T> {
        T apply(Connection connection, Lease lease) throws SQLException;
    }

    /** The borrowed connection. */
    public interface Lease {
        /**
         * Closes the connection for good (also while it is in use) instead of returning it to the
         * pool. Used when a statement may still be running on it.
         */
        void evict();
    }

    /** Connection counters of one pool. */
    public record PoolStatus(String connection, String user, int total, int active, int idle, int waiting,
            int maxPoolSize, long oldestActiveSeconds) {
    }

    /** All pools are in use by other accounts, so no new account can be served right now. */
    public static class TooManyAccountsException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public TooManyAccountsException(String message) {
            super(message);
        }
    }

    private static final class Entry {
        final String key;
        final String connectionName;
        final String user;
        final HikariDataSource pool;
        final ConcurrentHashMap<Object, Long> activeSince = new ConcurrentHashMap<>();
        int leases;
        long lastUsed;
        boolean retired;

        Entry(String key, String connectionName, String user, HikariDataSource pool, long now) {
            this.key = key;
            this.connectionName = connectionName;
            this.user = user;
            this.pool = pool;
            this.lastUsed = now;
        }
    }

    private final McpConfig config;
    private final LongSupplier nanoClock;
    private final Consumer<String> warnings;
    private final Object lock = new Object();
    private final Map<String, Entry> pools = new HashMap<>(); // guarded by lock
    private final ConcurrentHashMap<String, Object> creating = new ConcurrentHashMap<>();
    private final AtomicInteger poolSequence = new AtomicInteger();
    private final ScheduledExecutorService reaper;
    private boolean closed; // guarded by lock

    public PoolManager(McpConfig config) {
        this(config, System::nanoTime, true);
    }

    PoolManager(McpConfig config, LongSupplier nanoClock, boolean startReaper) {
        this.config = config;
        this.nanoClock = nanoClock;
        this.warnings = message -> LOG.warning(message);
        if (startReaper) {
            long periodSeconds = Math.max(1, Math.min(60, config.poolIdleTtlSeconds() / 4));
            reaper = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "jsql-pool-reaper");
                t.setDaemon(true);
                return t;
            });
            reaper.scheduleWithFixedDelay(this::reapIdle, periodSeconds, periodSeconds, TimeUnit.SECONDS);
        } else {
            reaper = null;
        }
    }

    /**
     * Runs the work with a connection from the pool of the given account, creating the pool if
     * needed.
     *
     * @throws SQLException                 database error, including a refused login
     * @throws TooManyAccountsException     the pool limit is reached and all pools are in use
     */
    public <T> T withConnection(Credentials credentials, ConnectionWork<T> work) throws SQLException {
        return withConnection(config.defaultConnection(), credentials, work);
    }

    public <T> T withConnection(ConnectionConfig connConfig, Credentials credentials, ConnectionWork<T> work)
            throws SQLException {
        return withConnection(connConfig, credentials, (connection, lease) -> work.apply(connection));
    }

    /** Like {@link #withConnection(ConnectionConfig, Credentials, ConnectionWork)}, with a {@link Lease}. */
    public <T> T withConnection(ConnectionConfig connConfig, Credentials credentials, LeasedWork<T> work)
            throws SQLException {
        if (connConfig == null) {
            connConfig = config.defaultConnection();
        }
        Entry entry = lease(connConfig, credentials);
        try {
            Connection connection;
            try {
                connection = entry.pool.getConnection();
            } catch (SQLTransientConnectionException e) {
                // HikariCP uses this exception both when all connections are busy (no cause) and
                // when it cannot create connections (cause = the driver's error, for example the
                // password was changed). Only in the second case is the pool dropped, so that the
                // next request validates the credentials again.
                if (e.getCause() != null) {
                    retire(entry);
                }
                throw e;
            }
            Object token = new Object();
            entry.activeSince.put(token, nanoClock.getAsLong());
            try (connection) {
                final Connection borrowed = connection;
                return work.apply(borrowed, () -> evictQuietly(entry, borrowed));
            } finally {
                entry.activeSince.remove(token);
            }
        } finally {
            release(entry);
        }
    }

    /** Counters of all open pools (one per connection and account). */
    public List<PoolStatus> status() {
        return status(null);
    }

    /** Counters of the open pools of one named connection; all pools if the name is null. */
    public List<PoolStatus> status(String connectionName) {
        List<Entry> entries;
        synchronized (lock) {
            entries = new ArrayList<>(pools.values());
        }
        long now = nanoClock.getAsLong();
        List<PoolStatus> result = new ArrayList<>();
        for (Entry e : entries) {
            if (connectionName != null && !connectionName.equalsIgnoreCase(e.connectionName)) {
                continue;
            }
            var mx = e.pool.getHikariPoolMXBean();
            if (mx == null) {
                continue;
            }
            long oldest = e.activeSince.values().stream().mapToLong(Long::longValue).min().stream()
                    .map(since -> now - since).findFirst().orElse(0);
            result.add(new PoolStatus(e.connectionName, e.user, mx.getTotalConnections(), mx.getActiveConnections(),
                    mx.getIdleConnections(), mx.getThreadsAwaitingConnection(), config.poolSize(),
                    TimeUnit.NANOSECONDS.toSeconds(oldest)));
        }
        result.sort(Comparator.comparing(PoolStatus::connection).thenComparing(PoolStatus::user));
        return result;
    }

    /**
     * Refreshes the pools of one named connection (all if the name is null).
     *
     * @param force false: idle connections are closed now and busy ones when they are returned.
     *              true: the pools are also dropped, so that the next request opens a new pool; the old
     *              pool is closed as soon as its last borrowed connection is returned (stop the running
     *              operations first, see {@link Operations})
     */
    public void reset(String connectionName, boolean force) {
        List<Entry> matching = new ArrayList<>();
        synchronized (lock) {
            for (Entry e : pools.values()) {
                if (connectionName == null || connectionName.equalsIgnoreCase(e.connectionName)) {
                    matching.add(e);
                }
            }
        }
        for (Entry e : matching) {
            if (!force) {
                e.pool.getHikariPoolMXBean().softEvictConnections();
                continue;
            }
            boolean closeNow;
            synchronized (lock) {
                pools.remove(e.key, e);
                e.retired = true;
                closeNow = e.leases == 0;
            }
            if (closeNow) {
                closeQuietly(e);
            }
            LOG.info("Reset connection pool " + e.pool.getPoolName());
        }
    }

    /** Number of pools (distinct accounts) currently open. */
    public int poolCount() {
        synchronized (lock) {
            return pools.size();
        }
    }

    /** Closes pools that have been idle longer than the configured time. Runs periodically. */
    void reapIdle() {
        List<Entry> toClose = new ArrayList<>();
        long ttlNanos = TimeUnit.SECONDS.toNanos(config.poolIdleTtlSeconds());
        synchronized (lock) {
            long now = nanoClock.getAsLong();
            pools.values().removeIf(e -> {
                boolean expired = e.leases == 0 && now - e.lastUsed > ttlNanos;
                if (expired) {
                    toClose.add(e);
                }
                return expired;
            });
        }
        toClose.forEach(PoolManager::closeQuietly);
        if (!toClose.isEmpty() && LOG.isLoggable(Level.FINE)) {
            LOG.fine("Closed " + toClose.size() + " idle pool(s)");
        }
    }

    @Override
    public void close() {
        List<Entry> toClose;
        synchronized (lock) {
            closed = true;
            toClose = new ArrayList<>(pools.values());
            pools.clear();
        }
        if (reaper != null) {
            reaper.shutdownNow();
        }
        toClose.forEach(PoolManager::closeQuietly);
    }

    // ---------------------------------------------------------------------------------------

    private Entry lease(ConnectionConfig connConfig, Credentials credentials) throws SQLException {
        String key = keyOf(connConfig, credentials);
        Entry existing = leaseExisting(key);
        if (existing != null) {
            return existing;
        }
        // One creation per account at a time; different accounts are created in parallel.
        Object keyLock = creating.computeIfAbsent(key, k -> new Object());
        try {
            synchronized (keyLock) {
                existing = leaseExisting(key);
                if (existing != null) {
                    return existing;
                }
                Entry created = createEntry(key, connConfig, credentials);
                List<Entry> toClose = new ArrayList<>();
                try {
                    synchronized (lock) {
                        if (closed) {
                            throw new IllegalStateException("The server is shutting down.");
                        }
                        makeRoom(toClose);
                        created.leases = 1;
                        created.lastUsed = nanoClock.getAsLong();
                        pools.put(key, created);
                    }
                } catch (RuntimeException e) {
                    closeQuietly(created);
                    throw e;
                } finally {
                    toClose.forEach(PoolManager::closeQuietly);
                }
                return created;
            }
        } finally {
            creating.remove(key, keyLock);
        }
    }

    private Entry leaseExisting(String key) {
        synchronized (lock) {
            Entry e = pools.get(key);
            if (e != null) {
                e.leases++;
                e.lastUsed = nanoClock.getAsLong();
            }
            return e;
        }
    }

    // Called with lock held. Removes least recently used idle pools until there is room for one more.
    private void makeRoom(List<Entry> toClose) {
        while (pools.size() >= config.maxPools()) {
            Entry oldest = null;
            for (Entry e : pools.values()) {
                if (e.leases == 0 && (oldest == null || e.lastUsed < oldest.lastUsed)) {
                    oldest = e;
                }
            }
            if (oldest == null) {
                throw new TooManyAccountsException("Too many different database accounts are in use at the same time ("
                        + config.maxPools() + "). Try again in a moment.");
            }
            pools.remove(oldest.key);
            toClose.add(oldest);
        }
    }

    private void release(Entry entry) {
        boolean closeNow;
        synchronized (lock) {
            entry.leases--;
            entry.lastUsed = nanoClock.getAsLong();
            closeNow = entry.retired && entry.leases == 0;
        }
        if (closeNow) {
            closeQuietly(entry);
        }
    }

    // The entry is closed by the last release.
    private void retire(Entry entry) {
        synchronized (lock) {
            pools.remove(entry.key, entry);
            entry.retired = true;
        }
    }

    private Entry createEntry(String key, ConnectionConfig connConfig, Credentials credentials) throws SQLException {
        ConnectionSettings settings = new ConnectionSettings(connConfig.jdbcUrl(), credentials.user(),
                credentials.password(), connConfig.insecure());

        // Check the credentials with a plain connection: fast, and the exception is the driver's own.
        settings.connect(warnings).close();

        Properties driverProperties = settings.toProperties(warnings);
        HikariConfig hc = new HikariConfig();
        hc.setPoolName("jsql-pool-" + connConfig.name() + "-" + poolSequence.incrementAndGet());
        hc.setJdbcUrl(connConfig.jdbcUrl());
        hc.setUsername(credentials.user());
        hc.setPassword(credentials.password());
        for (String name : driverProperties.stringPropertyNames()) {
            if (!name.equals("user") && !name.equals("password")) {
                hc.addDataSourceProperty(name, driverProperties.getProperty(name));
            }
        }
        hc.setMaximumPoolSize(config.poolSize());
        hc.setMinimumIdle(0);
        hc.setConnectionTimeout(TimeUnit.SECONDS.toMillis(config.connectTimeoutSeconds()));
        hc.setMaxLifetime(TimeUnit.SECONDS.toMillis(config.maxLifetimeSeconds()));
        hc.setIdleTimeout(TimeUnit.SECONDS.toMillis(config.idleTimeoutSeconds()));
        if (config.leakDetectionSeconds() > 0) {
            hc.setLeakDetectionThreshold(TimeUnit.SECONDS.toMillis(config.leakDetectionSeconds()));
        }
        hc.setInitializationFailTimeout(-1); // credentials were checked above
        hc.setAutoCommit(true);

        HikariDataSource pool = new HikariDataSource(hc);
        LOG.info("Opened connection pool " + hc.getPoolName() + " for user " + credentials.user());
        return new Entry(key, connConfig.name(), credentials.user(), pool, nanoClock.getAsLong());
    }

    // Aborts the connection (this also stops a running statement) and takes it out of the pool.
    private static void evictQuietly(Entry entry, Connection connection) {
        try {
            connection.abort(Runnable::run);
        } catch (SQLException | RuntimeException e) {
            LOG.log(Level.FINE, "Abort of connection failed", e);
        }
        try {
            entry.pool.evictConnection(connection);
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "Evict of connection failed", e);
        }
    }

    private static void closeQuietly(Entry entry) {
        try {
            entry.pool.close();
            LOG.fine("Closed connection pool for user " + entry.user);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not close pool for user " + entry.user, e);
        }
    }

    // The key identifies the account without keeping the password in a map key.
    static String keyOf(Credentials credentials) {
        return keyOf(null, credentials);
    }

    static String keyOf(ConnectionConfig connConfig, Credentials credentials) {
        String connPrefix = (connConfig == null ? "default" : connConfig.name()) + ":";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(connPrefix.getBytes(StandardCharsets.UTF_8));
            digest.update(credentials.user().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(credentials.password().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 is always available
        }
    }
}

package jsql.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayList;
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

    /** All pools are in use by other accounts, so no new account can be served right now. */
    public static class TooManyAccountsException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public TooManyAccountsException(String message) {
            super(message);
        }
    }

    private static final class Entry {
        final String key;
        final String user;
        final HikariDataSource pool;
        int leases;
        long lastUsed;
        boolean retired;

        Entry(String key, String user, HikariDataSource pool, long now) {
            this.key = key;
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
        Entry entry = lease(credentials);
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
            try (connection) {
                return work.apply(connection);
            }
        } finally {
            release(entry);
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

    private Entry lease(Credentials credentials) throws SQLException {
        String key = keyOf(credentials);
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
                Entry created = createEntry(key, credentials);
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

    private Entry createEntry(String key, Credentials credentials) throws SQLException {
        ConnectionSettings settings = new ConnectionSettings(config.jdbcUrl(), credentials.user(),
                credentials.password(), config.insecure());

        // Check the credentials with a plain connection: fast, and the exception is the driver's own.
        settings.connect(warnings).close();

        Properties driverProperties = settings.toProperties(warnings);
        HikariConfig hc = new HikariConfig();
        hc.setPoolName("jsql-pool-" + poolSequence.incrementAndGet());
        hc.setJdbcUrl(config.jdbcUrl());
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
        hc.setInitializationFailTimeout(-1); // credentials were checked above
        hc.setAutoCommit(true);

        HikariDataSource pool = new HikariDataSource(hc);
        LOG.info("Opened connection pool " + hc.getPoolName() + " for user " + credentials.user());
        return new Entry(key, credentials.user(), pool, nanoClock.getAsLong());
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
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(credentials.user().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(credentials.password().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 is always available
        }
    }
}

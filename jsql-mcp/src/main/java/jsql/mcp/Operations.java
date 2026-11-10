package jsql.mcp;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import jsql.core.InvalidRequestException;
import jsql.core.QueryTimeoutException;
import jsql.mcp.PoolManager.ConnectionWork;
import jsql.mcp.PoolManager.Lease;

/**
 * The shared execution helper of every tool that touches the database.
 *
 * <p>
 * The work runs on a worker thread while the calling thread waits with a deadline. JDBC metadata
 * calls have no statement timeout, so this is what stops them. When the deadline passes, the work
 * is interrupted and the connection is aborted and thrown away (not returned to the pool), so a
 * stuck call can never keep a pool connection busy. The same happens when an administrator cancels
 * an operation and when the watchdog finds an operation that has run longer than the configured
 * maximum plus a grace period.
 *
 * <p>
 * Every operation is listed in a registry while it runs and in a short history afterwards. Neither
 * holds result data or credentials; SQL text is shortened and the history has only a hash of it.
 */
public final class Operations implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(Operations.class.getName());

    static final int SQL_PREVIEW_LENGTH = 200;
    private static final int HISTORY_SIZE = 200;

    public enum State {
        RUNNING, OK, ERROR, TIMEOUT, CANCELLED;

        public String text() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** The operation was stopped on purpose, by an administrator or the watchdog. */
    public static class CancelledException extends InvalidRequestException {
        private static final long serialVersionUID = 1L;

        public CancelledException(String message) {
            super(message);
        }
    }

    /**
     * What is about to run.
     *
     * @param deadlineMillis 0 for no deadline
     */
    public record Spec(String tool, String connection, String sql, long deadlineMillis) {
    }

    /** A running operation, as listed by {@code list_running_queries}. */
    public record Running(String queryId, String connection, String tool, String sql, long elapsedSeconds,
            String state) {
    }

    /** A finished operation. No result data, no credentials, no SQL text. */
    public record HistoryEntry(Instant time, String connection, String tool, String sqlHash, String state,
            long elapsedMs, Integer rowCount, String errorType) {
    }

    private static final class Op {
        final String id;
        final Spec spec;
        final long startNanos;
        final Instant startTime = Instant.now();
        volatile Future<?> future;
        volatile Lease lease;
        volatile State state = State.RUNNING;
        volatile String stopReason;
        volatile Integer rowCount;

        Op(String id, Spec spec, long startNanos) {
            this.id = id;
            this.spec = spec;
            this.startNanos = startNanos;
        }
    }

    private static final ThreadLocal<Op> CURRENT = new ThreadLocal<>();

    private final LongSupplier nanoClock;
    private final long watchdogLimitSeconds; // 0 = the watchdog does not stop anything
    private final ConcurrentHashMap<String, Op> running = new ConcurrentHashMap<>();
    private final Deque<HistoryEntry> history = new ArrayDeque<>(); // guarded by itself
    private final AtomicLong sequence = new AtomicLong();
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "jsql-op-worker");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService watchdog;

    public Operations(McpConfig config) {
        this(config, System::nanoTime, true);
    }

    Operations(McpConfig config, LongSupplier nanoClock, boolean startWatchdog) {
        this.nanoClock = nanoClock;
        int max = config.limits().maxQueryTimeoutSeconds();
        this.watchdogLimitSeconds = max > 0 ? max + (long) config.watchdogGraceSeconds() : 0;
        if (startWatchdog && watchdogLimitSeconds > 0) {
            watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "jsql-watchdog");
                t.setDaemon(true);
                return t;
            });
            long period = config.watchdogIntervalSeconds();
            watchdog.scheduleWithFixedDelay(this::sweep, period, period, TimeUnit.SECONDS);
        } else {
            watchdog = null;
        }
    }

    /**
     * Runs the work on a worker thread and waits for it at most {@code spec.deadlineMillis()}.
     *
     * @throws QueryTimeoutException the deadline passed; the connection was thrown away
     * @throws CancelledException    an administrator or the watchdog stopped the operation
     */
    public <T> T execute(Spec spec, Connection connection, Lease lease, ConnectionWork<T> work) throws SQLException {
        Op op = new Op("q" + sequence.incrementAndGet(), spec, nanoClock.getAsLong());
        op.lease = lease;
        running.put(op.id, op);
        Future<T> future = workers.submit(() -> {
            CURRENT.set(op);
            try {
                return work.apply(connection);
            } finally {
                CURRENT.remove();
            }
        });
        op.future = future;
        State end = State.OK;
        String errorType = null;
        try {
            return spec.deadlineMillis() > 0 ? future.get(spec.deadlineMillis(), TimeUnit.MILLISECONDS) : future.get();
        } catch (TimeoutException e) {
            stop(op, State.TIMEOUT, "deadline");
            end = State.TIMEOUT;
            errorType = "timeout";
            throw new QueryTimeoutException("Operation '" + spec.tool() + "' timed out after "
                    + TimeUnit.MILLISECONDS.toSeconds(Math.max(1000, spec.deadlineMillis()))
                    + "s (error_type: timeout). The database connection was closed.");
        } catch (CancellationException e) {
            end = State.CANCELLED;
            errorType = "cancelled";
            throw new CancelledException("Operation '" + spec.tool() + "' was cancelled"
                    + (op.stopReason == null ? "" : " (" + op.stopReason + ")") + " (error_type: cancelled).");
        } catch (InterruptedException e) {
            stop(op, State.CANCELLED, "the request was interrupted");
            end = State.CANCELLED;
            errorType = "cancelled";
            Thread.currentThread().interrupt();
            throw new CancelledException("Operation '" + spec.tool() + "' was interrupted (error_type: cancelled).");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (op.state == State.CANCELLED || op.state == State.TIMEOUT) {
                // the connection was aborted underneath the work: the failure is a result of that
                end = op.state;
                errorType = op.state.text();
                throw new CancelledException("Operation '" + spec.tool() + "' was stopped (" + op.stopReason
                        + ") (error_type: " + errorType + ").");
            }
            end = State.ERROR;
            errorType = cause instanceof QueryTimeoutException ? "timeout" : "error";
            if (cause instanceof QueryTimeoutException) {
                end = State.TIMEOUT;
            }
            if (cause instanceof SQLException sql) {
                throw sql;
            }
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException(cause);
        } finally {
            running.remove(op.id);
            record(op, end, errorType);
        }
    }

    /** Records how many rows the operation on the current thread returned (for the history). */
    public static void recordRows(int rows) {
        Op op = CURRENT.get();
        if (op != null) {
            op.rowCount = rows;
        }
    }

    /** The operations running now, oldest first. */
    public List<Running> running() {
        long now = nanoClock.getAsLong();
        List<Running> result = new ArrayList<>();
        for (Op op : running.values()) {
            result.add(new Running(op.id, op.spec.connection(), op.spec.tool(), preview(op.spec.sql()),
                    TimeUnit.NANOSECONDS.toSeconds(now - op.startNanos), op.state.text()));
        }
        result.sort(Comparator.comparing(Running::elapsedSeconds).reversed().thenComparing(Running::queryId));
        return result;
    }

    /**
     * Stops one running operation: interrupts the work, aborts the statement and closes the
     * connection.
     *
     * @return false if no operation with this id is running
     */
    public boolean cancel(String queryId, String reason) {
        Op op = queryId == null ? null : running.get(queryId.trim());
        if (op == null) {
            return false;
        }
        stop(op, State.CANCELLED, reason);
        return true;
    }

    /** Stops all running operations of a connection (all if the name is null). Returns how many. */
    public int cancelAll(String connectionName, String reason) {
        int count = 0;
        for (Op op : new ArrayList<>(running.values())) {
            if (connectionName == null || connectionName.equalsIgnoreCase(op.spec.connection())) {
                stop(op, State.CANCELLED, reason);
                count++;
            }
        }
        return count;
    }

    /** The last finished operations, newest first. */
    public List<HistoryEntry> history(int limit) {
        synchronized (history) {
            List<HistoryEntry> all = new ArrayList<>(history);
            Collections.reverse(all);
            return all.size() > limit ? new ArrayList<>(all.subList(0, limit)) : all;
        }
    }

    /**
     * Stops operations that have run longer than the maximum timeout plus the grace period. Runs
     * periodically; public for tests.
     */
    void sweep() {
        if (watchdogLimitSeconds <= 0) {
            return;
        }
        long now = nanoClock.getAsLong();
        for (Op op : new ArrayList<>(running.values())) {
            long elapsed = TimeUnit.NANOSECONDS.toSeconds(now - op.startNanos);
            if (op.state == State.RUNNING && elapsed > watchdogLimitSeconds) {
                LOG.warning("Watchdog stops tool " + op.spec.tool() + " (connection " + op.spec.connection()
                        + ", sqlHash " + (op.spec.sql() == null ? "none" : AuditLog.sqlHash(op.spec.sql()))
                        + ") after " + elapsed + "s; the limit is " + watchdogLimitSeconds + "s");
                stop(op, State.CANCELLED, "stopped by the watchdog after " + elapsed + "s");
            }
        }
    }

    @Override
    public void close() {
        if (watchdog != null) {
            watchdog.shutdownNow();
        }
        for (Op op : new ArrayList<>(running.values())) {
            stop(op, State.CANCELLED, "the server is shutting down");
        }
        workers.shutdownNow();
    }

    // ---------------------------------------------------------------------------------------

    // Marks the operation, interrupts the worker and throws the connection away. Once only.
    private void stop(Op op, State state, String reason) {
        synchronized (op) {
            if (op.state != State.RUNNING) {
                return;
            }
            op.state = state;
            op.stopReason = reason;
        }
        Lease lease = op.lease;
        if (lease != null) {
            lease.evict();
        }
        Future<?> future = op.future;
        if (future != null) {
            future.cancel(true);
        }
    }

    private void record(Op op, State end, String errorType) {
        State finalState = op.state != State.RUNNING ? op.state : end;
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(nanoClock.getAsLong() - op.startNanos);
        String hash = op.spec.sql() == null ? null : AuditLog.sqlHash(op.spec.sql());
        HistoryEntry entry = new HistoryEntry(op.startTime, op.spec.connection(), op.spec.tool(), hash,
                finalState.text(), elapsedMs, op.rowCount, errorType);
        synchronized (history) {
            history.addLast(entry);
            while (history.size() > HISTORY_SIZE) {
                history.removeFirst();
            }
        }
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine("Operation " + op.id + " " + op.spec.tool() + " " + finalState.text() + " after " + elapsedMs
                    + "ms");
        }
    }

    static String preview(String sql) {
        if (sql == null) {
            return null;
        }
        String s = sql.strip();
        return s.length() <= SQL_PREVIEW_LENGTH ? s : s.substring(0, SQL_PREVIEW_LENGTH) + "...";
    }
}

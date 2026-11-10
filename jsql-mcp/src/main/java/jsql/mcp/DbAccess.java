package jsql.mcp;

import java.sql.SQLException;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.openliberty.mcp.tools.ToolCallException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jsql.core.InvalidRequestException;
import jsql.core.QueryLimits;
import jsql.core.QueryTimeoutException;
import jsql.mcp.Credentials.CredentialsException;
import jsql.mcp.PoolManager.ConnectionWork;
import jsql.mcp.PoolManager.TooManyAccountsException;

/**
 * The single place where tools get a database connection: resolves the account of the request,
 * borrows a pooled connection, runs the work under a deadline (see {@link Operations}) and turns
 * every failure into a {@link ToolCallException} whose message is safe and useful to show to the
 * AI client. Anything unexpected is logged here and reported with a generic message.
 */
@ApplicationScoped
public class DbAccess {

    private static final Logger LOG = Logger.getLogger(DbAccess.class.getName());

    /** The statement timeout fires first and gives the better message; the deadline is the backstop. */
    static final long DEADLINE_GRACE_MILLIS = 2000;

    @Inject
    ServerState state;

    /**
     * Describes one tool call for the registry, the history and the deadline.
     *
     * @param tool           tool name
     * @param sql            the SQL text, if the tool has one (only a shortened form is kept)
     * @param timeoutSeconds the {@code timeout_seconds} argument of the call, null if not given
     * @param streaming      true if the work legitimately outlives its statement (export): the deadline
     *                       is then the configured maximum timeout, not the per-call timeout
     */
    public record Call(String tool, String sql, Integer timeoutSeconds, boolean streaming) {
        public static Call of(String tool, Integer timeoutSeconds) {
            return new Call(tool, null, timeoutSeconds, false);
        }

        public static Call sql(String tool, String sql, Integer timeoutSeconds) {
            return new Call(tool, sql, timeoutSeconds, false);
        }
    }

    /**
     * @param call          what is being run
     * @param connectionName named connection, null or blank for the default
     * @param authorization value of the request's Authorization header, may be null
     * @param work          what to do with the connection
     */
    public <T> T run(Call call, String connectionName, String authorization, ConnectionWork<T> work) {
        long start = System.nanoTime();
        try {
            McpConfig config = state.config();
            ConnectionConfig connConfig = config.connection(connectionName);
            Credentials credentials = Credentials.resolve(authorization, connConfig.defaultCredentials());
            QueryLimits limits = config.limits();
            int effective = limits.effectiveTimeout(call.timeoutSeconds()); // rejects 0 and negative values
            long deadline = deadlineMillis(call, effective, limits);
            Operations.Spec spec = new Operations.Spec(call.tool(), connConfig.name(), call.sql(), deadline);
            return state.pools().withConnection(connConfig, credentials,
                    (connection, lease) -> state.operations().execute(spec, connection, lease, work));
        } catch (QueryTimeoutException e) {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            LOG.warning("Tool " + call.tool() + " timed out: " + e.getMessage());
            throw new ToolCallException(timeoutMessage(e, elapsedMs));
        } catch (InvalidRequestException | CredentialsException | TooManyAccountsException e) {
            LOG.warning("Tool call rejected: " + e.getMessage());
            throw new ToolCallException(e.getMessage());
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "Database error in tool: " + describe(e), e);
            throw new ToolCallException(describe(e));
        } catch (IllegalStateException e) {
            // thrown by ServerState when the configuration is invalid, or while shutting down
            LOG.log(Level.SEVERE, "Server state error in tool: " + e.getMessage(), e);
            throw new ToolCallException(e.getMessage());
        } catch (ToolCallException e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Unexpected error in tool", e);
            throw new ToolCallException("Unexpected server error. See the server log.");
        }
    }

    /**
     * For checks that a tool does before it touches the database (arguments, files): a request
     * that is wrong is reported with its own message instead of the container's generic
     * "server error".
     */
    public static <T> T guard(Supplier<T> action) {
        try {
            return action.get();
        } catch (ToolCallException e) {
            throw e;
        } catch (InvalidRequestException e) {
            LOG.warning("Tool call rejected: " + e.getMessage());
            throw new ToolCallException(e.getMessage());
        } catch (IllegalStateException e) {
            LOG.log(Level.SEVERE, "Server state error in tool: " + e.getMessage(), e);
            throw new ToolCallException(e.getMessage());
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Unexpected error in tool", e);
            throw new ToolCallException("Unexpected server error. See the server log.");
        }
    }

    private static long deadlineMillis(Call call, int effectiveTimeoutSeconds, QueryLimits limits) {
        if (call.streaming()) {
            int max = limits.maxQueryTimeoutSeconds();
            return max > 0 ? max * 1000L : 0;
        }
        return effectiveTimeoutSeconds > 0 ? effectiveTimeoutSeconds * 1000L + DEADLINE_GRACE_MILLIS : 0;
    }

    /** A structured timeout error: error_type, elapsed_ms and a hint, as JSON text. */
    private String timeoutMessage(QueryTimeoutException e, long elapsedMs) {
        int max = 0;
        try {
            max = state.config().limits().maxQueryTimeoutSeconds();
        } catch (IllegalStateException ignore) {
            // configuration problem is reported elsewhere
        }
        String hint = "Narrow the request (schema, name filter, fewer rows)" + (max > 0
                ? " or raise timeout_seconds (at most " + max + ")" : " or raise timeout_seconds")
                + ". Use pool_status to check that no connection is stuck.";
        return Json.createObjectBuilder().add("error", e.getMessage()).add("error_type", "timeout")
                .add("elapsed_ms", elapsedMs).add("hint", hint).build().toString();
    }

    /** Database error as text for the client: message, SQL state and vendor code. */
    static String describe(SQLException e) {
        StringBuilder sb = new StringBuilder("Database error");
        if (e.getSQLState() != null || e.getErrorCode() != 0) {
            sb.append(" (");
            if (e.getSQLState() != null) {
                sb.append("SQLState ").append(e.getSQLState());
            }
            if (e.getErrorCode() != 0) {
                sb.append(e.getSQLState() != null ? ", " : "").append("code ").append(e.getErrorCode());
            }
            sb.append(')');
        }
        sb.append(": ").append(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        return sb.toString();
    }
}

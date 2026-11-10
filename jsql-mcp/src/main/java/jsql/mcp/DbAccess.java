package jsql.mcp;

import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.openliberty.mcp.tools.ToolCallException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jsql.core.InvalidRequestException;
import jsql.mcp.Credentials.CredentialsException;
import jsql.mcp.PoolManager.ConnectionWork;
import jsql.mcp.PoolManager.TooManyAccountsException;

/**
 * The single place where tools get a database connection: resolves the account of the request,
 * borrows a pooled connection and turns every failure into a {@link ToolCallException} whose
 * message is safe and useful to show to the AI client. Anything unexpected is logged here and
 * reported with a generic message.
 */
@ApplicationScoped
public class DbAccess {

    private static final Logger LOG = Logger.getLogger(DbAccess.class.getName());

    @Inject
    ServerState state;

    /**
     * @param authorization value of the request's Authorization header, may be null
     * @param work          what to do with the connection
     */
    public <T> T run(String authorization, ConnectionWork<T> work) {
        return run(null, authorization, work);
    }

    public <T> T run(String connectionName, String authorization, ConnectionWork<T> work) {
        try {
            McpConfig config = state.config();
            ConnectionConfig connConfig = config.connection(connectionName);
            Credentials credentials = Credentials.resolve(authorization, connConfig.defaultCredentials());
            return state.pools().withConnection(connConfig, credentials, work);
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

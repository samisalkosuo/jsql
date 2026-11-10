package jsql.mcp;

import java.util.logging.Level;
import java.util.logging.Logger;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.servlet.ServletContext;
import jsql.core.QueryService;
import jsql.core.SchemaInspector;

/**
 * Holds the configuration and the connection pools for the whole application. The configuration is
 * read once at startup (see {@link #start}). An invalid configuration does not stop the server:
 * health checks report it and every tool call answers with the problem, which is easier to find
 * in a container platform than a crash loop.
 */
@ApplicationScoped
public class ServerState {

    private static final Logger LOG = Logger.getLogger(ServerState.class.getName());

    private volatile McpConfig config;
    private volatile PoolManager pools;
    private volatile Operations operations;
    private volatile SchemaInspector inspector;
    private volatile QueryService queries;
    private volatile String configProblem;

    /** Forces creation at application start instead of at the first request. */
    void start(@Observes @Initialized(ApplicationScoped.class) ServletContext context) {
        initialize();
    }

    /** Reads the configuration from the environment. Public for tests that need a custom state. */
    synchronized void initialize() {
        if (config != null || configProblem != null) {
            return;
        }
        try {
            McpConfig c = McpConfig.fromEnvironment(System.getenv());
            install(c, new PoolManager(c));
            LOG.info("jsql MCP server configured: " + c);
        } catch (ConfigException e) {
            configProblem = "The server is not configured correctly: " + e.getMessage();
            LOG.severe(configProblem);
        }
    }

    /** Test hook: use the given configuration instead of reading the environment. */
    synchronized void initialize(McpConfig c) {
        install(c, new PoolManager(c));
    }

    private void install(McpConfig c, PoolManager poolManager) {
        inspector = new SchemaInspector(c.limits());
        queries = new QueryService(c.limits(), c.readOnly());
        pools = poolManager;
        operations = new Operations(c);
        config = c; // last: config() is the "ready" signal
    }

    /**
     * @throws IllegalStateException with a message for the client if the configuration is invalid
     */
    McpConfig config() {
        initialize();
        McpConfig c = config;
        if (c == null) {
            throw new IllegalStateException(configProblem);
        }
        return c;
    }

    PoolManager pools() {
        config(); // throws if not configured
        return pools;
    }

    /** Registry, deadlines and history of everything that touches the database. */
    Operations operations() {
        config(); // throws if not configured
        return operations;
    }

    /** Metadata queries, configured with the server limits. */
    SchemaInspector inspector() {
        config(); // throws if not configured
        return inspector;
    }

    /** Row and SQL queries, configured with the server limits and the read-only switch. */
    QueryService queries() {
        config(); // throws if not configured
        return queries;
    }

    /** Null if the configuration is valid. */
    String configProblem() {
        initialize();
        return configProblem;
    }

    @PreDestroy
    void stop() {
        Operations o = operations;
        if (o != null) {
            o.close();
        }
        PoolManager p = pools;
        if (p != null) {
            try {
                p.close();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Error while closing connection pools", e);
            }
        }
    }
}

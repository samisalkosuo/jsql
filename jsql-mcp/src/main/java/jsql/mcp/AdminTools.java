package jsql.mcp;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.mcpjava.server.tools.ToolResponse;

import io.openliberty.mcp.tools.ToolCallException;
import io.openliberty.mcp.tools.ToolManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;
import jakarta.servlet.ServletContext;
import jsql.core.InvalidRequestException;

/**
 * Administrator tools: list and cancel running queries, reset connection pools.
 *
 * <p>
 * The tools are registered at startup only if {@code ADMIN_TOOLS_ENABLED=true}. Otherwise they do
 * not exist: they are not in the tool list and a call to them fails as an unknown tool. (They are
 * registered programmatically because annotated tool methods are always discovered.)
 */
@ApplicationScoped
public class AdminTools {

    private static final Logger LOG = Logger.getLogger(AdminTools.class.getName());

    static final String LIST_RUNNING = "list_running_queries";
    static final String CANCEL_QUERY = "cancel_query";
    static final String RESET_POOL = "reset_pool";

    @Inject
    ServerState state;

    @Inject
    ToolManager toolManager;

    void start(@Observes @Initialized(ApplicationScoped.class) ServletContext context) {
        registerTools();
    }

    /** Registers the tools if the configuration enables them. Returns true if they were registered. */
    boolean registerTools() {
        if (state.configProblem() != null || !state.config().adminToolsEnabled()) {
            return false;
        }
        toolManager.newTool(LIST_RUNNING).setTitle("List running queries")
                .setDescription("Lists the operations that are running now: query_id, connection, tool, "
                        + "shortened SQL, elapsed seconds. Administrator tool.")
                .setHandler(args -> respond(this::listRunningQueries)).register();
        toolManager.newTool(CANCEL_QUERY).setTitle("Cancel a running query")
                .setDescription("Stops one running operation by query_id (from list_running_queries): the work is "
                        + "interrupted and its database connection is closed. Administrator tool.")
                .addArgument("query_id", "The query_id from list_running_queries.", true, String.class)
                .setHandler(args -> respond(() -> cancelQuery(string(args.args(), "query_id")))).register();
        toolManager.newTool(RESET_POOL).setTitle("Reset connection pools")
                .setDescription("Retires the idle connections of the pool(s) of a connection so that they are "
                        + "opened again. With force=true running operations are stopped and all connections are "
                        + "closed. Returns the pool status before and after. Administrator tool.")
                .addArgument("connection", "Named connection. Omit for all connections.", false, String.class)
                .addArgument("force", "Also stop running operations and close busy connections (default false).",
                        false, (Type) Boolean.class)
                .setHandler(args -> respond(() -> resetPool(string(args.args(), "connection"),
                        bool(args.args(), "force"))))
                .register();
        LOG.info("Administrator tools are enabled: " + LIST_RUNNING + ", " + CANCEL_QUERY + ", " + RESET_POOL);
        return true;
    }

    String listRunningQueries() {
        return ToolJson.running(state.operations().running());
    }

    String cancelQuery(String queryId) {
        if (queryId == null || queryId.isBlank()) {
            throw new InvalidRequestException("query_id is required.");
        }
        boolean cancelled = state.operations().cancel(queryId, "cancelled by administrator");
        JsonObjectBuilder b = Json.createObjectBuilder().add("cancelled", cancelled);
        if (!cancelled) {
            b.add("reason", "No running query with this id. It may have finished already.");
        }
        return b.build().toString();
    }

    String resetPool(String connection, boolean force) {
        // throws InvalidRequestException for an unknown connection
        String name = connection == null || connection.isBlank() ? null : state.config().connection(connection).name();
        PoolManager pools = state.pools();
        var before = pools.status(name);
        int stopped = 0;
        if (force) {
            stopped = state.operations().cancelAll(name, "pool reset by administrator");
        }
        pools.reset(name, force);
        JsonObjectBuilder b = Json.createObjectBuilder();
        b.add("force", force);
        b.add("operationsStopped", stopped);
        b.add("before", poolArray(before));
        b.add("after", poolArray(pools.status(name)));
        return b.build().toString();
    }

    private static JsonArrayBuilder poolArray(List<PoolManager.PoolStatus> pools) {
        JsonArrayBuilder a = Json.createArrayBuilder();
        pools.forEach(p -> a.add(ToolJson.pool(p)));
        return a;
    }

    private static ToolResponse respond(java.util.function.Supplier<String> action) {
        try {
            return ToolResponse.ofText(DbAccess.guard(action));
        } catch (ToolCallException e) {
            return ToolResponse.ofError(e.getMessage());
        }
    }

    private static String string(Map<String, Object> args, String name) {
        Object v = args.get(name);
        return v == null ? null : v.toString();
    }

    private static boolean bool(Map<String, Object> args, String name) {
        Object v = args.get(name);
        return v instanceof Boolean b ? b : v != null && Boolean.parseBoolean(v.toString());
    }

}

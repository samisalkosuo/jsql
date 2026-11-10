package jsql.mcp;

import java.sql.DatabaseMetaData;

import org.mcpjava.server.tools.Tool;
import org.mcpjava.server.tools.ToolArg;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.servlet.http.HttpServletRequest;
import jsql.core.QueryLimits;
import jsql.mcp.DbAccess.Call;

/**
 * Tool that tells the client what it is connected to. It also checks the credentials of the
 * request, so it is the first tool to call.
 */
@ApplicationScoped
public class ServerInfoTool {

    @Inject
    DbAccess db;

    @Inject
    ServerState state;

    /** The container's view of the current HTTP request (a proxy; valid inside a tool call). */
    @Inject
    HttpServletRequest request;

    @Tool(name = "server_info", title = "Server and database information", description = "Shows which database this server is connected to "
            + "(product, version, driver), which database account is used for this connection, and the limits and "
            + "mode (read-only or not) in effect. Call this first: it also verifies that the credentials work.", annotations = @Tool.Annotations(readOnlyHint = true))
    public String serverInfo(
            @ToolArg(name = "timeout_seconds", description = "Timeout in seconds. Omit for the server default; larger values are cut to the server maximum.", required = false) Integer timeoutSeconds,
            @ToolArg(name = "connection", description = "Named connection to check. Omit for default.", required = false) String connection) {
        return info(request != null ? request.getHeader("Authorization") : null, connection, timeoutSeconds);
    }

    public String serverInfo() {
        return serverInfo(null, null);
    }

    String info(String authorization) {
        return info(authorization, null);
    }

    String info(String authorization, String connection) {
        return info(authorization, connection, null);
    }

    String info(String authorization, String connection, Integer timeoutSeconds) {
        return db.run(Call.of("server_info", timeoutSeconds), connection, authorization, conn -> {
            DatabaseMetaData md = conn.getMetaData();
            McpConfig config = state.config();
            ConnectionConfig connConfig = config.connection(connection);
            QueryLimits limits = config.limits();

            JsonArrayBuilder connNames = Json.createArrayBuilder();
            config.connectionNames().forEach(connNames::add);

            JsonObjectBuilder server = Json.createObjectBuilder()
                    .add("connectionName", connConfig.name())
                    .add("connections", connNames)
                    .add("jdbcUrl", connConfig.redactedJdbcUrl())
                    .add("databaseProduct", nullToEmpty(md.getDatabaseProductName()))
                    .add("databaseVersion", nullToEmpty(md.getDatabaseProductVersion()))
                    .add("driver", nullToEmpty(md.getDriverName()) + " " + nullToEmpty(md.getDriverVersion()))
                    .add("connectedAs", nullToEmpty(md.getUserName()))
                    .add("accountSource", authorization == null || authorization.isBlank()
                            ? "server default account (no Authorization header sent)"
                            : "Authorization header of this request")
                    .add("readOnlyMode", connConfig.readOnly())
                    .add("maxRows", limits.maxRows())
                    .add("queryTimeoutSeconds", limits.queryTimeoutSeconds())
                    .add("maxQueryTimeoutSeconds", limits.maxQueryTimeoutSeconds())
                    .add("maxValueLength", limits.maxValueLength())
                    .add("exportDir", config.exportDir())
                    .add("adminToolsEnabled", config.adminToolsEnabled())
                    .add("pools", poolSummary());
            JsonObject json = server.build();
            return json.toString();
        });
    }

    private JsonArrayBuilder poolSummary() {
        JsonArrayBuilder pools = Json.createArrayBuilder();
        state.pools().status().forEach(p -> pools.add(ToolJson.pool(p)));
        return pools;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}

package jsql.mcp;

import java.sql.DatabaseMetaData;

import org.mcpjava.server.tools.Tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.servlet.http.HttpServletRequest;
import jsql.core.QueryLimits;

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
    public String serverInfo() {
        return info(request.getHeader("Authorization"));
    }

    String info(String authorization) {
        return db.run(authorization, conn -> {
            DatabaseMetaData md = conn.getMetaData();
            McpConfig config = state.config();
            QueryLimits limits = config.limits();

            JsonObjectBuilder server = Json.createObjectBuilder()
                    .add("jdbcUrl", config.redactedJdbcUrl())
                    .add("databaseProduct", nullToEmpty(md.getDatabaseProductName()))
                    .add("databaseVersion", nullToEmpty(md.getDatabaseProductVersion()))
                    .add("driver", nullToEmpty(md.getDriverName()) + " " + nullToEmpty(md.getDriverVersion()))
                    .add("connectedAs", nullToEmpty(md.getUserName()))
                    .add("accountSource", authorization == null || authorization.isBlank()
                            ? "server default account (no Authorization header sent)"
                            : "Authorization header of this request")
                    .add("readOnlyMode", config.readOnly())
                    .add("maxRows", limits.maxRows())
                    .add("queryTimeoutSeconds", limits.queryTimeoutSeconds())
                    .add("maxValueLength", limits.maxValueLength());
            JsonObject json = server.build();
            return json.toString();
        });
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}

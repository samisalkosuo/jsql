package jsql.mcp;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.mcpjava.server.tools.Tool;
import org.mcpjava.server.tools.ToolArg;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import jakarta.servlet.http.HttpServletRequest;
import jsql.core.InvalidRequestException;
import jsql.core.ListResult;
import jsql.core.NamedSql;
import jsql.core.QueryService.QueryResult;
import jsql.core.SchemaInspector.TableInfo;

/**
 * The generic investigation tools for JDBC databases.
 */
@ApplicationScoped
public class DbTools {

    private static final int DEFAULT_SAMPLE_ROWS = 10;
    private static final int DEFAULT_TOP_VALUES = 10;

    @Inject
    DbAccess db;

    @Inject
    ServerState state;

    @Inject
    AnnotationsManager annotations;

    @Inject
    SavedQueriesManager savedQueries;

    @Inject
    AuditLog audit;

    /** The container's view of the current HTTP request (a proxy; valid inside a tool call). */
    @Inject
    HttpServletRequest request;

    private String auth() {
        return request != null ? request.getHeader("Authorization") : null;
    }

    @Tool(name = "list_connections", title = "List configured connections", description = "Lists all database connections configured on this server.", annotations = @Tool.Annotations(readOnlyHint = true))
    public String listConnections() {
        return ToolJson.connections(state.config().connections());
    }

    @Tool(name = "list_databases", title = "List databases (catalogs)", description = "Lists the catalogs (databases) visible "
            + "on this connection. Use list_schemas next.", annotations = @Tool.Annotations(readOnlyHint = true))
    public String listDatabases(
            @ToolArg(name = "connection", description = "Named connection to use. Omit for default.", required = false) String connection) {
        return listDatabases(auth(), connection);
    }

    String listDatabases(String authorization, String connection) {
        return db.run(connection, authorization, conn -> ToolJson.databases(state.inspector().listCatalogs(conn)));
    }

    @Tool(name = "list_schemas", title = "List schemas", description = "Lists the schemas of the database. This is the "
            + "usual starting point when investigating an unknown database. For databases without schemas "
            + "(for example MySQL) the list is empty; use list_databases and list_tables instead.", annotations = @Tool.Annotations(readOnlyHint = true))
    public String listSchemas(
            @ToolArg(name = "connection", description = "Named connection to use. Omit for default.", required = false) String connection) {
        return listSchemas(auth(), connection);
    }

    String listSchemas(String authorization, String connection) {
        return db.run(connection, authorization, conn -> ToolJson.schemas(state.inspector().listSchemas(conn)));
    }

    @Tool(name = "list_tables", title = "List tables and views", description = "Lists tables and views with their type and "
            + "comment. Filter by schema and by name pattern to keep the answer short. Results are cut at the "
            + "server's row limit and marked truncated.", annotations = @Tool.Annotations(readOnlyHint = true))
    public String listTables(
            @ToolArg(name = "schema", description = "Schema name as returned by list_schemas. Omit for all schemas.", required = false) String schema,
            @ToolArg(name = "name_pattern", description = "Table name pattern: % matches any text, _ one character, for example 'ORDER%'. Omit for all tables.", required = false) String namePattern,
            @ToolArg(name = "types", description = "Comma separated table types, for example 'TABLE,VIEW'. Omit for all types.", required = false) String types,
            @ToolArg(name = "database", description = "Catalog (database) name from list_databases. Usually omitted.", required = false) String database,
            @ToolArg(name = "connection", description = "Named connection to use. Omit for default.", required = false) String connection) {
        return listTables(auth(), connection, database, schema, namePattern, types);
    }

    String listTables(String authorization, String connection, String database, String schema, String namePattern,
            String types) {
        return db.run(connection, authorization, conn -> ToolJson
                .tables(state.inspector().listTables(conn, database, schema, blankToNull(namePattern), split(types))));
    }

    @Tool(name = "find_table", title = "Find tables across connections", description = "Searches for tables by name pattern across all configured connections or in one connection.", annotations = @Tool.Annotations(readOnlyHint = true))
    public String findTable(
            @ToolArg(name = "name_pattern", description = "Table name or pattern (e.g. 'CUST%').") String namePattern,
            @ToolArg(name = "connection", description = "Named connection to limit search to. Omit to search all.", required = false) String connection) {
        return findTable(auth(), connection, namePattern);
    }

    String findTable(String authorization, String connection, String namePattern) {
        List<ToolJson.TableLocation> matches = new ArrayList<>();
        List<ConnectionConfig> targets = connection != null && !connection.isBlank()
                ? List.of(state.config().connection(connection))
                : state.config().connections();

        for (ConnectionConfig cc : targets) {
            try {
                db.run(cc.name(), authorization, conn -> {
                    ListResult<TableInfo> r = state.inspector().listTables(conn, null, null, blankToNull(namePattern),
                            null);
                    for (TableInfo ti : r.items()) {
                        matches.add(new ToolJson.TableLocation(cc.name(), ti.table().catalog(), ti.table().schema(),
                                ti.table().name(), ti.type(), ti.remarks()));
                    }
                    return null;
                });
            } catch (Exception ignore) {
                // if one connection fails during search, continue with others
            }
        }
        return ToolJson.findTable(matches);
    }

    @Tool(name = "describe_table", title = "Describe a table", description = "Shows the columns (type, size, nullable, default, "
            + "comment, and annotations), primary key and indexes of one table or view.", annotations = @Tool.Annotations(readOnlyHint = true))
    public String describeTable(
            @ToolArg(name = "table", description = "Table or view name.") String table,
            @ToolArg(name = "schema", description = "Schema name. Omit if the table name is unique; an ambiguous name returns the candidates.", required = false) String schema,
            @ToolArg(name = "database", description = "Catalog (database) name. Usually omitted.", required = false) String database,
            @ToolArg(name = "connection", description = "Named connection to use. Omit for default.", required = false) String connection) {
        return describeTable(auth(), connection, database, schema, table);
    }

    String describeTable(String authorization, String connection, String database, String schema, String table) {
        String connName = connection != null && !connection.isBlank() ? connection
                : (state.config().defaultConnection() != null ? state.config().defaultConnection().name() : "default");
        AnnotationsManager.TableAnnotations ann = annotations != null
                ? annotations.getAnnotations(connName, database, schema, table)
                : AnnotationsManager.TableAnnotations.EMPTY;
        return db.run(connection, authorization,
                conn -> ToolJson.description(state.inspector().describeTable(conn, database, schema, table), ann));
    }

    @Tool(name = "search_schema", title = "Search table and column names", description = "Finds tables and columns whose name "
            + "matches a case-insensitive regular expression, for example 'customer|cust_id'.", annotations = @Tool.Annotations(readOnlyHint = true))
    public String searchSchema(
            @ToolArg(name = "regex", description = "Case-insensitive regular expression (Java syntax) matched against table and column names.") String regex,
            @ToolArg(name = "schema", description = "Limit the search to this schema. Omit to search all schemas.", required = false) String schema,
            @ToolArg(name = "database", description = "Catalog (database) name. Usually omitted.", required = false) String database,
            @ToolArg(name = "connection", description = "Named connection to use. Omit for default.", required = false) String connection) {
        return searchSchema(auth(), connection, database, schema, regex);
    }

    String searchSchema(String authorization, String connection, String database, String schema, String regex) {
        return db.run(connection, authorization,
                conn -> ToolJson.matches(state.inspector().searchSchema(conn, regex, database, schema)));
    }

    @Tool(name = "get_relationships", title = "Foreign key relationships of a table", description = "Shows the foreign keys of "
            + "a table: 'references' are tables this table points to, 'referencedBy' are tables that point to it.", annotations = @Tool.Annotations(readOnlyHint = true))
    public String getRelationships(
            @ToolArg(name = "table", description = "Table name.") String table,
            @ToolArg(name = "schema", description = "Schema name. Omit if the table name is unique.", required = false) String schema,
            @ToolArg(name = "database", description = "Catalog (database) name. Usually omitted.", required = false) String database,
            @ToolArg(name = "connection", description = "Named connection to use. Omit for default.", required = false) String connection) {
        return getRelationships(auth(), connection, database, schema, table);
    }

    String getRelationships(String authorization, String connection, String database, String schema, String table) {
        return db.run(connection, authorization,
                conn -> ToolJson.relationships(state.inspector().getRelationships(conn, database, schema, table)));
    }

    @Tool(name = "sample_rows", title = "Sample rows of a table", description = "Returns the first rows of a table or view "
            + "(not a random sample). Long text is cut and binary values are shown as sizes.", annotations = @Tool.Annotations(readOnlyHint = true))
    public String sampleRows(
            @ToolArg(name = "table", description = "Table or view name.") String table,
            @ToolArg(name = "schema", description = "Schema name. Omit if the table name is unique.", required = false) String schema,
            @ToolArg(name = "rows", description = "How many rows, default 10, limited by the server's row limit.", required = false) Integer rows,
            @ToolArg(name = "database", description = "Catalog (database) name. Usually omitted.", required = false) String database,
            @ToolArg(name = "connection", description = "Named connection to use. Omit for default.", required = false) String connection) {
        return sampleRows(auth(), connection, database, schema, table, rows);
    }

    String sampleRows(String authorization, String connection, String database, String schema, String table,
            Integer rows) {
        int count = rows == null ? DEFAULT_SAMPLE_ROWS : rows;
        return db.run(connection, authorization, conn -> {
            if (count < 1) {
                throw new InvalidRequestException("rows must be at least 1.");
            }
            return ToolJson.rows(state.queries().sampleRows(conn, database, schema, table, count));
        });
    }

    @Tool(name = "execute_query", title = "Execute SQL", description = "Runs one SQL statement and returns the rows (as arrays "
            + "in column order) or the update count. Supports pagination (offset/limit) and per-call timeout. "
            + "When readOnlyMode is true, only read queries are accepted.")
    public String executeQuery(
            @ToolArg(name = "sql", description = "One SQL statement, without a trailing semicolon.") String sql,
            @ToolArg(name = "offset", description = "Number of rows to skip for pagination (default 0).", required = false) Integer offset,
            @ToolArg(name = "limit", description = "Maximum number of rows to return (capped by server maxRows).", required = false) Integer limit,
            @ToolArg(name = "timeout_seconds", description = "Query timeout in seconds. Omit for default.", required = false) Integer timeoutSeconds,
            @ToolArg(name = "connection", description = "Named connection to use. Omit for default.", required = false) String connection) {
        return executeQuery(auth(), connection, sql, offset, limit, timeoutSeconds);
    }

    String executeQuery(String authorization, String sql) {
        return executeQuery(authorization, null, sql, null, null, null);
    }

    String executeQuery(String authorization, String connection, String sql, Integer offset, Integer limit,
            Integer timeoutSeconds) {
        return db.run(connection, authorization, conn -> {
            QueryResult r = state.queries().execute(conn, sql, offset, limit, timeoutSeconds);
            if (audit != null) {
                audit.log(connection, sql, r.elapsedMs() != null ? r.elapsedMs() : 0,
                        r.updateCount() != null ? r.updateCount() : (r.rows() != null ? r.rows().size() : 0));
            }
            return ToolJson.rows(r);
        });
    }

    @Tool(name = "export_query_to_file", title = "Export query results to file", description = "Executes a SQL query and streams the complete results to a file in the configured export directory. Formats: 'csv' (default), 'json', 'jsonl'.")
    public String exportQueryToFile(
            @ToolArg(name = "sql", description = "Query to execute.") String sql,
            @ToolArg(name = "path", description = "Target file path relative to configured export directory.") String path,
            @ToolArg(name = "format", description = "Output format: 'csv' (default), 'json', or 'jsonl'.", required = false) String format,
            @ToolArg(name = "max_rows", description = "Maximum rows to export. Omit for default limit.", required = false) Integer maxRows,
            @ToolArg(name = "include_header", description = "Include CSV header line (default true).", required = false) Boolean includeHeader,
            @ToolArg(name = "overwrite", description = "Overwrite existing file if true (default false).", required = false) Boolean overwrite,
            @ToolArg(name = "timeout_seconds", description = "Query timeout in seconds. Omit for default.", required = false) Integer timeoutSeconds,
            @ToolArg(name = "connection", description = "Named connection to use. Omit for default.", required = false) String connection) {
        return exportQueryToFile(auth(), connection, sql, path, format, maxRows, includeHeader, overwrite,
                timeoutSeconds);
    }

    String exportQueryToFile(String authorization, String connection, String sql, String path, String format,
            Integer maxRows, Boolean includeHeader, Boolean overwrite, Integer timeoutSeconds) {
        if (path == null || path.isBlank()) {
            throw new InvalidRequestException("Export path is required.");
        }
        McpConfig config = state.config();
        Path exportRoot = Paths.get(config.exportDir()).toAbsolutePath().normalize();
        Path target = exportRoot.resolve(path).normalize();
        if (!target.startsWith(exportRoot) || target.equals(exportRoot)) {
            throw new InvalidRequestException("Export path must be inside the export directory: " + path);
        }
        if (Files.exists(target) && !Boolean.TRUE.equals(overwrite)) {
            throw new InvalidRequestException("File already exists: " + path + ". Set overwrite=true to replace.");
        }
        int effectiveMaxRows = maxRows != null ? Math.min(maxRows, config.maxExportRows()) : config.maxExportRows();
        boolean header = includeHeader == null || includeHeader;

        return db.run(connection, authorization, conn -> {
            try {
                var res = state.queries().exportToFile(conn, sql, target, format, effectiveMaxRows, header,
                        timeoutSeconds);
                if (audit != null) {
                    audit.log(connection, sql, res.elapsedMs(), (int) Math.min(Integer.MAX_VALUE, res.rowsWritten()));
                }
                return ToolJson.export(res);
            } catch (Exception e) {
                if (e instanceof InvalidRequestException ire) {
                    throw ire;
                }
                throw new InvalidRequestException("Export failed: " + e.getMessage(), e);
            }
        });
    }

    @Tool(name = "get_row_count", title = "Get row count of a table", description = "Returns the row count of a table or view.", annotations = @Tool.Annotations(readOnlyHint = true))
    public String getRowCount(
            @ToolArg(name = "table", description = "Table name.") String table,
            @ToolArg(name = "schema", description = "Schema name. Omit if unique.", required = false) String schema,
            @ToolArg(name = "database", description = "Catalog (database) name. Usually omitted.", required = false) String database,
            @ToolArg(name = "timeout_seconds", description = "Timeout in seconds.", required = false) Integer timeoutSeconds,
            @ToolArg(name = "connection", description = "Named connection to use. Omit for default.", required = false) String connection) {
        return getRowCount(auth(), connection, database, schema, table, timeoutSeconds);
    }

    String getRowCount(String authorization, String connection, String database, String schema, String table,
            Integer timeoutSeconds) {
        return db.run(connection, authorization,
                conn -> ToolJson.rowCount(state.queries().getRowCount(conn, database, schema, table, timeoutSeconds)));
    }

    @Tool(name = "list_saved_queries", title = "List saved queries", description = "Lists stored query definitions from the server's saved queries directory.", annotations = @Tool.Annotations(readOnlyHint = true))
    public String listSavedQueries(
            @ToolArg(name = "connection", description = "Filter by connection name. Omit for all.", required = false) String connection) {
        if (savedQueries == null) {
            return ToolJson.savedQueries(Collections.emptyList());
        }
        return ToolJson.savedQueries(savedQueries.listQueries(connection));
    }

    @Tool(name = "get_saved_query", title = "Get saved query definition", description = "Retrieves the full SQL and metadata of a saved query.", annotations = @Tool.Annotations(readOnlyHint = true))
    public String getSavedQuery(@ToolArg(name = "name", description = "Name of the saved query.") String name) {
        if (savedQueries == null) {
            throw new InvalidRequestException("Saved queries are not configured.");
        }
        return ToolJson.savedQuery(savedQueries.getQuery(name));
    }

    @Tool(name = "run_saved_query", title = "Run a saved query", description = "Executes a saved query with named parameters bound safely using prepared statements.")
    public String runSavedQuery(
            @ToolArg(name = "name", description = "Name of the saved query.") String name,
            @ToolArg(name = "parameters", description = "JSON object of parameter values, e.g. '{\"id\": 1, \"status\": \"ACTIVE\"}'.", required = false) String parametersJson,
            @ToolArg(name = "timeout_seconds", description = "Timeout in seconds.", required = false) Integer timeoutSeconds,
            @ToolArg(name = "connection", description = "Override connection. Omit to use query's configured connection or default.", required = false) String connection) {
        return runSavedQuery(auth(), connection, name, parametersJson, timeoutSeconds);
    }

    String runSavedQuery(String authorization, String connection, String name, String parametersJson,
            Integer timeoutSeconds) {
        if (savedQueries == null) {
            throw new InvalidRequestException("Saved queries are not configured.");
        }
        SavedQueriesManager.SavedQuery sq = savedQueries.getQuery(name);
        String targetConn = connection != null && !connection.isBlank() ? connection : sq.connection();

        Map<String, Object> paramMap = parseJsonParams(parametersJson);
        NamedSql ns = NamedSql.parse(sq.sql());
        List<Object> bound = ns.bindValues(paramMap);

        return db.run(targetConn, authorization, conn -> {
            QueryResult r = state.queries().executePrepared(conn, ns.sql(), bound, null, null, timeoutSeconds);
            if (audit != null) {
                audit.log(targetConn, sq.sql(), r.elapsedMs() != null ? r.elapsedMs() : 0,
                        r.updateCount() != null ? r.updateCount() : (r.rows() != null ? r.rows().size() : 0));
            }
            return ToolJson.rows(r);
        });
    }

    @Tool(name = "save_query", title = "Save a query definition", description = "Saves a reusable SQL query with optional named parameter placeholders (:param) to the server's saved queries directory.")
    public String saveQuery(
            @ToolArg(name = "name", description = "Name of the query (alphanumeric, hyphens, underscores).") String name,
            @ToolArg(name = "sql", description = "SQL query statement.") String sql,
            @ToolArg(name = "description", description = "Description of the query.", required = false) String description,
            @ToolArg(name = "connection", description = "Default connection to run against.", required = false) String connection,
            @ToolArg(name = "overwrite", description = "Overwrite if already exists (default false).", required = false) Boolean overwrite) {
        if (savedQueries == null) {
            throw new InvalidRequestException("Saved queries are not configured.");
        }
        savedQueries.saveQuery(name, sql, description, null, connection, Boolean.TRUE.equals(overwrite));
        return "{\"saved\": true, \"name\": \"" + name + "\"}";
    }

    @Tool(name = "profile_column", title = "Profile a column", description = "Summarises one column: row count, null count, "
            + "distinct count, min, max and the most frequent values.", annotations = @Tool.Annotations(readOnlyHint = true))
    public String profileColumn(
            @ToolArg(name = "table", description = "Table or view name.") String table,
            @ToolArg(name = "column", description = "Column name.") String column,
            @ToolArg(name = "schema", description = "Schema name. Omit if the table name is unique.", required = false) String schema,
            @ToolArg(name = "top_values", description = "How many of the most frequent values to return, default 10, 0 for none.", required = false) Integer topValues,
            @ToolArg(name = "database", description = "Catalog (database) name. Usually omitted.", required = false) String database,
            @ToolArg(name = "connection", description = "Named connection to use. Omit for default.", required = false) String connection) {
        return profileColumn(auth(), connection, database, schema, table, column, topValues);
    }

    String profileColumn(String authorization, String connection, String database, String schema, String table,
            String column, Integer topValues) {
        int top = topValues == null ? DEFAULT_TOP_VALUES : topValues;
        return db.run(connection, authorization, conn -> {
            if (top < 0) {
                throw new InvalidRequestException("top_values must not be negative.");
            }
            return ToolJson.profile(state.queries().profileColumn(conn, database, schema, table, column, top));
        });
    }

    @Tool(name = "validate_sql", title = "Check SQL without running it", description = "Asks the database to parse a SQL "
            + "statement without executing it, and reports whether it is valid, the result columns and the number "
            + "of ? parameters.", annotations = @Tool.Annotations(readOnlyHint = true))
    public String validateSql(
            @ToolArg(name = "sql", description = "One SQL statement, without a trailing semicolon.") String sql,
            @ToolArg(name = "connection", description = "Named connection to use. Omit for default.", required = false) String connection) {
        return validateSql(auth(), connection, sql);
    }

    String validateSql(String authorization, String connection, String sql) {
        return db.run(connection, authorization, conn -> ToolJson.validation(state.queries().validateSql(conn, sql)));
    }

    @Tool(name = "explain_query", title = "Execution plan of a query", description = "Shows the database's execution plan for "
            + "one SELECT, WITH, INSERT, UPDATE, DELETE, MERGE or VALUES statement, without running it.", annotations = @Tool.Annotations(readOnlyHint = true))
    public String explainQuery(
            @ToolArg(name = "sql", description = "The statement to explain, without EXPLAIN and without a trailing semicolon.") String sql,
            @ToolArg(name = "connection", description = "Named connection to use. Omit for default.", required = false) String connection) {
        return explainQuery(auth(), connection, sql);
    }

    String explainQuery(String authorization, String connection, String sql) {
        return db.run(connection, authorization, conn -> ToolJson.rows(state.queries().explain(conn, sql)));
    }

    // ---------------------------------------------------------------------------------------

    private static Map<String, Object> parseJsonParams(String jsonText) {
        if (jsonText == null || jsonText.isBlank()) {
            return Collections.emptyMap();
        }
        try (JsonReader reader = Json.createReader(new StringReader(jsonText))) {
            JsonObject obj = reader.readObject();
            Map<String, Object> map = new HashMap<>();
            for (String key : obj.keySet()) {
                JsonValue val = obj.get(key);
                switch (val.getValueType()) {
                case STRING -> map.put(key, obj.getString(key));
                case NUMBER -> map.put(key, obj.getJsonNumber(key).numberValue());
                case TRUE -> map.put(key, Boolean.TRUE);
                case FALSE -> map.put(key, Boolean.FALSE);
                case NULL -> map.put(key, null);
                default -> map.put(key, val.toString());
                }
            }
            return map;
        } catch (Exception e) {
            throw new InvalidRequestException("Invalid JSON for parameters: " + e.getMessage());
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** "TABLE, VIEW" to a list; null if nothing was given. */
    private static List<String> split(String csv) {
        if (csv == null || csv.isBlank()) {
            return null;
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(s -> s.toUpperCase(java.util.Locale.ROOT)).toList();
    }
}

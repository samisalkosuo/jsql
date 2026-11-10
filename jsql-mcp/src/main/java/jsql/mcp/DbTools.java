package jsql.mcp;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.mcpjava.server.tools.Tool;
import org.mcpjava.server.tools.ToolArg;

import io.openliberty.mcp.tools.ToolCallException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import jakarta.servlet.http.HttpServletRequest;
import jsql.core.InvalidRequestException;
import jsql.core.ListResult;
import jsql.core.NamedSql;
import jsql.core.QueryService.QueryResult;
import jsql.core.SchemaInspector.TableInfo;
import jsql.mcp.DbAccess.Call;
import jsql.mcp.PoolManager.ConnectionWork;

/**
 * The generic investigation tools for JDBC databases. Every tool that touches the database goes
 * through {@link DbAccess#run}, which applies the timeout, the cancellation and the cleanup.
 */
@ApplicationScoped
public class DbTools {

    private static final int DEFAULT_SAMPLE_ROWS = 10;
    private static final int DEFAULT_TOP_VALUES = 10;
    private static final int DEFAULT_TABLE_PAGE = 200;
    private static final int DEFAULT_HISTORY = 50;

    private record BatchItem(String sql, Integer timeoutSeconds) {
    }

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

    private <T> T run(String tool, Integer timeoutSeconds, String connection, String authorization,
            ConnectionWork<T> work) {
        return db.run(Call.of(tool, timeoutSeconds), connection, authorization, work);
    }

    @Tool(name = "list_connections", title = "List configured connections", description = "Lists all database connections configured on this server.", annotations = @Tool.Annotations(readOnlyHint = true))
    public String listConnections() {
        return DbAccess.guard(() -> ToolJson.connections(state.config().connections()));
    }

    @Tool(name = "list_databases", title = "List databases (catalogs)", description = "Lists the catalogs (databases) visible "
            + "on this connection. Use list_schemas next.", annotations = @Tool.Annotations(readOnlyHint = true))
    public String listDatabases(
            @ToolArg(name = "connection", description = "Named connection to use. Omit for default.", required = false) String connection,
            @ToolArg(name = "timeout_seconds", description = "Timeout in seconds. Omit for the server default; larger values are cut to the server maximum.", required = false) Integer timeoutSeconds) {
        return listDatabases(auth(), connection, timeoutSeconds);
    }

    String listDatabases(String authorization, String connection, Integer timeoutSeconds) {
        return run("list_databases", timeoutSeconds, connection, authorization,
                conn -> ToolJson.databases(state.inspector().listCatalogs(conn)));
    }

    @Tool(name = "list_schemas", title = "List schemas", description = "Lists the schemas of the database. This is the "
            + "usual starting point when investigating an unknown database. For databases without schemas "
            + "(for example MySQL) the list is empty; use list_databases and list_tables instead.", annotations = @Tool.Annotations(readOnlyHint = true))
    public String listSchemas(
            @ToolArg(name = "connection", description = "Named connection to use. Omit for default.", required = false) String connection,
            @ToolArg(name = "timeout_seconds", description = "Timeout in seconds. Omit for the server default; larger values are cut to the server maximum.", required = false) Integer timeoutSeconds) {
        return listSchemas(auth(), connection, timeoutSeconds);
    }

    String listSchemas(String authorization, String connection, Integer timeoutSeconds) {
        return run("list_schemas", timeoutSeconds, connection, authorization,
                conn -> ToolJson.schemas(state.inspector().listSchemas(conn)));
    }

    @Tool(name = "list_tables", title = "List tables and views", description = "Lists tables and views with their type and "
            + "comment, one page at a time (default 200 per page). Filter by schema and by name pattern to keep the "
            + "answer short. When 'truncated' is true, call again with offset set to 'nextOffset'.", annotations = @Tool.Annotations(readOnlyHint = true))
    public String listTables(
            @ToolArg(name = "schema", description = "Schema name as returned by list_schemas. Omit for all schemas.", required = false) String schema,
            @ToolArg(name = "name_pattern", description = "Table name pattern: % matches any text, _ one character, for example 'ORDER%'. Omit for all tables.", required = false) String namePattern,
            @ToolArg(name = "types", description = "Comma separated table types, for example 'TABLE,VIEW'. Omit for all types.", required = false) String types,
            @ToolArg(name = "database", description = "Catalog (database) name from list_databases. Usually omitted.", required = false) String database,
            @ToolArg(name = "limit", description = "Page size, default 200, limited by the server's row limit.", required = false) Integer limit,
            @ToolArg(name = "offset", description = "Number of tables to skip, default 0. Use 'nextOffset' of the previous answer.", required = false) Integer offset,
            @ToolArg(name = "timeout_seconds", description = "Timeout in seconds. Omit for the server default; larger values are cut to the server maximum.", required = false) Integer timeoutSeconds,
            @ToolArg(name = "connection", description = "Named connection to use. Omit for default.", required = false) String connection) {
        return listTables(auth(), connection, database, schema, namePattern, types, limit, offset, timeoutSeconds);
    }

    String listTables(String authorization, String connection, String database, String schema, String namePattern,
            String types, Integer limit, Integer offset, Integer timeoutSeconds) {
        int off = offset == null ? 0 : offset;
        int page = limit == null ? DEFAULT_TABLE_PAGE : limit;
        return run("list_tables", timeoutSeconds, connection, authorization, conn -> {
            ListResult<TableInfo> r = state.inspector().listTables(conn, database, schema, blankToNull(namePattern),
                    split(types), off, page);
            return ToolJson.tables(r, r.truncated() ? off + r.items().size() : null);
        });
    }

    @Tool(name = "find_table", title = "Find tables across connections", description = "Searches for tables by name pattern across all configured connections or in one connection.", annotations = @Tool.Annotations(readOnlyHint = true))
    public String findTable(
            @ToolArg(name = "name_pattern", description = "Table name or pattern (e.g. 'CUST%').") String namePattern,
            @ToolArg(name = "connection", description = "Named connection to limit search to. Omit to search all.", required = false) String connection,
            @ToolArg(name = "database", description = "Catalog (database) name to limit the search to. Omit for all catalogs.", required = false) String database,
            @ToolArg(name = "timeout_seconds", description = "Timeout in seconds for each connection searched.", required = false) Integer timeoutSeconds) {
        return findTable(auth(), connection, database, namePattern, timeoutSeconds);
    }

    String findTable(String authorization, String connection, String database, String namePattern,
            Integer timeoutSeconds) {
        List<ConnectionConfig> targets = DbAccess.guard(() -> connection != null && !connection.isBlank()
                ? List.of(state.config().connection(connection))
                : state.config().connections());
        List<ToolJson.TableLocation> matches = new ArrayList<>();
        for (ConnectionConfig cc : targets) {
            try {
                run("find_table", timeoutSeconds, cc.name(), authorization, conn -> {
                    ListResult<TableInfo> r = state.inspector().listTables(conn, blankToNull(database), null,
                            blankToNull(namePattern), null);
                    for (TableInfo ti : r.items()) {
                        matches.add(new ToolJson.TableLocation(cc.name(), ti.table().catalog(), ti.table().schema(),
                                ti.table().name(), ti.type(), ti.remarks()));
                    }
                    return null;
                });
            } catch (ToolCallException e) {
                // a connection that fails (wrong credentials, down, timeout) is skipped; the others are searched
                if (targets.size() == 1) {
                    throw e;
                }
            }
        }
        return ToolJson.findTable(matches);
    }

    @Tool(name = "describe_table", title = "Describe a table", description = "Shows the columns (type, size, nullable, default, "
            + "comment, and annotations) and the primary key of one table or view. Indexes are only returned when "
            + "include_indexes is true, because looking them up can be very slow on large tables.", annotations = @Tool.Annotations(readOnlyHint = true))
    public String describeTable(
            @ToolArg(name = "table", description = "Table or view name.") String table,
            @ToolArg(name = "schema", description = "Schema name. Omit if the table name is unique; an ambiguous name returns the candidates.", required = false) String schema,
            @ToolArg(name = "database", description = "Catalog (database) name. Usually omitted.", required = false) String database,
            @ToolArg(name = "columns_only", description = "True: columns and primary key only, never indexes (fastest).", required = false) Boolean columnsOnly,
            @ToolArg(name = "include_indexes", description = "True: also return the indexes (can be slow on large tables). Default depends on the server setting, normally false.", required = false) Boolean includeIndexes,
            @ToolArg(name = "timeout_seconds", description = "Timeout in seconds. Omit for the server default; larger values are cut to the server maximum.", required = false) Integer timeoutSeconds,
            @ToolArg(name = "connection", description = "Named connection to use. Omit for default.", required = false) String connection) {
        return describeTable(auth(), connection, database, schema, table, columnsOnly, includeIndexes, timeoutSeconds);
    }

    String describeTable(String authorization, String connection, String database, String schema, String table,
            Boolean columnsOnly, Boolean includeIndexes, Integer timeoutSeconds) {
        return run("describe_table", timeoutSeconds, connection, authorization, conn -> {
            if (Boolean.TRUE.equals(columnsOnly) && Boolean.TRUE.equals(includeIndexes)) {
                throw new InvalidRequestException("columns_only and include_indexes cannot both be true.");
            }
            boolean indexes;
            if (includeIndexes != null) {
                indexes = includeIndexes;
            } else {
                indexes = !Boolean.TRUE.equals(columnsOnly) && state.config().describeIncludeIndexes();
            }
            String connName = connection != null && !connection.isBlank() ? connection
                    : (state.config().defaultConnection() != null ? state.config().defaultConnection().name()
                            : "default");
            AnnotationsManager.TableAnnotations ann = annotations != null
                    ? annotations.getAnnotations(connName, database, schema, table)
                    : AnnotationsManager.TableAnnotations.EMPTY;
            return ToolJson.description(state.inspector().describeTable(conn, database, schema, table, true, indexes),
                    ann);
        });
    }

    @Tool(name = "search_schema", title = "Search table and column names", description = "Finds tables and columns whose name "
            + "matches a case-insensitive regular expression, for example 'customer|cust_id'.", annotations = @Tool.Annotations(readOnlyHint = true))
    public String searchSchema(
            @ToolArg(name = "regex", description = "Case-insensitive regular expression (Java syntax) matched against table and column names.") String regex,
            @ToolArg(name = "schema", description = "Limit the search to this schema. Omit to search all schemas.", required = false) String schema,
            @ToolArg(name = "database", description = "Catalog (database) name. Usually omitted.", required = false) String database,
            @ToolArg(name = "timeout_seconds", description = "Timeout in seconds. Omit for the server default; larger values are cut to the server maximum.", required = false) Integer timeoutSeconds,
            @ToolArg(name = "connection", description = "Named connection to use. Omit for default.", required = false) String connection) {
        return searchSchema(auth(), connection, database, schema, regex, timeoutSeconds);
    }

    String searchSchema(String authorization, String connection, String database, String schema, String regex,
            Integer timeoutSeconds) {
        return run("search_schema", timeoutSeconds, connection, authorization,
                conn -> ToolJson.matches(state.inspector().searchSchema(conn, regex, database, schema)));
    }

    @Tool(name = "get_relationships", title = "Foreign key relationships of a table", description = "Shows the foreign keys of "
            + "a table: 'references' are tables this table points to, 'referencedBy' are tables that point to it.", annotations = @Tool.Annotations(readOnlyHint = true))
    public String getRelationships(
            @ToolArg(name = "table", description = "Table name.") String table,
            @ToolArg(name = "schema", description = "Schema name. Omit if the table name is unique.", required = false) String schema,
            @ToolArg(name = "database", description = "Catalog (database) name. Usually omitted.", required = false) String database,
            @ToolArg(name = "timeout_seconds", description = "Timeout in seconds. Omit for the server default; larger values are cut to the server maximum.", required = false) Integer timeoutSeconds,
            @ToolArg(name = "connection", description = "Named connection to use. Omit for default.", required = false) String connection) {
        return getRelationships(auth(), connection, database, schema, table, timeoutSeconds);
    }

    String getRelationships(String authorization, String connection, String database, String schema, String table,
            Integer timeoutSeconds) {
        return run("get_relationships", timeoutSeconds, connection, authorization,
                conn -> ToolJson.relationships(state.inspector().getRelationships(conn, database, schema, table)));
    }

    @Tool(name = "sample_rows", title = "Sample rows of a table", description = "Returns the first rows of a table or view "
            + "(not a random sample). Long text is cut and binary values are shown as sizes.", annotations = @Tool.Annotations(readOnlyHint = true))
    public String sampleRows(
            @ToolArg(name = "table", description = "Table or view name.") String table,
            @ToolArg(name = "schema", description = "Schema name. Omit if the table name is unique.", required = false) String schema,
            @ToolArg(name = "rows", description = "How many rows, default 10, limited by the server's row limit.", required = false) Integer rows,
            @ToolArg(name = "database", description = "Catalog (database) name. Usually omitted.", required = false) String database,
            @ToolArg(name = "timeout_seconds", description = "Timeout in seconds. Omit for the server default; larger values are cut to the server maximum.", required = false) Integer timeoutSeconds,
            @ToolArg(name = "connection", description = "Named connection to use. Omit for default.", required = false) String connection) {
        return sampleRows(auth(), connection, database, schema, table, rows, timeoutSeconds);
    }

    String sampleRows(String authorization, String connection, String database, String schema, String table,
            Integer rows, Integer timeoutSeconds) {
        int count = rows == null ? DEFAULT_SAMPLE_ROWS : rows;
        return run("sample_rows", timeoutSeconds, connection, authorization, conn -> {
            if (count < 1) {
                throw new InvalidRequestException("rows must be at least 1.");
            }
            QueryResult r = state.queries().sampleRows(conn, database, schema, table, count, timeoutSeconds);
            Operations.recordRows(r.rows().size());
            return ToolJson.rows(r, effectiveTimeout(timeoutSeconds));
        });
    }

    @Tool(name = "execute_query", title = "Execute SQL", description = "Runs one SQL statement and returns the rows (as arrays "
            + "in column order) or the update count. Supports pagination (offset/limit) and per-call timeout. "
            + "When readOnlyMode is true, only read queries are accepted.")
    public String executeQuery(
            @ToolArg(name = "sql", description = "One SQL statement, without a trailing semicolon.") String sql,
            @ToolArg(name = "offset", description = "Number of rows to skip for pagination (default 0).", required = false) Integer offset,
            @ToolArg(name = "limit", description = "Maximum number of rows to return (capped by server maxRows).", required = false) Integer limit,
            @ToolArg(name = "timeout_seconds", description = "Query timeout in seconds. Omit for default; larger values are cut to the server maximum.", required = false) Integer timeoutSeconds,
            @ToolArg(name = "connection", description = "Named connection to use. Omit for default.", required = false) String connection) {
        return executeQuery(auth(), connection, sql, offset, limit, timeoutSeconds);
    }

    String executeQuery(String authorization, String sql) {
        return executeQuery(authorization, null, sql, null, null, null);
    }

    String executeQuery(String authorization, String connection, String sql, Integer offset, Integer limit,
            Integer timeoutSeconds) {
        return db.run(Call.sql("execute_query", sql, timeoutSeconds), connection, authorization, conn -> {
            QueryResult r = state.queries().execute(conn, sql, offset, limit, timeoutSeconds);
            Operations.recordRows(rowCountOf(r));
            if (audit != null) {
                audit.log(connection, sql, r.elapsedMs() != null ? r.elapsedMs() : 0, rowCountOf(r));
            }
            return ToolJson.rows(r, effectiveTimeout(timeoutSeconds));
        });
    }

    @Tool(name = "execute_batch", title = "Execute several queries", description = "Runs up to the server's batch limit (default 10) "
            + "independent SQL statements one after another, each with its own timeout and row limit, and returns one "
            + "result or error per statement. A failing statement does not stop the others. Read-only mode applies "
            + "as for execute_query.")
    public String executeBatch(
            @ToolArg(name = "queries", description = "JSON array of statements. Each item is a SQL string, or an object like {\"sql\": \"select 1\", \"timeout_seconds\": 10}.") String queries,
            @ToolArg(name = "timeout_seconds", description = "Default timeout in seconds for every statement of the batch.", required = false) Integer timeoutSeconds,
            @ToolArg(name = "connection", description = "Named connection to use. Omit for default.", required = false) String connection) {
        return executeBatch(auth(), connection, queries, timeoutSeconds);
    }

    String executeBatch(String authorization, String connection, String queriesJson, Integer defaultTimeout) {
        List<BatchItem> items = DbAccess.guard(() -> parseBatch(queriesJson, defaultTimeout));
        JsonArrayBuilder results = Json.createArrayBuilder();
        int index = 0;
        for (BatchItem item : items) {
            JsonObjectBuilder entry = Json.createObjectBuilder().add("index", index++);
            try {
                String result = db.run(Call.sql("execute_batch", item.sql(), item.timeoutSeconds()), connection,
                        authorization, conn -> {
                            QueryResult r = state.queries().execute(conn, item.sql(), null, null,
                                    item.timeoutSeconds());
                            Operations.recordRows(rowCountOf(r));
                            if (audit != null) {
                                audit.log(connection, item.sql(), r.elapsedMs() != null ? r.elapsedMs() : 0,
                                        rowCountOf(r));
                            }
                            return ToolJson.rows(r, effectiveTimeout(item.timeoutSeconds()));
                        });
                entry.add("ok", true).add("result", parseObject(result));
            } catch (ToolCallException e) {
                entry.add("ok", false).add("error", String.valueOf(e.getMessage()));
            }
            results.add(entry);
        }
        return Json.createObjectBuilder().add("count", items.size()).add("results", results).build().toString();
    }

    @Tool(name = "export_query_to_file", title = "Export query results to file", description = "Executes a SQL query and streams the complete results to a file in the configured export directory. Formats: 'csv' (default), 'json', 'jsonl'. "
            + "Use list_exports and read_export to get the file when the export directory is not visible to you.")
    public String exportQueryToFile(
            @ToolArg(name = "sql", description = "Query to execute.") String sql,
            @ToolArg(name = "path", description = "Target file path relative to configured export directory.") String path,
            @ToolArg(name = "format", description = "Output format: 'csv' (default), 'json', or 'jsonl'.", required = false) String format,
            @ToolArg(name = "max_rows", description = "Maximum rows to export. Omit for default limit.", required = false) Integer maxRows,
            @ToolArg(name = "include_header", description = "Include CSV header line (default true).", required = false) Boolean includeHeader,
            @ToolArg(name = "overwrite", description = "Overwrite existing file if true (default false).", required = false) Boolean overwrite,
            @ToolArg(name = "timeout_seconds", description = "Query timeout in seconds. Omit for default; larger values are cut to the server maximum.", required = false) Integer timeoutSeconds,
            @ToolArg(name = "connection", description = "Named connection to use. Omit for default.", required = false) String connection) {
        return exportQueryToFile(auth(), connection, sql, path, format, maxRows, includeHeader, overwrite,
                timeoutSeconds);
    }

    String exportQueryToFile(String authorization, String connection, String sql, String path, String format,
            Integer maxRows, Boolean includeHeader, Boolean overwrite, Integer timeoutSeconds) {
        McpConfig config = DbAccess.guard(state::config);
        Path target = DbAccess.guard(() -> {
            if (path == null || path.isBlank()) {
                throw new InvalidRequestException("Export path is required.");
            }
            Path resolved = ExportFiles.resolve(config.exportDir(), path);
            if (resolved.equals(ExportFiles.root(config.exportDir()))) {
                throw new InvalidRequestException(ExportFiles.OUTSIDE_MESSAGE + ": " + path);
            }
            if (Files.exists(resolved) && !Boolean.TRUE.equals(overwrite)) {
                throw new InvalidRequestException("File already exists: " + path + ". Set overwrite=true to replace.");
            }
            return resolved;
        });
        int effectiveMaxRows = maxRows != null ? Math.min(maxRows, config.maxExportRows()) : config.maxExportRows();
        boolean header = includeHeader == null || includeHeader;

        return db.run(new Call("export_query_to_file", sql, timeoutSeconds, true), connection, authorization,
                conn -> {
                    try {
                        var res = state.queries().exportToFile(conn, sql, target, format, effectiveMaxRows, header,
                                timeoutSeconds);
                        Operations.recordRows((int) Math.min(Integer.MAX_VALUE, res.rowsWritten()));
                        if (audit != null) {
                            audit.log(connection, sql, res.elapsedMs(),
                                    (int) Math.min(Integer.MAX_VALUE, res.rowsWritten()));
                        }
                        return ToolJson.export(res);
                    } catch (IOException e) {
                        throw new InvalidRequestException("Export failed: " + e.getMessage(), e);
                    }
                });
    }

    @Tool(name = "list_exports", title = "List exported files", description = "Lists the files in the export directory "
            + "(or in a sub directory of it) with size and modification time.", annotations = @Tool.Annotations(readOnlyHint = true))
    public String listExports(
            @ToolArg(name = "path", description = "Sub directory of the export directory. Omit for the whole directory.", required = false) String path) {
        return DbAccess.guard(() -> ToolJson.exports(ExportFiles.list(state.config().exportDir(), path)));
    }

    @Tool(name = "read_export", title = "Read an exported file", description = "Returns a text chunk of an exported file "
            + "(at most 256 KB per call) with 'next_offset' and 'eof'. Call again with offset set to next_offset until eof is true.", annotations = @Tool.Annotations(readOnlyHint = true))
    public String readExport(
            @ToolArg(name = "path", description = "File path relative to the export directory.") String path,
            @ToolArg(name = "offset", description = "Byte offset to start at, default 0. Use 'next_offset' of the previous answer.", required = false) Long offset,
            @ToolArg(name = "limit_bytes", description = "Bytes to read, default and maximum 262144.", required = false) Integer limitBytes) {
        return DbAccess.guard(
                () -> ToolJson.exportChunk(ExportFiles.read(state.config().exportDir(), path, offset, limitBytes)));
    }

    @Tool(name = "delete_export", title = "Delete an exported file", description = "Deletes one file in the export directory.", annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true))
    public String deleteExport(
            @ToolArg(name = "path", description = "File path relative to the export directory.") String path) {
        return DbAccess.guard(() -> {
            boolean deleted = ExportFiles.delete(state.config().exportDir(), path);
            return Json.createObjectBuilder().add("deleted", deleted).build().toString();
        });
    }

    @Tool(name = "get_row_count", title = "Get row count of a table", description = "Returns the row count of a table or view.", annotations = @Tool.Annotations(readOnlyHint = true))
    public String getRowCount(
            @ToolArg(name = "table", description = "Table name.") String table,
            @ToolArg(name = "schema", description = "Schema name. Omit if unique.", required = false) String schema,
            @ToolArg(name = "database", description = "Catalog (database) name. Usually omitted.", required = false) String database,
            @ToolArg(name = "timeout_seconds", description = "Timeout in seconds. Omit for the server default; larger values are cut to the server maximum.", required = false) Integer timeoutSeconds,
            @ToolArg(name = "connection", description = "Named connection to use. Omit for default.", required = false) String connection) {
        return getRowCount(auth(), connection, database, schema, table, timeoutSeconds);
    }

    String getRowCount(String authorization, String connection, String database, String schema, String table,
            Integer timeoutSeconds) {
        return run("get_row_count", timeoutSeconds, connection, authorization,
                conn -> ToolJson.rowCount(state.queries().getRowCount(conn, database, schema, table, timeoutSeconds)));
    }

    @Tool(name = "get_table_stats", title = "Table statistics", description = "Row count and per-column distinct counts, null "
            + "fractions and min/max from the database's own statistics, without scanning the table. Only some "
            + "databases support this (Presto/Trino). Otherwise 'supported' is false: use get_row_count and profile_column.", annotations = @Tool.Annotations(readOnlyHint = true))
    public String getTableStats(
            @ToolArg(name = "table", description = "Table name.") String table,
            @ToolArg(name = "schema", description = "Schema name. Omit if unique.", required = false) String schema,
            @ToolArg(name = "database", description = "Catalog (database) name. Usually omitted.", required = false) String database,
            @ToolArg(name = "timeout_seconds", description = "Timeout in seconds. Omit for the server default; larger values are cut to the server maximum.", required = false) Integer timeoutSeconds,
            @ToolArg(name = "connection", description = "Named connection to use. Omit for default.", required = false) String connection) {
        return getTableStats(auth(), connection, database, schema, table, timeoutSeconds);
    }

    String getTableStats(String authorization, String connection, String database, String schema, String table,
            Integer timeoutSeconds) {
        return run("get_table_stats", timeoutSeconds, connection, authorization,
                conn -> ToolJson.tableStats(state.queries().tableStats(conn, database, schema, table, timeoutSeconds)));
    }

    @Tool(name = "list_saved_queries", title = "List saved queries", description = "Lists stored query definitions from the server's saved queries directory.", annotations = @Tool.Annotations(readOnlyHint = true))
    public String listSavedQueries(
            @ToolArg(name = "connection", description = "Filter by connection name. Omit for all.", required = false) String connection) {
        return DbAccess.guard(() -> {
            if (savedQueries == null) {
                return ToolJson.savedQueries(Collections.emptyList());
            }
            return ToolJson.savedQueries(savedQueries.listQueries(connection));
        });
    }

    @Tool(name = "get_saved_query", title = "Get saved query definition", description = "Retrieves the full SQL and metadata of a saved query.", annotations = @Tool.Annotations(readOnlyHint = true))
    public String getSavedQuery(@ToolArg(name = "name", description = "Name of the saved query.") String name) {
        return DbAccess.guard(() -> {
            if (savedQueries == null) {
                throw new InvalidRequestException("Saved queries are not configured.");
            }
            return ToolJson.savedQuery(savedQueries.getQuery(name));
        });
    }

    @Tool(name = "run_saved_query", title = "Run a saved query", description = "Executes a saved query with named parameters bound safely using prepared statements.")
    public String runSavedQuery(
            @ToolArg(name = "name", description = "Name of the saved query.") String name,
            @ToolArg(name = "parameters", description = "JSON object of parameter values, e.g. '{\"id\": 1, \"status\": \"ACTIVE\"}'.", required = false) String parametersJson,
            @ToolArg(name = "timeout_seconds", description = "Timeout in seconds. Omit for default; larger values are cut to the server maximum.", required = false) Integer timeoutSeconds,
            @ToolArg(name = "connection", description = "Override connection. Omit to use query's configured connection or default.", required = false) String connection) {
        return runSavedQuery(auth(), connection, name, parametersJson, timeoutSeconds);
    }

    String runSavedQuery(String authorization, String connection, String name, String parametersJson,
            Integer timeoutSeconds) {
        record Prepared(SavedQueriesManager.SavedQuery query, String targetConn, NamedSql sql, List<Object> bound) {
        }
        Prepared p = DbAccess.guard(() -> {
            if (savedQueries == null) {
                throw new InvalidRequestException("Saved queries are not configured.");
            }
            SavedQueriesManager.SavedQuery sq = savedQueries.getQuery(name);
            String targetConn = connection != null && !connection.isBlank() ? connection : sq.connection();
            Map<String, Object> paramMap = parseJsonParams(parametersJson);
            NamedSql ns = NamedSql.parse(sq.sql());
            return new Prepared(sq, targetConn, ns, ns.bindValues(paramMap));
        });
        return db.run(Call.sql("run_saved_query", p.query().sql(), timeoutSeconds), p.targetConn(), authorization,
                conn -> {
                    QueryResult r = state.queries().executePrepared(conn, p.sql().sql(), p.bound(), null, null,
                            timeoutSeconds);
                    Operations.recordRows(rowCountOf(r));
                    if (audit != null) {
                        audit.log(p.targetConn(), p.query().sql(), r.elapsedMs() != null ? r.elapsedMs() : 0,
                                rowCountOf(r));
                    }
                    return ToolJson.rows(r, effectiveTimeout(timeoutSeconds));
                });
    }

    @Tool(name = "save_query", title = "Save a query definition", description = "Saves a reusable SQL query with optional named parameter placeholders (:param) to the server's saved queries directory.")
    public String saveQuery(
            @ToolArg(name = "name", description = "Name of the query (alphanumeric, hyphens, underscores).") String name,
            @ToolArg(name = "sql", description = "SQL query statement.") String sql,
            @ToolArg(name = "description", description = "Description of the query.", required = false) String description,
            @ToolArg(name = "connection", description = "Default connection to run against.", required = false) String connection,
            @ToolArg(name = "overwrite", description = "Overwrite if already exists (default false).", required = false) Boolean overwrite) {
        return DbAccess.guard(() -> {
            if (savedQueries == null) {
                throw new InvalidRequestException("Saved queries are not configured.");
            }
            savedQueries.saveQuery(name, sql, description, null, connection, Boolean.TRUE.equals(overwrite));
            return Json.createObjectBuilder().add("saved", true).add("name", name).build().toString();
        });
    }

    @Tool(name = "delete_saved_query", title = "Delete a saved query", description = "Deletes one saved query definition by name.", annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true))
    public String deleteSavedQuery(
            @ToolArg(name = "name", description = "Name of the saved query (alphanumeric, hyphens, underscores).") String name) {
        return DbAccess.guard(() -> {
            if (savedQueries == null) {
                throw new InvalidRequestException("Saved queries are not configured.");
            }
            boolean deleted = savedQueries.deleteQuery(name);
            return Json.createObjectBuilder().add("deleted", deleted).add("name", name).build().toString();
        });
    }

    @Tool(name = "profile_column", title = "Profile a column", description = "Summarises one column: row count, null count, "
            + "distinct count, min, max and the most frequent values.", annotations = @Tool.Annotations(readOnlyHint = true))
    public String profileColumn(
            @ToolArg(name = "table", description = "Table or view name.") String table,
            @ToolArg(name = "column", description = "Column name.") String column,
            @ToolArg(name = "schema", description = "Schema name. Omit if the table name is unique.", required = false) String schema,
            @ToolArg(name = "top_values", description = "How many of the most frequent values to return, default 10, 0 for none.", required = false) Integer topValues,
            @ToolArg(name = "database", description = "Catalog (database) name. Usually omitted.", required = false) String database,
            @ToolArg(name = "timeout_seconds", description = "Timeout in seconds. Omit for the server default; larger values are cut to the server maximum.", required = false) Integer timeoutSeconds,
            @ToolArg(name = "connection", description = "Named connection to use. Omit for default.", required = false) String connection) {
        return profileColumn(auth(), connection, database, schema, table, column, topValues, timeoutSeconds);
    }

    String profileColumn(String authorization, String connection, String database, String schema, String table,
            String column, Integer topValues, Integer timeoutSeconds) {
        int top = topValues == null ? DEFAULT_TOP_VALUES : topValues;
        return run("profile_column", timeoutSeconds, connection, authorization, conn -> {
            if (top < 0) {
                throw new InvalidRequestException("top_values must not be negative.");
            }
            return ToolJson.profile(
                    state.queries().profileColumn(conn, database, schema, table, column, top, timeoutSeconds));
        });
    }

    @Tool(name = "validate_sql", title = "Check SQL without running it", description = "Asks the database to parse a SQL "
            + "statement without executing it, and reports whether it is valid, the result columns and the number "
            + "of ? parameters.", annotations = @Tool.Annotations(readOnlyHint = true))
    public String validateSql(
            @ToolArg(name = "sql", description = "One SQL statement, without a trailing semicolon.") String sql,
            @ToolArg(name = "timeout_seconds", description = "Timeout in seconds. Omit for the server default; larger values are cut to the server maximum.", required = false) Integer timeoutSeconds,
            @ToolArg(name = "connection", description = "Named connection to use. Omit for default.", required = false) String connection) {
        return validateSql(auth(), connection, sql, timeoutSeconds);
    }

    String validateSql(String authorization, String connection, String sql, Integer timeoutSeconds) {
        return db.run(Call.sql("validate_sql", sql, timeoutSeconds), connection, authorization,
                conn -> ToolJson.validation(state.queries().validateSql(conn, sql)));
    }

    @Tool(name = "explain_query", title = "Execution plan of a query", description = "Shows the database's execution plan for "
            + "one SELECT, WITH, INSERT, UPDATE, DELETE, MERGE or VALUES statement, without running it.", annotations = @Tool.Annotations(readOnlyHint = true))
    public String explainQuery(
            @ToolArg(name = "sql", description = "The statement to explain, without EXPLAIN and without a trailing semicolon.") String sql,
            @ToolArg(name = "timeout_seconds", description = "Timeout in seconds. Omit for the server default; larger values are cut to the server maximum.", required = false) Integer timeoutSeconds,
            @ToolArg(name = "connection", description = "Named connection to use. Omit for default.", required = false) String connection) {
        return explainQuery(auth(), connection, sql, timeoutSeconds);
    }

    String explainQuery(String authorization, String connection, String sql, Integer timeoutSeconds) {
        return db.run(Call.sql("explain_query", sql, timeoutSeconds), connection, authorization,
                conn -> ToolJson.rows(state.queries().explain(conn, sql, timeoutSeconds),
                        effectiveTimeout(timeoutSeconds)));
    }

    @Tool(name = "pool_status", title = "Connection pool status", description = "Shows, for each open connection pool, "
            + "the total, active, idle and waiting connections, the pool size and how long the oldest active connection "
            + "has been busy. Works even when the pools are exhausted.", annotations = @Tool.Annotations(readOnlyHint = true))
    public String poolStatus() {
        return DbAccess.guard(() -> ToolJson.pools(state.pools().status()));
    }

    @Tool(name = "get_query_history", title = "Recent operations", description = "The last operations of this server "
            + "(default 50, newest first): time, connection, tool, SQL hash, state (ok, error, timeout, cancelled), "
            + "elapsed time, row count and error type. No result data and no credentials.", annotations = @Tool.Annotations(readOnlyHint = true))
    public String getQueryHistory(
            @ToolArg(name = "limit", description = "How many operations, default 50.", required = false) Integer limit) {
        return DbAccess.guard(() -> {
            int n = limit == null ? DEFAULT_HISTORY : limit;
            if (n < 1) {
                throw new InvalidRequestException("limit must be at least 1.");
            }
            return ToolJson.history(state.operations().history(n));
        });
    }

    // ---------------------------------------------------------------------------------------

    private int effectiveTimeout(Integer timeoutSeconds) {
        return state.config().limits().effectiveTimeout(timeoutSeconds);
    }

    private static int rowCountOf(QueryResult r) {
        return r.updateCount() != null ? r.updateCount() : (r.rows() != null ? r.rows().size() : 0);
    }

    private List<BatchItem> parseBatch(String json, Integer defaultTimeout) {
        if (json == null || json.isBlank()) {
            throw new InvalidRequestException("queries is required: a JSON array of SQL statements.");
        }
        JsonArray array;
        try (JsonReader reader = Json.createReader(new StringReader(json))) {
            array = reader.readArray();
        } catch (RuntimeException e) {
            throw new InvalidRequestException(
                    "queries must be a JSON array of SQL strings or objects: " + e.getMessage());
        }
        int max = state.config().maxBatchQueries();
        if (array.isEmpty()) {
            throw new InvalidRequestException("queries is empty.");
        }
        if (array.size() > max) {
            throw new InvalidRequestException("Too many queries: " + array.size() + ", the limit is " + max + ".");
        }
        List<BatchItem> items = new ArrayList<>();
        for (JsonValue v : array) {
            if (v.getValueType() == JsonValue.ValueType.STRING) {
                items.add(new BatchItem(((JsonString) v).getString(), defaultTimeout));
            } else if (v.getValueType() == JsonValue.ValueType.OBJECT) {
                JsonObject o = v.asJsonObject();
                String sql = o.containsKey("sql") && o.get("sql").getValueType() == JsonValue.ValueType.STRING
                        ? o.getString("sql")
                        : null;
                if (sql == null) {
                    throw new InvalidRequestException("Every query object needs a \"sql\" field.");
                }
                Integer t = defaultTimeout;
                if (o.containsKey("timeout_seconds") && !o.isNull("timeout_seconds")) {
                    try {
                        t = o.getInt("timeout_seconds");
                    } catch (RuntimeException e) {
                        throw new InvalidRequestException("timeout_seconds must be a whole number.");
                    }
                }
                items.add(new BatchItem(sql, t));
            } else {
                throw new InvalidRequestException(
                        "Every query must be a SQL string or an object with a \"sql\" field.");
            }
        }
        return items;
    }

    private static JsonObject parseObject(String text) {
        try (JsonReader reader = Json.createReader(new StringReader(text))) {
            return reader.readObject();
        }
    }

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

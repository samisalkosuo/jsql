package jsql.mcp;

import java.util.Arrays;
import java.util.List;

import org.mcpjava.server.tools.Tool;
import org.mcpjava.server.tools.ToolArg;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jsql.core.InvalidRequestException;

/**
 * The essential investigation tools. Each tool is a thin layer: take the account from the
 * Authorization header, borrow a pooled connection, call the core service and format the result
 * as compact JSON. Names are matched as the database reports them; the core retries upper and
 * lower case.
 */
@ApplicationScoped
public class DbTools {

    private static final int DEFAULT_SAMPLE_ROWS = 10;
    private static final int DEFAULT_TOP_VALUES = 10;

    @Inject
    DbAccess db;

    @Inject
    ServerState state;

    /** The container's view of the current HTTP request (a proxy; valid inside a tool call). */
    @Inject
    HttpServletRequest request;

    private String auth() {
        return request.getHeader("Authorization");
    }

    @Tool(name = "list_databases", title = "List databases (catalogs)", description = "Lists the catalogs (databases) visible "
            + "on this connection. This server is connected to one JDBC URL, so this is usually a short list "
            + "and may be empty for databases that have no catalogs. Use list_schemas next.", annotations = @Tool.Annotations(readOnlyHint = true))
    public String listDatabases() {
        return listDatabases(auth());
    }

    String listDatabases(String authorization) {
        return db.run(authorization, conn -> ToolJson.databases(state.inspector().listCatalogs(conn)));
    }

    @Tool(name = "list_schemas", title = "List schemas", description = "Lists the schemas of the database. This is the "
            + "usual starting point when investigating an unknown database. For databases without schemas "
            + "(for example MySQL) the list is empty; use list_databases and list_tables instead.", annotations = @Tool.Annotations(readOnlyHint = true))
    public String listSchemas() {
        return listSchemas(auth());
    }

    String listSchemas(String authorization) {
        return db.run(authorization, conn -> ToolJson.schemas(state.inspector().listSchemas(conn)));
    }

    @Tool(name = "list_tables", title = "List tables and views", description = "Lists tables and views with their type and "
            + "comment. Filter by schema and by name pattern to keep the answer short. Results are cut at the "
            + "server's row limit and marked truncated.", annotations = @Tool.Annotations(readOnlyHint = true))
    public String listTables(
            @ToolArg(name = "schema", description = "Schema name as returned by list_schemas. Omit for all schemas.", required = false) String schema,
            @ToolArg(name = "name_pattern", description = "Table name pattern: % matches any text, _ one character, for example 'ORDER%'. Omit for all tables.", required = false) String namePattern,
            @ToolArg(name = "types", description = "Comma separated table types, for example 'TABLE,VIEW'. Omit for all types.", required = false) String types,
            @ToolArg(name = "database", description = "Catalog (database) name from list_databases. Usually omitted.", required = false) String database) {
        return listTables(auth(), database, schema, namePattern, types);
    }

    String listTables(String authorization, String database, String schema, String namePattern, String types) {
        return db.run(authorization, conn -> ToolJson
                .tables(state.inspector().listTables(conn, database, schema, blankToNull(namePattern), split(types))));
    }

    @Tool(name = "describe_table", title = "Describe a table", description = "Shows the columns (type, size, nullable, default, "
            + "comment), primary key and indexes of one table or view. Use it before writing SQL against the table.", annotations = @Tool.Annotations(readOnlyHint = true))
    public String describeTable(
            @ToolArg(name = "table", description = "Table or view name.") String table,
            @ToolArg(name = "schema", description = "Schema name. Omit if the table name is unique; an ambiguous name returns the candidates.", required = false) String schema,
            @ToolArg(name = "database", description = "Catalog (database) name. Usually omitted.", required = false) String database) {
        return describeTable(auth(), database, schema, table);
    }

    String describeTable(String authorization, String database, String schema, String table) {
        return db.run(authorization,
                conn -> ToolJson.description(state.inspector().describeTable(conn, database, schema, table)));
    }

    @Tool(name = "search_schema", title = "Search table and column names", description = "Finds tables and columns whose name "
            + "matches a case-insensitive regular expression, for example 'customer|cust_id'. Use it to find where "
            + "a concept lives in a database you know nothing about. A hit without 'column' is a table name match.", annotations = @Tool.Annotations(readOnlyHint = true))
    public String searchSchema(
            @ToolArg(name = "regex", description = "Case-insensitive regular expression (Java syntax) matched against table and column names.") String regex,
            @ToolArg(name = "schema", description = "Limit the search to this schema. Omit to search all schemas.", required = false) String schema,
            @ToolArg(name = "database", description = "Catalog (database) name. Usually omitted.", required = false) String database) {
        return searchSchema(auth(), database, schema, regex);
    }

    String searchSchema(String authorization, String database, String schema, String regex) {
        return db.run(authorization,
                conn -> ToolJson.matches(state.inspector().searchSchema(conn, regex, database, schema)));
    }

    @Tool(name = "get_relationships", title = "Foreign key relationships of a table", description = "Shows the foreign keys of "
            + "a table: 'references' are tables this table points to, 'referencedBy' are tables that point to it. "
            + "Use it to work out joins and how the data model fits together.", annotations = @Tool.Annotations(readOnlyHint = true))
    public String getRelationships(
            @ToolArg(name = "table", description = "Table name.") String table,
            @ToolArg(name = "schema", description = "Schema name. Omit if the table name is unique.", required = false) String schema,
            @ToolArg(name = "database", description = "Catalog (database) name. Usually omitted.", required = false) String database) {
        return getRelationships(auth(), database, schema, table);
    }

    String getRelationships(String authorization, String database, String schema, String table) {
        return db.run(authorization,
                conn -> ToolJson.relationships(state.inspector().getRelationships(conn, database, schema, table)));
    }

    @Tool(name = "sample_rows", title = "Sample rows of a table", description = "Returns the first rows of a table or view "
            + "(not a random sample). Long text is cut and binary values are shown as sizes. Use it to see what the "
            + "data looks like.", annotations = @Tool.Annotations(readOnlyHint = true))
    public String sampleRows(
            @ToolArg(name = "table", description = "Table or view name.") String table,
            @ToolArg(name = "schema", description = "Schema name. Omit if the table name is unique.", required = false) String schema,
            @ToolArg(name = "rows", description = "How many rows, default 10, limited by the server's row limit.", required = false) Integer rows,
            @ToolArg(name = "database", description = "Catalog (database) name. Usually omitted.", required = false) String database) {
        return sampleRows(auth(), database, schema, table, rows);
    }

    String sampleRows(String authorization, String database, String schema, String table, Integer rows) {
        int count = rows == null ? DEFAULT_SAMPLE_ROWS : rows;
        return db.run(authorization, conn -> {
            if (count < 1) {
                throw new InvalidRequestException("rows must be at least 1.");
            }
            return ToolJson.rows(state.queries().sampleRows(conn, database, schema, table, count));
        });
    }

    @Tool(name = "execute_query", title = "Execute SQL", description = "Runs one SQL statement and returns the rows (as arrays "
            + "in column order) or the update count. Rows are limited by the server and marked truncated, so add "
            + "your own filtering and ORDER BY. Check server_info for 'readOnlyMode': when true, only reads are "
            + "accepted. Otherwise the database account's permissions decide what is allowed, and statements "
            + "that change data DO change it: do not run them unless the user asked for it.")
    public String executeQuery(
            @ToolArg(name = "sql", description = "One SQL statement, without a trailing semicolon.") String sql) {
        return executeQuery(auth(), sql);
    }

    String executeQuery(String authorization, String sql) {
        return db.run(authorization, conn -> ToolJson.rows(state.queries().execute(conn, sql)));
    }

    @Tool(name = "profile_column", title = "Profile a column", description = "Summarises one column: row count, null count, "
            + "distinct count, min, max and the most frequent values. Use it to learn what a column holds (codes, "
            + "dates, free text) before filtering on it. It scans the table several times, so it can be slow on large "
            + "tables. Parts the column type cannot do (for example min/max of a LOB) are left out and explained in "
            + "'notes'.", annotations = @Tool.Annotations(readOnlyHint = true))
    public String profileColumn(
            @ToolArg(name = "table", description = "Table or view name.") String table,
            @ToolArg(name = "column", description = "Column name.") String column,
            @ToolArg(name = "schema", description = "Schema name. Omit if the table name is unique.", required = false) String schema,
            @ToolArg(name = "top_values", description = "How many of the most frequent values to return, default 10, 0 for none.", required = false) Integer topValues,
            @ToolArg(name = "database", description = "Catalog (database) name. Usually omitted.", required = false) String database) {
        return profileColumn(auth(), database, schema, table, column, topValues);
    }

    String profileColumn(String authorization, String database, String schema, String table, String column,
            Integer topValues) {
        int top = topValues == null ? DEFAULT_TOP_VALUES : topValues;
        return db.run(authorization, conn -> {
            if (top < 0) {
                throw new InvalidRequestException("top_values must not be negative.");
            }
            return ToolJson.profile(state.queries().profileColumn(conn, database, schema, table, column, top));
        });
    }

    @Tool(name = "validate_sql", title = "Check SQL without running it", description = "Asks the database to parse a SQL "
            + "statement without executing it, and reports whether it is valid, the result columns and the number "
            + "of ? parameters. A syntax or name error is returned as valid=false with the database's message, not as "
            + "a tool error. How much is checked depends on the database driver: some only report errors when the "
            + "statement runs. Use it to check SQL before execute_query.", annotations = @Tool.Annotations(readOnlyHint = true))
    public String validateSql(
            @ToolArg(name = "sql", description = "One SQL statement, without a trailing semicolon.") String sql) {
        return validateSql(auth(), sql);
    }

    String validateSql(String authorization, String sql) {
        return db.run(authorization, conn -> ToolJson.validation(state.queries().validateSql(conn, sql)));
    }

    @Tool(name = "explain_query", title = "Execution plan of a query", description = "Shows the database's execution plan for "
            + "one SELECT, WITH, INSERT, UPDATE, DELETE, MERGE or VALUES statement, without running it. Use it to "
            + "see whether a query uses indexes before running it on a large table. Works for PostgreSQL, MySQL, "
            + "MariaDB, H2, Presto/Trino and Oracle; other databases return an error saying so. Do not start the "
            + "statement with EXPLAIN, the tool adds it.", annotations = @Tool.Annotations(readOnlyHint = true))
    public String explainQuery(
            @ToolArg(name = "sql", description = "The statement to explain, without EXPLAIN and without a trailing semicolon.") String sql) {
        return explainQuery(auth(), sql);
    }

    String explainQuery(String authorization, String sql) {
        return db.run(authorization, conn -> ToolJson.rows(state.queries().explain(conn, sql)));
    }

    // ---------------------------------------------------------------------------------------

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

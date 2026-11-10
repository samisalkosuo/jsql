package jsql.core;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Runs SQL with limits: row limit, query timeout, value length. Also provides the higher level
 * investigation helpers built on SQL (sample, profile, validate, explain, export).
 *
 * <p>
 * Read-only mode ({@code readOnly == true}): statements given by the caller are checked with a
 * best-effort text check ({@link SqlGuard}) and run on a connection marked read-only. This is a
 * safety net, not a security boundary. When {@code readOnly == false} the SQL is not touched and
 * the database user's permissions decide what is possible.
 *
 * <p>
 * All methods take a connection that the caller owns. Connections are expected to be in
 * auto-commit mode (so that a failed statement does not leave a transaction open).
 */
public final class QueryService {

    /**
     * @param columns        column labels; empty when the statement returned no result set
     * @param columnTypes    column type names
     * @param rows           values as text, in column order; SQL NULL is a Java null
     * @param truncated      true if more rows exist than were returned
     * @param valueTruncated true if any value was cut by maxValueLength
     * @param updateCount    rows affected, or null when the statement returned a result set
     * @param elapsedMs      time taken to execute in milliseconds
     */
    public record QueryResult(List<String> columns, List<String> columnTypes, List<List<String>> rows,
            boolean truncated, boolean valueTruncated, Integer updateCount, Long elapsedMs) {

        public QueryResult(List<String> columns, List<List<String>> rows, boolean truncated, Integer updateCount) {
            this(columns, List.of(), rows, truncated, false, updateCount, null);
        }
    }

    public record ExportResult(String path, long rowsWritten, boolean truncated, List<String> columns,
            long elapsedMs) {
    }

    public record RowCountResult(long rowCount, boolean estimated) {
    }

    public record ValueCount(String value, long count) {
    }

    /**
     * Profile of one column. Values that could not be computed for this column type (for example
     * min/max of a LOB) are null and explained in {@code notes}.
     */
    public record ColumnProfile(String column, String typeName, long rowCount, long nullCount, Long distinctCount,
            String min, String max, List<ValueCount> topValues, List<String> notes) {
    }

    public record ResultColumn(String name, String typeName) {
    }

    public record ColumnStats(String column, String distinctValues, String nullsFraction, String min, String max) {
    }

    /**
     * Statistics the database already keeps. {@code supported} is false when the database has no
     * plain-SQL way to read them; {@code hint} then says what to use instead.
     */
    public record TableStats(boolean supported, String hint, Long rowCount, List<ColumnStats> columns) {
    }

    /**
     * @param valid          true if the database accepted the statement
     * @param error          database error message when not valid
     * @param sqlState       SQL state of the error, if any
     * @param columns        result columns the statement would return; empty if unknown or not a query
     * @param parameterCount number of {@code ?} parameters, or -1 if the driver does not tell
     */
    public record Validation(boolean valid, String error, String sqlState, List<ResultColumn> columns,
            int parameterCount) {
    }

    private interface Action<T, E extends Exception> {
        T run() throws SQLException, E;
    }

    private final QueryLimits limits;
    private final boolean readOnly;
    private final SchemaInspector inspector;

    public QueryService(QueryLimits limits, boolean readOnly) {
        this.limits = limits;
        this.readOnly = readOnly;
        this.inspector = new SchemaInspector(limits);
    }

    /** Runs one SQL statement of any kind (unless read-only mode rejects it). */
    public QueryResult execute(Connection conn, String sql) throws SQLException {
        return execute(conn, sql, null, null, null);
    }

    /** Runs one SQL statement with pagination and optional per-call timeout. */
    public QueryResult execute(Connection conn, String sql, Integer offset, Integer limit, Integer timeoutSeconds)
            throws SQLException {
        if (readOnly) {
            SqlGuard.requireReadOnly(sql);
        } else if (sql == null || sql.isBlank()) {
            throw new InvalidRequestException("SQL is empty.");
        }
        if (offset != null && offset < 0) {
            throw new InvalidRequestException("offset must not be negative.");
        }
        if (limit != null && limit < 1) {
            throw new InvalidRequestException("limit must be at least 1.");
        }
        if (timeoutSeconds != null && timeoutSeconds < 0) {
            throw new InvalidRequestException("timeout_seconds must not be negative.");
        }
        int effectiveLimit = limit != null ? Math.min(limit, limits.maxRows()) : limits.maxRows();
        int off = offset != null ? offset : 0;
        int effectiveTimeout = limits.effectiveTimeout(timeoutSeconds);

        return withReadOnlyConnection(conn, () -> runStatement(conn, sql, off, effectiveLimit, effectiveTimeout));
    }

    /** Runs a parameterized prepared SQL statement with pagination and timeout. */
    public QueryResult executePrepared(Connection conn, String sql, List<Object> params, Integer offset, Integer limit,
            Integer timeoutSeconds) throws SQLException {
        if (readOnly) {
            SqlGuard.requireReadOnly(sql);
        } else if (sql == null || sql.isBlank()) {
            throw new InvalidRequestException("SQL is empty.");
        }
        if (offset != null && offset < 0) {
            throw new InvalidRequestException("offset must not be negative.");
        }
        if (limit != null && limit < 1) {
            throw new InvalidRequestException("limit must be at least 1.");
        }
        if (timeoutSeconds != null && timeoutSeconds < 0) {
            throw new InvalidRequestException("timeout_seconds must not be negative.");
        }
        int effectiveLimit = limit != null ? Math.min(limit, limits.maxRows()) : limits.maxRows();
        int off = offset != null ? offset : 0;
        int effectiveTimeout = limits.effectiveTimeout(timeoutSeconds);

        return withReadOnlyConnection(conn,
                () -> runPreparedStatement(conn, sql, params, off, effectiveLimit, effectiveTimeout));
    }

    /**
     * Exports query results directly to a local file, streaming with fetch size to minimize memory usage.
     */
    public ExportResult exportToFile(Connection conn, String sql, Path targetPath, String format, int maxRows,
            boolean includeHeader, Integer timeoutSeconds) throws SQLException, IOException {
        if (readOnly) {
            SqlGuard.requireReadOnly(sql);
        } else if (sql == null || sql.isBlank()) {
            throw new InvalidRequestException("SQL is empty.");
        }
        if (maxRows < 1) {
            throw new InvalidRequestException("max_rows must be at least 1.");
        }
        if (timeoutSeconds != null && timeoutSeconds < 0) {
            throw new InvalidRequestException("timeout_seconds must not be negative.");
        }
        String fmt = format == null || format.isBlank() ? "csv" : format.toLowerCase(Locale.ROOT).trim();
        if (!fmt.equals("csv") && !fmt.equals("json") && !fmt.equals("jsonl")) {
            throw new InvalidRequestException("format must be 'csv', 'json' or 'jsonl', but is '" + format + "'.");
        }
        int effectiveTimeout = limits.effectiveTimeout(timeoutSeconds);

        return withReadOnlyConnection(conn, () -> {
            long startTime = System.currentTimeMillis();
            try (Statement st = conn.createStatement()) {
                configureStatement(st, 0, effectiveTimeout);
                try {
                    st.setFetchSize(1000);
                } catch (SQLException e) {
                    // some drivers don't support setFetchSize
                }
                boolean hasResultSet;
                try {
                    hasResultSet = st.execute(sql);
                } catch (SQLTimeoutException e) {
                    cancelQuietly(st);
                    throw new QueryTimeoutException("Query timed out after " + effectiveTimeout
                            + "s (error_type: timeout). Narrow your filters or use explain_query.", e);
                } catch (SQLException e) {
                    if (isTimeout(e)) {
                        cancelQuietly(st);
                        throw new QueryTimeoutException("Query timed out after " + effectiveTimeout
                                + "s (error_type: timeout). Narrow your filters or use explain_query.", e);
                    }
                    throw e;
                }
                if (!hasResultSet) {
                    throw new InvalidRequestException("Export requires a query that returns a result set.");
                }
                try (ResultSet rs = st.getResultSet()) {
                    ResultSetMetaData rsmd = rs.getMetaData();
                    int columnCount = rsmd.getColumnCount();
                    List<String> columns = new ArrayList<>(columnCount);
                    for (int i = 1; i <= columnCount; i++) {
                        columns.add(rsmd.getColumnLabel(i));
                    }
                    if (targetPath.getParent() != null) {
                        Files.createDirectories(targetPath.getParent());
                    }
                    long rowsWritten = 0;
                    boolean truncated = false;
                    try (BufferedWriter writer = Files.newBufferedWriter(targetPath, StandardCharsets.UTF_8)) {
                        switch (fmt) {
                        case "csv" -> {
                            if (includeHeader) {
                                writeCsvRow(writer, columns);
                            }
                            while (rs.next()) {
                                if (rowsWritten >= maxRows) {
                                    truncated = true;
                                    break;
                                }
                                List<String> row = new ArrayList<>(columnCount);
                                for (int i = 1; i <= columnCount; i++) {
                                    Object val = rs.getObject(i);
                                    row.add(val == null ? null : val.toString());
                                }
                                writeCsvRow(writer, row);
                                rowsWritten++;
                            }
                        }
                        case "json" -> {
                            writer.write("[\n");
                            boolean first = true;
                            while (rs.next()) {
                                if (rowsWritten >= maxRows) {
                                    truncated = true;
                                    break;
                                }
                                if (!first) {
                                    writer.write(",\n");
                                }
                                first = false;
                                writeJsonObject(writer, rs, columns);
                                rowsWritten++;
                            }
                            writer.write("\n]\n");
                        }
                        case "jsonl" -> {
                            while (rs.next()) {
                                if (rowsWritten >= maxRows) {
                                    truncated = true;
                                    break;
                                }
                                writeJsonObject(writer, rs, columns);
                                writer.write('\n');
                                rowsWritten++;
                            }
                        }
                        }
                    }
                    long elapsedMs = System.currentTimeMillis() - startTime;
                    return new ExportResult(targetPath.toString(), rowsWritten, truncated, columns, elapsedMs);
                }
            }
        });
    }

    /**
     * Gets the row count of a table, using statistics where available or falling back to COUNT(*).
     */
    public RowCountResult getRowCount(Connection conn, String catalog, String schema, String table,
            Integer timeoutSeconds) throws SQLException {
        TableRef ref = inspector.resolveTable(conn, catalog, schema, table).table();
        DatabaseMetaData md = conn.getMetaData();
        String from = Dialect.qualifiedName(md, ref);
        int effectiveTimeout = limits.effectiveTimeout(timeoutSeconds);

        return withReadOnlyConnection(conn, () -> {
            try (Statement st = conn.createStatement()) {
                configureStatement(st, 1, effectiveTimeout);
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + from)) {
                    if (rs.next()) {
                        return new RowCountResult(rs.getLong(1), false);
                    }
                } catch (SQLTimeoutException e) {
                    cancelQuietly(st);
                    throw new QueryTimeoutException("Counting rows timed out after " + effectiveTimeout
                            + "s (error_type: timeout). Table may be very large.", e);
                }
            }
            return new RowCountResult(0, false);
        });
    }

    /**
     * Table and column statistics from the database's own statistics, without scanning the table.
     * Supported for Presto and Trino ({@code SHOW STATS FOR}); other databases give
     * {@code supported = false}.
     */
    public TableStats tableStats(Connection conn, String catalog, String schema, String table,
            Integer timeoutSeconds) throws SQLException {
        int timeout = limits.effectiveTimeout(timeoutSeconds);
        TableRef ref = inspector.resolveTable(conn, catalog, schema, table).table();
        DatabaseMetaData md = conn.getMetaData();
        String product = md.getDatabaseProductName() == null ? "" : md.getDatabaseProductName().toLowerCase(Locale.ROOT);
        if (!product.contains("presto") && !product.contains("trino")) {
            return new TableStats(false, "Table statistics are not available for " + md.getDatabaseProductName()
                    + " through plain SQL. Use get_row_count for the number of rows and profile_column for a column.",
                    null, List.of());
        }
        QueryResult r = run(conn, "SHOW STATS FOR " + Dialect.qualifiedName(md, ref), limits.maxRows(), timeout);
        int nameIdx = r.columns().indexOf("column_name");
        int distinctIdx = r.columns().indexOf("distinct_values_count");
        int nullsIdx = r.columns().indexOf("nulls_fraction");
        int rowsIdx = r.columns().indexOf("row_count");
        int lowIdx = r.columns().indexOf("low_value");
        int highIdx = r.columns().indexOf("high_value");
        Long rowCount = null;
        List<ColumnStats> columns = new ArrayList<>();
        for (List<String> row : r.rows()) {
            if (nameIdx < 0 || row.get(nameIdx) == null) {
                if (rowsIdx >= 0 && row.get(rowsIdx) != null) {
                    try {
                        rowCount = (long) Double.parseDouble(row.get(rowsIdx));
                    } catch (NumberFormatException e) {
                        // statistics not known
                    }
                }
            } else {
                columns.add(new ColumnStats(row.get(nameIdx), cell(row, distinctIdx), cell(row, nullsIdx),
                        cell(row, lowIdx), cell(row, highIdx)));
            }
        }
        return new TableStats(true, null, rowCount, columns);
    }

    private static String cell(List<String> row, int index) {
        return index < 0 ? null : row.get(index);
    }

    /** The first rows of a table or view. {@code rows} is cut to the configured maximum. */
    public QueryResult sampleRows(Connection conn, String catalog, String schema, String table, int rows)
            throws SQLException {
        return sampleRows(conn, catalog, schema, table, rows, null);
    }

    /** Like {@link #sampleRows(Connection, String, String, String, int)} with a per-call timeout. */
    public QueryResult sampleRows(Connection conn, String catalog, String schema, String table, int rows,
            Integer timeoutSeconds) throws SQLException {
        int timeout = limits.effectiveTimeout(timeoutSeconds);
        TableRef ref = inspector.resolveTable(conn, catalog, schema, table).table();
        int count = Math.max(1, Math.min(rows, limits.maxRows()));
        return run(conn, "SELECT * FROM " + Dialect.qualifiedName(conn.getMetaData(), ref), count, timeout);
    }

    /**
     * Row, null and distinct counts, min, max and the most frequent values of a column. This scans
     * the table several times: use with care on large tables.
     *
     * @param topValues how many frequent values to return (0 to skip)
     */
    public ColumnProfile profileColumn(Connection conn, String catalog, String schema, String table, String column,
            int topValues) throws SQLException {
        return profileColumn(conn, catalog, schema, table, column, topValues, null);
    }

    /** Like {@link #profileColumn(Connection, String, String, String, String, int)} with a per-statement timeout. */
    public ColumnProfile profileColumn(Connection conn, String catalog, String schema, String table, String column,
            int topValues, Integer timeoutSeconds) throws SQLException {
        int timeout = limits.effectiveTimeout(timeoutSeconds);
        TableRef ref = inspector.resolveTable(conn, catalog, schema, table).table();
        SchemaInspector.ColumnInfo col = findColumn(inspector.columns(conn, ref), column);
        DatabaseMetaData md = conn.getMetaData();
        String from = Dialect.qualifiedName(md, ref);
        String c = Dialect.quote(md, col.name());
        List<String> notes = new ArrayList<>();

        QueryResult counts = run(conn, "SELECT COUNT(*), COUNT(" + c + ") FROM " + from, 1, timeout);
        long rowCount = Long.parseLong(counts.rows().get(0).get(0));
        long nonNull = Long.parseLong(counts.rows().get(0).get(1));

        Long distinct = null;
        try {
            QueryResult r = run(conn, "SELECT COUNT(DISTINCT " + c + ") FROM " + from, 1, timeout);
            distinct = Long.parseLong(r.rows().get(0).get(0));
        } catch (SQLException e) {
            notes.add("distinct count not available for type " + col.typeName() + ": " + e.getMessage());
        }

        String min = null;
        String max = null;
        try {
            QueryResult r = run(conn, "SELECT MIN(" + c + "), MAX(" + c + ") FROM " + from, 1, timeout);
            min = r.rows().get(0).get(0);
            max = r.rows().get(0).get(1);
        } catch (SQLException e) {
            notes.add("min/max not available for type " + col.typeName() + ": " + e.getMessage());
        }

        List<ValueCount> top = new ArrayList<>();
        if (topValues > 0) {
            try {
                QueryResult r = run(conn, "SELECT " + c + ", COUNT(*) FROM " + from + " GROUP BY " + c
                        + " ORDER BY COUNT(*) DESC", Math.min(topValues, limits.maxRows()), timeout);
                for (List<String> row : r.rows()) {
                    top.add(new ValueCount(row.get(0), Long.parseLong(row.get(1))));
                }
            } catch (SQLException e) {
                notes.add("most frequent values not available for type " + col.typeName() + ": " + e.getMessage());
            }
        }
        return new ColumnProfile(col.name(), col.typeName(), rowCount, rowCount - nonNull, distinct, min, max, top,
                notes);
    }

    /**
     * Asks the database to parse the statement without running it. A database error is returned as
     * an invalid result, not thrown. Note: how much is checked depends on the driver; some drivers
     * only send the statement to the server when it is executed.
     */
    public Validation validateSql(Connection conn, String sql) throws SQLException {
        if (readOnly) {
            SqlGuard.requireReadOnly(sql);
        } else if (sql == null || sql.isBlank()) {
            throw new InvalidRequestException("SQL is empty.");
        }
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            // Some drivers (PostgreSQL) send the statement to the server only when asked for its
            // metadata, so a real error can show up here. Only "not supported" is ignored.
            int parameterCount = -1;
            try {
                ParameterMetaData pmd = ps.getParameterMetaData();
                parameterCount = pmd.getParameterCount();
            } catch (SQLException e) {
                rethrowUnlessNotSupported(e);
            } catch (UnsupportedOperationException e) {
                // driver does not tell
            }
            List<ResultColumn> columns = new ArrayList<>();
            try {
                ResultSetMetaData rsmd = ps.getMetaData();
                if (rsmd != null) {
                    for (int i = 1; i <= rsmd.getColumnCount(); i++) {
                        columns.add(new ResultColumn(rsmd.getColumnLabel(i), rsmd.getColumnTypeName(i)));
                    }
                }
            } catch (SQLException e) {
                rethrowUnlessNotSupported(e);
            } catch (UnsupportedOperationException e) {
                // driver does not tell
            }
            return new Validation(true, null, null, columns, parameterCount);
        } catch (SQLException e) {
            return new Validation(false, e.getMessage(), e.getSQLState(), List.of(), -1);
        }
    }

    // SQLState 0A000 (feature not supported) and HYC00 (optional feature not implemented) mean the
    // driver cannot tell; everything else is a real error about the statement.
    private static void rethrowUnlessNotSupported(SQLException e) throws SQLException {
        String state = e.getSQLState();
        boolean notSupported = e instanceof SQLFeatureNotSupportedException
                || (state != null && (state.startsWith("0A") || state.startsWith("HYC")));
        if (!notSupported) {
            throw e;
        }
    }

    /**
     * The execution plan of a statement, without running it. Supported for PostgreSQL, MySQL,
     * MariaDB, H2, Presto/Trino and Oracle; other databases give an {@link InvalidRequestException}.
     * The plan is returned as rows of text.
     */
    public QueryResult explain(Connection conn, String sql) throws SQLException {
        return explain(conn, sql, null);
    }

    /** Like {@link #explain(Connection, String)} with a per-call timeout. */
    public QueryResult explain(Connection conn, String sql, Integer timeoutSeconds) throws SQLException {
        int timeout = limits.effectiveTimeout(timeoutSeconds);
        if (readOnly) {
            SqlGuard.requireReadOnly(sql);
        }
        String statement = SqlGuard.requireExplainable(sql);
        List<String> steps = Dialect.explainStatements(conn.getMetaData().getDatabaseProductName(), statement);
        for (int i = 0; i < steps.size() - 1; i++) {
            try (Statement st = conn.createStatement()) {
                configureStatement(st, 1, timeout);
                st.execute(steps.get(i));
            }
        }
        return run(conn, steps.get(steps.size() - 1), limits.maxRows(), timeout);
    }

    // ---------------------------------------------------------------------------------------

    private QueryResult run(Connection conn, String sql, int rowLimit, int timeoutSeconds) throws SQLException {
        return runStatement(conn, sql, 0, rowLimit, timeoutSeconds);
    }

    private QueryResult runStatement(Connection conn, String sql, int offset, int rowLimit, int timeoutSeconds)
            throws SQLException {
        long startTime = System.currentTimeMillis();
        try (Statement st = conn.createStatement()) {
            configureStatement(st, offset > 0 ? 0 : (rowLimit + 1), timeoutSeconds);
            boolean hasResultSet;
            try {
                hasResultSet = st.execute(sql);
            } catch (SQLTimeoutException e) {
                cancelQuietly(st);
                throw new QueryTimeoutException("Query timed out after " + timeoutSeconds
                        + "s (error_type: timeout). Narrow your filters or use explain_query.", e);
            } catch (SQLException e) {
                if (isTimeout(e)) {
                    cancelQuietly(st);
                    throw new QueryTimeoutException("Query timed out after " + timeoutSeconds
                            + "s (error_type: timeout). Narrow your filters or use explain_query.", e);
                }
                throw e;
            }
            if (!hasResultSet) {
                long elapsed = System.currentTimeMillis() - startTime;
                return new QueryResult(List.of(), List.of(), List.of(), false, false, st.getUpdateCount(), elapsed);
            }
            try (ResultSet rs = st.getResultSet()) {
                return extractResult(rs, offset, rowLimit, startTime);
            }
        }
    }

    private QueryResult runPreparedStatement(Connection conn, String sql, List<Object> params, int offset,
            int rowLimit, int timeoutSeconds) throws SQLException {
        long startTime = System.currentTimeMillis();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            configureStatement(ps, offset > 0 ? 0 : (rowLimit + 1), timeoutSeconds);
            if (params != null) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
            }
            boolean hasResultSet;
            try {
                hasResultSet = ps.execute();
            } catch (SQLTimeoutException e) {
                cancelQuietly(ps);
                throw new QueryTimeoutException("Query timed out after " + timeoutSeconds
                        + "s (error_type: timeout). Narrow your filters or use explain_query.", e);
            } catch (SQLException e) {
                if (isTimeout(e)) {
                    cancelQuietly(ps);
                    throw new QueryTimeoutException("Query timed out after " + timeoutSeconds
                            + "s (error_type: timeout). Narrow your filters or use explain_query.", e);
                }
                throw e;
            }
            if (!hasResultSet) {
                long elapsed = System.currentTimeMillis() - startTime;
                return new QueryResult(List.of(), List.of(), List.of(), false, false, ps.getUpdateCount(), elapsed);
            }
            try (ResultSet rs = ps.getResultSet()) {
                return extractResult(rs, offset, rowLimit, startTime);
            }
        }
    }

    private QueryResult extractResult(ResultSet rs, int offset, int rowLimit, long startTime) throws SQLException {
        ResultSetMetaData rsmd = rs.getMetaData();
        int columnCount = rsmd.getColumnCount();
        List<String> columns = new ArrayList<>(columnCount);
        List<String> columnTypes = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            columns.add(rsmd.getColumnLabel(i));
            columnTypes.add(rsmd.getColumnTypeName(i) == null ? "" : rsmd.getColumnTypeName(i));
        }

        // Handle offset pagination
        if (offset > 0) {
            int skipped = 0;
            while (skipped < offset && rs.next()) {
                skipped++;
            }
        }

        List<List<String>> rows = new ArrayList<>();
        boolean truncated = false;
        boolean valueTruncated = false;
        while (rs.next()) {
            if (rows.size() == rowLimit) {
                truncated = true;
                break;
            }
            List<String> row = new ArrayList<>(columnCount);
            for (int i = 1; i <= columnCount; i++) {
                String formatted = ValueFormatter.format(rs.getObject(i), limits.maxValueLength());
                if (formatted != null && formatted.endsWith("...[truncated]")) {
                    valueTruncated = true;
                }
                row.add(formatted);
            }
            rows.add(row);
        }
        long elapsed = System.currentTimeMillis() - startTime;
        return new QueryResult(columns, columnTypes, rows, truncated, valueTruncated, null, elapsed);
    }

    private void configureStatement(Statement st, int maxRows, int timeoutSeconds) throws SQLException {
        if (maxRows > 0) {
            st.setMaxRows(maxRows);
        }
        if (timeoutSeconds > 0) {
            try {
                st.setQueryTimeout(timeoutSeconds);
            } catch (SQLFeatureNotSupportedException e) {
                // driver has no statement timeout
            }
        }
    }

    private static void cancelQuietly(Statement st) {
        try {
            st.cancel();
        } catch (SQLException e) {
            // ignore cancel failure
        }
    }

    private static boolean isTimeout(SQLException e) {
        String sqlState = e.getSQLState();
        if ("57014".equals(sqlState)) { // SQLState for query cancelled or timeout
            return true;
        }
        String msg = e.getMessage();
        return msg != null && (msg.toLowerCase(Locale.ROOT).contains("timeout")
                || msg.toLowerCase(Locale.ROOT).contains("timed out")
                || msg.toLowerCase(Locale.ROOT).contains("canceling statement due to statement timeout"));
    }

    private static void writeCsvRow(BufferedWriter writer, List<String> values) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            String val = values.get(i);
            if (val != null) {
                boolean quote = val.contains(",") || val.contains("\"") || val.contains("\n") || val.contains("\r");
                if (quote) {
                    sb.append('"').append(val.replace("\"", "\"\"")).append('"');
                } else {
                    sb.append(val);
                }
            }
        }
        sb.append("\r\n");
        writer.write(sb.toString());
    }

    private static void writeJsonObject(BufferedWriter writer, ResultSet rs, List<String> columns)
            throws SQLException, IOException {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(escapeJson(columns.get(i))).append("\":");
            Object val = rs.getObject(i + 1);
            if (val == null) {
                sb.append("null");
            } else if (val instanceof Number || val instanceof Boolean) {
                sb.append(val);
            } else {
                sb.append('"').append(escapeJson(val.toString())).append('"');
            }
        }
        sb.append('}');
        writer.write(sb.toString());
    }

    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
            case '"' -> sb.append("\\\"");
            case '\\' -> sb.append("\\\\");
            case '\b' -> sb.append("\\b");
            case '\f' -> sb.append("\\f");
            case '\n' -> sb.append("\\n");
            case '\r' -> sb.append("\\r");
            case '\t' -> sb.append("\\t");
            default -> {
                if (c < ' ') {
                    sb.append(String.format("\\u%04x", (int) c));
                } else {
                    sb.append(c);
                }
            }
            }
        }
        return sb.toString();
    }

    // Marks the connection read-only while the action runs. Failure to do so is ignored: some
    // drivers refuse it, and the text check in SqlGuard has already been applied.
    private <T, E extends Exception> T withReadOnlyConnection(Connection conn, Action<T, E> action)
            throws SQLException, E {
        if (!readOnly) {
            return action.run();
        }
        boolean previous = false;
        boolean changed = false;
        try {
            previous = conn.isReadOnly();
            conn.setReadOnly(true);
            changed = true;
        } catch (SQLException e) {
            // ignore, see above
        }
        try {
            return action.run();
        } finally {
            if (changed) {
                try {
                    conn.setReadOnly(previous);
                } catch (SQLException e) {
                    // ignore
                }
            }
        }
    }

    private static SchemaInspector.ColumnInfo findColumn(List<SchemaInspector.ColumnInfo> columns, String name) {
        if (name == null || name.isBlank()) {
            throw new InvalidRequestException("Column name is required.");
        }
        for (SchemaInspector.ColumnInfo c : columns) {
            if (c.name().equals(name)) {
                return c;
            }
        }
        List<SchemaInspector.ColumnInfo> matches = new ArrayList<>();
        for (SchemaInspector.ColumnInfo c : columns) {
            if (c.name().toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT))) {
                matches.add(c);
            }
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }
        List<String> names = new ArrayList<>();
        for (SchemaInspector.ColumnInfo c : columns) {
            names.add(c.name());
        }
        throw new InvalidRequestException("Column not found" + (matches.size() > 1 ? " (name is ambiguous)" : "") + ": "
                + name + ". Columns of the table: " + String.join(", ", names));
    }
}

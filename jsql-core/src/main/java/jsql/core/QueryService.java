package jsql.core;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Runs SQL with limits: row limit, query timeout, value length. Also provides the higher level
 * investigation helpers built on SQL (sample, profile, validate, explain).
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
     * @param columns     column labels; empty when the statement returned no result set
     * @param rows        values as text, in column order; SQL NULL is a Java null
     * @param truncated   true if more rows exist than were returned
     * @param updateCount rows affected, or null when the statement returned a result set
     */
    public record QueryResult(List<String> columns, List<List<String>> rows, boolean truncated, Integer updateCount) {
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

    private interface Action<T> {
        T run() throws SQLException;
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
        if (readOnly) {
            SqlGuard.requireReadOnly(sql);
        } else if (sql == null || sql.isBlank()) {
            throw new InvalidRequestException("SQL is empty.");
        }
        return withReadOnlyConnection(conn, () -> run(conn, sql, limits.maxRows()));
    }

    /** The first rows of a table or view. {@code rows} is cut to the configured maximum. */
    public QueryResult sampleRows(Connection conn, String catalog, String schema, String table, int rows)
            throws SQLException {
        TableRef ref = inspector.resolveTable(conn, catalog, schema, table).table();
        int count = Math.max(1, Math.min(rows, limits.maxRows()));
        return run(conn, "SELECT * FROM " + Dialect.qualifiedName(conn.getMetaData(), ref), count);
    }

    /**
     * Row, null and distinct counts, min, max and the most frequent values of a column. This scans
     * the table several times: use with care on large tables.
     *
     * @param topValues how many frequent values to return (0 to skip)
     */
    public ColumnProfile profileColumn(Connection conn, String catalog, String schema, String table, String column,
            int topValues) throws SQLException {
        TableRef ref = inspector.resolveTable(conn, catalog, schema, table).table();
        SchemaInspector.ColumnInfo col = findColumn(inspector.columns(conn, ref), column);
        DatabaseMetaData md = conn.getMetaData();
        String from = Dialect.qualifiedName(md, ref);
        String c = Dialect.quote(md, col.name());
        List<String> notes = new ArrayList<>();

        QueryResult counts = run(conn, "SELECT COUNT(*), COUNT(" + c + ") FROM " + from, 1);
        long rowCount = Long.parseLong(counts.rows().get(0).get(0));
        long nonNull = Long.parseLong(counts.rows().get(0).get(1));

        Long distinct = null;
        try {
            QueryResult r = run(conn, "SELECT COUNT(DISTINCT " + c + ") FROM " + from, 1);
            distinct = Long.parseLong(r.rows().get(0).get(0));
        } catch (SQLException e) {
            notes.add("distinct count not available for type " + col.typeName() + ": " + e.getMessage());
        }

        String min = null;
        String max = null;
        try {
            QueryResult r = run(conn, "SELECT MIN(" + c + "), MAX(" + c + ") FROM " + from, 1);
            min = r.rows().get(0).get(0);
            max = r.rows().get(0).get(1);
        } catch (SQLException e) {
            notes.add("min/max not available for type " + col.typeName() + ": " + e.getMessage());
        }

        List<ValueCount> top = new ArrayList<>();
        if (topValues > 0) {
            try {
                QueryResult r = run(conn, "SELECT " + c + ", COUNT(*) FROM " + from + " GROUP BY " + c
                        + " ORDER BY COUNT(*) DESC", Math.min(topValues, limits.maxRows()));
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
        if (readOnly) {
            SqlGuard.requireReadOnly(sql);
        }
        String statement = SqlGuard.requireExplainable(sql);
        List<String> steps = Dialect.explainStatements(conn.getMetaData().getDatabaseProductName(), statement);
        for (int i = 0; i < steps.size() - 1; i++) {
            try (Statement st = conn.createStatement()) {
                configure(st, 1);
                st.execute(steps.get(i));
            }
        }
        return run(conn, steps.get(steps.size() - 1), limits.maxRows());
    }

    // ---------------------------------------------------------------------------------------

    private QueryResult run(Connection conn, String sql, int rowLimit) throws SQLException {
        try (Statement st = conn.createStatement()) {
            configure(st, rowLimit + 1);
            if (!st.execute(sql)) {
                return new QueryResult(List.of(), List.of(), false, st.getUpdateCount());
            }
            try (ResultSet rs = st.getResultSet()) {
                ResultSetMetaData rsmd = rs.getMetaData();
                int columnCount = rsmd.getColumnCount();
                List<String> columns = new ArrayList<>();
                for (int i = 1; i <= columnCount; i++) {
                    columns.add(rsmd.getColumnLabel(i));
                }
                List<List<String>> rows = new ArrayList<>();
                boolean truncated = false;
                while (rs.next()) {
                    if (rows.size() == rowLimit) {
                        truncated = true;
                        break;
                    }
                    List<String> row = new ArrayList<>(columnCount);
                    for (int i = 1; i <= columnCount; i++) {
                        row.add(ValueFormatter.format(rs.getObject(i), limits.maxValueLength()));
                    }
                    rows.add(row);
                }
                return new QueryResult(columns, rows, truncated, null);
            }
        }
    }

    private void configure(Statement st, int maxRows) throws SQLException {
        st.setMaxRows(maxRows);
        if (limits.queryTimeoutSeconds() > 0) {
            try {
                st.setQueryTimeout(limits.queryTimeoutSeconds());
            } catch (SQLFeatureNotSupportedException e) {
                // driver has no statement timeout
            }
        }
    }

    // Marks the connection read-only while the action runs. Failure to do so is ignored: some
    // drivers refuse it, and the text check in SqlGuard has already been applied.
    private <T> T withReadOnlyConnection(Connection conn, Action<T> action) throws SQLException {
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

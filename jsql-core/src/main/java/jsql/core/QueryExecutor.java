package jsql.core;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Executes a single SQL statement and streams the outcome to a {@link Handler}. */
public final class QueryExecutor {

    /** Receives the outcome of a statement. Rows are streamed, not collected. */
    public interface Handler {
        /** Statement returned a result set with these columns. Called once, before any row. */
        void columns(List<String> columnNames);

        /** One row of the result set; null values are given as the string "NULL". */
        void row(List<String> values);

        /** Statement returned no result set. */
        void updateCount(int updateCount);
    }

    private QueryExecutor() {
    }

    public static void execute(Connection conn, String sql, Handler handler) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
            try (ResultSet rs = st.getResultSet()) {
                if (rs == null) {
                    handler.updateCount(st.getUpdateCount());
                    return;
                }
                ResultSetMetaData rsmd = rs.getMetaData();
                int columnCount = rsmd.getColumnCount();
                List<String> columnNames = new ArrayList<>();
                for (int i = 0; i < columnCount; i++) {
                    columnNames.add(rsmd.getColumnName(i + 1));
                }
                handler.columns(columnNames);

                while (rs.next()) {
                    List<String> columnValues = new ArrayList<>();
                    for (int i = 0; i < columnCount; i++) {
                        Object v = rs.getObject(i + 1);
                        columnValues.add(v != null ? v.toString() : "NULL");
                    }
                    handler.row(columnValues);
                }
            }
        }
    }
}

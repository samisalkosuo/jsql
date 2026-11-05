package jsql.core;

import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

/** The few places where SQL differs between database products. */
final class Dialect {

    private Dialect() {
    }

    /** Quotes an identifier with the quote string reported by the driver (none if unsupported). */
    static String quote(DatabaseMetaData md, String identifier) throws SQLException {
        String q = md.getIdentifierQuoteString();
        if (q == null || q.isBlank()) {
            return identifier;
        }
        return q + identifier.replace(q, q + q) + q;
    }

    /** Fully qualified, quoted table name using the catalog and schema that exist. */
    static String qualifiedName(DatabaseMetaData md, TableRef ref) throws SQLException {
        StringBuilder sb = new StringBuilder();
        if (ref.catalog() != null && !ref.catalog().isEmpty()) {
            sb.append(quote(md, ref.catalog())).append('.');
        }
        if (ref.schema() != null && !ref.schema().isEmpty()) {
            sb.append(quote(md, ref.schema())).append('.');
        }
        return sb.append(quote(md, ref.name())).toString();
    }

    /**
     * Statements that produce an execution plan without executing the query. All but the last are
     * only executed; the last one returns the plan.
     *
     * @throws InvalidRequestException for products where the plan cannot be fetched with plain SQL
     */
    static List<String> explainStatements(String productName, String sql) {
        String p = productName == null ? "" : productName.toLowerCase(Locale.ROOT);
        if (p.contains("postgres") || p.contains("mysql") || p.contains("mariadb") || p.equals("h2")
                || p.contains("presto") || p.contains("trino")) {
            return List.of("EXPLAIN " + sql);
        }
        if (p.contains("oracle")) {
            return List.of("EXPLAIN PLAN FOR " + sql, "SELECT PLAN_TABLE_OUTPUT FROM TABLE(DBMS_XPLAN.DISPLAY())");
        }
        throw new InvalidRequestException("explain is not supported for " + productName
                + ": the execution plan cannot be fetched with plain SQL on this database. Use validate_sql to check"
                + " the statement or the database's own tools to see the plan.");
    }
}

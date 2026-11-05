package jsql.core;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Read-only inspection of database structure through JDBC {@link DatabaseMetaData}. All methods
 * take a connection that the caller owns. Lists are cut at {@link QueryLimits#maxRows()}.
 *
 * <p>
 * Name parameters: a blank catalog or schema means "not restricted". Table, schema and catalog
 * names are matched exactly; if nothing is found the upper-case and lower-case forms are tried,
 * because databases differ in how they fold case.
 */
public final class SchemaInspector {

    public record SchemaRef(String catalog, String schema) {
    }

    public record TableInfo(TableRef table, String type, String remarks) {
    }

    public record ColumnInfo(String name, String typeName, int size, Integer decimalDigits, boolean nullable,
            String defaultValue, String remarks) {
    }

    public record IndexInfo(String name, boolean unique, List<String> columns) {
    }

    public record TableDescription(TableInfo table, List<ColumnInfo> columns, List<String> primaryKey,
            List<IndexInfo> indexes) {
    }

    /** A search hit. {@code column} is null when the table name matched. */
    public record SchemaMatch(TableRef table, String column) {
    }

    public record ForeignKey(String name, TableRef fkTable, List<String> fkColumns, TableRef pkTable,
            List<String> pkColumns) {
    }

    /**
     * @param references   foreign keys of this table (this table points to another table)
     * @param referencedBy foreign keys of other tables that point to this table
     */
    public record Relationships(TableRef table, List<ForeignKey> references, List<ForeignKey> referencedBy) {
    }

    private interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private interface ResultSetSource {
        ResultSet open() throws SQLException;
    }

    private final int maxItems;

    public SchemaInspector(QueryLimits limits) {
        this.maxItems = limits.maxRows();
    }

    /** Catalog (database) names; empty if the database has no catalogs. */
    public List<String> listCatalogs(Connection conn) throws SQLException {
        List<String> result = readRows(() -> conn.getMetaData().getCatalogs(), rs -> rs.getString(1));
        result.removeIf(name -> name == null || name.isEmpty()); // Db2 reports one row without a name
        Collections.sort(result);
        return result;
    }

    /** Schemas; empty if the database has no schemas (for example MySQL, which uses catalogs). */
    public ListResult<SchemaRef> listSchemas(Connection conn) throws SQLException {
        List<SchemaRef> all = readRows(() -> conn.getMetaData().getSchemas(), rs -> {
            boolean hasCatalog = rs.getMetaData().getColumnCount() >= 2;
            return new SchemaRef(hasCatalog ? rs.getString(2) : null, rs.getString(1));
        });
        return cut(all);
    }

    /**
     * Tables and views.
     *
     * @param namePattern JDBC pattern: {@code %} matches any text, {@code _} one character; null for all
     * @param types       table types such as TABLE or VIEW; null or empty for the user's tables and views
     *                    (types that are indexes, sequences, types or system objects are then left out;
     *                    name them explicitly, for example {@code SYSTEM TABLE}, to see them)
     */
    public ListResult<TableInfo> listTables(Connection conn, String catalog, String schema, String namePattern,
            List<String> types) throws SQLException {
        String[] typeArray = types == null || types.isEmpty() ? null : types.toArray(new String[0]);
        List<TableInfo> items = new ArrayList<>();
        boolean truncated = false;
        try (ResultSet rs = conn.getMetaData().getTables(blankToNull(catalog), blankToNull(schema),
                namePattern == null ? "%" : namePattern, typeArray)) {
            while (rs.next()) {
                if (typeArray == null && isNoiseType(rs.getString("TABLE_TYPE"))) {
                    continue;
                }
                if (items.size() == maxItems) {
                    truncated = true;
                    break;
                }
                items.add(tableInfo(rs));
            }
        }
        return new ListResult<>(items, truncated);
    }

    /**
     * Finds exactly one table or view.
     *
     * @throws InvalidRequestException if it does not exist or the name is ambiguous
     */
    public TableInfo resolveTable(Connection conn, String catalog, String schema, String table) throws SQLException {
        if (table == null || table.isBlank()) {
            throw new InvalidRequestException("Table name is required.");
        }
        DatabaseMetaData md = conn.getMetaData();
        String cat = blankToNull(catalog);
        String sch = blankToNull(schema);
        for (String schemaCandidate : caseVariants(sch)) {
            for (String tableCandidate : caseVariants(table)) {
                List<TableInfo> found = new ArrayList<>();
                try (ResultSet rs = md.getTables(cat, schemaCandidate, escape(md, tableCandidate), null)) {
                    while (rs.next()) {
                        TableInfo info = tableInfo(rs);
                        boolean schemaOk = schemaCandidate == null || schemaCandidate.equals(info.table().schema());
                        if (tableCandidate.equals(info.table().name()) && schemaOk) {
                            found.add(info);
                        }
                    }
                }
                if (found.size() == 1) {
                    return found.get(0);
                }
                if (found.size() > 1) {
                    List<String> names = new ArrayList<>();
                    for (TableInfo f : found) {
                        names.add(display(f.table()));
                    }
                    throw new InvalidRequestException(
                            "Table name '" + table + "' is ambiguous. Specify catalog and/or schema. Candidates: "
                                    + String.join(", ", names));
                }
            }
        }
        throw new InvalidRequestException("Table not found: " + (sch == null ? "" : sch + ".") + table
                + ". Use list_tables or search_schema to find the right name.");
    }

    public TableDescription describeTable(Connection conn, String catalog, String schema, String table)
            throws SQLException {
        TableInfo info = resolveTable(conn, catalog, schema, table);
        TableRef ref = info.table();
        DatabaseMetaData md = conn.getMetaData();

        List<ColumnInfo> columns = columns(conn, ref);

        List<String[]> pkParts = readOptional(() -> md.getPrimaryKeys(ref.catalog(), ref.schema(), ref.name()),
                rs -> new String[] { String.format("%05d", rs.getShort("KEY_SEQ")), rs.getString("COLUMN_NAME") });
        pkParts.sort(Comparator.comparing(p -> p[0]));
        List<String> primaryKey = new ArrayList<>();
        for (String[] p : pkParts) {
            primaryKey.add(p[1]);
        }

        List<IndexInfo> indexes = indexes(md, ref);
        return new TableDescription(info, columns, primaryKey, indexes);
    }

    /** Columns of a resolved table, in table order. */
    public List<ColumnInfo> columns(Connection conn, TableRef ref) throws SQLException {
        DatabaseMetaData md = conn.getMetaData();
        List<ColumnInfo> columns = new ArrayList<>();
        try (ResultSet rs = md.getColumns(ref.catalog(), ref.schema(), escape(md, ref.name()), "%")) {
            while (rs.next()) {
                if (!ref.name().equals(rs.getString("TABLE_NAME"))) {
                    continue;
                }
                int decimals = rs.getInt("DECIMAL_DIGITS");
                Integer decimalDigits = rs.wasNull() ? null : decimals;
                columns.add(new ColumnInfo(rs.getString("COLUMN_NAME"), rs.getString("TYPE_NAME"),
                        rs.getInt("COLUMN_SIZE"), decimalDigits,
                        rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls, rs.getString("COLUMN_DEF"),
                        rs.getString("REMARKS")));
            }
        }
        return columns;
    }

    /**
     * Finds tables and columns whose name matches a regular expression (case-insensitive, matches
     * anywhere in the name). On large databases pass a schema, because all columns are scanned.
     * Indexes, sequences, types and system objects are not searched.
     */
    public ListResult<SchemaMatch> searchSchema(Connection conn, String regex, String catalog, String schema)
            throws SQLException {
        Pattern pattern;
        try {
            pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException | NullPointerException e) {
            throw new InvalidRequestException("Invalid regular expression: " + regex);
        }
        DatabaseMetaData md = conn.getMetaData();
        String cat = blankToNull(catalog);
        String sch = blankToNull(schema);
        List<SchemaMatch> items = new ArrayList<>();
        Set<TableRef> skipped = new LinkedHashSet<>(); // indexes, sequences, system objects
        boolean truncated = false;

        try (ResultSet rs = md.getTables(cat, sch, "%", null)) {
            while (rs.next() && !truncated) {
                if (isNoiseType(rs.getString("TABLE_TYPE"))) {
                    skipped.add(ref(rs));
                } else if (pattern.matcher(rs.getString("TABLE_NAME")).find()) {
                    truncated = !add(items, new SchemaMatch(ref(rs), null));
                }
            }
        }
        if (!truncated) {
            try (ResultSet rs = md.getColumns(cat, sch, "%", "%")) {
                while (rs.next() && !truncated) {
                    if (pattern.matcher(rs.getString("COLUMN_NAME")).find() && !skipped.contains(ref(rs))) {
                        truncated = !add(items, new SchemaMatch(ref(rs), rs.getString("COLUMN_NAME")));
                    }
                }
            }
        }
        return new ListResult<>(items, truncated);
    }

    public Relationships getRelationships(Connection conn, String catalog, String schema, String table)
            throws SQLException {
        TableRef ref = resolveTable(conn, catalog, schema, table).table();
        DatabaseMetaData md = conn.getMetaData();
        List<ForeignKey> references = foreignKeys(
                readOptional(() -> md.getImportedKeys(ref.catalog(), ref.schema(), ref.name()), SchemaInspector::keyPart));
        List<ForeignKey> referencedBy = foreignKeys(
                readOptional(() -> md.getExportedKeys(ref.catalog(), ref.schema(), ref.name()), SchemaInspector::keyPart));
        return new Relationships(ref, references, referencedBy);
    }

    // ---------------------------------------------------------------------------------------

    private record KeyPart(String name, TableRef fkTable, String fkColumn, TableRef pkTable, String pkColumn,
            int seq) {
    }

    private static KeyPart keyPart(ResultSet rs) throws SQLException {
        TableRef pk = new TableRef(rs.getString("PKTABLE_CAT"), rs.getString("PKTABLE_SCHEM"),
                rs.getString("PKTABLE_NAME"));
        TableRef fk = new TableRef(rs.getString("FKTABLE_CAT"), rs.getString("FKTABLE_SCHEM"),
                rs.getString("FKTABLE_NAME"));
        return new KeyPart(rs.getString("FK_NAME"), fk, rs.getString("FKCOLUMN_NAME"), pk,
                rs.getString("PKCOLUMN_NAME"), rs.getShort("KEY_SEQ"));
    }

    // Joins the per-column rows of composite keys into one ForeignKey each.
    private static List<ForeignKey> foreignKeys(List<KeyPart> parts) {
        Map<String, List<KeyPart>> groups = new LinkedHashMap<>();
        int unnamedCount = 0;
        int previousSeq = 0;
        for (KeyPart p : parts) {
            String key;
            if (p.name() != null) {
                key = p.fkTable() + "|" + p.pkTable() + "|" + p.name();
            } else {
                // No name to group by: a sequence number that does not increase starts a new key.
                if (p.seq() <= previousSeq || unnamedCount == 0) {
                    unnamedCount++;
                }
                key = "unnamed|" + unnamedCount;
                previousSeq = p.seq();
            }
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }
        List<ForeignKey> result = new ArrayList<>();
        for (List<KeyPart> group : groups.values()) {
            group.sort(Comparator.comparingInt(KeyPart::seq));
            List<String> fkColumns = new ArrayList<>();
            List<String> pkColumns = new ArrayList<>();
            for (KeyPart p : group) {
                fkColumns.add(p.fkColumn());
                pkColumns.add(p.pkColumn());
            }
            KeyPart first = group.get(0);
            result.add(new ForeignKey(first.name(), first.fkTable(), fkColumns, first.pkTable(), pkColumns));
        }
        return result;
    }

    private static List<IndexInfo> indexes(DatabaseMetaData md, TableRef ref) throws SQLException {
        record Part(String index, boolean unique, int position, String column) {
        }
        List<Part> parts = readOptional(() -> md.getIndexInfo(ref.catalog(), ref.schema(), ref.name(), false, true),
                rs -> {
                    if (rs.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) {
                        return null;
                    }
                    return new Part(rs.getString("INDEX_NAME"), !rs.getBoolean("NON_UNIQUE"),
                            rs.getShort("ORDINAL_POSITION"), rs.getString("COLUMN_NAME"));
                });
        Map<String, List<Part>> byIndex = new LinkedHashMap<>();
        for (Part p : parts) {
            if (p != null) {
                byIndex.computeIfAbsent(String.valueOf(p.index()), k -> new ArrayList<>()).add(p);
            }
        }
        List<IndexInfo> result = new ArrayList<>();
        for (List<Part> group : byIndex.values()) {
            group.sort(Comparator.comparingInt(Part::position));
            List<String> columns = new ArrayList<>();
            for (Part p : group) {
                columns.add(p.column());
            }
            result.add(new IndexInfo(group.get(0).index(), group.get(0).unique(), columns));
        }
        return result;
    }

    private boolean add(List<SchemaMatch> items, SchemaMatch match) {
        if (items.size() == maxItems) {
            return false;
        }
        items.add(match);
        return true;
    }

    private <T> ListResult<T> cut(List<T> all) {
        if (all.size() > maxItems) {
            return new ListResult<>(new ArrayList<>(all.subList(0, maxItems)), true);
        }
        return new ListResult<>(all, false);
    }

    private static <T> List<T> readRows(ResultSetSource source, RowMapper<T> mapper) throws SQLException {
        List<T> result = new ArrayList<>();
        try (ResultSet rs = source.open()) {
            while (rs.next()) {
                result.add(mapper.map(rs));
            }
        }
        return result;
    }

    // For metadata that some drivers do not implement: "not supported" is reported as "none".
    private static <T> List<T> readOptional(ResultSetSource source, RowMapper<T> mapper) throws SQLException {
        try {
            return readRows(source, mapper);
        } catch (SQLFeatureNotSupportedException e) {
            return new ArrayList<>();
        }
    }

    /** Table types that are not tables or views of the user: indexes, sequences, types, system objects. */
    static boolean isNoiseType(String type) {
        if (type == null) {
            return false;
        }
        String t = type.toUpperCase(Locale.ROOT);
        return t.startsWith("SYSTEM") || t.contains("INDEX") || t.contains("SEQUENCE") || t.endsWith("TYPE");
    }

    private static TableInfo tableInfo(ResultSet rs) throws SQLException {
        return new TableInfo(ref(rs), rs.getString("TABLE_TYPE"), rs.getString("REMARKS"));
    }

    private static TableRef ref(ResultSet rs) throws SQLException {
        return new TableRef(rs.getString("TABLE_CAT"), rs.getString("TABLE_SCHEM"), rs.getString("TABLE_NAME"));
    }

    private static String display(TableRef ref) {
        StringBuilder sb = new StringBuilder();
        if (ref.catalog() != null && !ref.catalog().isEmpty()) {
            sb.append(ref.catalog()).append('.');
        }
        if (ref.schema() != null && !ref.schema().isEmpty()) {
            sb.append(ref.schema()).append('.');
        }
        return sb.append(ref.name()).toString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    // The name as given, then upper case, then lower case. A null stays a single null candidate.
    private static Set<String> caseVariants(String name) {
        Set<String> result = new LinkedHashSet<>();
        if (name == null) {
            result.add(null);
        } else {
            result.add(name);
            result.add(name.toUpperCase(Locale.ROOT));
            result.add(name.toLowerCase(Locale.ROOT));
        }
        return result;
    }

    // Table names are patterns in JDBC metadata calls: escape _ and % so the name matches literally.
    private static String escape(DatabaseMetaData md, String name) throws SQLException {
        String esc = md.getSearchStringEscape();
        if (esc == null || esc.isEmpty()) {
            return name;
        }
        return name.replace(esc, esc + esc).replace("_", esc + "_").replace("%", esc + "%");
    }
}

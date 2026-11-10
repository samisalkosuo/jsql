package jsql.mcp;

import java.util.List;
import java.util.function.Function;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;
import jsql.core.ListResult;
import jsql.core.QueryService.ColumnProfile;
import jsql.core.QueryService.ExportResult;
import jsql.core.QueryService.QueryResult;
import jsql.core.QueryService.ResultColumn;
import jsql.core.QueryService.RowCountResult;
import jsql.core.QueryService.ValueCount;
import jsql.core.QueryService.Validation;
import jsql.core.SchemaInspector.ColumnInfo;
import jsql.core.SchemaInspector.ForeignKey;
import jsql.core.SchemaInspector.IndexInfo;
import jsql.core.SchemaInspector.Relationships;
import jsql.core.SchemaInspector.SchemaMatch;
import jsql.core.SchemaInspector.SchemaRef;
import jsql.core.SchemaInspector.TableDescription;
import jsql.core.SchemaInspector.TableInfo;
import jsql.core.TableRef;

/**
 * Converts core results to compact JSON text for the AI client. Null values and empty fields are
 * left out where that loses nothing, to keep the answers short. Lists that were cut at the row
 * limit always carry {@code "truncated": true}.
 */
final class ToolJson {

    private ToolJson() {
    }

    static String databases(List<String> catalogs) {
        return Json.createObjectBuilder().add("databases", strings(catalogs)).build().toString();
    }

    static String schemas(ListResult<SchemaRef> result) {
        JsonObjectBuilder b = Json.createObjectBuilder();
        b.add("schemas", array(result.items(), s -> {
            JsonObjectBuilder o = Json.createObjectBuilder();
            put(o, "catalog", s.catalog());
            put(o, "schema", s.schema());
            return o.build();
        }));
        return truncated(b, result.truncated());
    }

    static String tables(ListResult<TableInfo> result) {
        JsonObjectBuilder b = Json.createObjectBuilder();
        b.add("tables", array(result.items(), ToolJson::tableInfo));
        return truncated(b, result.truncated());
    }

    static String description(TableDescription d) {
        return description(d, AnnotationsManager.TableAnnotations.EMPTY);
    }

    static String description(TableDescription d, AnnotationsManager.TableAnnotations annotations) {
        JsonObjectBuilder b = Json.createObjectBuilder();
        b.add("table", tableInfo(d.table(), annotations.notes()));
        b.add("columns", array(d.columns(), c -> column(c, annotations.columnNotes().get(c.name()))));
        b.add("primaryKey", strings(d.primaryKey()));
        b.add("indexes", array(d.indexes(), ToolJson::index));
        return b.build().toString();
    }

    static String matches(ListResult<SchemaMatch> result) {
        JsonObjectBuilder b = Json.createObjectBuilder();
        b.add("matches", array(result.items(), m -> {
            JsonObjectBuilder o = tableRef(m.table());
            put(o, "column", m.column());
            return o.build();
        }));
        return truncated(b, result.truncated());
    }

    static String relationships(Relationships r) {
        JsonObjectBuilder b = Json.createObjectBuilder();
        b.add("table", tableRef(r.table()).build());
        b.add("references", array(r.references(), ToolJson::foreignKey));
        b.add("referencedBy", array(r.referencedBy(), ToolJson::foreignKey));
        return b.build().toString();
    }

    /** Rows as arrays in column order (column names once), SQL NULL as JSON null. */
    static String rows(QueryResult r) {
        JsonObjectBuilder b = Json.createObjectBuilder();
        if (r.updateCount() != null) {
            b.add("updateCount", r.updateCount());
            if (r.elapsedMs() != null) {
                b.add("elapsedMs", r.elapsedMs());
            }
            return b.build().toString();
        }
        b.add("columns", strings(r.columns()));
        if (r.columnTypes() != null && !r.columnTypes().isEmpty()) {
            b.add("columnTypes", strings(r.columnTypes()));
        }
        JsonArrayBuilder rows = Json.createArrayBuilder();
        for (List<String> row : r.rows()) {
            JsonArrayBuilder values = Json.createArrayBuilder();
            for (String v : row) {
                if (v == null) {
                    values.addNull();
                } else {
                    values.add(v);
                }
            }
            rows.add(values);
        }
        b.add("rows", rows);
        b.add("rowCount", r.rows().size());
        if (r.valueTruncated()) {
            b.add("valueTruncated", true);
            b.add("valueTruncatedNote", "Some values were truncated by the server's maxValueLength setting.");
        }
        if (r.elapsedMs() != null) {
            b.add("elapsedMs", r.elapsedMs());
        }
        return truncated(b, r.truncated());
    }

    static String export(ExportResult r) {
        JsonObjectBuilder b = Json.createObjectBuilder();
        b.add("path", r.path());
        b.add("rowsWritten", r.rowsWritten());
        b.add("truncated", r.truncated());
        b.add("columns", strings(r.columns()));
        b.add("elapsedMs", r.elapsedMs());
        return b.build().toString();
    }

    static String rowCount(RowCountResult r) {
        JsonObjectBuilder b = Json.createObjectBuilder();
        b.add("rowCount", r.rowCount());
        b.add("estimated", r.estimated());
        return b.build().toString();
    }

    public record TableLocation(String connection, String catalog, String schema, String table, String type,
            String remarks) {
    }

    static String findTable(List<TableLocation> matches) {
        JsonObjectBuilder b = Json.createObjectBuilder();
        b.add("tables", array(matches, m -> {
            JsonObjectBuilder o = Json.createObjectBuilder();
            put(o, "connection", m.connection());
            put(o, "catalog", m.catalog());
            put(o, "schema", m.schema());
            put(o, "table", m.table());
            put(o, "type", m.type());
            put(o, "remarks", m.remarks());
            return o.build();
        }));
        return b.build().toString();
    }

    static String connections(List<ConnectionConfig> configs) {
        JsonObjectBuilder b = Json.createObjectBuilder();
        b.add("connections", array(configs, c -> {
            JsonObjectBuilder o = Json.createObjectBuilder();
            o.add("name", c.name());
            o.add("jdbcUrl", c.redactedJdbcUrl());
            o.add("readOnly", c.readOnly());
            o.add("isDefault", c.isDefault());
            return o.build();
        }));
        return b.build().toString();
    }

    static String savedQueries(List<SavedQueriesManager.SavedQuery> list) {
        JsonObjectBuilder b = Json.createObjectBuilder();
        b.add("savedQueries", array(list, q -> {
            JsonObjectBuilder o = Json.createObjectBuilder();
            o.add("name", q.name());
            put(o, "description", q.description());
            put(o, "sql", q.sql());
            put(o, "connection", q.connection());
            if (q.parameters() != null && !q.parameters().isEmpty()) {
                o.add("parameters", strings(q.parameters()));
            }
            return o.build();
        }));
        return b.build().toString();
    }

    static String savedQuery(SavedQueriesManager.SavedQuery q) {
        JsonObjectBuilder o = Json.createObjectBuilder();
        o.add("name", q.name());
        put(o, "description", q.description());
        put(o, "sql", q.sql());
        put(o, "connection", q.connection());
        if (q.parameters() != null && !q.parameters().isEmpty()) {
            o.add("parameters", strings(q.parameters()));
        }
        return o.build().toString();
    }

    static String profile(ColumnProfile p) {
        JsonObjectBuilder b = Json.createObjectBuilder();
        b.add("column", p.column());
        b.add("type", p.typeName() == null ? "" : p.typeName());
        b.add("rowCount", p.rowCount());
        b.add("nullCount", p.nullCount());
        if (p.distinctCount() != null) {
            b.add("distinctCount", p.distinctCount());
        }
        if (p.min() != null) {
            b.add("min", p.min());
        }
        if (p.max() != null) {
            b.add("max", p.max());
        }
        JsonArrayBuilder top = Json.createArrayBuilder();
        for (ValueCount v : p.topValues()) {
            JsonObjectBuilder o = Json.createObjectBuilder();
            if (v.value() == null) {
                o.addNull("value");
            } else {
                o.add("value", v.value());
            }
            o.add("count", v.count());
            top.add(o);
        }
        b.add("topValues", top);
        if (!p.notes().isEmpty()) {
            b.add("notes", strings(p.notes()));
        }
        return b.build().toString();
    }

    static String validation(Validation v) {
        JsonObjectBuilder b = Json.createObjectBuilder();
        b.add("valid", v.valid());
        if (!v.valid()) {
            put(b, "error", v.error());
            put(b, "sqlState", v.sqlState());
        } else {
            JsonArrayBuilder columns = Json.createArrayBuilder();
            for (ResultColumn c : v.columns()) {
                columns.add(Json.createObjectBuilder().add("name", c.name()).add("type",
                        c.typeName() == null ? "" : c.typeName()));
            }
            b.add("columns", columns);
            if (v.parameterCount() >= 0) {
                b.add("parameterCount", v.parameterCount());
            }
        }
        return b.build().toString();
    }

    // ---------------------------------------------------------------------------------------

    private static String truncated(JsonObjectBuilder b, boolean truncated) {
        b.add("truncated", truncated);
        if (truncated) {
            b.add("note", "More results exist than were returned. Narrow the request with filters.");
        }
        return b.build().toString();
    }

    private static JsonObject tableInfo(TableInfo t) {
        return tableInfo(t, null);
    }

    private static JsonObject tableInfo(TableInfo t, String notes) {
        JsonObjectBuilder o = tableRef(t.table());
        put(o, "type", t.type());
        put(o, "remarks", t.remarks());
        put(o, "notes", notes);
        return o.build();
    }

    private static JsonObjectBuilder tableRef(TableRef t) {
        JsonObjectBuilder o = Json.createObjectBuilder();
        put(o, "catalog", t.catalog());
        put(o, "schema", t.schema());
        o.add("table", t.name());
        return o;
    }

    private static JsonObject column(ColumnInfo c) {
        return column(c, null);
    }

    private static JsonObject column(ColumnInfo c, String annotationNotes) {
        JsonObjectBuilder o = Json.createObjectBuilder();
        o.add("name", c.name());
        o.add("type", c.typeName() == null ? "" : c.typeName());
        o.add("size", c.size());
        if (c.decimalDigits() != null) {
            o.add("decimalDigits", c.decimalDigits());
        }
        o.add("nullable", c.nullable());
        put(o, "default", c.defaultValue());
        put(o, "remarks", c.remarks());
        put(o, "notes", annotationNotes);
        return o.build();
    }

    private static JsonObject index(IndexInfo i) {
        JsonObjectBuilder o = Json.createObjectBuilder();
        put(o, "name", i.name());
        o.add("unique", i.unique());
        o.add("columns", strings(i.columns()));
        return o.build();
    }

    private static JsonObject foreignKey(ForeignKey fk) {
        JsonObjectBuilder o = Json.createObjectBuilder();
        put(o, "name", fk.name());
        o.add("fromTable", tableRef(fk.fkTable()).build());
        o.add("fromColumns", strings(fk.fkColumns()));
        o.add("toTable", tableRef(fk.pkTable()).build());
        o.add("toColumns", strings(fk.pkColumns()));
        return o.build();
    }

    private static void put(JsonObjectBuilder o, String name, String value) {
        if (value != null && !value.isEmpty()) {
            o.add(name, value);
        }
    }

    private static JsonArray strings(List<String> values) {
        JsonArrayBuilder a = Json.createArrayBuilder();
        for (String v : values) {
            a.add(v == null ? JsonValue.NULL : Json.createValue(v));
        }
        return a.build();
    }

    private static <T> JsonArray array(List<T> items, Function<T, JsonObject> mapper) {
        JsonArrayBuilder a = Json.createArrayBuilder();
        for (T item : items) {
            a.add(mapper.apply(item));
        }
        return a.build();
    }
}

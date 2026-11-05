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
import jsql.core.QueryService.QueryResult;
import jsql.core.QueryService.ResultColumn;
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
        JsonObjectBuilder b = Json.createObjectBuilder();
        b.add("table", tableInfo(d.table()));
        b.add("columns", array(d.columns(), ToolJson::column));
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
            return b.build().toString();
        }
        b.add("columns", strings(r.columns()));
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
        return truncated(b, r.truncated());
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
        JsonObjectBuilder o = tableRef(t.table());
        put(o, "type", t.type());
        put(o, "remarks", t.remarks());
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

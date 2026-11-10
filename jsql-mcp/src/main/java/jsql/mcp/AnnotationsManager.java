package jsql.mcp;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;

/**
 * Loads user-supplied annotations/notes from a local file (e.g. annotations.json) to augment
 * database schema remarks with business context, special values, and join advice.
 */
@ApplicationScoped
public class AnnotationsManager {

    private static final Logger LOG = Logger.getLogger(AnnotationsManager.class.getName());

    public record TableAnnotations(String notes, Map<String, String> columnNotes) {
        public static final TableAnnotations EMPTY = new TableAnnotations(null, Collections.emptyMap());
    }

    @Inject
    ServerState state;

    public TableAnnotations getAnnotations(String connectionName, String catalog, String schema, String table) {
        try {
            McpConfig config = state.config();
            String filePath = config.annotationsFile();
            if (filePath == null || filePath.isBlank()) {
                return TableAnnotations.EMPTY;
            }
            Path path = Paths.get(filePath);
            if (!Files.exists(path) || !Files.isRegularFile(path)) {
                return TableAnnotations.EMPTY;
            }
            try (InputStream in = Files.newInputStream(path); JsonReader reader = Json.createReader(in)) {
                JsonObject root = reader.readObject();
                return findTableAnnotations(root, connectionName, catalog, schema, table);
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "Could not load annotations file", e);
            return TableAnnotations.EMPTY;
        }
    }

    private TableAnnotations findTableAnnotations(JsonObject root, String connectionName, String catalog,
            String schema, String table) {
        if (root == null || table == null) {
            return TableAnnotations.EMPTY;
        }
        String tableNameLower = table.toLowerCase(Locale.ROOT);
        String schemaTable = (schema != null ? schema.toLowerCase(Locale.ROOT) + "." : "") + tableNameLower;

        JsonObject tables = null;
        if (root.containsKey("tables") && root.get("tables").getValueType() == JsonValue.ValueType.OBJECT) {
            tables = root.getJsonObject("tables");
        } else if (connectionName != null && root.containsKey("connections")) {
            JsonObject conns = root.getJsonObject("connections");
            if (conns != null && conns.containsKey(connectionName.toLowerCase(Locale.ROOT))) {
                JsonObject connObj = conns.getJsonObject(connectionName.toLowerCase(Locale.ROOT));
                if (connObj != null && connObj.containsKey("tables")) {
                    tables = connObj.getJsonObject("tables");
                }
            }
        }

        if (tables == null) {
            return TableAnnotations.EMPTY;
        }

        JsonObject tableEntry = null;
        for (String key : tables.keySet()) {
            String keyLower = key.toLowerCase(Locale.ROOT);
            if (keyLower.equals(schemaTable) || keyLower.equals(tableNameLower)) {
                tableEntry = tables.getJsonObject(key);
                break;
            }
        }

        if (tableEntry == null) {
            return TableAnnotations.EMPTY;
        }

        String tableNotes = tableEntry.containsKey("notes") ? tableEntry.getString("notes") : null;
        Map<String, String> columnNotes = new HashMap<>();
        if (tableEntry.containsKey("columns")
                && tableEntry.get("columns").getValueType() == JsonValue.ValueType.OBJECT) {
            JsonObject cols = tableEntry.getJsonObject("columns");
            for (String colName : cols.keySet()) {
                JsonValue val = cols.get(colName);
                if (val.getValueType() == JsonValue.ValueType.STRING) {
                    columnNotes.put(colName.toUpperCase(Locale.ROOT), cols.getString(colName));
                }
            }
        }
        return new TableAnnotations(tableNotes, columnNotes);
    }
}

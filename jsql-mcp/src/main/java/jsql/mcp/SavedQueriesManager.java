package jsql.mcp;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import jakarta.json.JsonWriter;
import jsql.core.InvalidRequestException;
import jsql.core.NamedSql;

/**
 * Manages stored query definitions in a configured local directory.
 */
@ApplicationScoped
public class SavedQueriesManager {

    private static final Pattern SAFE_NAME = Pattern.compile("^[A-Za-z0-9_-]+$");

    public record SavedQuery(String name, String description, String sql, List<String> parameters, String connection) {
    }

    @Inject
    ServerState state;

    public List<SavedQuery> listQueries(String connectionFilter) {
        Path dir = getQueriesDirectory();
        if (dir == null || !Files.exists(dir) || !Files.isDirectory(dir)) {
            return Collections.emptyList();
        }
        List<SavedQuery> list = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(p -> {
                try {
                    SavedQuery sq = loadQueryFile(p);
                    if (sq != null) {
                        if (connectionFilter == null || connectionFilter.isBlank()
                                || (sq.connection() != null && sq.connection().equalsIgnoreCase(connectionFilter))) {
                            list.add(sq);
                        }
                    }
                } catch (Exception ignore) {
                }
            });
        } catch (Exception e) {
            // ignore error reading dir
        }
        return list;
    }

    public SavedQuery getQuery(String name) {
        validateName(name);
        Path dir = getQueriesDirectory();
        if (dir == null || !Files.exists(dir)) {
            throw new InvalidRequestException("Saved query not found: " + name);
        }
        Path file = dir.resolve(name + ".json").normalize();
        if (!file.startsWith(dir) || !Files.exists(file)) {
            throw new InvalidRequestException("Saved query not found: " + name);
        }
        try {
            return loadQueryFile(file);
        } catch (Exception e) {
            throw new InvalidRequestException("Error reading saved query '" + name + "': " + e.getMessage());
        }
    }

    public void saveQuery(String name, String sql, String description, List<String> parameters, String connection,
            boolean overwrite) {
        validateName(name);
        if (sql == null || sql.isBlank()) {
            throw new InvalidRequestException("SQL is required.");
        }
        Path dir = getQueriesDirectory();
        if (dir == null) {
            throw new InvalidRequestException("Saved queries directory is not configured.");
        }
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(name + ".json").normalize();
            if (!file.startsWith(dir)) {
                throw new InvalidRequestException("Invalid query name: " + name);
            }
            if (Files.exists(file) && !overwrite) {
                throw new InvalidRequestException("Saved query '" + name + "' already exists. Set overwrite=true to replace.");
            }

            List<String> params = parameters;
            if (params == null || params.isEmpty()) {
                NamedSql parsed = NamedSql.parse(sql);
                params = parsed.parameterNames();
            }

            JsonObjectBuilder b = Json.createObjectBuilder();
            b.add("name", name);
            if (description != null && !description.isBlank()) {
                b.add("description", description);
            }
            b.add("sql", sql);
            if (connection != null && !connection.isBlank()) {
                b.add("connection", connection);
            }
            if (params != null && !params.isEmpty()) {
                JsonArrayBuilder pa = Json.createArrayBuilder();
                params.forEach(pa::add);
                b.add("parameters", pa);
            }

            try (OutputStream out = Files.newOutputStream(file); JsonWriter writer = Json.createWriter(out)) {
                writer.writeObject(b.build());
            }
        } catch (InvalidRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidRequestException("Could not save query '" + name + "': " + e.getMessage(), e);
        }
    }

    private Path getQueriesDirectory() {
        McpConfig config = state.config();
        String dirName = config.savedQueriesDir();
        if (dirName == null || dirName.isBlank()) {
            return null;
        }
        return Paths.get(dirName).toAbsolutePath().normalize();
    }

    private void validateName(String name) {
        if (name == null || name.isBlank() || !SAFE_NAME.matcher(name).matches()) {
            throw new InvalidRequestException(
                    "Query name must contain only letters, numbers, hyphens and underscores: '" + name + "'");
        }
    }

    private SavedQuery loadQueryFile(Path file) throws Exception {
        try (InputStream in = Files.newInputStream(file); JsonReader reader = Json.createReader(in)) {
            JsonObject obj = reader.readObject();
            String name = obj.getString("name", file.getFileName().toString().replace(".json", ""));
            String desc = obj.containsKey("description") ? obj.getString("description") : null;
            String sql = obj.getString("sql", "");
            String conn = obj.containsKey("connection") ? obj.getString("connection") : null;
            List<String> params = new ArrayList<>();
            if (obj.containsKey("parameters") && obj.get("parameters").getValueType() == JsonValue.ValueType.ARRAY) {
                JsonArray arr = obj.getJsonArray("parameters");
                for (int i = 0; i < arr.size(); i++) {
                    params.add(arr.getString(i));
                }
            }
            return new SavedQuery(name, desc, sql, params, conn);
        }
    }
}

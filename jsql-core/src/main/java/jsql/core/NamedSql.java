package jsql.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Utility to parse SQL statements containing named parameters (e.g. {@code :param_name}) and
 * translate them into standard JDBC positional parameter SQL (with {@code ?}) while extracting the
 * parameter names in order.
 *
 * <p>
 * String literals, comments and double-quoted identifiers are ignored so colons inside them are not
 * treated as parameter placeholders.
 */
public final class NamedSql {

    private final String sqlWithPlaceholders;
    private final List<String> parameterNames;

    public NamedSql(String sqlWithPlaceholders, List<String> parameterNames) {
        this.sqlWithPlaceholders = sqlWithPlaceholders;
        this.parameterNames = List.copyOf(parameterNames);
    }

    public String sql() {
        return sqlWithPlaceholders;
    }

    public List<String> parameterNames() {
        return parameterNames;
    }

    /**
     * Parses the SQL string and replaces {@code :identifier} with {@code ?}.
     */
    public static NamedSql parse(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new InvalidRequestException("SQL is empty.");
        }
        StringBuilder out = new StringBuilder();
        List<String> names = new ArrayList<>();
        int n = sql.length();
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                int end = skipQuoted(sql, i, c);
                out.append(sql, i, end);
                i = end;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i);
                if (end < 0) {
                    end = n;
                }
                out.append(sql, i, end);
                i = end;
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) {
                    end = n;
                } else {
                    end += 2;
                }
                out.append(sql, i, end);
                i = end;
            } else if (c == ':' && i + 1 < n && isIdentifierStart(sql.charAt(i + 1))) {
                // If :: (PostgreSQL type cast e.g. ::int), don't treat as parameter
                if (i > 0 && sql.charAt(i - 1) == ':') {
                    out.append(c);
                    i++;
                    continue;
                }
                if (i + 1 < n && sql.charAt(i + 1) == ':') {
                    out.append("::");
                    i += 2;
                    continue;
                }
                int start = i + 1;
                int end = start;
                while (end < n && isIdentifierPart(sql.charAt(end))) {
                    end++;
                }
                String name = sql.substring(start, end);
                names.add(name);
                out.append('?');
                i = end;
            } else {
                out.append(c);
                i++;
            }
        }
        return new NamedSql(out.toString(), names);
    }

    /**
     * Extracts values for the parameters in this query from a case-insensitive parameter map.
     */
    public List<Object> bindValues(Map<String, ?> values) {
        if (parameterNames.isEmpty()) {
            return List.of();
        }
        List<Object> result = new ArrayList<>(parameterNames.size());
        for (String paramName : parameterNames) {
            Object val = findValue(values, paramName);
            result.add(val);
        }
        return result;
    }

    private static Object findValue(Map<String, ?> values, String key) {
        if (values == null) {
            return null;
        }
        if (values.containsKey(key)) {
            return values.get(key);
        }
        for (Map.Entry<String, ?> e : values.entrySet()) {
            if (e.getKey().equalsIgnoreCase(key)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static boolean isIdentifierStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static boolean isIdentifierPart(char c) {
        return isIdentifierStart(c) || (c >= '0' && c <= '9');
    }

    private static int skipQuoted(String sql, int start, char quote) {
        int n = sql.length();
        int j = start + 1;
        while (j < n) {
            char c = sql.charAt(j);
            if (c == quote) {
                if (j + 1 < n && sql.charAt(j + 1) == quote) {
                    j += 2;
                    continue;
                }
                return j + 1;
            }
            j++;
        }
        return n;
    }
}

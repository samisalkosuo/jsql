package jsql.core;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort check that a statement only reads data. This is NOT a security boundary: it cannot
 * detect functions with side effects (for example sequence functions). The database user's
 * permissions are the real control; combine this with a read-only connection and account.
 */
final class SqlGuard {

    private static final Set<String> ALLOWED_FIRST_WORD = Set.of("SELECT", "WITH", "EXPLAIN", "SHOW", "DESCRIBE",
            "DESC", "VALUES");

    private static final Set<String> FORBIDDEN_WORDS = Set.of("INSERT", "UPDATE", "DELETE", "MERGE", "DROP", "ALTER",
            "CREATE", "TRUNCATE", "GRANT", "REVOKE", "CALL", "EXEC", "EXECUTE", "INTO");

    private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_$#]*");

    private static final Pattern DOLLAR_QUOTE = Pattern.compile("\\$[A-Za-z_]*\\$");

    private SqlGuard() {
    }

    private static final Set<String> EXPLAINABLE_FIRST_WORD = Set.of("SELECT", "WITH", "INSERT", "UPDATE", "DELETE",
            "MERGE", "VALUES");

    /**
     * Checks that the text is a single statement that can be put after EXPLAIN without risk that
     * the database executes it (PostgreSQL runs the statement for {@code EXPLAIN ANALYZE}). Returns
     * the statement without trailing semicolons.
     */
    static String requireExplainable(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new InvalidRequestException("SQL is empty.");
        }
        if (DOLLAR_QUOTE.matcher(sql).find() || sql.contains("/*!")) {
            throw new InvalidRequestException("This kind of quoting or comment is not supported by explain.");
        }
        String cleaned = sql.trim();
        while (cleaned.endsWith(";")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }
        if (strip(cleaned).indexOf(';') >= 0) {
            throw new InvalidRequestException("Only a single statement can be explained.");
        }
        Matcher m = WORD.matcher(cleaned);
        if (!m.find() || m.start() != 0 || !EXPLAINABLE_FIRST_WORD.contains(m.group().toUpperCase(Locale.ROOT))) {
            throw new InvalidRequestException(
                    "Explain needs a statement that starts with SELECT, WITH, INSERT, UPDATE, DELETE, MERGE or VALUES"
                            + " (do not include EXPLAIN or options).");
        }
        return cleaned;
    }

    static void requireReadOnly(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new InvalidRequestException("SQL is empty.");
        }
        // Constructs that make quoting ambiguous between databases are rejected, because the
        // scanner below could then be tricked into treating code as a string or comment.
        if (DOLLAR_QUOTE.matcher(sql).find()) {
            throw new InvalidRequestException("Dollar-quoted strings are not supported in read-only mode.");
        }
        if (sql.contains("/*!")) {
            throw new InvalidRequestException("Executable comments are not supported in read-only mode.");
        }

        String s = strip(sql).trim();
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        if (s.indexOf(';') >= 0) {
            throw new InvalidRequestException("Multiple statements are not allowed in read-only mode.");
        }

        Matcher m = WORD.matcher(s);
        boolean first = true;
        while (m.find()) {
            String word = m.group().toUpperCase(Locale.ROOT);
            if (first) {
                if (!ALLOWED_FIRST_WORD.contains(word)) {
                    throw notAllowed(word);
                }
                first = false;
            }
            if (FORBIDDEN_WORDS.contains(word)) {
                throw notAllowed(word);
            }
        }
        if (first) {
            throw new InvalidRequestException("Only read-only statements are allowed in read-only mode.");
        }
    }

    private static InvalidRequestException notAllowed(String word) {
        return new InvalidRequestException(
                "Statement not allowed in read-only mode (found " + word + "). Only SELECT, WITH, EXPLAIN, SHOW,"
                        + " DESCRIBE and VALUES statements are allowed.");
    }

    // Replaces string literals, quoted identifiers and comments with a space.
    private static String strip(String sql) {
        StringBuilder out = new StringBuilder();
        int n = sql.length();
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                i = skipQuoted(sql, i, c);
                out.append(' ');
            } else if (c == '[') {
                int end = sql.indexOf(']', i + 1);
                i = end < 0 ? n : end + 1;
                out.append(' ');
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i);
                i = end < 0 ? n : end + 1;
                out.append(' ');
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                out.append(' ');
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    // Returns the index after the closing quote. A doubled quote is an escaped quote.
    private static int skipQuoted(String sql, int start, char quote) {
        int n = sql.length();
        int j = start + 1;
        while (j < n) {
            char c = sql.charAt(j);
            if (c == '\\') {
                // Backslash escapes work in some databases and not in others, so the end of the
                // literal cannot be determined reliably.
                throw new InvalidRequestException("Backslashes in quoted text are not supported in read-only mode.");
            }
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

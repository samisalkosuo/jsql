package jsql.core;

import java.sql.Blob;
import java.sql.Clob;
import java.sql.SQLException;
import java.sql.SQLXML;

/** Converts JDBC values to strings that are safe to return: LOBs are summarised, long text is cut. */
final class ValueFormatter {

    private ValueFormatter() {
    }

    /** Returns null for SQL NULL. */
    static String format(Object value, int maxLength) throws SQLException {
        if (value == null) {
            return null;
        }
        String s;
        if (value instanceof Clob clob) {
            long length = clob.length();
            s = clob.getSubString(1, (int) Math.min(length, maxLength + 1L));
        } else if (value instanceof Blob blob) {
            return "<BLOB " + blob.length() + " bytes>";
        } else if (value instanceof byte[] bytes) {
            return "<binary " + bytes.length + " bytes>";
        } else if (value instanceof SQLXML xml) {
            s = xml.getString();
        } else {
            s = value.toString();
        }
        if (s.length() > maxLength) {
            return s.substring(0, maxLength) + "...[truncated]";
        }
        return s;
    }
}

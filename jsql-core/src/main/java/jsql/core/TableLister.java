package jsql.core;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/** Lists table names using JDBC {@link DatabaseMetaData}. */
public final class TableLister {

    private TableLister() {
    }

    /** Returns the names of all tables, views, etc. in the database, sorted. */
    public static List<String> allTableNames(DatabaseMetaData databaseMetaData) throws SQLException {
        // TABLE_TYPE String => table type. Typical types are "TABLE", "VIEW", "SYSTEM
        // TABLE", "GLOBAL TEMPORARY", "LOCAL TEMPORARY", "ALIAS", "SYNONYM".
        List<String> tableList = new ArrayList<>();
        try (ResultSet tables = databaseMetaData.getTables(null, null, "%", null)) {
            while (tables.next()) {
                tableList.add(tables.getString(3));
            }
        }
        Collections.sort(tableList);
        return tableList;
    }

    /** Returns names that contain a match for the regex. A null regex returns all names. */
    public static List<String> filter(List<String> tableNames, String regex) {
        if (regex == null) {
            return tableNames;
        }
        Pattern p = Pattern.compile(regex);
        List<String> result = new ArrayList<>();
        for (String tableName : tableNames) {
            if (p.matcher(tableName).find()) {
                result.add(tableName);
            }
        }
        return result;
    }
}

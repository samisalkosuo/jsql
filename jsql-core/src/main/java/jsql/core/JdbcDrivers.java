package jsql.core;

import java.util.function.Consumer;

/**
 * Loads the JDBC drivers bundled with the application.
 * Drivers that are not on the classpath are reported as warnings, not errors.
 */
public final class JdbcDrivers {

    private static final String[] DRIVER_CLASSES = {
            "com.ibm.db2.jcc.DB2Jcc",
            "org.postgresql.Driver",
            "com.informix.jdbc.IfxDriver",
            "oracle.jdbc.driver.OracleDriver",
            "com.mysql.cj.jdbc.Driver",
            "org.mariadb.jdbc.Driver",
            "com.microsoft.sqlserver.jdbc.SQLServerDriver",
            "com.facebook.presto.jdbc.PrestoDriver" };

    private JdbcDrivers() {
    }

    /** Tries to load all known drivers; each failure is reported to warnings. */
    public static void load(Consumer<String> warnings) {
        for (String driverClass : DRIVER_CLASSES) {
            try {
                Class.forName(driverClass);
            } catch (Exception e) {
                warnings.accept("WARNING: " + e.toString());
            }
        }
    }
}

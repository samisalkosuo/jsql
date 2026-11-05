package jsql;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.Callable;

import jsql.core.ConnectionSettings;
import jsql.core.JdbcDrivers;
import jsql.core.QueryExecutor;
import jsql.core.TableLister;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "jsql", mixinStandardHelpOptions = true, version = "jsql 1.0", description = "JDBC/SQL helper tool for databases.")
class JSQLMain implements Callable<Integer> {

    // @Parameters(index = "0", description = "The file whose checksum to
    // calculate.")
    // private File file;

    @Option(names = { "-j", "--jdbc" }, required = false, description = "JDBC URL.")
    private String jdbcUrl = System.getenv("JSQL_JDBC_URL");

    @Option(names = { "-u", "--user" }, required = false, description = "User name.")
    private String userName = System.getenv("JSQL_USER_NAME");

    @Option(names = { "-p", "--password" }, required = false, description = "User password.")
    private String userPassword = System.getenv("JSQL_USER_PASSWORD");

    @Option(names = { "-r",
            "--regex" }, required = false, description = "Regex for tables. List tables that match given regex.")
    private String regexString = null;

    @Option(names = { "-s", "--sql" }, required = false, description = "SQL statement to be executed.")
    private String sqlString = "";

    @Option(names = { "-S",
            "--stacktrace" }, required = false, description = "Print full exception stack trace, if any.")
    private boolean printStackTrace = false;

    @Option(names = { "-t",
            "--tables" }, required = false, description = "Print all tables in the database.")
    private boolean printTables = false;

    @Option(names = { "-q",
            "--quiet" }, required = false, description = "Do not print output, except errors.")
    private boolean doNotPrintOutput = false;

    @Option(names = { "-k",
            "--insecure" }, required = false, description = "Accept all TLS/SSL certificates, including self-signed. Disables server certificate validation (insecure). Supported for Presto, SQL Server, PostgreSQL, MySQL and MariaDB.")
    private boolean insecure = false;

    // returns status code
    @Override
    public Integer call() throws Exception {

        if (jdbcUrl == null) {
            System.out.println("JDBC URL is missing. Use -j option or JSQL_JDBC_URL environment variable.");
            return 2;
        }

        if (userName == null) {
            System.out.println("User name is missing. Use -u option or JSQL_USER_NAME environment variable.");
            return 3;
        }

        if (userPassword == null) {
            System.out.println("User password is missing. Use -p option or JSQL_USER_PASSWORD environment variable.");
            return 4;
        }

        ConnectionSettings settings = new ConnectionSettings(jdbcUrl, userName, userPassword, insecure);

        // Get a connection
        try (Connection conn = settings.connect(this::println)) {
            DatabaseMetaData databaseMetaData = conn.getMetaData();
            println("Connection established successfully.");

            // printDatabaseInfo(databaseMetaData);

            if (printTables == true) {
                printTables(databaseMetaData);
            }

            if (sqlString != null && !sqlString.equals("")) {
                executeSQL(conn);
            }
        } catch (Exception e) {
            if (printStackTrace == true) {
                e.printStackTrace();

            } else {
                System.out.println(e.toString());
            }
            return 1;
        }
        println("Connection closed.");
        return 0;
    }

    private void printDatabaseInfo(DatabaseMetaData databaseMetaData) throws SQLException {
        String productName = databaseMetaData.getDatabaseProductName();
        String productVersion = databaseMetaData.getDatabaseProductVersion();
        int dbMajorVersion = databaseMetaData.getDatabaseMajorVersion();
        int dbMinorVersion = databaseMetaData.getDatabaseMinorVersion();
        String dbUserName = databaseMetaData.getUserName();
        String driverName = databaseMetaData.getDriverName();
        String driverVersion = databaseMetaData.getDriverVersion();
        String dbUrl = databaseMetaData.getURL();
        int jdbcMajorVersion = databaseMetaData.getJDBCMajorVersion();
        int jdbcMinorVersion = databaseMetaData.getJDBCMinorVersion();
        println("Product name    : " + productName);
        println("Product version : " + productVersion);
        println("Database version: " + dbMajorVersion + "." + dbMinorVersion);
        println("Driver name     : " + driverName);
        println("Driver version  : " + driverVersion);
        println("JDBC URL        : " + dbUrl);
        println("JDBC version    : " + jdbcMajorVersion + "." + jdbcMinorVersion);
        println("User name       : " + dbUserName);
    }

    private void printTables(DatabaseMetaData databaseMetaData) throws SQLException {
        String headline = "\nTables:";
        if (regexString != null) {
            headline = "\nTables (" + regexString + "):";
        }
        System.out.println(headline);

        List<String> tableList = TableLister.allTableNames(databaseMetaData);
        if (tableList.size() == 0) {
            println("No tables in the database.");
        } else {
            for (String tableName : TableLister.filter(tableList, regexString)) {
                println(tableName);
            }
        }
        println();

    }

    private void executeSQL(Connection conn) throws SQLException {
        println();
        println("SQL:");
        println(sqlString);
        println();
        QueryExecutor.execute(conn, sqlString, new QueryExecutor.Handler() {
            @Override
            public void columns(List<String> columnNames) {
                println(String.join(",", columnNames));
            }

            @Override
            public void row(List<String> values) {
                println(String.join(",", values));
            }

            @Override
            public void updateCount(int updateCount) {
                println("Result: " + updateCount);
            }
        });
        println();

    }

    private void println() {
        println("");
    }

    private void println(String msg) {
        if (doNotPrintOutput == false) {
            System.out.println(msg);
        }

    }

    public static void main(String... args) {

        JSQLMain jsqlMain = new JSQLMain();
        JdbcDrivers.load(System.out::println);
        int exitCode = new CommandLine(jsqlMain).execute(args);
        if (exitCode == 2 || exitCode == 3 || exitCode == 4) {
            System.out.print("Usage: ");
            System.out.println(new CommandLine(jsqlMain).getHelp().synopsis(exitCode));
        }
        System.exit(exitCode);
    }
}

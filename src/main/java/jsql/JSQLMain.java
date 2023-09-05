package jsql;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

        // Set user and password properties
        Properties properties = new Properties();
        properties.put("user", userName);
        properties.put("password", userPassword);
        properties.put("retreiveMessagesFromServerOnGetMessage", "true");
        // Get a connection
        try {
            Connection conn = DriverManager.getConnection(jdbcUrl, properties);
            DatabaseMetaData databaseMetaData = conn.getMetaData();
            println("Connection established successfully.");

            printDatabaseInfo(databaseMetaData);

            if (printTables == true) {
                printTables(databaseMetaData);
            }

            if (sqlString != null && !sqlString.equals("")) {
                executeSQL(conn);
            }

            conn.close(); // close connection
            println("Connection closed.");
            return 0;
        } catch (Exception e) {
            if (printStackTrace == true) {
                e.printStackTrace();

            } else {
                System.out.println(e.toString());
            }
            return 1;
        }
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
        String[] types = null;

        String headline = "\nTables:";
        if (regexString != null) {
            headline = "\nTables (" + regexString + "):";
        }
        System.out.println(headline);
        // TABLE_TYPE String => table type. Typical types are "TABLE", "VIEW", "SYSTEM
        // TABLE", "GLOBAL TEMPORARY", "LOCAL TEMPORARY", "ALIAS", "SYNONYM".
        // Retrieving the columns in the database
        ResultSet tables = databaseMetaData.getTables(null, null, "%", types);
        ArrayList<String> tableList = new ArrayList<String>();
        while (tables.next()) {
            tableList.add(tables.getString(3));

        }
        if (tableList.size() == 0) {
            println("No tables in the database.");
        } else {
            Collections.sort(tableList);
            Pattern p = null;
            if (regexString != null) {
                p = Pattern.compile(regexString);
            }
            for (String tableName : tableList) {
                if (regexString != null) {

                    Matcher m = p.matcher(tableName);
                    if (m.find() == true) {
                        println(tableName);
                    }
                } else {
                    println(tableName);

                }

            }
        }
        println();

    }

    private void executeSQL(Connection conn) throws SQLException {
        println();
        println("SQL:");
        println(sqlString);
        println();
        Statement st = conn.createStatement();
        st.execute(sqlString);
        ResultSet rs = st.getResultSet();
        if (rs == null) {
            println("Result: " + st.getUpdateCount());
        } else {
            ResultSetMetaData rsmd = rs.getMetaData();
            ArrayList<String> columnNames = new ArrayList<String>();
            int columnCount = rsmd.getColumnCount();
            for (int i = 0; i < columnCount; i++) {
                columnNames.add(rsmd.getColumnName(i + 1));

            }

            println(String.join(",", columnNames));

            while (rs.next()) {
                ArrayList<String> columnValues = new ArrayList<String>();
                for (int i = 0; i < columnCount; i++) {
                    columnValues.add(rs.getObject(i + 1).toString());
                }
                println(String.join(",", columnValues));

            }
        }
        st.close(); // close statement
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

    private void loadDrivers() {
        try {
            Class.forName("com.ibm.db2.jcc.DB2Jcc");
            Class.forName("org.postgresql.Driver");
            Class.forName("com.informix.jdbc.IfxDriver");
            Class.forName("oracle.jdbc.driver.OracleDriver");
            Class.forName("com.mysql.cj.jdbc.Driver");
            
        } catch (Exception e) {
            System.out.println("WARNING: " + e.toString());
        }

    }

    public static void main(String... args) {

        JSQLMain jsqlMain = new JSQLMain();
        jsqlMain.loadDrivers();
        int exitCode = new CommandLine(jsqlMain).execute(args);
        if (exitCode == 2 || exitCode == 3 || exitCode == 4) {
            System.out.print("Usage: ");
            System.out.println(new CommandLine(jsqlMain).getHelp().synopsis(exitCode));
        }
        System.exit(exitCode);
    }
}
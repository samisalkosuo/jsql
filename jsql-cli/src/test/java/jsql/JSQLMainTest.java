package jsql;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import picocli.CommandLine;

public class JSQLMainTest {

    private static final String URL = "jdbc:h2:mem:clitest;DB_CLOSE_DELAY=-1";

    private final PrintStream originalOut = System.out;
    private ByteArrayOutputStream captured;

    @Before
    public void captureOutput() {
        captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true));
    }

    @After
    public void restoreOutput() {
        System.setOut(originalOut);
    }

    private int run(String... args) {
        return new CommandLine(new JSQLMain()).execute(args);
    }

    private String output() {
        return captured.toString().replace("\r\n", "\n");
    }

    @Test
    public void missingJdbcUrlReturns2() {
        // Env vars are not set in the test environment; pass nothing to force the path.
        if (System.getenv("JSQL_JDBC_URL") != null) {
            return;
        }
        assertEquals(2, run());
        assertTrue(output().contains("JDBC URL is missing"));
    }

    @Test
    public void missingUserReturns3() {
        if (System.getenv("JSQL_USER_NAME") != null) {
            return;
        }
        assertEquals(3, run("-j", URL));
        assertTrue(output().contains("User name is missing"));
    }

    @Test
    public void missingPasswordReturns4() {
        if (System.getenv("JSQL_USER_PASSWORD") != null) {
            return;
        }
        assertEquals(4, run("-j", URL, "-u", "sa"));
        assertTrue(output().contains("User password is missing"));
    }

    @Test
    public void connectionFailureReturns1() {
        assertEquals(1, run("-j", "jdbc:nosuchdriver:x", "-u", "u", "-p", "p"));
    }

    @Test
    public void sqlOutputIsCsvLike() {
        assertEquals(0, run("-j", URL, "-u", "sa", "-p", "", "-s", "create table t1 (a int, b varchar(8))"));
        assertEquals(0, run("-j", URL, "-u", "sa", "-p", "", "-s", "insert into t1 values (1,'x')"));
        captured.reset();

        assertEquals(0, run("-j", URL, "-u", "sa", "-p", "", "-s", "select * from t1"));
        String out = output();
        assertTrue(out, out.contains("Connection established successfully.\n"));
        assertTrue(out, out.contains("SQL:\nselect * from t1\n\nA,B\n1,x\n\n"));
        assertTrue(out, out.contains("Connection closed.\n"));
    }

    @Test
    public void updateCountIsPrintedAsResult() {
        assertEquals(0, run("-j", URL, "-u", "sa", "-p", "", "-s", "create table t2 (a int)"));
        captured.reset();
        assertEquals(0, run("-j", URL, "-u", "sa", "-p", "", "-s", "insert into t2 values (1)"));
        assertTrue(output(), output().contains("Result: 1\n"));
    }

    @Test
    public void tablesAreListedWithRegex() {
        assertEquals(0, run("-j", URL, "-u", "sa", "-p", "", "-s", "create table xs_one (a int)"));
        assertEquals(0, run("-j", URL, "-u", "sa", "-p", "", "-s", "create table other_one (a int)"));
        captured.reset();

        assertEquals(0, run("-j", URL, "-u", "sa", "-p", "", "-t", "-r", "^XS"));
        String out = output();
        assertTrue(out, out.contains("Tables (^XS):\nXS_ONE\n"));
        assertTrue(out, !out.contains("OTHER_ONE"));
    }

    @Test
    public void quietSuppressesOutput() {
        assertEquals(0, run("-j", URL, "-u", "sa", "-p", "", "-q", "-s", "select 1"));
        assertEquals("", output());
    }
}

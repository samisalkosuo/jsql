package jsql.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class CoreTest {

    private static final String URL = "jdbc:h2:mem:coretest;DB_CLOSE_DELAY=-1";

    private Connection conn;

    @Before
    public void setUp() throws Exception {
        conn = new ConnectionSettings(URL, "sa", "", false).connect(w -> {
        });
        try (Statement st = conn.createStatement()) {
            st.execute("create table mytable (cola int, colb varchar(8))");
            st.execute("create table other (id int)");
        }
    }

    @After
    public void tearDown() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("drop all objects");
        }
        conn.close();
    }

    @Test
    public void tableListIsSortedAndFiltered() throws Exception {
        List<String> all = TableLister.allTableNames(conn.getMetaData());
        assertTrue(all.contains("MYTABLE"));
        assertTrue(all.contains("OTHER"));
        assertTrue(all.indexOf("MYTABLE") < all.indexOf("OTHER"));

        assertEquals(List.of("MYTABLE"), TableLister.filter(List.of("MYTABLE", "OTHER"), "^MY"));
        assertEquals(List.of("MYTABLE", "OTHER"), TableLister.filter(List.of("MYTABLE", "OTHER"), null));
    }

    @Test
    public void updateCountIsReported() throws Exception {
        RecordingHandler h = new RecordingHandler();
        QueryExecutor.execute(conn, "insert into mytable values (0,'hello')", h);
        assertEquals(Integer.valueOf(1), h.updateCount);
        assertTrue(h.columns.isEmpty());
    }

    @Test
    public void rowsAreStreamedAndNullsPrintedAsNULL() throws Exception {
        QueryExecutor.execute(conn, "insert into mytable values (0,'hello')", new RecordingHandler());
        QueryExecutor.execute(conn, "insert into mytable values (1,null)", new RecordingHandler());

        RecordingHandler h = new RecordingHandler();
        QueryExecutor.execute(conn, "select * from mytable order by cola", h);
        assertEquals(List.of("COLA", "COLB"), h.columns);
        assertEquals(List.of(List.of("0", "hello"), List.of("1", "NULL")), h.rows);
        assertEquals(null, h.updateCount);
    }

    @Test
    public void db2GetsMessageProperty() {
        Properties p = new ConnectionSettings("jdbc:db2://h:1/db", "u", "p", false).toProperties(w -> {
        });
        assertEquals("true", p.get("retreiveMessagesFromServerOnGetMessage"));
        assertEquals("u", p.get("user"));
        assertEquals("p", p.get("password"));
    }

    @Test
    public void insecureSetsDriverSpecificProperties() {
        Properties pg = new ConnectionSettings("jdbc:postgresql://h/db", "u", "p", true).toProperties(w -> {
        });
        assertEquals("org.postgresql.ssl.NonValidatingFactory", pg.get("sslfactory"));

        Properties mssql = new ConnectionSettings("jdbc:sqlserver://h", "u", "p", true).toProperties(w -> {
        });
        assertEquals("true", mssql.get("trustServerCertificate"));

        Properties maria = new ConnectionSettings("jdbc:mariadb://h/db", "u", "p", true).toProperties(w -> {
        });
        assertEquals("trust", maria.get("sslMode"));
    }

    @Test
    public void insecureWarnsForUnsupportedDriver() {
        List<String> warnings = new ArrayList<>();
        new ConnectionSettings("jdbc:oracle:thin:@h:1:x", "u", "p", true).toProperties(warnings::add);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("-k has no effect"));

        List<String> none = new ArrayList<>();
        new ConnectionSettings("jdbc:oracle:thin:@h:1:x", "u", "p", false).toProperties(none::add);
        assertFalse(none.size() > 0);
    }

    private static class RecordingHandler implements QueryExecutor.Handler {
        List<String> columns = new ArrayList<>();
        List<List<String>> rows = new ArrayList<>();
        Integer updateCount;

        @Override
        public void columns(List<String> columnNames) {
            columns = columnNames;
        }

        @Override
        public void row(List<String> values) {
            rows.add(values);
        }

        @Override
        public void updateCount(int count) {
            updateCount = count;
        }
    }
}

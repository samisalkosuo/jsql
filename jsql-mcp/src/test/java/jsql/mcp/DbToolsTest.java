package jsql.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import io.openliberty.mcp.tools.ToolCallException;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;

public class DbToolsTest {

    private static final String URL = "jdbc:h2:mem:toolstest";

    private Connection keepAlive;
    private ServerState state;
    private DbTools tools;
    private Path tempDir;

    private void start(boolean readOnly, String maxRows) throws Exception {
        keepAlive = DriverManager.getConnection(URL, "sa", "");
        try (Statement st = keepAlive.createStatement()) {
            st.execute("create schema if not exists shop");
            st.execute("create table shop.customer(id int primary key, name varchar(50) not null, note varchar(2000))");
            st.execute("create table shop.orders(id int primary key, customer_id int not null, total decimal(10,2),"
                    + " constraint fk_cust foreign key (customer_id) references shop.customer(id))");
            st.execute("create index ix_orders_total on shop.orders(total)");
            st.execute("create view shop.big_orders as select * from shop.orders where total > 100");
            st.execute("insert into shop.customer values (1,'Ann',null),(2,'Bob','" + "x".repeat(1500) + "'),(3,'Cid',null)");
            st.execute("insert into shop.orders values (10,1,50.5),(11,2,150)");
        }
        tempDir = Files.createTempDirectory("jsql-dbtoolstest");
        Path exportDir = tempDir.resolve("export");
        Path queriesDir = tempDir.resolve("queries");
        Files.createDirectories(exportDir);
        Files.createDirectories(queriesDir);

        Map<String, String> env = new HashMap<>();
        env.put("JSQL_JDBC_URL", URL);
        env.put("JSQL_USER_NAME", "sa");
        env.put("JSQL_USER_PASSWORD", "");
        env.put("JSQL_READ_ONLY", String.valueOf(readOnly));
        env.put("JSQL_MAX_ROWS", maxRows);
        env.put("JSQL_POOL_SIZE", "2");
        env.put("JSQL_EXPORT_DIR", exportDir.toString());
        env.put("JSQL_SAVED_QUERIES_DIR", queriesDir.toString());

        state = new ServerState();
        state.initialize(McpConfig.fromEnvironment(env));
        DbAccess db = new DbAccess();
        db.state = state;

        AnnotationsManager ann = new AnnotationsManager();
        ann.state = state;

        SavedQueriesManager sq = new SavedQueriesManager();
        sq.state = state;

        AuditLog audit = new AuditLog();

        tools = new DbTools();
        tools.db = db;
        tools.state = state;
        tools.annotations = ann;
        tools.savedQueries = sq;
        tools.audit = audit;
    }

    @Before
    public void setUp() throws Exception {
        start(false, "1000");
    }

    @After
    public void tearDown() throws Exception {
        state.stop();
        try (Statement st = keepAlive.createStatement()) {
            st.execute("drop all objects");
        }
        keepAlive.close();
        if (tempDir != null && Files.exists(tempDir)) {
            try (var stream = Files.walk(tempDir)) {
                stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignore) {
                    }
                });
            }
        }
    }

    private static JsonObject json(String text) {
        return Json.createReader(new StringReader(text)).readObject();
    }

    private static String errorOf(Runnable call) {
        try {
            call.run();
        } catch (ToolCallException e) {
            return e.getMessage();
        }
        fail("expected ToolCallException");
        return null;
    }

    @Test
    public void listSchemasContainsShop() {
        JsonArray schemas = json(tools.listSchemas(null, null)).getJsonArray("schemas");
        assertTrue(schemas.toString().contains("SHOP"));
        assertFalse(json(tools.listSchemas(null, null)).getBoolean("truncated"));
    }

    @Test
    public void listDatabasesReturnsArray() {
        assertNotNull(json(tools.listDatabases(null, null)).getJsonArray("databases"));
    }

    @Test
    public void listTablesFiltersAndHandlesTypes() {
        JsonObject all = json(tools.listTables(null, null, null, "SHOP", null, null));
        assertEquals(3, all.getJsonArray("tables").size());

        JsonObject views = json(tools.listTables(null, null, null, "SHOP", null, "view"));
        assertEquals(1, views.getJsonArray("tables").size());
        assertEquals("BIG_ORDERS", views.getJsonArray("tables").getJsonObject(0).getString("table"));

        JsonObject pattern = json(tools.listTables(null, null, null, "SHOP", "CUST%", ""));
        assertEquals(1, pattern.getJsonArray("tables").size());
    }

    @Test
    public void listConnectionsShowsDefault() {
        JsonObject conns = json(tools.listConnections());
        JsonArray arr = conns.getJsonArray("connections");
        assertEquals(1, arr.size());
        assertEquals("default", arr.getJsonObject(0).getString("name"));
        assertTrue(arr.getJsonObject(0).getBoolean("isDefault"));
    }

    @Test
    public void findTableLocatesTablesAcrossConnections() {
        JsonObject res = json(tools.findTable(null, null, "CUST%"));
        JsonArray tables = res.getJsonArray("tables");
        assertEquals(1, tables.size());
        assertEquals("CUSTOMER", tables.getJsonObject(0).getString("table"));
        assertEquals("default", tables.getJsonObject(0).getString("connection"));
    }

    @Test
    public void describeTableShowsColumnsKeysAndIndexes() {
        JsonObject d = json(tools.describeTable(null, null, null, "shop", "orders"));
        assertEquals(3, d.getJsonArray("columns").size());
        assertEquals("ID", d.getJsonArray("primaryKey").getString(0));
        assertTrue(d.getJsonArray("indexes").toString().contains("TOTAL"));
        assertFalse(d.getJsonArray("columns").getJsonObject(0).getBoolean("nullable"));
    }

    @Test
    public void describeUnknownTableIsReadableError() {
        String msg = errorOf(() -> tools.describeTable(null, null, null, null, "nope"));
        assertTrue(msg, msg.toLowerCase().contains("nope"));
    }

    @Test
    public void searchSchemaFindsTablesAndColumns() {
        JsonObject r = json(tools.searchSchema(null, null, null, null, "cust"));
        String text = r.getJsonArray("matches").toString();
        assertTrue(text, text.contains("CUSTOMER"));
        assertTrue(text, text.contains("CUSTOMER_ID"));
    }

    @Test
    public void invalidRegexIsReadableError() {
        String msg = errorOf(() -> tools.searchSchema(null, null, null, null, "("));
        assertTrue(msg, msg.toLowerCase().contains("regular expression") || msg.toLowerCase().contains("regex"));
    }

    @Test
    public void relationshipsBothDirections() {
        JsonObject orders = json(tools.getRelationships(null, null, null, "SHOP", "ORDERS"));
        assertEquals(1, orders.getJsonArray("references").size());
        assertEquals(0, orders.getJsonArray("referencedBy").size());
        JsonObject customer = json(tools.getRelationships(null, null, null, "SHOP", "CUSTOMER"));
        assertEquals(1, customer.getJsonArray("referencedBy").size());
        assertEquals("CUSTOMER_ID",
                customer.getJsonArray("referencedBy").getJsonObject(0).getJsonArray("fromColumns").getString(0));
    }

    @Test
    public void sampleRowsDefaultAndExplicitCount() {
        JsonObject r = json(tools.sampleRows(null, null, null, "SHOP", "CUSTOMER", null));
        assertEquals(3, r.getInt("rowCount"));
        assertFalse(r.getBoolean("truncated"));
        assertTrue("SQL NULL is JSON null", r.getJsonArray("rows").getJsonArray(0).isNull(2));

        JsonObject two = json(tools.sampleRows(null, null, null, "SHOP", "CUSTOMER", 2));
        assertEquals(2, two.getInt("rowCount"));
        assertTrue(two.getBoolean("truncated"));
    }

    @Test
    public void sampleRowsCutsLongValues() {
        JsonObject r = json(tools.sampleRows(null, null, null, "SHOP", "CUSTOMER", 10));
        String bobNote = r.getJsonArray("rows").getJsonArray(1).getString(2);
        assertTrue(bobNote.length() < 1500);
        assertTrue(bobNote, bobNote.endsWith("[truncated]"));
    }

    @Test
    public void sampleRowsRejectsZero() {
        String msg = errorOf(() -> tools.sampleRows(null, null, null, "SHOP", "CUSTOMER", 0));
        assertTrue(msg, msg.contains("at least 1"));
    }

    @Test
    public void executeQueryReturnsRowsAndUpdateCount() {
        JsonObject r = json(tools.executeQuery(null, "select id, name from shop.customer order by id"));
        assertEquals(3, r.getInt("rowCount"));
        assertEquals("Ann", r.getJsonArray("rows").getJsonArray(0).getString(1));
        assertNotNull(r.getJsonArray("columnTypes"));

        JsonObject u = json(tools.executeQuery(null, "update shop.customer set note = 'x' where id = 1"));
        assertEquals(1, u.getInt("updateCount"));
    }

    @Test
    public void executeQueryWithPaginationOffsetAndLimit() {
        JsonObject r = json(tools.executeQuery(null, null, "select id, name from shop.customer order by id", 1, 1, 10));
        assertEquals(1, r.getInt("rowCount"));
        assertEquals("Bob", r.getJsonArray("rows").getJsonArray(0).getString(1));
        assertTrue(r.getBoolean("truncated"));
    }

    @Test
    public void executeQueryTruncatesAtMaxRows() throws Exception {
        tearDown();
        start(false, "2");
        JsonObject r = json(tools.executeQuery(null, "select * from shop.customer"));
        assertEquals(2, r.getInt("rowCount"));
        assertTrue(r.getBoolean("truncated"));
        assertTrue(r.containsKey("note"));
    }

    @Test
    public void sqlErrorIsReportedWithSqlState() {
        String msg = errorOf(() -> tools.executeQuery(null, "select * from shop.missing"));
        assertTrue(msg, msg.startsWith("Database error (SQLState "));
    }

    @Test
    public void readOnlyModeBlocksWrites() throws Exception {
        tearDown();
        start(true, "1000");
        assertEquals(3, json(tools.executeQuery(null, "select * from shop.customer")).getInt("rowCount"));
        String msg = errorOf(() -> tools.executeQuery(null, "delete from shop.customer"));
        assertTrue(msg, msg.toLowerCase().contains("read-only"));
        // nothing was deleted
        assertEquals(3, json(tools.sampleRows(null, null, null, "SHOP", "CUSTOMER", 10)).getInt("rowCount"));
    }

    @Test
    public void missingCredentialsAreReadableError() throws Exception {
        tearDown();
        keepAlive = DriverManager.getConnection(URL, "sa", "");
        Map<String, String> env = new HashMap<>();
        env.put("JSQL_JDBC_URL", URL);
        state = new ServerState();
        state.initialize(McpConfig.fromEnvironment(env));
        DbAccess db = new DbAccess();
        db.state = state;
        tools.db = db;
        tools.state = state;
        String msg = errorOf(() -> tools.listSchemas(null, null));
        assertTrue(msg, msg.toLowerCase().contains("authorization"));
    }

    @Test
    public void exportQueryToFileCsvAndPathTraversalProtection() throws Exception {
        JsonObject res = json(tools.exportQueryToFile(null, null, "select id, name from shop.customer order by id",
                "customers.csv", "csv", 100, true, false, 10));
        assertEquals(3, res.getInt("rowsWritten"));
        assertFalse(res.getBoolean("truncated"));

        // Path traversal rejection
        String msg = errorOf(() -> tools.exportQueryToFile(null, null, "select * from shop.customer", "../secret.csv",
                "csv", 100, true, false, 10));
        assertTrue(msg, msg.contains("inside the export directory"));
    }

    @Test
    public void getRowCountReturnsCount() {
        JsonObject res = json(tools.getRowCount(null, null, null, "SHOP", "CUSTOMER", 10));
        assertEquals(3, res.getInt("rowCount"));
        assertFalse(res.getBoolean("estimated"));
    }

    @Test
    public void savedQueriesLifecycle() {
        tools.saveQuery("cust_by_id", "select * from shop.customer where id = :id", "Find customer by ID", "default", false);
        JsonObject list = json(tools.listSavedQueries(null));
        assertEquals(1, list.getJsonArray("savedQueries").size());

        JsonObject q = json(tools.getSavedQuery("cust_by_id"));
        assertEquals("cust_by_id", q.getString("name"));
        assertTrue(q.getString("sql").contains(":id"));

        JsonObject res = json(tools.runSavedQuery(null, null, "cust_by_id", "{\"id\": 1}", 10));
        assertEquals(1, res.getInt("rowCount"));
        assertEquals("Ann", res.getJsonArray("rows").getJsonArray(0).getString(1));
    }

    @Test
    public void profileColumnSummarisesValues() {
        JsonObject p = json(tools.profileColumn(null, null, null, "SHOP", "CUSTOMER", "name", 2));
        assertEquals("NAME", p.getString("column"));
        assertEquals(3, p.getInt("rowCount"));
        assertEquals(0, p.getInt("nullCount"));
        assertEquals(3, p.getInt("distinctCount"));
        assertEquals("Ann", p.getString("min"));
        assertEquals("Cid", p.getString("max"));
        assertEquals(2, p.getJsonArray("topValues").size());
        assertFalse(p.containsKey("notes"));
    }

    @Test
    public void profileColumnCountsNullsAndUsesDefaultTopValues() {
        JsonObject p = json(tools.profileColumn(null, null, null, "SHOP", "CUSTOMER", "note", null));
        assertEquals(2, p.getInt("nullCount"));
        assertEquals(2, p.getJsonArray("topValues").size()); // NULL and the long text
    }

    @Test
    public void profileColumnErrorsAreReadable() {
        String unknown = errorOf(() -> tools.profileColumn(null, null, null, "SHOP", "CUSTOMER", "nope", null));
        assertTrue(unknown, unknown.toLowerCase().contains("nope"));
        String negative = errorOf(() -> tools.profileColumn(null, null, null, "SHOP", "CUSTOMER", "name", -1));
        assertTrue(negative, negative.contains("negative"));
    }

    @Test
    public void validateSqlReportsColumnsAndParameters() {
        JsonObject v = json(tools.validateSql(null, null, "select id, name from shop.customer where id = ?"));
        assertTrue(v.getBoolean("valid"));
        assertEquals(2, v.getJsonArray("columns").size());
        assertEquals("ID", v.getJsonArray("columns").getJsonObject(0).getString("name"));
        assertEquals(1, v.getInt("parameterCount"));
    }

    @Test
    public void validateSqlReturnsInvalidWithMessageNotToolError() {
        JsonObject v = json(tools.validateSql(null, null, "select * from shop.missing"));
        assertFalse(v.getBoolean("valid"));
        assertTrue(v.getString("error"), v.getString("error").toLowerCase().contains("missing"));
        assertTrue(v.containsKey("sqlState"));
        assertFalse(v.containsKey("columns"));
    }

    @Test
    public void validateSqlDoesNotRunTheStatement() {
        assertTrue(json(tools.validateSql(null, null, "delete from shop.orders")).getBoolean("valid"));
        assertEquals(2, json(tools.sampleRows(null, null, null, "SHOP", "ORDERS", 10)).getInt("rowCount"));
    }

    @Test
    public void explainQueryReturnsPlanRows() {
        JsonObject r = json(tools.explainQuery(null, null, "select * from shop.customer where id = 1"));
        assertTrue(r.getInt("rowCount") >= 1);
        assertTrue(r.getJsonArray("rows").toString().toUpperCase().contains("CUSTOMER"));
    }

    @Test
    public void explainQueryRejectsExplainPrefix() {
        String msg = errorOf(() -> tools.explainQuery(null, null, "explain analyze delete from shop.orders"));
        assertTrue(msg, msg.toUpperCase().contains("EXPLAIN"));
    }

    @Test
    public void explainQueryInReadOnlyModeRejectsWrites() throws Exception {
        tearDown();
        start(true, "1000");
        String msg = errorOf(() -> tools.explainQuery(null, null, "delete from shop.orders"));
        assertTrue(msg, msg.toLowerCase().contains("read-only"));
    }
}

package jsql.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import jsql.core.QueryService.ColumnProfile;
import jsql.core.QueryService.QueryResult;
import jsql.core.QueryService.Validation;
import jsql.core.SchemaInspector.ForeignKey;
import jsql.core.SchemaInspector.Relationships;
import jsql.core.SchemaInspector.SchemaMatch;
import jsql.core.SchemaInspector.TableDescription;
import jsql.core.SchemaInspector.TableInfo;

public class CoreServicesTest {

    private static final String URL = "jdbc:h2:mem:coreservices;DB_CLOSE_DELAY=-1";

    private final QueryLimits limits = new QueryLimits(100, 30, 50);
    private final SchemaInspector inspector = new SchemaInspector(limits);
    private final QueryService query = new QueryService(limits, false);

    private Connection conn;

    @Before
    public void setUp() throws Exception {
        conn = new ConnectionSettings(URL, "sa", "", false).connect(w -> {
        });
        exec("create table customers (id int primary key, name varchar(20) not null default 'anon', "
                + "email varchar(40), notes clob)");
        exec("create index idx_customers_name on customers (name)");
        exec("create table orders (id int primary key, customer_id int, amount decimal(10,2), "
                + "constraint fk_orders_customer foreign key (customer_id) references customers (id))");
        exec("create table parent (a int, b int, primary key (a, b))");
        exec("create table child (x int, y int, constraint fk_child foreign key (x, y) references parent (a, b))");
        exec("insert into customers values (1, 'Ann', 'ann@x.com', 'a note')");
        exec("insert into customers values (2, 'Bob', null, null)");
        exec("insert into customers values (3, 'Bob', 'bob@x.com', null)");
        exec("insert into orders values (10, 1, 5.50)");
    }

    @After
    public void tearDown() throws Exception {
        exec("drop all objects");
        conn.close();
    }

    private void exec(String sql) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    // ---- SchemaInspector --------------------------------------------------------------------

    @Test
    public void listsCatalogsAndSchemas() throws Exception {
        assertFalse(inspector.listCatalogs(conn).isEmpty());
        List<String> schemas = new ArrayList<>();
        inspector.listSchemas(conn).items().forEach(s -> schemas.add(s.schema()));
        assertTrue(schemas.contains("PUBLIC"));
        assertTrue(schemas.contains("INFORMATION_SCHEMA"));
    }

    @Test
    public void listsTablesWithFilters() throws Exception {
        ListResult<TableInfo> all = inspector.listTables(conn, null, "PUBLIC", null, List.of("TABLE"));
        List<String> names = new ArrayList<>();
        all.items().forEach(t -> names.add(t.table().name()));
        assertTrue(names.containsAll(List.of("CUSTOMERS", "ORDERS", "PARENT", "CHILD")));
        assertFalse(all.truncated());

        ListResult<TableInfo> like = inspector.listTables(conn, null, "PUBLIC", "CUST%", null);
        assertEquals(1, like.items().size());
        assertEquals("CUSTOMERS", like.items().get(0).table().name());
        // H2 reports "BASE TABLE"; other databases report "TABLE"
        assertTrue(like.items().get(0).type(), like.items().get(0).type().endsWith("TABLE"));
    }

    // H2 has no such object types, so the filter is tested on the type names that other drivers report
    // (PostgreSQL: INDEX, SEQUENCE, SYSTEM TABLE ...; Oracle: SYNONYM stays; Db2: SYSTEM TABLE).
    @Test
    public void indexesSequencesAndSystemObjectsAreNoise() {
        for (String type : List.of("INDEX", "SYSTEM INDEX", "SEQUENCE", "SYSTEM TABLE", "SYSTEM VIEW", "TYPE",
                "COMPOSITE TYPE", "DOMAIN TYPE", "partitioned index")) {
            assertTrue(type, SchemaInspector.isNoiseType(type));
        }
        for (String type : List.of("TABLE", "BASE TABLE", "VIEW", "MATERIALIZED VIEW", "FOREIGN TABLE",
                "PARTITIONED TABLE", "TEMPORARY TABLE", "SYNONYM", "ALIAS", "GLOBAL TEMPORARY", "LOCAL TEMPORARY")) {
            assertFalse(type, SchemaInspector.isNoiseType(type));
        }
        assertFalse(SchemaInspector.isNoiseType(null));
    }

    @Test
    public void listIsCutAtMaxRows() throws Exception {
        SchemaInspector small = new SchemaInspector(new QueryLimits(2, 30, 50));
        ListResult<TableInfo> r = small.listTables(conn, null, "PUBLIC", null, null);
        assertEquals(2, r.items().size());
        assertTrue(r.truncated());
    }

    @Test
    public void resolvesTableCaseInsensitively() throws Exception {
        assertEquals("CUSTOMERS", inspector.resolveTable(conn, null, null, "customers").table().name());
        assertEquals("CUSTOMERS", inspector.resolveTable(conn, null, "public", "Customers").table().name());
    }

    @Test
    public void unknownTableGivesHelpfulError() throws Exception {
        try {
            inspector.resolveTable(conn, null, null, "nosuch");
            fail();
        } catch (InvalidRequestException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("Table not found"));
        }
    }

    @Test
    public void ambiguousTableNameIsReported() throws Exception {
        exec("create schema other");
        exec("create table other.customers (id int)");
        try {
            inspector.resolveTable(conn, null, null, "customers");
            fail();
        } catch (InvalidRequestException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("ambiguous"));
        }
        assertEquals("OTHER", inspector.resolveTable(conn, null, "other", "customers").table().schema());
    }

    @Test
    public void underscoreInTableNameIsNotAWildcard() throws Exception {
        exec("create table ab_c (id int)");
        exec("create table abxc (id int)");
        assertEquals("AB_C", inspector.resolveTable(conn, null, null, "ab_c").table().name());
    }

    @Test
    public void describesTable() throws Exception {
        TableDescription d = inspector.describeTable(conn, null, null, "customers");
        assertEquals("CUSTOMERS", d.table().table().name());
        assertEquals(List.of("ID", "NAME", "EMAIL", "NOTES"), d.columns().stream().map(c -> c.name()).toList());
        assertEquals(List.of("ID"), d.primaryKey());

        SchemaInspector.ColumnInfo name = d.columns().get(1);
        assertFalse(name.nullable());
        assertEquals(20, name.size());
        assertTrue(name.defaultValue(), name.defaultValue().contains("anon"));
        assertTrue(d.columns().get(2).nullable());

        SchemaInspector.IndexInfo idx = d.indexes().stream().filter(i -> "IDX_CUSTOMERS_NAME".equals(i.name()))
                .findFirst().orElseThrow();
        assertEquals(List.of("NAME"), idx.columns());
        assertFalse(idx.unique());
    }

    @Test
    public void primaryKeyIsOrderedBySequence() throws Exception {
        assertEquals(List.of("A", "B"), inspector.describeTable(conn, null, null, "parent").primaryKey());
    }

    @Test
    public void searchesTablesAndColumns() throws Exception {
        ListResult<SchemaMatch> r = inspector.searchSchema(conn, "cust", null, "PUBLIC");
        List<String> hits = new ArrayList<>();
        r.items().forEach(m -> hits.add(m.table().name() + "." + m.column()));
        assertTrue(hits.toString(), hits.contains("CUSTOMERS.null"));
        assertTrue(hits.toString(), hits.contains("ORDERS.CUSTOMER_ID"));
        assertFalse(hits.toString(), hits.contains("PARENT.null"));
    }

    @Test
    public void searchRejectsBadRegex() throws Exception {
        try {
            inspector.searchSchema(conn, "(", null, null);
            fail();
        } catch (InvalidRequestException e) {
            assertTrue(e.getMessage().contains("Invalid regular expression"));
        }
    }

    @Test
    public void findsRelationshipsInBothDirections() throws Exception {
        Relationships orders = inspector.getRelationships(conn, null, null, "orders");
        assertEquals(1, orders.references().size());
        ForeignKey fk = orders.references().get(0);
        assertEquals("FK_ORDERS_CUSTOMER", fk.name());
        assertEquals("ORDERS", fk.fkTable().name());
        assertEquals(List.of("CUSTOMER_ID"), fk.fkColumns());
        assertEquals("CUSTOMERS", fk.pkTable().name());
        assertEquals(List.of("ID"), fk.pkColumns());
        assertTrue(orders.referencedBy().isEmpty());

        Relationships customers = inspector.getRelationships(conn, null, null, "customers");
        assertTrue(customers.references().isEmpty());
        assertEquals(1, customers.referencedBy().size());
        assertEquals("ORDERS", customers.referencedBy().get(0).fkTable().name());
    }

    @Test
    public void compositeForeignKeyIsOneEntry() throws Exception {
        Relationships child = inspector.getRelationships(conn, null, null, "child");
        assertEquals(1, child.references().size());
        assertEquals(List.of("X", "Y"), child.references().get(0).fkColumns());
        assertEquals(List.of("A", "B"), child.references().get(0).pkColumns());
    }

    // ---- QueryService -----------------------------------------------------------------------

    @Test
    public void executeReturnsRowsWithNulls() throws Exception {
        QueryResult r = query.execute(conn, "select id, email from customers order by id");
        assertEquals(List.of("ID", "EMAIL"), r.columns());
        assertEquals(3, r.rows().size());
        assertEquals("ann@x.com", r.rows().get(0).get(1));
        assertNull(r.rows().get(1).get(1));
        assertFalse(r.truncated());
        assertNull(r.updateCount());
    }

    @Test
    public void executeReportsUpdateCount() throws Exception {
        QueryResult r = query.execute(conn, "update customers set name = 'Z' where id < 3");
        assertEquals(Integer.valueOf(2), r.updateCount());
        assertTrue(r.columns().isEmpty());
    }

    @Test
    public void executeCutsRowsAndMarksTruncated() throws Exception {
        QueryService small = new QueryService(new QueryLimits(2, 30, 50), false);
        QueryResult r = small.execute(conn, "select * from customers order by id");
        assertEquals(2, r.rows().size());
        assertTrue(r.truncated());

        QueryResult exact = new QueryService(new QueryLimits(3, 30, 50), false)
                .execute(conn, "select * from customers");
        assertEquals(3, exact.rows().size());
        assertFalse(exact.truncated());
    }

    @Test
    public void longValuesAndLobsAreCut() throws Exception {
        exec("update customers set notes = repeat('x', 500) where id = 2");
        QueryResult r = query.execute(conn, "select notes from customers where id = 2");
        String v = r.rows().get(0).get(0);
        assertTrue(v, v.endsWith("...[truncated]"));
        assertEquals(50 + "...[truncated]".length(), v.length());

        QueryResult shortLob = query.execute(conn, "select notes from customers where id = 1");
        assertEquals("a note", shortLob.rows().get(0).get(0));
    }

    @Test
    public void binaryValuesAreSummarised() throws Exception {
        QueryResult r = query.execute(conn, "select x'0102' from dual");
        assertEquals("<binary 2 bytes>", r.rows().get(0).get(0));
    }

    @Test
    public void emptySqlIsRejected() throws Exception {
        try {
            query.execute(conn, "  ");
            fail();
        } catch (InvalidRequestException e) {
            assertEquals("SQL is empty.", e.getMessage());
        }
    }

    @Test
    public void readOnlyModeRejectsWritesButAllowsReads() throws Exception {
        QueryService ro = new QueryService(limits, true);
        assertEquals(3, ro.execute(conn, "select * from customers").rows().size());
        try {
            ro.execute(conn, "delete from customers");
            fail();
        } catch (InvalidRequestException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("DELETE"));
        }
        // nothing was deleted
        assertEquals("3", query.execute(conn, "select count(*) from customers").rows().get(0).get(0));
    }

    @Test
    public void sampleRowsReturnsFirstRows() throws Exception {
        QueryResult r = query.sampleRows(conn, null, null, "customers", 2);
        assertEquals(2, r.rows().size());
        assertTrue(r.truncated());
        assertEquals(4, r.columns().size());
    }

    @Test
    public void sampleRowsIsCappedByMaxRows() throws Exception {
        QueryService small = new QueryService(new QueryLimits(1, 30, 50), false);
        assertEquals(1, small.sampleRows(conn, null, null, "customers", 50).rows().size());
    }
    // ---- timeouts, paging, describe flags, stats -----------------------------------------------

    @Test
    public void effectiveTimeoutUsesDefaultClampsAndRejectsNonPositive() {
        QueryLimits l = new QueryLimits(100, 30, 50, 120);
        assertEquals(30, l.effectiveTimeout(null));
        assertEquals(10, l.effectiveTimeout(10));
        assertEquals(120, l.effectiveTimeout(100_000));
        for (int bad : new int[] { 0, -1 }) {
            try {
                l.effectiveTimeout(bad);
                fail("expected rejection of " + bad);
            } catch (InvalidRequestException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("at least 1"));
            }
        }
    }

    @Test
    public void listTablesPagesThroughManyTables() throws Exception {
        for (int i = 0; i < 500; i++) {
            exec("create table pg_t" + String.format("%03d", i) + " (id int)");
        }
        SchemaInspector wide = new SchemaInspector(new QueryLimits(1000, 30, 50));
        ListResult<TableInfo> first = wide.listTables(conn, null, null, "PG_T%", null, 0, 200);
        assertEquals(200, first.items().size());
        assertTrue(first.truncated());
        ListResult<TableInfo> second = wide.listTables(conn, null, null, "PG_T%", null, 200, 200);
        assertEquals(200, second.items().size());
        assertTrue(second.truncated());
        ListResult<TableInfo> last = wide.listTables(conn, null, null, "PG_T%", null, 400, 200);
        assertEquals(100, last.items().size());
        assertFalse(last.truncated());
        assertNotEquals(first.items().get(0).table().name(), second.items().get(0).table().name());
    }

    @Test
    public void listTablesRejectsBadPaging() throws Exception {
        try {
            inspector.listTables(conn, null, null, null, null, -1, 10);
            fail();
        } catch (InvalidRequestException e) {
            assertTrue(e.getMessage().contains("offset"));
        }
        try {
            inspector.listTables(conn, null, null, null, null, 0, 0);
            fail();
        } catch (InvalidRequestException e) {
            assertTrue(e.getMessage().contains("limit"));
        }
    }

    @Test
    public void listTablesWithUnknownCatalogIsEmpty() throws Exception {
        assertTrue(inspector.listTables(conn, "NO_SUCH_CATALOG", null, null, null).items().isEmpty());
    }

    @Test
    public void describeTableLeavesOutKeyAndIndexesWhenNotRequested() throws Exception {
        TableDescription fast = inspector.describeTable(conn, null, null, "customers", false, false);
        assertEquals(4, fast.columns().size());
        assertNull(fast.indexes());
        assertNull(fast.primaryKey());
        TableDescription withPk = inspector.describeTable(conn, null, null, "customers", true, false);
        assertNull(withPk.indexes());
        assertEquals(List.of("ID"), withPk.primaryKey());
        TableDescription full = inspector.describeTable(conn, null, null, "customers");
        assertFalse(full.indexes().isEmpty());
    }

    @Test
    public void tableStatsAreNotSupportedOnH2() throws Exception {
        QueryService.TableStats stats = query.tableStats(conn, null, null, "customers", null);
        assertFalse(stats.supported());
        assertNotNull(stats.hint());
        assertTrue(stats.columns().isEmpty());
    }



    @Test
    public void sampleRowsOfUnknownTableFails() throws Exception {
        try {
            query.sampleRows(conn, null, null, "nosuch", 5);
            fail();
        } catch (InvalidRequestException e) {
            assertTrue(e.getMessage().contains("Table not found"));
        }
    }

    @Test
    public void profilesColumn() throws Exception {
        ColumnProfile p = query.profileColumn(conn, null, null, "customers", "name", 5);
        assertEquals("NAME", p.column());
        assertEquals(3, p.rowCount());
        assertEquals(0, p.nullCount());
        assertEquals(Long.valueOf(2), p.distinctCount());
        assertEquals("Ann", p.min());
        assertEquals("Bob", p.max());
        assertEquals("Bob", p.topValues().get(0).value());
        assertEquals(2, p.topValues().get(0).count());
    }

    @Test
    public void profileCountsNullsAndHandlesLobColumn() throws Exception {
        ColumnProfile email = query.profileColumn(conn, null, null, "customers", "EMAIL", 0);
        assertEquals(1, email.nullCount());
        assertTrue(email.topValues().isEmpty());

        // LOB columns do not support DISTINCT/MIN/MAX/GROUP BY; the profile still returns counts
        ColumnProfile notes = query.profileColumn(conn, null, null, "customers", "notes", 3);
        assertEquals(3, notes.rowCount());
        assertEquals(2, notes.nullCount());
        assertNotNull(notes.notes());
    }

    @Test
    public void profileUnknownColumnListsColumns() throws Exception {
        try {
            query.profileColumn(conn, null, null, "customers", "nosuch", 3);
            fail();
        } catch (InvalidRequestException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("ID, NAME, EMAIL, NOTES"));
        }
    }

    @Test
    public void validatesGoodAndBadSql() throws Exception {
        Validation good = query.validateSql(conn, "select id, name from customers where id = ?");
        assertTrue(good.valid());
        assertEquals(1, good.parameterCount());
        assertEquals(List.of("ID", "NAME"), good.columns().stream().map(c -> c.name()).toList());

        Validation bad = query.validateSql(conn, "select * from nosuchtable");
        assertFalse(bad.valid());
        assertNotNull(bad.error());
        assertTrue(bad.columns().isEmpty());
    }

    @Test
    public void validateDoesNotExecute() throws Exception {
        assertTrue(query.validateSql(conn, "delete from customers").valid());
        assertEquals("3", query.execute(conn, "select count(*) from customers").rows().get(0).get(0));
    }

    // PostgreSQL only sends the statement to the server when its metadata is asked for, so the real
    // error arrives from getParameterMetaData/getMetaData. H2 prepares eagerly and cannot show this.
    private static Connection connectionWhoseMetadataFails(String sqlState) {
        java.lang.reflect.InvocationHandler statement = (proxy, method, args) -> {
            switch (method.getName()) {
            case "getParameterMetaData":
            case "getMetaData":
                throw new java.sql.SQLException("boom", sqlState);
            case "close":
                return null;
            default:
                throw new UnsupportedOperationException(method.getName());
            }
        };
        java.lang.reflect.InvocationHandler connection = (proxy, method, args) -> {
            if (method.getName().equals("prepareStatement")) {
                return java.lang.reflect.Proxy.newProxyInstance(CoreServicesTest.class.getClassLoader(),
                        new Class<?>[] { java.sql.PreparedStatement.class }, statement);
            }
            throw new UnsupportedOperationException(method.getName());
        };
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(CoreServicesTest.class.getClassLoader(),
                new Class<?>[] { Connection.class }, connection);
    }

    @Test
    public void validateReportsErrorsThatArriveWhenMetadataIsRequested() throws Exception {
        Validation v = query.validateSql(connectionWhoseMetadataFails("42P01"), "select * from nosuchtable");
        assertFalse(v.valid());
        assertEquals("boom", v.error());
        assertEquals("42P01", v.sqlState());
    }

    @Test
    public void validateIgnoresMetadataThatTheDriverDoesNotSupport() throws Exception {
        for (String state : List.of("0A000", "HYC00")) {
            Validation v = query.validateSql(connectionWhoseMetadataFails(state), "select 1");
            assertTrue(state, v.valid());
            assertEquals(-1, v.parameterCount());
            assertTrue(v.columns().isEmpty());
        }
    }

    @Test
    public void explainReturnsPlan() throws Exception {
        QueryResult r = query.explain(conn, "select * from customers where id = 1;");
        assertFalse(r.rows().isEmpty());
        assertTrue(r.rows().get(0).get(0).toUpperCase().contains("CUSTOMERS"));
    }

    @Test
    public void explainRejectsExplainPrefixAndMultipleStatements() throws Exception {
        for (String sql : new String[] { "explain analyze select 1", "select 1; delete from customers", "drop table orders" }) {
            try {
                query.explain(conn, sql);
                fail(sql);
            } catch (InvalidRequestException e) {
                // expected
            }
        }
        assertEquals("3", query.execute(conn, "select count(*) from customers").rows().get(0).get(0));
    }

    @Test
    public void explainIsRejectedForUnsupportedProduct() {
        try {
            Dialect.explainStatements("DB2/NT64", "select 1");
            fail();
        } catch (InvalidRequestException e) {
            assertTrue(e.getMessage().contains("not supported"));
        }
        assertEquals(2, Dialect.explainStatements("Oracle", "select 1 from dual").size());
        assertEquals(List.of("EXPLAIN select 1"), Dialect.explainStatements("PostgreSQL", "select 1"));
    }

    @Test
    public void executeWithPaginationOffsetAndLimit() throws Exception {
        QueryResult r = query.execute(conn, "select id, name from customers order by id", 1, 1, 10);
        assertEquals(1, r.rows().size());
        assertEquals("2", r.rows().get(0).get(0));
        assertEquals("Bob", r.rows().get(0).get(1));
        assertTrue(r.truncated());
        assertEquals(List.of("ID", "NAME"), r.columns());
        assertEquals(List.of("INTEGER", "CHARACTER VARYING"), r.columnTypes());
        assertNotNull(r.elapsedMs());
    }

    @Test
    public void exportToCsvAndJsonAndJsonl() throws Exception {
        java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("jsql-export-test");
        try {
            java.nio.file.Path csvPath = tempDir.resolve("out.csv");
            QueryService.ExportResult csvResult = query.exportToFile(conn,
                    "select id, name, email from customers order by id", csvPath, "csv", 100, true, 10);
            assertEquals(3, csvResult.rowsWritten());
            assertFalse(csvResult.truncated());
            String csvText = java.nio.file.Files.readString(csvPath);
            assertTrue(csvText.contains("ID,NAME,EMAIL"));
            assertTrue(csvText.contains("1,Ann,ann@x.com"));

            java.nio.file.Path jsonPath = tempDir.resolve("out.json");
            QueryService.ExportResult jsonResult = query.exportToFile(conn,
                    "select id, name from customers order by id", jsonPath, "json", 2, false, 10);
            assertEquals(2, jsonResult.rowsWritten());
            assertTrue(jsonResult.truncated());
            String jsonText = java.nio.file.Files.readString(jsonPath);
            assertTrue(jsonText.startsWith("[\n{"));

            java.nio.file.Path jsonlPath = tempDir.resolve("out.jsonl");
            QueryService.ExportResult jsonlResult = query.exportToFile(conn,
                    "select id, name from customers order by id", jsonlPath, "jsonl", 100, false, 10);
            assertEquals(3, jsonlResult.rowsWritten());
            String jsonlText = java.nio.file.Files.readString(jsonlPath);
            assertEquals(3, jsonlText.trim().split("\n").length);
        } finally {
            // Clean up
            try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.walk(tempDir)) {
                stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        java.nio.file.Files.deleteIfExists(p);
                    } catch (Exception ignore) {
                    }
                });
            }
        }
    }

    @Test
    public void getRowCountReturnsCount() throws Exception {
        QueryService.RowCountResult rc = query.getRowCount(conn, null, "PUBLIC", "CUSTOMERS", 10);
        assertEquals(3, rc.rowCount());
        assertFalse(rc.estimated());
    }

    @Test
    public void namedSqlParsesAndBinds() throws Exception {
        NamedSql ns = NamedSql.parse("select * from customers where name = :name and id > :min_id");
        assertEquals("select * from customers where name = ? and id > ?", ns.sql());
        assertEquals(List.of("name", "min_id"), ns.parameterNames());
        List<Object> values = ns.bindValues(java.util.Map.of("name", "Bob", "min_id", 1));
        assertEquals(List.of("Bob", 1), values);

        QueryResult r = query.executePrepared(conn, ns.sql(), values, null, null, null);
        assertEquals(2, r.rows().size());
    }
}

package jsql.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

public class SqlGuardTest {

    private static void allowed(String sql) {
        SqlGuard.requireReadOnly(sql);
    }

    private static void rejected(String sql) {
        try {
            SqlGuard.requireReadOnly(sql);
            fail("should be rejected: " + sql);
        } catch (InvalidRequestException e) {
            // expected
        }
    }

    @Test
    public void allowsReadStatements() {
        allowed("select * from t");
        allowed("  SELECT a, b FROM t WHERE c = 1;  ");
        allowed("with x as (select 1) select * from x");
        allowed("explain select 1");
        allowed("show tables");
        allowed("describe t");
        allowed("values (1)");
        allowed("-- comment\nselect 1");
        allowed("/* c */ select 1");
    }

    @Test
    public void rejectsWriteStatements() {
        rejected("insert into t values (1)");
        rejected("update t set a = 1");
        rejected("delete from t");
        rejected("merge into t using s on (1=1) when matched then update set a = 1");
        rejected("drop table t");
        rejected("create table t (a int)");
        rejected("alter table t add b int");
        rejected("truncate table t");
        rejected("grant all on t to u");
        rejected("call proc()");
        rejected("exec proc");
        rejected("begin delete from t; end");
        rejected("");
        rejected("   ");
        rejected(";");
        rejected("-- only a comment");
    }

    @Test
    public void rejectsWritesHiddenInsideReadStatements() {
        rejected("select * into newtable from t");
        rejected("with d as (delete from t returning *) select * from d");
        rejected("explain analyze delete from t");
        rejected("select 1; delete from t");
        rejected("select 1; select 2");
    }

    @Test
    public void ignoresKeywordsInsideLiteralsCommentsAndIdentifiers() {
        allowed("select 'delete from t; drop table x' from t");
        allowed("select \"update\" from t");
        allowed("select `delete` from t");
        allowed("select [insert] from t");
        allowed("select 1 -- delete from t");
        allowed("select /* drop table t */ 1");
        allowed("select updated_at, created, deleted from t");
        allowed("select 'it''s' from t where a = 'x;y'");
    }

    @Test
    public void commentsCannotHideStatements() {
        rejected("select 1 /* */ ; /* */ delete from t");
        rejected("select 1; -- x\ndelete from t");
        rejected("select 1 /*! drop table t */");
    }

    @Test
    public void rejectsAmbiguousQuoting() {
        rejected("select $$ x $$");
        rejected("select $tag$ x $tag$");
        rejected("select 'a\\' ; delete from t; --'");
    }

    @Test
    public void explainableStatementIsCleaned() {
        assertEquals("select 1", SqlGuard.requireExplainable(" select 1 ;; "));
        assertEquals("with a as (select 1) select * from a", SqlGuard.requireExplainable("with a as (select 1) select * from a"));
    }

    @Test
    public void explainableRejectsPrefixesAndMultipleStatements() {
        for (String sql : new String[] { "explain select 1", "(analyze) select 1", "select 1; select 2", "drop table t",
                "", "$$ select 1 $$", "-- hi\nselect 1" }) {
            try {
                SqlGuard.requireExplainable(sql);
                fail(sql);
            } catch (InvalidRequestException e) {
                // expected
            }
        }
    }
}

package tr.com.innova.akis.execution;

import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;
import static org.mockito.Mockito.*;
import tr.com.innova.akis.execution.TargetLedgerPort.*;
import tr.com.innova.akis.knowledge.JdbcStagingTransfer.Table;
import tr.com.innova.akis.knowledge.JdbcTransactionBoundary;

/** Isolated schema and a statement-level delay reproduce the observed30s publish boundary. */
class PostgresPublishTimeoutIT {
    @Test void targetInsertExceedingThirtySecondsCommitsWithinConfiguredSixtySecondPolicy() throws Exception {
        String url=System.getenv("AKIS_POSTGRES_RUNTIME_URL");assumeTrue(url!=null,"PostgreSQL fixture not configured");
        String schema="TIMEOUT_IT_"+UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.ROOT);
        String user=System.getenv("AKIS_POSTGRES_RUNTIME_USERNAME"),password=System.getenv("AKIS_POSTGRES_RUNTIME_PASSWORD");
        Table stage=new Table(schema,"STAGE"),target=new Table(schema,"TARGET");
        try(Connection setup=DriverManager.getConnection(url,user,password);Connection writer=DriverManager.getConnection(url,user,password)) {
            try(var sql=setup.createStatement()) {
                sql.execute("CREATE SCHEMA \""+schema+"\"");
                sql.execute("CREATE TABLE "+stage.sql()+" (\"ID\" bigint)");sql.execute("INSERT INTO "+stage.sql()+" VALUES(1),(2)");
                sql.execute("CREATE TABLE "+target.sql()+" (\"ID\" bigint)");sql.execute("INSERT INTO "+target.sql()+" VALUES(99)");
                sql.execute("CREATE FUNCTION \""+schema+"\".delay_publish() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_sleep(31); RETURN NULL; END $$");
                sql.execute("CREATE TRIGGER slow_publish BEFORE INSERT ON "+target.sql()+" FOR EACH STATEMENT EXECUTE FUNCTION \""+schema+"\".delay_publish()");
            }
            try {
                writer.setAutoCommit(false);var ledger=mock(TargetLedgerPort.class);var data=mock(DataLedgerSession.class);
                when(ledger.bindData(any(),any())).thenReturn(data);
                var evidence=new PublishEvidence("HEDEFE_YAZ","a".repeat(64),"b".repeat(64),2,2,0,null,null);
                when(data.preparePublish(evidence)).thenReturn(new PublishPreparation(UUID.randomUUID(),evidence,"guard",false));
                var context=new TargetLedgerContext("c".repeat(64),1,UUID.randomUUID(),UUID.randomUUID(),1,"d".repeat(64),"e".repeat(64));
                long started=System.nanoTime();
                var result=new JdbcPostgresAtomicRefreshWriter(ledger).publish(writer,context,evidence,stage,target,
                    List.of(new JdbcStagedAtomicRefreshWriter.Column("ID","ID")),60,()->{},()->{},JdbcTransactionBoundary.direct(writer),JdbcStagedAtomicRefreshWriter.WriteMode.APPEND);
                assertTrue((System.nanoTime()-started)/1e9>=30,"Exercise a real operation beyond the old30s boundary");
                assertEquals(JdbcStagedAtomicRefreshWriter.Outcome.COMMITTED,result.outcome());
                try(var sql=setup.createStatement();var rows=sql.executeQuery("SELECT count(*) FROM "+target.sql())) {assertTrue(rows.next());assertEquals(3,rows.getLong(1));}
            } finally {
                writer.rollback();try(var sql=setup.createStatement()){sql.execute("DROP SCHEMA \""+schema+"\" CASCADE");}
            }
        }
    }
}

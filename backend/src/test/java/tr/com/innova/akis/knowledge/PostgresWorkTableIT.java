package tr.com.innova.akis.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tr.com.innova.akis.knowledge.JdbcStagingTransfer.Column;
import tr.com.innova.akis.knowledge.JdbcStagingTransfer.QueryOptions;
import tr.com.innova.akis.knowledge.JdbcStagingTransfer.Table;
import tr.com.innova.akis.knowledge.JdbcStagingTransfer.Type;
import tr.com.innova.akis.knowledge.WorkObjectLifecycle.State;

/**
 * Real PostgreSQL work table + COPY transfer acceptance (fixture-gated). The registry is mocked (it needs a live run
 * lease); everything else is the actual adapter code against pg_catalog and the COPY API. A PostgreSQL table stands in
 * for the Oracle source cursor: the transfer only touches it through JDBC types.
 */
class PostgresWorkTableIT {

    @Test
    void createsCopiesSealsVerifiesAndDropsAWorkTable() throws Exception {
        String url = System.getenv("AKIS_POSTGRES_RUNTIME_URL");
        String username = System.getenv("AKIS_POSTGRES_RUNTIME_USERNAME");
        String password = System.getenv("AKIS_POSTGRES_RUNTIME_PASSWORD");
        assumeTrue(url != null && username != null && password != null, "PostgreSQL runtime fixture is not configured");
        String suffix = Long.toHexString(System.nanoTime());
        Table work = new Table("public", "AKIS_C$_IT_" + suffix);
        Table source = new Table("public", "akis_it_src_" + suffix);
        WorkObjectStore store = mock(WorkObjectStore.class);
        UUID allocated = UUID.randomUUID();
        when(store.allocate(any(), eq("WORK_SOURCE_1"), any(), eq("public"), eq(work.name()), any(), any())).thenReturn(allocated);
        var owner = new WorkObjectStore.Owner(UUID.randomUUID(), UUID.randomUUID(), 1, "worker");
        var area = new WorkObjectStore.WorkArea(UUID.randomUUID(), 1);
        PostgresWorkTableManager tables = new PostgresWorkTableManager(store);
        List<WorkTableManagerPort.Column> columns = List.of(
                new WorkTableManagerPort.Column("ID", "NUMERIC(19,0)"),
                new WorkTableManagerPort.Column("AD", "VARCHAR(120)"),
                new WorkTableManagerPort.Column("NOT", "TEXT"),
                new WorkTableManagerPort.Column("ZAMAN", "TIMESTAMP(6)"));
        try (Connection control = DriverManager.getConnection(url, username, password);
                Connection data = DriverManager.getConnection(url, username, password);
                Connection reader = DriverManager.getConnection(url, username, password)) {
            control.setAutoCommit(false);
            data.setAutoCommit(false);
            try (Statement ddl = reader.createStatement()) {
                ddl.execute("create table " + source.sql() + " (\"ID\" numeric(19,0), \"AD\" varchar(120), \"NOT\" text, \"ZAMAN\" timestamp(6))");
                ddl.execute("insert into " + source.sql() + " values (1, 'düz', 'a,b', timestamp '2026-09-22 10:00:00'), (2, 'tırnak \"x\"', E'satır\\nsonu', null), (3, null, '\\N', timestamp '2026-01-01 00:00:00.123456')");
            }
            String database = PostgresWorkTableManager.databaseIdentity(control);
            var created = tables.create(control, owner, database, work, columns, 30, () -> { }, area);
            assertEquals(allocated, created.uuid());
            assertEquals(PostgresWorkStructure.expected(columns), created.structureHash());
            verify(store).transition(owner, allocated, State.CREATING, State.READY, created.objectId(), null);
            tables.verify(control, created, 30);

            var transferColumns = List.of(
                    new Column("SRC", "ID", "ID", Type.NUMBER), new Column("SRC", "AD", "AD", Type.VARCHAR2),
                    new Column("SRC", "NOT", "NOT", Type.VARCHAR2), new Column("SRC", "ZAMAN", "ZAMAN", Type.TIMESTAMP));
            var options = new StagedMappingDefinition.Options(2, 100, 1_000, 10_000_000, false);
            var result = new PostgresCopyStagingTransfer(false).transfer(reader, data, source, work, transferColumns, options, 30, () -> { },
                    JdbcTransactionBoundary.direct(data), new QueryOptions(false, ""));
            assertEquals(3, result.rows());
            assertEquals(64, result.payloadHash().length());

            try (Statement query = control.createStatement(); ResultSet rows = query.executeQuery(
                    "select \"ID\", \"AD\", \"NOT\", \"ZAMAN\" from " + work.sql() + " order by \"ID\"")) {
                rows.next();
                assertEquals(new BigDecimal("1"), rows.getBigDecimal(1)); assertEquals("düz", rows.getString(2)); assertEquals("a,b", rows.getString(3));
                assertEquals("2026-09-22 10:00:00.0", rows.getTimestamp(4).toString());
                rows.next();
                assertEquals("tırnak \"x\"", rows.getString(2)); assertEquals("satır\nsonu", rows.getString(3)); assertNull(rows.getTimestamp(4));
                rows.next();
                assertNull(rows.getString(2)); assertEquals("\\N", rows.getString(3), "literal backslash-N survives quoting");
                assertEquals("2026-01-01 00:00:00.123456", rows.getTimestamp(4).toString());
            }
            control.rollback();

            // Structure drift is detected.
            try (Statement ddl = reader.createStatement()) { ddl.execute("alter table " + work.sql() + " add column extra int"); }
            assertThrows(IllegalStateException.class, () -> tables.verify(control, created, 30));
            try (Statement ddl = reader.createStatement()) { ddl.execute("alter table " + work.sql() + " drop column extra"); }

            when(store.claimCleanup(owner, allocated)).thenReturn(new WorkObjectStore.ObjectRow(allocated, "WORK_SOURCE_1", database, "public", work.name(),
                    created.objectId(), created.structureHash(), State.CLEANUP_PENDING, 3L, result.logicalBytes(), result.payloadHash()));
            tables.cleanup(control, owner, allocated, 30);
            verify(store).transition(owner, allocated, State.CLEANUP_PENDING, State.DROPPED, null, null);
            try (Statement check = reader.createStatement(); ResultSet gone = check.executeQuery("select to_regclass('" + work.sql().replace("\"", "\"\"") + "')")) {
                gone.next(); assertNull(gone.getString(1));
            }
            try (Statement ddl = reader.createStatement()) { ddl.execute("drop table " + source.sql()); }
            assertNotNull(database);
        }
    }
}

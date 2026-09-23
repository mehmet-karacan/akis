package tr.com.innova.akis.knowledge;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static tr.com.innova.akis.knowledge.WorkObjectLifecycle.State;

/**
 * PostgreSQL adapter of {@link WorkTableManagerPort}. The work table lives in the physical schema's work namespace on the
 * target database; DDL is transactional here, so the control connection commits explicitly after each catalog change and
 * a session advisory lock serialises concurrent DDL on the same name. The registry (work object store) stays the only
 * authority for lifecycle; the catalog oid is the object identity that adoption re-verifies.
 */
public final class PostgresWorkTableManager implements WorkTableManagerPort {
    private static final Logger LOG = LoggerFactory.getLogger(PostgresWorkTableManager.class);
    private final WorkObjectStore store;
    private final OracleDdlLockPort ddlLocks;

    public PostgresWorkTableManager(WorkObjectStore store) { this(store, new JdbcPostgresDdlLock()); }

    PostgresWorkTableManager(WorkObjectStore store, OracleDdlLockPort ddlLocks) {
        this.store = Objects.requireNonNull(store); this.ddlLocks = Objects.requireNonNull(ddlLocks);
    }

    @Override
    public Created create(Connection control, WorkObjectStore.Owner owner, String targetDatabaseIdentity, JdbcStagingTransfer.Table table,
            List<Column> columns, int timeout, Runnable checkpoint, WorkObjectStore.WorkArea workArea) {
        columns = List.copyOf(columns);
        if (columns.isEmpty() || columns.size() > 256 || timeout < 1 || timeout > 3600 || !table.name().startsWith("AKIS_")) throw new IllegalArgumentException("Çalışma tablosu sözleşmesi geçersiz.");
        for (Column column : columns) if (!PostgresWorkStructure.supported(column.ddlType())) throw new IllegalArgumentException("Desteklenmeyen çalışma kolonu tipi.");
        if (columns.stream().map(Column::name).distinct().count() != columns.size()) throw new IllegalArgumentException("Çalışma kolonları benzersiz olmalıdır.");
        String structure = PostgresWorkStructure.expected(columns);
        UUID allocated = null;
        boolean creating = false;
        try {
            checkpoint.run();
            String database = databaseIdentity(control);
            if (!database.equals(targetDatabaseIdentity)) throw new IllegalStateException("Çalışma bağlantısı hedef veritabanıyla eşleşmiyor.");
            if (!canCreate(control, table.owner())) throw new IllegalStateException("Çalışma şemasında CREATE yetkisi yok.");
            try (var ignored = ddlLocks.acquire(control, lockName(database, table), timeout)) {
                if (objectId(control, table) != null) throw new IllegalStateException("Çalışma adı zaten var; mevcut nesne sahiplenilmedi.");
                allocated = store.allocate(owner, "WORK_SOURCE_1", database, table.owner(), table.name(), structure, workArea);
                store.transition(owner, allocated, State.ALLOCATED, State.CREATING, null, null); creating = true;
                checkpoint.run();
                String sql = "CREATE TABLE " + table.sql() + " (" + String.join(",", columns.stream().map(c -> quote(c.name()) + " " + c.ddlType()).toList()) + ")";
                execute(control, sql, timeout);
                commitIfNeeded(control);
                Long id = objectId(control, table);
                if (id == null) throw new IllegalStateException("Oluşturulan nesnenin kimliği doğrulanamadı.");
                if (!structure.equals(PostgresWorkStructure.read(control, table, timeout))) throw new IllegalStateException("Oluşturulan nesnenin kolonları uyuşmuyor.");
                store.transition(owner, allocated, State.CREATING, State.READY, id, null); creating = false;
                return new Created(allocated, database, table, id, structure);
            }
        } catch (SQLException | RuntimeException failure) {
            rollbackQuietly(control);
            if (creating) try { store.transition(owner, allocated, State.CREATING, State.REVIEW_REQUIRED, null, null); } catch (RuntimeException ignored) { }
            LOG.warn("PostgreSQL work table {} could not be prepared: {}", table.sql(), failure.toString());
            throw new IllegalStateException("Çalışma tablosu hazırlığı doğrulanamadı; otomatik DROP/tekrar yapılmadı.");
        }
    }

    /**
     * PostgreSQL needs no grant: a target "owner" is a schema rather than a role, and the publish session connects as the
     * same runtime role that created the work table, so it can already read it. Only the identity is re-verified here.
     */
    @Override
    public void grantRead(Connection control, Created created, String targetUser, int timeout, Runnable checkpoint) {
        StagedMappingDefinition.identifier(targetUser);
        try {
            try (var ignored = ddlLocks.acquire(control, lockName(created.databaseIdentity(), created.table()), timeout)) {
                verify(control, created, timeout); checkpoint.run();
            }
        } catch (SQLException ex) { rollbackQuietly(control); throw new IllegalStateException("Çalışma tablosu okuma yetkisi doğrulanamadı."); }
    }

    @Override
    public void verify(Connection control, Created created, int timeout) throws SQLException {
        if (!created.databaseIdentity().equals(databaseIdentity(control))
                || !Objects.equals(created.objectId(), objectId(control, created.table()))
                || !created.structureHash().equals(PostgresWorkStructure.read(control, created.table(), timeout)))
            throw new IllegalStateException("Çalışma nesnesi kimliği veya yapısı değişmiş.");
    }

    @Override
    public void cleanup(Connection control, WorkObjectStore.Owner owner, UUID object, int timeout) {
        if (timeout < 1 || timeout > 3600) throw new IllegalArgumentException("Temizleme zaman sınırı geçersiz.");
        var row = store.claimCleanup(owner, object);
        var table = new JdbcStagingTransfer.Table(row.owner(), row.name());
        try {
            try (var ignored = ddlLocks.acquire(control, lockName(row.databaseIdentity(), table), timeout)) {
                Long actualId = objectId(control, table);
                if (actualId == null) throw new IllegalStateException("Nesne yok; önceki DROP sonucunun mutabakatı gerekir.");
                WorkObjectLifecycle.requireDrop(row.state(), row.databaseIdentity(), databaseIdentity(control),
                        row.owner(), row.owner(), row.objectId(), actualId, row.structureHash(),
                        PostgresWorkStructure.read(control, table, timeout), true);
                execute(control, "DROP TABLE " + table.sql(), timeout);
                commitIfNeeded(control);
                if (objectId(control, table) != null) throw new IllegalStateException("DROP sonrası nesne hâlâ var.");
                store.transition(owner, object, State.CLEANUP_PENDING, State.DROPPED, null, null);
            }
        } catch (SQLException | RuntimeException failure) {
            rollbackQuietly(control);
            try { store.transition(owner, object, State.CLEANUP_PENDING, State.REVIEW_REQUIRED, null, null); } catch (RuntimeException ignored) { }
            LOG.warn("PostgreSQL work table {} cleanup failed: {}", table.sql(), failure.toString());
            throw new IllegalStateException("Çalışma tablosu temizliği doğrulanamadı; tekrar DROP yapılmadı.");
        }
    }

    /** Same identity the discovery adapter pins on the connection: database name + pg_database oid. */
    public static String databaseIdentity(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT current_database(), d.oid FROM pg_catalog.pg_database d WHERE d.datname = current_database()");
                ResultSet result = statement.executeQuery()) {
            if (!result.next()) throw new SQLException("Missing database identity");
            return KmCanonical.hash("POSTGRESQL_DB_V1|" + result.getString(1) + "|" + result.getLong(2));
        }
    }

    private static boolean canCreate(Connection connection, String schema) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_catalog.has_schema_privilege(?, 'CREATE')")) {
            statement.setString(1, schema);
            try (ResultSet result = statement.executeQuery()) { return result.next() && result.getBoolean(1); }
        }
    }

    private static Long objectId(Connection connection, JdbcStagingTransfer.Table table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT c.oid, c.relkind::text FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = ? AND c.relname = ?")) {
            statement.setString(1, table.owner()); statement.setString(2, table.name());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null; long id = result.getLong(1);
                if (!"r".equals(result.getString(2)) || result.next()) throw new SQLException("Ambiguous object identity"); return id;
            }
        }
    }

    private static void execute(Connection connection, String sql, int timeout) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) { statement.setQueryTimeout(timeout); statement.execute(); }
    }

    private static void commitIfNeeded(Connection connection) throws SQLException { if (!connection.getAutoCommit()) connection.commit(); }

    private static void rollbackQuietly(Connection connection) {
        try { if (!connection.getAutoCommit()) connection.rollback(); } catch (SQLException ignored) { }
    }

    private static String lockName(String database, JdbcStagingTransfer.Table table) { return "AKIS_DDL/1/" + database + "/" + table.owner() + "/" + table.name(); }

    private static String quote(String name) { return "\"" + StagedMappingDefinition.identifier(name) + "\""; }
}

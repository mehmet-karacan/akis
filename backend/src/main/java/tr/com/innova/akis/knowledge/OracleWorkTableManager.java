package tr.com.innova.akis.knowledge;

import java.sql.*;
import java.util.*;
import static tr.com.innova.akis.knowledge.WorkObjectLifecycle.State;

/** DDL runs on a dedicated work-owner connection, never the target data transaction. */
public final class OracleWorkTableManager {
    public record Column(String name,String oracleType) {
        public Column {
            StagedMappingDefinition.identifier(name);
            if (oracleType==null || !oracleType.matches("NUMBER(\\(([1-9]|[12][0-9]|3[0-8])(,-?([0-9]|[1-7][0-9]|8[0-4]))?\\))?|VARCHAR2\\(([1-9][0-9]{0,3}) CHAR\\)|NVARCHAR2\\(([1-9][0-9]{0,3})\\)|DATE|TIMESTAMP\\([0-9]\\)"))
                throw new IllegalArgumentException("Desteklenmeyen çalışma kolonu tipi.");
        }
    }
    public record Created(UUID uuid,String databaseIdentity,JdbcStagingTransfer.Table table,long objectId,String structureHash) { }
    private final WorkObjectStore store;
    public OracleWorkTableManager(WorkObjectStore store) { this.store=Objects.requireNonNull(store); }
    public Created create(Connection control,WorkObjectStore.Owner owner,String targetDatabaseIdentity,
            JdbcStagingTransfer.Table table,List<Column> columns,int timeout,Runnable checkpoint,WorkObjectStore.WorkArea workArea) {
        columns=List.copyOf(columns);
        if (columns.isEmpty() || columns.size()>256 || timeout<1 || timeout>3600 || !table.name().startsWith("AKIS_")) throw new IllegalArgumentException("Çalışma tablosu sözleşmesi geçersiz.");
        if (columns.stream().map(Column::name).distinct().count()!=columns.size()) throw new IllegalArgumentException("Çalışma kolonları benzersiz olmalıdır.");
        String structure=OracleWorkStructure.expected(columns);
        UUID allocated=null;
        boolean creating=false;
        try {
            checkpoint.run();
            String database=databaseIdentity(control);
            if (!database.equals(targetDatabaseIdentity) || !sessionUser(control).equals(table.owner())) throw new IllegalStateException("Çalışma hesabı veya hedef DB/PDB eşleşmiyor.");
            if (objectId(control,table)!=null) throw new IllegalStateException("Çalışma adı zaten var; mevcut nesne sahiplenilmedi.");
            allocated=store.allocate(owner,"WORK_SOURCE_1",database,table.owner(),table.name(),structure,workArea);
            store.transition(owner,allocated,State.ALLOCATED,State.CREATING,null,null); creating=true;
            checkpoint.run();
            String sql="CREATE TABLE "+table.sql()+" ("+String.join(",",columns.stream().map(c->quote(c.name())+" "+c.oracleType()).toList())+")";
            execute(control,sql,timeout);
            Long id=objectId(control,table);
            if (id==null) throw new IllegalStateException("Oluşturulan nesnenin kimliği doğrulanamadı.");
            if (!structure.equals(OracleWorkStructure.read(control,table,timeout))) throw new IllegalStateException("Oluşturulan nesnenin kolonları uyuşmuyor.");
            store.transition(owner,allocated,State.CREATING,State.READY,id,null); creating=false;
            return new Created(allocated,database,table,id,structure);
        } catch(SQLException | RuntimeException failure) {
            if (creating) try { store.transition(owner,allocated,State.CREATING,State.REVIEW_REQUIRED,null,null); } catch(RuntimeException ignored) { }
            throw new IllegalStateException("Çalışma tablosu hazırlığı doğrulanamadı; otomatik DROP/tekrar yapılmadı.");
        }
    }
    public void grantRead(Connection control,Created created,String targetUser,int timeout,Runnable checkpoint) {
        StagedMappingDefinition.identifier(targetUser);
        try {
            verify(control,created,timeout); checkpoint.run();
            if (!targetUser.equals(created.table().owner())) execute(control,"GRANT SELECT ON "+created.table().sql()+" TO "+quote(targetUser),timeout);
        } catch(SQLException ex) { throw new IllegalStateException("Çalışma tablosu okuma yetkisi doğrulanamadı."); }
    }
    public void verify(Connection control,Created created,int timeout) throws SQLException {
        if (!created.databaseIdentity().equals(databaseIdentity(control)) || !created.table().owner().equals(sessionUser(control))
                || !Objects.equals(created.objectId(),objectId(control,created.table()))
                || !created.structureHash().equals(OracleWorkStructure.read(control,created.table(),timeout)))
            throw new IllegalStateException("Çalışma nesnesi kimliği veya yapısı değişmiş.");
    }
    /** Only the registry can grant cleanup. Do not PURGE: Oracle recycle-bin recovery remains available. */
    public void cleanup(Connection control,WorkObjectStore.Owner owner,UUID object,int timeout) {
        if(timeout<1 || timeout>3600) throw new IllegalArgumentException("Temizleme zaman sınırı geçersiz.");
        var row=store.claimCleanup(owner,object);
        var table=new JdbcStagingTransfer.Table(row.owner(),row.name());
        try {
            Long actualId=objectId(control,table);
            if(actualId==null) throw new IllegalStateException("Nesne yok; önceki DROP sonucunun mutabakatı gerekir.");
            WorkObjectLifecycle.requireDrop(row.state(),row.databaseIdentity(),databaseIdentity(control),
                    row.owner(),sessionUser(control),row.objectId(),actualId,row.structureHash(),
                    OracleWorkStructure.read(control,table,timeout),true);
            execute(control,"DROP TABLE "+table.sql(),timeout);
            if(objectId(control,table)!=null) throw new IllegalStateException("DROP sonrası nesne hâlâ var.");
            store.transition(owner,object,State.CLEANUP_PENDING,State.DROPPED,null,null);
        } catch(SQLException | RuntimeException failure) {
            try { store.transition(owner,object,State.CLEANUP_PENDING,State.REVIEW_REQUIRED,null,null); } catch(RuntimeException ignored) { }
            throw new IllegalStateException("Çalışma tablosu temizliği doğrulanamadı; tekrar DROP yapılmadı.");
        }
    }
    public static String databaseIdentity(Connection connection) throws SQLException {
        try(PreparedStatement statement=connection.prepareStatement("SELECT SYS_CONTEXT('USERENV','DB_UNIQUE_NAME'), SYS_CONTEXT('USERENV','CON_NAME') FROM DUAL");ResultSet result=statement.executeQuery()) {
            if(!result.next()) throw new SQLException("Missing database identity");
            String db=result.getString(1),pdb=result.getString(2);
            if(db==null || db.isBlank() || pdb==null || pdb.isBlank() || result.next()) throw new SQLException("Ambiguous database identity");
            return KmCanonical.hash(db.toUpperCase(Locale.ROOT)+"\u0000"+pdb.toUpperCase(Locale.ROOT));
        }
    }
    private static String sessionUser(Connection connection) throws SQLException {
        try(PreparedStatement statement=connection.prepareStatement("SELECT SYS_CONTEXT('USERENV','SESSION_USER') FROM DUAL");ResultSet result=statement.executeQuery()) {
            if(!result.next()) throw new SQLException("Missing session identity"); return result.getString(1);
        }
    }
    private static Long objectId(Connection connection,JdbcStagingTransfer.Table table) throws SQLException {
        try(PreparedStatement statement=connection.prepareStatement("SELECT OBJECT_ID, OBJECT_TYPE FROM ALL_OBJECTS WHERE OWNER=? AND OBJECT_NAME=? AND SUBOBJECT_NAME IS NULL")) {
            statement.setString(1,table.owner()); statement.setString(2,table.name());
            try(ResultSet result=statement.executeQuery()) {
                if(!result.next()) return null; long id=result.getLong(1);
                if(!"TABLE".equals(result.getString(2)) || result.next()) throw new SQLException("Ambiguous object identity"); return id;
            }
        }
    }
    private static void execute(Connection connection,String sql,int timeout) throws SQLException {
        try(PreparedStatement statement=connection.prepareStatement(sql)) { statement.setQueryTimeout(timeout); statement.execute(); }
    }
    private static String quote(String name) { return "\""+StagedMappingDefinition.identifier(name)+"\""; }
}

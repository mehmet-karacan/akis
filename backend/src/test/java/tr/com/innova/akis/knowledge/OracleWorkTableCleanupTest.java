package tr.com.innova.akis.knowledge;

import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static tr.com.innova.akis.knowledge.WorkObjectLifecycle.State;

class OracleWorkTableCleanupTest {
    private final Connection connection=mock(Connection.class);
    private final WorkObjectStore store=mock(WorkObjectStore.class);
    private final WorkObjectStore.Owner owner=new WorkObjectStore.Owner(UUID.randomUUID(),UUID.randomUUID(),1,"worker");
    private final UUID object=UUID.randomUUID();
    private final String shape="a".repeat(64);
    private final PreparedStatement drop=mock(PreparedStatement.class);
    private final OracleDdlLockPort locks=mock(OracleDdlLockPort.class);

    private OracleWorkTableManager manager() throws SQLException {
        when(locks.acquire(eq(connection),anyString(),eq(30))).thenReturn(()->{});
        return new OracleWorkTableManager(store,locks);
    }

    private void setup(long actualId) throws SQLException {
        String database=KmCanonical.hash("DB\u0000PDB");
        when(store.claimCleanup(owner,object)).thenReturn(new WorkObjectStore.ObjectRow(object,"WORK_SOURCE_1",database,
                "WORK","AKIS_C_TEST",123L,shape,State.CLEANUP_PENDING,10L,100L,"b".repeat(64)));
        when(connection.prepareStatement(contains("DB_UNIQUE_NAME"))).thenAnswer(call->{
            var s=mock(PreparedStatement.class);var r=mock(ResultSet.class);when(s.executeQuery()).thenReturn(r);
            when(r.next()).thenReturn(true,false);when(r.getString(1)).thenReturn("DB");when(r.getString(2)).thenReturn("PDB");return s;
        });
        when(connection.prepareStatement(contains("SESSION_USER"))).thenAnswer(call->{
            var s=mock(PreparedStatement.class);var r=mock(ResultSet.class);when(s.executeQuery()).thenReturn(r);
            when(r.next()).thenReturn(true,false);when(r.getString(1)).thenReturn("WORK");return s;
        });
        var objects=mock(PreparedStatement.class); var before=mock(ResultSet.class);var after=mock(ResultSet.class);
        when(connection.prepareStatement(contains("ALL_OBJECTS"))).thenReturn(objects);
        when(objects.executeQuery()).thenReturn(before,after);
        when(before.next()).thenReturn(true,false);when(before.getLong(1)).thenReturn(actualId);when(before.getString(2)).thenReturn("TABLE");
        when(after.next()).thenReturn(false);
        when(connection.prepareStatement(startsWith("DROP TABLE"))).thenReturn(drop);
    }
    @Test void exactRegisteredObjectIsDroppedOnceWithoutPurge() throws Exception {
        setup(123);
        try(var structure=mockStatic(OracleWorkStructure.class)) {
            structure.when(()->OracleWorkStructure.read(eq(connection),any(),eq(30))).thenReturn(shape);
            manager().cleanup(connection,owner,object,30);
        }
        verify(connection).prepareStatement("DROP TABLE \"WORK\".\"AKIS_C_TEST\"");
        verify(drop,times(1)).execute();
        verify(store).transition(owner,object,State.CLEANUP_PENDING,State.DROPPED,null,null);
    }
    @Test void replacedObjectWithSameNameIsNeverDropped() throws Exception {
        setup(456);
        try(var structure=mockStatic(OracleWorkStructure.class)) {
            structure.when(()->OracleWorkStructure.read(eq(connection),any(),eq(30))).thenReturn(shape);
            var manager=manager();
            assertThrows(IllegalStateException.class,()->manager.cleanup(connection,owner,object,30));
        }
        verify(drop,never()).execute();
        verify(store).transition(owner,object,State.CLEANUP_PENDING,State.REVIEW_REQUIRED,null,null);
    }
    @Test void unconfirmedRunCannotAuthorizeDatabaseAccess() {
        when(store.claimCleanup(owner,object)).thenThrow(new IllegalStateException());
        assertThrows(IllegalStateException.class,()->manager().cleanup(connection,owner,object,30));
        verifyNoInteractions(connection);
    }
    @Test void lostDropAcknowledgementIsNotRetried() throws Exception {
        setup(123);when(drop.execute()).thenThrow(new SQLException("secret endpoint"));
        try(var structure=mockStatic(OracleWorkStructure.class)) {
            structure.when(()->OracleWorkStructure.read(eq(connection),any(),eq(30))).thenReturn(shape);
            var manager=manager();
            var error=assertThrows(IllegalStateException.class,()->manager.cleanup(connection,owner,object,30));
            assertFalse(error.toString().contains("secret"));
        }
        verify(drop,times(1)).execute();
        verify(store).transition(owner,object,State.CLEANUP_PENDING,State.REVIEW_REQUIRED,null,null);
    }
}

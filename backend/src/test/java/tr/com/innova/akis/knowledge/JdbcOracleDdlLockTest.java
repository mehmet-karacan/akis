package tr.com.innova.akis.knowledge;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;

class JdbcOracleDdlLockTest {

    @Test
    void holdsSessionLockUntilExplicitClose() throws Exception {
        Connection connection = mock(Connection.class);
        CallableStatement request = mock(CallableStatement.class);
        CallableStatement release = mock(CallableStatement.class);
        when(connection.prepareCall("BEGIN ? := DBMS_LOCK.REQUEST(?, 6, ?, FALSE); END;"))
                .thenReturn(request);
        when(connection.prepareCall("BEGIN ? := DBMS_LOCK.RELEASE(?); END;"))
                .thenReturn(release);
        when(request.getInt(1)).thenReturn(0);
        when(release.getInt(1)).thenReturn(0);

        try (var ignored = new JdbcOracleDdlLock().acquire(connection,
                "AKIS_DDL/1/db/WORK/AKIS_C_TEST", 30)) {
            verify(request).execute();
        }
        verify(release).execute();
        verify(request).setInt(3, 30);
    }

    @Test
    void busyLockFailsClosed() throws Exception {
        Connection connection = mock(Connection.class);
        CallableStatement request = mock(CallableStatement.class);
        when(connection.prepareCall("BEGIN ? := DBMS_LOCK.REQUEST(?, 6, ?, FALSE); END;"))
                .thenReturn(request);
        when(request.getInt(1)).thenReturn(1);

        assertThrows(SQLException.class, () -> new JdbcOracleDdlLock().acquire(connection,
                "AKIS_DDL/1/db/WORK/AKIS_C_TEST", 1));
    }
}

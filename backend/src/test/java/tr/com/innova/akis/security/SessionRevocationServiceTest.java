package tr.com.innova.akis.security;

import static org.mockito.ArgumentMatchers.assertArg;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class SessionRevocationServiceTest {

    @Test
    void deletesOtherSessionsForTheSamePrincipal() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql("""
                delete from akis.spring_session
                 where principal_name = :principalName
                   and session_id <> :currentSessionId
                """)).thenReturn(statement);
        when(statement.param("principalName", "mehmet")).thenReturn(statement);
        when(statement.param("currentSessionId", "current-session")).thenReturn(statement);
        when(statement.update()).thenReturn(2);
        var service = new SessionRevocationService(jdbc);

        service.revokeOtherSessions("mehmet", "current-session");

        verify(statement).param("principalName", "mehmet");
        verify(statement).param("currentSessionId", "current-session");
        verify(statement).update();
    }
}

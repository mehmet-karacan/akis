package tr.com.innova.akis.security;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
final class SessionRevocationService {

    private final JdbcClient jdbc;

    SessionRevocationService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void revokeOtherSessions(String principalName, String currentSessionId) {
        jdbc.sql("""
                delete from akis.spring_session
                 where principal_name = :principalName
                   and session_id <> :currentSessionId
                """)
                .param("principalName", principalName)
                .param("currentSessionId", currentSessionId)
                .update();
    }
}

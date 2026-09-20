package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import tr.com.innova.akis.metadata.ApiException;

class VariableTestServiceTest {
    final UUID project = UUID.randomUUID(), definition = UUID.randomUUID(), logical = UUID.randomUUID(), environment = UUID.randomUUID(), version = UUID.randomUUID(), physical = UUID.randomUUID();
    final JdbcClient jdbc = mock(JdbcClient.class);
    final JdbcProcedureVariableRuntime profiles = mock(JdbcProcedureVariableRuntime.class);
    final RuntimeOracleConnectionProvider connections = mock(RuntimeOracleConnectionProvider.class);
    final JdbcClient.StatementSpec binding = mock(JdbcClient.StatementSpec.class, RETURNS_SELF), insert = mock(JdbcClient.StatementSpec.class, RETURNS_SELF), history = mock(JdbcClient.StatementSpec.class, RETURNS_SELF);
    final VariableTestService service = new VariableTestService(jdbc, profiles, connections);
    final VariableTestController.Request request = new VariableTestController.Request(logical, environment, "DATE", "SELECT SYSDATE - 2 FROM DUAL");
    @SuppressWarnings("unchecked")
    void binding(boolean found) {
        when(jdbc.sql(contains("from akis.proje p"))).thenReturn(binding);
        JdbcClient.MappedQuerySpec<VariableTestService.Binding> query = mock(JdbcClient.MappedQuerySpec.class);
        when(binding.query(org.mockito.ArgumentMatchers.<RowMapper<VariableTestService.Binding>>any())).thenReturn(query);
        when(query.optional()).thenReturn(found ? Optional.of(new VariableTestService.Binding(version,physical,"WORK_SCHEMA")) : Optional.empty());
    }
    @SuppressWarnings("unchecked")
    void persistence(boolean success) {
        when(jdbc.sql(contains("insert into akis.degisken_test_gecmisi"))).thenReturn(insert);
        JdbcClient.MappedQuerySpec<Long> id = mock(JdbcClient.MappedQuerySpec.class);
        when(insert.query(Long.class)).thenReturn(id); when(id.single()).thenReturn(1L);
        when(jdbc.sql(contains("select h.*"))).thenReturn(history);
        JdbcClient.MappedQuerySpec<VariableTestService.Result> result = mock(JdbcClient.MappedQuerySpec.class);
        when(history.query(org.mockito.ArgumentMatchers.<RowMapper<VariableTestService.Result>>any())).thenReturn(result);
        when(result.list()).thenReturn(List.of(new VariableTestService.Result(1,"TEST",success,success ? "2026-09-15 12:00:00.0" : null,"DATE",2,success ? null : "VARIABLE_RESULT_INVALID","Test","Logical",OffsetDateTime.now())));
    }
    @Test void executesUnsavedQueryAgainstResolvedOwnerAndOnlyWritesTestHistory() throws Exception {
        binding(true); persistence(true);
        var profile = mock(RuntimeOracleConnectionMetadataPort.ConnectionProfile.class);
        when(profiles.profile(project,version,physical)).thenReturn(profile);
        var session = mock(RuntimeOracleConnectionProvider.RuntimeOracleSession.class);
        var connection = mock(Connection.class); var statement = mock(PreparedStatement.class); var rows = mock(ResultSet.class); var meta = mock(ResultSetMetaData.class);
        when(connections.openVariable(profile,version,"WORK_SCHEMA")).thenReturn(session);
        when(session.connection()).thenReturn(connection); when(connection.prepareStatement(request.query())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows); when(rows.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(1); when(rows.next()).thenReturn(true,false);
        when(rows.getTimestamp(1)).thenReturn(Timestamp.valueOf("2026-09-15 12:00:00"));
        assertTrue(service.test(project,definition,request).success());
        verify(statement).setMaxRows(2); verify(session).close(); verify(rows).close();
        verify(binding).param("project",project); verify(binding).param("definition",definition);
        verify(binding).param("logical",logical); verify(binding).param("environment",environment);
        verify(insert).param("value","2026-09-15 12:00:00.0");
        verify(jdbc,never()).sql(contains("degisken_deger_gecmisi")); verify(jdbc,never()).sql(contains("tanim_taslak"));
    }
    @Test void refusesUnmappedOrCrossProjectContextBeforeOpeningConnection() {
        binding(false); assertThrows(ApiException.class, () -> service.test(project,definition,request));
        verifyNoInteractions(connections,profiles,insert);
    }
    @ParameterizedTest
    @ValueSource(strings={"empty","multipleRows","multipleColumns","nullValue"})
    void invalidScalarIsRecordedAsFailedTestWithoutChangingRuntimeValue(String shape) throws Exception {
        binding(true); persistence(false);
        var profile=mock(RuntimeOracleConnectionMetadataPort.ConnectionProfile.class);
        var session=mock(RuntimeOracleConnectionProvider.RuntimeOracleSession.class);
        var connection=mock(Connection.class); var statement=mock(PreparedStatement.class);
        var rows=mock(ResultSet.class); var meta=mock(ResultSetMetaData.class);
        when(profiles.profile(project,version,physical)).thenReturn(profile);
        when(connections.openVariable(profile,version,"WORK_SCHEMA")).thenReturn(session);
        when(session.connection()).thenReturn(connection);
        when(connection.prepareStatement(request.query())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows); when(rows.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(shape.equals("multipleColumns")?2:1);
        when(rows.next()).thenReturn(!shape.equals("empty"),shape.equals("multipleRows"),false);
        when(rows.getTimestamp(1)).thenReturn(shape.equals("nullValue")?null:Timestamp.valueOf("2026-09-15 12:00:00"));
        assertFalse(service.test(project,definition,request).success());
        verify(insert).param("success",false); verify(insert).param("value",null);
        verify(insert).param("error","VARIABLE_RESULT_INVALID");
        verify(rows).close(); verify(statement).close(); verify(session).close();
        verify(jdbc,never()).sql(contains("degisken_deger_gecmisi"));
        verify(jdbc,never()).sql(contains("tanim_taslak"));
    }
    @Test void unsafeSqlIsRejectedBeforeMetadataOrRemoteCalls() {
        assertThrows(ApiException.class, () -> service.test(project,definition,new VariableTestController.Request(logical,environment,"STRING","DELETE FROM T")));
        verifyNoInteractions(jdbc,connections,profiles);
    }
    @Test void storesSanitizedFailureWithoutLeakingJdbcMessage() {
        binding(true); persistence(false);
        when(profiles.profile(project,version,physical)).thenThrow(new IllegalStateException("credentials and SQL must never leak"));
        service.test(project,definition,request);
        verify(insert).param("error","VARIABLE_CONNECTION_FAILED"); verify(insert).param("value",null); verify(insert).param("success",false);
    }
    @Test void preservesSafeConnectionFailureCategoryForActionableFeedback() {
        binding(true); persistence(false);
        when(profiles.profile(project,version,physical)).thenThrow(
            new RuntimeOracleConnectionException(RuntimeOracleConnectionException.Failure.CREDENTIAL_UNAVAILABLE));
        service.test(project,definition,request);
        verify(insert).param("error","VARIABLE_CREDENTIAL_UNAVAILABLE");
        verify(insert).param("value",null);
    }
}

package tr.com.innova.akis.execution;

import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.*;
import tools.jackson.databind.ObjectMapper;

/** Real PostgreSQL persistence, isolated fixture; Oracle JDBC response is simulated. */
class CleanVariableHistoryIT {
    @Test void retainsAllLatestAndNoneAndReusesRunSnapshot() throws Exception {
        String url = System.getenv("SPRING_DATASOURCE_URL");
        if (url == null || !url.matches(".*/akis_execution_test_[0-9]+$")) throw new IllegalStateException("Isolated database required");
        var ds = new DriverManagerDataSource(url,System.getenv("SPRING_DATASOURCE_USERNAME"),System.getenv("SPRING_DATASOURCE_PASSWORD"));
        var jdbc = JdbcClient.create(ds);
        long p=jdbc.sql("insert into akis.proje(kod,ad) values ('VARIABLE_IT','Variable IT') returning id").query(Long.class).single();
        UUID project=jdbc.sql("select uuid from akis.proje where id=:p").param("p",p).query(UUID.class).single();
        UUID definition=jdbc.sql("insert into akis.tanim(proje_id,tur,kod,ad) values (:p,'DEGISKEN','D','Date') returning uuid").param("p",p).query(UUID.class).single();
        UUID environment=jdbc.sql("insert into akis.ortam(proje_id,kod,ad) values (:p,'TEST','Test') returning uuid").param("p",p).query(UUID.class).single();
        UUID logical=jdbc.sql("insert into akis.mantiksal_sema(proje_id,kod,ad) values (:p,'LS','Logical') returning uuid").param("p",p).query(UUID.class).single();
        long b=jdbc.sql("insert into akis.baglanti(proje_id,kod,ad,saglayici_turu) values (:p,'DB','Database','ORACLE') returning id").param("p",p).query(Long.class).single();
        UUID version=jdbc.sql("insert into akis.baglanti_surumu(proje_id,baglanti_id,surum_no,baglanti_modu,jndi_adi) values (:p,:b,1,'JNDI','test') returning uuid").param("p",p).param("b",b).query(UUID.class).single();
        var provider=mock(RuntimeOracleConnectionProvider.class);
        var session=mock(RuntimeOracleConnectionProvider.RuntimeOracleSession.class);
        var connection=mock(Connection.class); var statement=mock(PreparedStatement.class); var rows=mock(ResultSet.class); var meta=mock(ResultSetMetaData.class);
        when(provider.openVariable(any(),eq(version))).thenReturn(session);
        when(session.connection()).thenReturn(connection); when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows); when(rows.getMetaData()).thenReturn(meta); when(meta.getColumnCount()).thenReturn(1);
        when(rows.next()).thenReturn(true,false,true,false,true,false,true,false,true,false,true,false);
        var date=Timestamp.valueOf("2026-09-14 13:22:33"); when(rows.getTimestamp(1)).thenReturn(date);
        var mapper=new ObjectMapper();
        var runtime=spy(new JdbcProcedureVariableRuntime(jdbc,provider,mapper,new DataSourceTransactionManager(ds)));
        doReturn(mock(RuntimeOracleConnectionMetadataPort.ConnectionProfile.class)).when(runtime).profile(any(),any(),any());
        for (String mode : List.of("ALL","LATEST","NONE")) {
            var node=mapper.createObjectNode();
            node.putObject("variableBindings").putObject(definition.toString()).put("projectUuid",project.toString())
                .put("environmentUuid",environment.toString()).put("logicalSchemaUuid",logical.toString())
                .put("connectionVersionUuid",version.toString()).put("physicalSchemaUuid",UUID.randomUUID().toString())
                .put("historyMode",mode).put("type","DATE").put("query","SELECT SYSDATE - 1 FROM DUAL");
            var plan=new ProcedureRuntimePlan(1,"a".repeat(64),"b".repeat(64),"c".repeat(64),definition,UUID.randomUUID(),List.of(),Map.of(),node);
            var parameter=new ProcedureRuntimePlan.ParameterValue(ProcedureRuntimePlan.ParameterType.DATE,null,ProcedureRuntimePlan.ParameterSource.REFRESH_QUERY,"SELECT SYSDATE - 1 FROM DUAL",definition,logical,mode);
            UUID run=UUID.randomUUID(); assertEquals(date,runtime.resolve(run,plan,parameter));
            if (!mode.equals("NONE")) assertEquals(date,runtime.resolve(run,plan,parameter));
            assertEquals(date,runtime.resolve(UUID.randomUUID(),plan,parameter));
        }
        assertEquals(2,jdbc.sql("select count(*) from akis.degisken_deger_gecmisi where tanim_uuid=:d and gecmis_modu='ALL'").param("d",definition).query(Integer.class).single());
        assertEquals(1,jdbc.sql("select count(*) from akis.degisken_deger_gecmisi where tanim_uuid=:d and gecmis_modu='LATEST'").param("d",definition).query(Integer.class).single());
        verify(provider,times(6)).openVariable(any(),eq(version));
        var invalidPlan=new ProcedureRuntimePlan(1,"a".repeat(64),"b".repeat(64),"c".repeat(64),definition,UUID.randomUUID(),List.of(),Map.of(),mapper.createObjectNode());
        assertThrows(SQLException.class,()->runtime.resolve(UUID.randomUUID(),invalidPlan,new ProcedureRuntimePlan.ParameterValue(ProcedureRuntimePlan.ParameterType.DATE,null,ProcedureRuntimePlan.ParameterSource.REFRESH_QUERY,"SELECT SYSDATE - 1 FROM DUAL",definition,logical,"ALL")));
    }
}

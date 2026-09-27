package tr.com.innova.akis.scenario;

import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.metadata.DefinitionContentValidator;
import tr.com.innova.akis.projectbundle.SecretValueSanitizer;

/** Calls the real scenario compiler from cross-package integration tests without widening production APIs. */
public final class ScenarioAcceptanceBridge {
    private ScenarioAcceptanceBridge() {
    }

    public static UUID compile(JdbcClient jdbc, DataSource dataSource, ObjectMapper mapper,
            UUID projectUuid, UUID definitionUuid, UUID versionUuid) {
        var service = new ScenarioService(new JdbcScenarioStore(jdbc, mapper),
                new ScenarioPlanCompiler(mapper, new DefinitionContentValidator(), new SecretValueSanitizer()));
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource))
                .execute(status -> service.compile(projectUuid, definitionUuid, versionUuid).scenario().uuid());
    }
}

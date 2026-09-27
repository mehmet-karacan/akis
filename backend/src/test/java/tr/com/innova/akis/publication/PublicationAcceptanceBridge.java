package tr.com.innova.akis.publication;

import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.execution.PendingRecipeConsumer;
import tr.com.innova.akis.execution.PilotRuntimePlanResolver;
import tr.com.innova.akis.execution.ProcedureRuntimePlanResolver;
import tr.com.innova.akis.metadata.DefinitionContentValidator;
import tr.com.innova.akis.projectbundle.SecretValueSanitizer;

/** Uses the real publication service for imported-recipe integration acceptance. */
public final class PublicationAcceptanceBridge {
    private PublicationAcceptanceBridge() {
    }

    public static UUID publish(JdbcClient jdbc, DataSource dataSource, ObjectMapper mapper,
            UUID projectUuid, UUID scenarioUuid, UUID environmentUuid) {
        var sanitizer = new SecretValueSanitizer();
        var service = new PublicationService(new JdbcPublicationStore(jdbc, mapper), mapper,
                new PilotRuntimePlanResolver(mapper, sanitizer),
                new ProcedureRuntimePlanResolver(mapper, sanitizer, new DefinitionContentValidator()), sanitizer);
        service.configurePendingRecipeConsumer(new PendingRecipeConsumer(jdbc));
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource))
                .execute(status -> service.create(projectUuid, scenarioUuid, environmentUuid).publication().uuid());
    }
}

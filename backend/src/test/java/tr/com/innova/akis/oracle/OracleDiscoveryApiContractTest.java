package tr.com.innova.akis.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

class OracleDiscoveryApiContractTest {

    @Test
    void discoveryColumnExposesCanonicalAndExecutionCapabilities() {
        var view = OracleDiscoveryController.ColumnView.from(
                new OracleDiscoveryModels.ColumnMetadata(
                        "PAYLOAD", java.sql.Types.CLOB, "CLOB", 1,
                        null, null, true, null));

        assertEquals("TEXT", view.canonicalType());
        assertEquals("CATALOG_ONLY", view.executionCapability());
    }

    @Test
    void publicRequestAndResponseContractsContainNoSecretOrCredentialField() {
        List<Class<? extends Record>> contracts = List.of(
                OracleDiscoveryController.DiscoveryRequest.class,
                OracleDiscoveryController.ConnectionTestView.class,
                OracleDiscoveryController.DiscoveryView.class,
                OracleDiscoveryController.TableView.class,
                OracleDiscoveryController.ColumnView.class,
                OracleDiscoveryController.ConstraintView.class,
                OracleConnectionLifecycleController.ActivateRequest.class,
                OracleConnectionLifecycleController.LifecycleView.class,
                OracleConnectionLifecycleController.TestAttemptView.class,
                OracleConnectionLifecycleController.ProbeView.class,
                OracleSchemaSnapshotCaptureController.SnapshotView.class,
                OracleSchemaSnapshotCaptureController.ColumnView.class,
                OracleSchemaSnapshotCaptureController.ConstraintView.class);

        contracts.forEach(contract -> java.util.Arrays.stream(contract.getRecordComponents())
                .forEach(component -> {
                    String name = component.getName().toLowerCase(Locale.ROOT);
                    assertTrue(!name.contains("password"));
                    assertTrue(!name.contains("secret"));
                    assertTrue(!name.contains("credential"));
                }));
    }
}

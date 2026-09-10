package tr.com.innova.akis.oracle;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

class OracleDiscoveryApiContractTest {

    @Test
    void publicRequestAndResponseContractsContainNoSecretOrCredentialField() {
        List<Class<? extends Record>> contracts = List.of(
                OracleDiscoveryController.DiscoveryRequest.class,
                OracleDiscoveryController.ConnectionTestView.class,
                OracleDiscoveryController.DiscoveryView.class,
                OracleDiscoveryController.TableView.class,
                OracleDiscoveryController.ColumnView.class,
                OracleDiscoveryController.ConstraintView.class);

        contracts.forEach(contract -> java.util.Arrays.stream(contract.getRecordComponents())
                .forEach(component -> {
                    String name = component.getName().toLowerCase(Locale.ROOT);
                    assertTrue(!name.contains("password"));
                    assertTrue(!name.contains("secret"));
                    assertTrue(!name.contains("credential"));
                }));
    }
}

package tr.com.innova.akis.oracle;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.ConnectionProfile;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.Credentials;

class EnvironmentCredentialResolverTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void resolvesOnlyTheNamedEnvironmentCredentialBundle() {
        EnvironmentCredentialResolver resolver = new EnvironmentCredentialResolver(
                objectMapper,
                Map.of("AKIS_ORACLE_TEST_CREDENTIAL", "{\"username\":\"reader\",\"password\":\"private-value\"}")::get);

        try (Credentials credentials = resolver.resolve(profile(
                "ENV", "AKIS_ORACLE_TEST_CREDENTIAL", "AKTIF"))) {
            assertEquals("reader", credentials.username());
            assertArrayEquals("private-value".toCharArray(), credentials.password());
        }
    }

    @Test
    void rejectsUnsupportedProviderBeforeReadingTheEnvironment() {
        EnvironmentCredentialResolver resolver = new EnvironmentCredentialResolver(
                objectMapper,
                ignored -> {
                    throw new AssertionError("Environment must not be accessed");
                });

        ApiException error = assertThrows(
                ApiException.class,
                () -> resolver.resolve(profile(
                        "VAULT", "AKIS_ORACLE_TEST_CREDENTIAL", "AKTIF")));

        assertEquals("ORACLE_CREDENTIAL_UNAVAILABLE", error.code());
    }

    @Test
    void malformedSecretProducesOnlyASafeError() {
        String secret = "do-not-leak-this-value";
        EnvironmentCredentialResolver resolver = new EnvironmentCredentialResolver(
                objectMapper,
                ignored -> secret);

        ApiException error = assertThrows(
                ApiException.class,
                () -> resolver.resolve(profile(
                        "ENV", "AKIS_ORACLE_TEST_CREDENTIAL", "AKTIF")));

        assertEquals("ORACLE_CREDENTIAL_UNAVAILABLE", error.code());
        assertTrue(!error.getMessage().contains(secret));
    }

    private ConnectionProfile profile(String provider, String reference, String status) {
        return OracleDiscoveryTestFixtures.profile(7L, provider, reference, status);
    }
}

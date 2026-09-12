package tr.com.innova.akis.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class OracleLocalCredentialStoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsCredentialsOutsideMetadataAndResolvesThemByOpaqueReference() throws Exception {
        Path file = temporaryDirectory.resolve(".env.oracle-credentials.json");
        OracleLocalCredentialStore store = new OracleLocalCredentialStore(
                new ObjectMapper(), file.toString());
        char[] password = "local-password".toCharArray();

        OracleLocalCredentialStore.StoredCredential stored = store.store("reader", password);

        assertTrue(Files.exists(file));
        assertTrue(stored.reference().matches("AKIS_ORACLE_LOCAL_[A-F0-9]{32}"));
        assertEquals("reader", stored.username());
        assertTrue(store.lookup(stored.reference()).contains("local-password"));
        assertFalse(store.lookup(stored.reference()).contains(stored.reference()));
    }
}

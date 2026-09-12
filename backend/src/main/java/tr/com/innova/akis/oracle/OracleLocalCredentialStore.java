package tr.com.innova.akis.oracle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Local, Git-ignored credential storage for the development installation. */
@Component
public final class OracleLocalCredentialStore {

    private final ObjectMapper objectMapper;
    private final Path file;

    OracleLocalCredentialStore(
            ObjectMapper objectMapper,
            @Value("${akis.oracle.local-credential-file:.env.oracle-credentials.json}") String file) {
        this.objectMapper = objectMapper;
        Path configured = Path.of(file);
        if (configured.isAbsolute()) {
            this.file = configured.normalize();
        }
        else {
            Path workingDirectory = Path.of("").toAbsolutePath().normalize();
            Path projectDirectory = Files.isDirectory(workingDirectory.resolve(".git"))
                    ? workingDirectory
                    : workingDirectory.getParent() != null
                            && Files.isDirectory(workingDirectory.getParent().resolve(".git"))
                                    ? workingDirectory.getParent() : workingDirectory;
            this.file = projectDirectory.resolve(configured).normalize();
        }
    }

    public synchronized StoredCredential store(String username, char[] password) {
        String reference = "AKIS_ORACLE_LOCAL_"
                + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        ObjectNode root = readRoot();
        ObjectNode credential = objectMapper.createObjectNode();
        credential.put("username", username);
        credential.put("password", new String(password));
        root.set(reference, credential);
        writeAtomically(root);
        return new StoredCredential(reference, username);
    }

    synchronized String lookup(String reference) {
        JsonNode value = readRoot().get(reference);
        if (value == null || !value.isObject()) return null;
        return objectMapper.writeValueAsString(value);
    }

    private ObjectNode readRoot() {
        if (!Files.exists(file)) return objectMapper.createObjectNode();
        try {
            JsonNode value = objectMapper.readTree(Files.readString(file));
            if (value == null || !value.isObject()) {
                throw new IllegalStateException("Yerel Oracle kimlik dosyası geçersiz.");
            }
            return (ObjectNode) value;
        }
        catch (IOException exception) {
            throw new IllegalStateException("Yerel Oracle kimlik dosyası okunamadı.", exception);
        }
    }

    private void writeAtomically(ObjectNode root) {
        Path parent = file.getParent();
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(temporary, objectMapper.writeValueAsString(root));
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (IOException unsupportedAtomicMove) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        catch (IOException exception) {
            throw new IllegalStateException("Yerel Oracle kimliği kaydedilemedi.", exception);
        }
    }

    public record StoredCredential(String reference, String username) { }
}

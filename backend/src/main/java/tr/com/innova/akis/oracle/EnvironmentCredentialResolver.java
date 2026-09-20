package tr.com.innova.akis.oracle;

import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.ConnectionProfile;
import tr.com.innova.akis.oracle.OracleDiscoveryModels.Credentials;
import tr.com.innova.akis.security.ConnectionCredentialCipher;

@Component
final class EnvironmentCredentialResolver {

    private static final Pattern ENVIRONMENT_NAME = Pattern.compile("[A-Z][A-Z0-9_]{1,199}");
    private static final Set<String> CREDENTIAL_FIELDS = Set.of("username", "password");

    private final ObjectMapper objectMapper;
    private final Function<String, String> environment;
    private final ConnectionCredentialCipher cipher;

    @Autowired
    EnvironmentCredentialResolver(ObjectMapper objectMapper, ConnectionCredentialCipher cipher) {
        this(objectMapper, System::getenv, cipher);
    }

    EnvironmentCredentialResolver(
            ObjectMapper objectMapper,
            Function<String, String> environment,
            ConnectionCredentialCipher cipher) {
        this.objectMapper = objectMapper;
        this.environment = environment;
        this.cipher = cipher;
    }

    Credentials resolve(ConnectionProfile profile) {
        if (!Set.of("ENV", "TABLO").contains(profile.secretProvider())) {
            throw unavailable("Bağlantı kimliği için yalnız ENV veya TABLO secret sağlayıcısı destekleniyor.");
        }
        if (!"AKTIF".equals(profile.secretStatus())) {
            throw unavailable("Bağlantı kimlik secret referansı aktif değil.");
        }
        String reference = profile.secretReferencePath();
        String rawCredential;
        if ("TABLO".equals(profile.secretProvider())) {
            if (reference == null || reference.isBlank()) {
                throw unavailable("Bağlantı kimlik değeri tabloda bulunamadı.");
            }
            rawCredential = cipher.decrypt(reference);
        }
        else {
            if (reference == null || !ENVIRONMENT_NAME.matcher(reference).matches()) {
                throw unavailable("Oracle kimlik secret referansı geçersiz.");
            }
            rawCredential = environment.apply(reference);
        }
        if (rawCredential == null || rawCredential.isBlank()) {
            throw unavailable("Oracle kimlik secret değeri çalışma ortamında bulunamadı.");
        }

        try {
            JsonNode root = objectMapper.readTree(rawCredential);
            if (root == null || !root.isObject()
                    || !root.propertyNames().stream().allMatch(CREDENTIAL_FIELDS::contains)) {
                throw unavailable("Oracle kimlik secret biçimi geçersiz.");
            }
            String username = text(root, "username");
            String password = text(root, "password");
            return new Credentials(username, password.toCharArray());
        }
        catch (JacksonException exception) {
            throw unavailable("Oracle kimlik secret biçimi geçersiz.");
        }
    }

    private String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isString() || node.stringValue().isBlank()) {
            throw unavailable("Oracle kimlik secret biçimi geçersiz.");
        }
        return node.stringValue();
    }

    private ApiException unavailable(String message) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ORACLE_CREDENTIAL_UNAVAILABLE", message);
    }
}

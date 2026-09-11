package tr.com.innova.akis.execution;

import java.util.Locale;

final class IntegrationTestDatabase {

    private IntegrationTestDatabase() {
    }

    static String requireIsolatedUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(
                    "SPRING_DATASOURCE_URL is required for the JDBC integration test.");
        }
        String withoutQuery = url.split("\\?", 2)[0];
        int separator = withoutQuery.lastIndexOf('/');
        String database = separator < 0 ? "" : withoutQuery.substring(separator + 1);
        String normalized = database.toLowerCase(Locale.ROOT);
        if (!normalized.endsWith("_it") && !normalized.endsWith("_test")) {
            throw new IllegalStateException(
                    "Destructive JDBC integration tests require an isolated database "
                            + "whose name ends with _it or _test.");
        }
        return url;
    }
}

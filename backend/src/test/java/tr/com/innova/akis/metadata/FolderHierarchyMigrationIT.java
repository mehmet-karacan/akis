package tr.com.innova.akis.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Locale;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class FolderHierarchyMigrationIT {

    private static AnnotationConfigApplicationContext context;
    private static JdbcClient jdbc;

    @BeforeAll
    static void startDatabaseContext() {
        context = new AnnotationConfigApplicationContext(TestConfiguration.class);
        Flyway.configure()
                .dataSource(context.getBean(DataSource.class))
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = context.getBean(JdbcClient.class);
    }

    @AfterAll
    static void stopDatabaseContext() {
        if (context != null) {
            context.close();
        }
    }

    @BeforeEach
    void resetProject() {
        jdbc.sql("truncate table entegrasyon.proje restart identity cascade").update();
        jdbc.sql("insert into entegrasyon.proje(kod, ad) values ('FOLDER_IT', 'Folder hierarchy IT')").update();
    }

    @Test
    void rejectsMovingFolderBelowItsDescendant() {
        long projectId = projectId();
        long rootId = insertFolder(projectId, null, "ROOT");
        long childId = insertFolder(projectId, rootId, "CHILD");

        assertThrows(DataAccessException.class, () -> jdbc.sql("""
                        update entegrasyon.klasor
                           set ust_klasor_id = :childId
                         where id = :rootId
                        """)
                .param("childId", childId)
                .param("rootId", rootId)
                .update());

        Long parentId = jdbc.sql("select ust_klasor_id from entegrasyon.klasor where id = :rootId")
                .param("rootId", rootId)
                .query(Long.class)
                .optional()
                .orElse(null);
        assertEquals(null, parentId);
    }

    @Test
    void acceptsOneHundredLevelsAndRejectsTheNext() {
        long projectId = projectId();
        Long parentId = null;
        for (int level = 1; level <= 100; level++) {
            parentId = insertFolder(projectId, parentId, "LEVEL_" + level);
        }
        Long maximumParentId = parentId;

        assertThrows(DataAccessException.class, () -> insertFolder(
                projectId, maximumParentId, "LEVEL_101"));
        assertEquals(100, jdbc.sql("select count(*) from entegrasyon.klasor where proje_id = :projectId")
                .param("projectId", projectId)
                .query(Integer.class)
                .single());
    }

    private long projectId() {
        return jdbc.sql("select id from entegrasyon.proje where kod = 'FOLDER_IT'")
                .query(Long.class)
                .single();
    }

    private long insertFolder(long projectId, Long parentId, String code) {
        return jdbc.sql("""
                        insert into entegrasyon.klasor(proje_id, ust_klasor_id, kod, ad)
                        values (:projectId, :parentId, :code, :name)
                        returning id
                        """)
                .param("projectId", projectId)
                .param("parentId", parentId, java.sql.Types.BIGINT)
                .param("code", code)
                .param("name", code)
                .query(Long.class)
                .single();
    }

    @Configuration(proxyBeanMethods = false)
    static class TestConfiguration {

        @Bean
        DataSource dataSource() {
            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setUrl(isolatedUrl(requiredEnvironment("SPRING_DATASOURCE_URL")));
            dataSource.setUsername(requiredEnvironment("SPRING_DATASOURCE_USERNAME"));
            dataSource.setPassword(requiredEnvironment("SPRING_DATASOURCE_PASSWORD"));
            return dataSource;
        }

        @Bean
        JdbcClient jdbcClient(DataSource dataSource) {
            return JdbcClient.create(dataSource);
        }

        private static String isolatedUrl(String url) {
            String database = url.split("\\?", 2)[0];
            database = database.substring(database.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
            if (!database.endsWith("_it") && !database.endsWith("_test")) {
                throw new IllegalStateException("Folder hierarchy tests require an isolated *_it or *_test database.");
            }
            return url;
        }

        private static String requiredEnvironment(String name) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException(name + " is required for the JDBC integration test.");
            }
            return value;
        }
    }
}

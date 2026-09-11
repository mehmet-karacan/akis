package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.discovery.SchemaFingerprint;
import tr.com.innova.akis.discovery.SchemaFingerprintInput;
import tr.com.innova.akis.discovery.SchemaFingerprintInput.Column;
import tr.com.innova.akis.execution.PilotRuntimePlan.DataObjectType;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatabaseType;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatasetBinding;
import tr.com.innova.akis.execution.PilotRuntimePlan.DatasetRole;
import tr.com.innova.akis.execution.PilotRuntimePlan.DirectColumnMapping;
import tr.com.innova.akis.execution.PilotRuntimePlan.WriteStrategy;

class JdbcPinnedSchemaSnapshotStoreIT {

    private static final String RELEASE_HASH = "a".repeat(64);
    private static final String PLAN_HASH = "b".repeat(64);
    private static final String RUNTIME_PLAN_HASH = "f".repeat(64);
    private static final UUID PROJECT_UUID = UUID.fromString(
            "00000000-0000-0000-0000-000000000101");
    private static final UUID DEFINITION_UUID = UUID.fromString(
            "00000000-0000-0000-0000-000000000102");
    private static final UUID DEFINITION_VERSION_UUID = UUID.fromString(
            "00000000-0000-0000-0000-000000000103");
    private static final UUID PUBLICATION_UUID = UUID.fromString(
            "00000000-0000-0000-0000-000000000104");

    private static AnnotationConfigApplicationContext context;
    private static JdbcClient jdbc;
    private static JdbcTemplate jdbcTemplate;
    private static JdbcPinnedSchemaSnapshotStore store;
    private static JdbcRuntimeOracleConnectionMetadataStore connectionMetadataStore;
    private static ObjectMapper objectMapper;

    private DatasetBinding source;
    private DatasetBinding target;

    @BeforeAll
    static void startDatabaseContext() {
        context = new AnnotationConfigApplicationContext(TestConfiguration.class);
        Flyway.configure()
                .dataSource(context.getBean(DataSource.class))
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = context.getBean(JdbcClient.class);
        jdbcTemplate = context.getBean(JdbcTemplate.class);
        store = context.getBean(JdbcPinnedSchemaSnapshotStore.class);
        connectionMetadataStore = context.getBean(
                JdbcRuntimeOracleConnectionMetadataStore.class);
        objectMapper = context.getBean(ObjectMapper.class);
    }

    @AfterAll
    static void stopDatabaseContext() {
        context.close();
    }

    @BeforeEach
    void createFixture() {
        jdbcTemplate.execute("""
                SET search_path TO entegrasyon, public;
                TRUNCATE TABLE proje RESTART IDENTITY CASCADE;
                """);

        SchemaFingerprintInput body = new SchemaFingerprintInput(
                "19.0.0.0.0", 1, objectMapper.createObjectNode(),
                List.of(new Column(
                        "ID", "NUMBER(10,0)", "INTEGER", 1,
                        10, 0, null, null, false, null, "ID")),
                List.of());
        String fingerprint = new SchemaFingerprint(objectMapper).calculate(body);

        long projectId = insertReturning("""
                insert into entegrasyon.proje(uuid, kod, ad)
                values (:uuid, 'PINNED_IT', 'Pinned snapshot integration test')
                returning id
                """, "uuid", PROJECT_UUID);
        long folderId = insertReturning("""
                insert into entegrasyon.klasor(proje_id, kod, ad)
                values (:projectId, 'ROOT', 'Root') returning id
                """, "projectId", projectId);
        long environmentId = insertReturning("""
                insert into entegrasyon.ortam(proje_id, kod, risk_kodu, ad)
                values (:projectId, 'TEST', 'DUSUK', 'Test') returning id
                """, "projectId", projectId);

        FixtureBinding sourceFixture = createBinding(
                projectId, environmentId, "SRC", "SOURCE_NODE", "KAYNAK",
                "SRC", "SRC_TABLE", fingerprint, body);
        FixtureBinding targetFixture = createBinding(
                projectId, environmentId, "TGT", "TARGET_NODE", "HEDEF",
                "TGT", "TGT_TABLE", fingerprint, body);

        long definitionId = jdbc.sql("""
                        insert into entegrasyon.tanim(
                            proje_id, klasor_id, kapsam_kodu, tur_kodu,
                            kod, ad, uuid)
                        values (:projectId, :folderId, 'PROJE', 'MAPPING',
                                'PINNED_MAP', 'Pinned map', :uuid)
                        returning id
                        """)
                .param("projectId", projectId).param("folderId", folderId)
                .param("uuid", DEFINITION_UUID).query(Long.class).single();
        long definitionVersionId = jdbc.sql("""
                        insert into entegrasyon.tanim_surumu(
                            tanim_id, surum_no, sema_surumu, icerik_ozeti,
                            icerik, uuid)
                        values (:definitionId, 1, 2, repeat('1', 64),
                                '{}'::jsonb, :uuid)
                        returning id
                        """)
                .param("definitionId", definitionId)
                .param("uuid", DEFINITION_VERSION_UUID)
                .query(Long.class).single();
        long validationId = jdbc.sql("""
                        insert into entegrasyon.dogrulama(
                            tanim_surumu_id, ortam_id, icerik_ozeti,
                            sonuc_kodu, sonuc)
                        values (:versionId, :environmentId, repeat('1', 64),
                                'GECTI', '{}'::jsonb)
                        returning id
                        """)
                .param("versionId", definitionVersionId)
                .param("environmentId", environmentId)
                .query(Long.class).single();
        long scenarioId = jdbc.sql("""
                        insert into entegrasyon.senaryo(
                            tanim_surumu_id, dogrulama_id, surum_no,
                            plan_surumu, plan_ozeti, plan)
                        values (:versionId, :validationId, 1, 2,
                                :planHash, '{}'::jsonb)
                        returning id
                        """)
                .param("versionId", definitionVersionId)
                .param("validationId", validationId)
                .param("planHash", PLAN_HASH)
                .query(Long.class).single();
        long publicationId = jdbc.sql("""
                        insert into entegrasyon.yayin(
                            proje_id, senaryo_id, ortam_id, yayin_no,
                            durum_kodu, bagimlilik_ozeti, fiziksel_manifesto,
                            yayin_zamani, uuid)
                        values (:projectId, :scenarioId, :environmentId, 1,
                                'AKTIF', repeat('2', 64),
                                jsonb_build_object('releaseHash', :releaseHash),
                                current_timestamp, :uuid)
                        returning id
                        """)
                .param("projectId", projectId).param("scenarioId", scenarioId)
                .param("environmentId", environmentId)
                .param("releaseHash", RELEASE_HASH).param("uuid", PUBLICATION_UUID)
                .query(Long.class).single();

        source = publishBinding(
                projectId, definitionVersionId, publicationId, sourceFixture,
                DatasetRole.SOURCE, "SOURCE_NODE", "KAYNAK");
        target = publishBinding(
                projectId, definitionVersionId, publicationId, targetFixture,
                DatasetRole.TARGET, "TARGET_NODE", "HEDEF");
    }

    @Test
    void loadsBothImmutableBodiesOnlyThroughTheirExactPublishedBindings() {
        var snapshots = store.load(plan(source, target));

        assertEquals(PROJECT_UUID, snapshots.projectUuid());
        assertEquals(PUBLICATION_UUID, snapshots.publicationUuid());
        assertEquals(source.schemaSnapshotUuid(), snapshots.source().schemaSnapshotUuid());
        assertEquals(source.schemaSnapshotFingerprint(),
                snapshots.source().verifiedFingerprint());
        assertEquals("ID", snapshots.source().body().columns().getFirst().reference());
        assertEquals(target.schemaSnapshotUuid(), snapshots.target().schemaSnapshotUuid());
    }

    @Test
    void rejectsAPlanBindingThatDoesNotBelongToThePublishedTuple() {
        DatasetBinding forged = new DatasetBinding(
                source.datasetId(), source.role(), source.databaseType(),
                source.dataObjectType(), source.definitionDataObjectUuid(),
                source.dataObjectUuid(), source.environmentSchemaBindingUuid(),
                source.physicalSchemaUuid(), source.connectionVersionUuid(),
                source.schemaSnapshotUuid(), source.bindingVersion(),
                source.schemaSnapshotFingerprint(), "OTHER.SRC_TABLE",
                source.owner(), source.objectName());

        PinnedSchemaSnapshotException exception = assertThrows(
                PinnedSchemaSnapshotException.class,
                () -> store.load(plan(forged, target)));

        assertEquals(PinnedSchemaSnapshotException.Failure.BINDING_NOT_FOUND,
                exception.failure());
    }

    @Test
    void loadsRuntimeConnectionMetadataOnlyForTheExactBoundVersion() {
        var profile = connectionMetadataStore.find(source).orElseThrow();

        assertEquals(source.connectionVersionUuid(), profile.connectionVersionUuid());
        assertEquals("oracle.jdbc.OracleDriver", profile.driverReference());
        assertEquals("ENV", profile.secretProvider());
        assertEquals("AKIS_SRC_CREDENTIAL", profile.secretReferencePath());

        DatasetBinding forged = new DatasetBinding(
                source.datasetId(), source.role(), source.databaseType(),
                source.dataObjectType(), source.definitionDataObjectUuid(),
                source.dataObjectUuid(), source.environmentSchemaBindingUuid(),
                source.physicalSchemaUuid(), UUID.randomUUID(),
                source.schemaSnapshotUuid(), source.bindingVersion(),
                source.schemaSnapshotFingerprint(), source.physicalIdentity(),
                source.owner(), source.objectName());
        assertEquals(java.util.Optional.empty(), connectionMetadataStore.find(forged));
    }

    private FixtureBinding createBinding(
            long projectId,
            long environmentId,
            String code,
            String datasetId,
            String storedRole,
            String owner,
            String objectName,
            String fingerprint,
            SchemaFingerprintInput body) {
        UUID connectionVersionUuid = UUID.randomUUID();
        UUID physicalSchemaUuid = UUID.randomUUID();
        UUID environmentBindingUuid = UUID.randomUUID();
        UUID dataObjectUuid = UUID.randomUUID();
        UUID snapshotUuid = UUID.randomUUID();

        long connectionId = jdbc.sql("""
                        insert into entegrasyon.baglanti(
                            proje_id, kod, veritabani_turu, durum_kodu, ad)
                        values (:projectId, :code, 'ORACLE', 'AKTIF', :code)
                        returning id
                        """)
                .param("projectId", projectId).param("code", code)
                .query(Long.class).single();
        long connectionVersionId = jdbc.sql("""
                        insert into entegrasyon.baglanti_surumu(
                            proje_id, baglanti_id, surum_no, surucu_referansi,
                            sunucu_adi, servis_adi, port, uuid)
                        values (:projectId, :connectionId, 1, 'oracle.jdbc.OracleDriver',
                                'db.invalid', 'SERVICE', 1521, :uuid)
                        returning id
                        """)
                .param("projectId", projectId).param("connectionId", connectionId)
                .param("uuid", connectionVersionUuid).query(Long.class).single();
        long secretId = jdbc.sql("""
                        insert into entegrasyon.secret_referansi(
                            proje_id, kod, referans_yolu, saglayici_kodu,
                            durum_kodu, ad)
                        values (:projectId, :code, :reference, 'ENV', 'AKTIF', :code)
                        returning id
                        """)
                .param("projectId", projectId).param("code", code + "_SECRET")
                .param("reference", "AKIS_" + code + "_CREDENTIAL")
                .query(Long.class).single();
        jdbc.sql("""
                        insert into entegrasyon.baglanti_secret_bagi(
                            proje_id, baglanti_surumu_id, secret_referansi_id,
                            rol_kodu)
                        values (:projectId, :connectionVersionId, :secretId, 'KIMLIK')
                        """)
                .param("projectId", projectId)
                .param("connectionVersionId", connectionVersionId)
                .param("secretId", secretId).update();
        long physicalSchemaId = jdbc.sql("""
                        insert into entegrasyon.fiziksel_sema(
                            proje_id, baglanti_id, kod, sema_referansi, ad, uuid)
                        values (:projectId, :connectionId, :code, :owner,
                                :code, :uuid)
                        returning id
                        """)
                .param("projectId", projectId).param("connectionId", connectionId)
                .param("code", code).param("owner", owner)
                .param("uuid", physicalSchemaUuid).query(Long.class).single();
        long logicalSchemaId = jdbc.sql("""
                        insert into entegrasyon.mantiksal_sema(proje_id, kod, ad)
                        values (:projectId, :code, :code) returning id
                        """)
                .param("projectId", projectId).param("code", code)
                .query(Long.class).single();
        long environmentBindingId = jdbc.sql("""
                        insert into entegrasyon.ortam_sema_eslemesi(
                            proje_id, mantiksal_sema_id, ortam_id,
                            fiziksel_sema_id, baglanti_surumu_id, uuid)
                        values (:projectId, :logicalSchemaId, :environmentId,
                                :physicalSchemaId, :connectionVersionId, :uuid)
                        returning id
                        """)
                .param("projectId", projectId).param("logicalSchemaId", logicalSchemaId)
                .param("environmentId", environmentId)
                .param("physicalSchemaId", physicalSchemaId)
                .param("connectionVersionId", connectionVersionId)
                .param("uuid", environmentBindingUuid).query(Long.class).single();
        long modelId = jdbc.sql("""
                        insert into entegrasyon.model(
                            proje_id, mantiksal_sema_id, kod, ad)
                        values (:projectId, :logicalSchemaId, :code, :code)
                        returning id
                        """)
                .param("projectId", projectId).param("logicalSchemaId", logicalSchemaId)
                .param("code", code).query(Long.class).single();
        long dataObjectId = jdbc.sql("""
                        insert into entegrasyon.veri_nesnesi(
                            proje_id, model_id, kod, nesne_referansi,
                            tur_kodu, ad, uuid)
                        values (:projectId, :modelId, :code, :objectName,
                                'TABLO', :code, :uuid)
                        returning id
                        """)
                .param("projectId", projectId).param("modelId", modelId)
                .param("code", code).param("objectName", objectName)
                .param("uuid", dataObjectUuid).query(Long.class).single();
        long snapshotId = jdbc.sql("""
                        insert into entegrasyon.sema_goruntusu(
                            proje_id, veri_nesnesi_id, fiziksel_sema_id,
                            baglanti_surumu_id, parmak_izi, motor_surumu,
                            kesif_zamani, ozellik_surumu, ozellik, uuid)
                        values (:projectId, :dataObjectId, :physicalSchemaId,
                                :connectionVersionId, :fingerprint, :engineVersion,
                                current_timestamp, :propertyVersion,
                                cast(:properties as jsonb), :uuid)
                        returning id
                        """)
                .param("projectId", projectId).param("dataObjectId", dataObjectId)
                .param("physicalSchemaId", physicalSchemaId)
                .param("connectionVersionId", connectionVersionId)
                .param("fingerprint", fingerprint)
                .param("engineVersion", body.engineVersion())
                .param("propertyVersion", body.propertyVersion())
                .param("properties", body.properties().toString())
                .param("uuid", snapshotUuid).query(Long.class).single();
        Column column = body.columns().getFirst();
        jdbc.sql("""
                        insert into entegrasyon.kolon_goruntusu(
                            proje_id, sema_goruntusu_id, kolon_referansi,
                            uretici_tip_kodu, kanonik_tip_kodu, sira_no,
                            hassasiyet, olcek, null_olabilir, ad)
                        values (:projectId, :snapshotId, :reference,
                                :producerType, :canonicalType, :ordinal,
                                :precision, :scale, :nullable, :name)
                        """)
                .param("projectId", projectId).param("snapshotId", snapshotId)
                .param("reference", column.reference())
                .param("producerType", column.producerType())
                .param("canonicalType", column.canonicalType())
                .param("ordinal", column.ordinal()).param("precision", column.precision())
                .param("scale", column.scale()).param("nullable", column.nullable())
                .param("name", column.name()).update();
        return new FixtureBinding(
                datasetId, storedRole, owner, objectName, connectionVersionUuid,
                physicalSchemaUuid, environmentBindingUuid, environmentBindingId,
                dataObjectUuid, dataObjectId, snapshotUuid, snapshotId, fingerprint);
    }

    private DatasetBinding publishBinding(
            long projectId,
            long definitionVersionId,
            long publicationId,
            FixtureBinding fixture,
            DatasetRole role,
            String datasetId,
            String storedRole) {
        UUID definitionDataObjectUuid = UUID.randomUUID();
        long definitionDataObjectId = jdbc.sql("""
                        insert into entegrasyon.tanim_veri_nesnesi(
                            proje_id, tanim_surumu_id, veri_nesnesi_id,
                            sema_goruntusu_id, dugum_kodu, rol_kodu, uuid)
                        values (:projectId, :versionId, :dataObjectId,
                                :snapshotId, :datasetId, :storedRole, :uuid)
                        returning id
                        """)
                .param("projectId", projectId).param("versionId", definitionVersionId)
                .param("dataObjectId", fixture.dataObjectId())
                .param("snapshotId", fixture.snapshotId()).param("datasetId", datasetId)
                .param("storedRole", storedRole).param("uuid", definitionDataObjectUuid)
                .query(Long.class).single();
        String physicalIdentity = fixture.owner() + "." + fixture.objectName();
        jdbc.sql("""
                        insert into entegrasyon.yayin_veri_bagi(
                            proje_id, yayin_id, tanim_veri_nesnesi_id,
                            ortam_sema_eslemesi_id, fiziksel_sema_id,
                            baglanti_surumu_id, sema_goruntusu_id,
                            fiziksel_kimlik, bag_versiyon_no)
                        select :projectId, :publicationId, :definitionDataObjectId,
                               :environmentBindingId, fs.id, bs.id, :snapshotId,
                               :physicalIdentity, 1
                          from entegrasyon.fiziksel_sema fs
                          join entegrasyon.baglanti_surumu bs
                            on bs.baglanti_id = fs.baglanti_id
                         where fs.uuid = :physicalSchemaUuid
                           and bs.uuid = :connectionVersionUuid
                        """)
                .param("projectId", projectId).param("publicationId", publicationId)
                .param("definitionDataObjectId", definitionDataObjectId)
                .param("environmentBindingId", fixture.environmentBindingId())
                .param("snapshotId", fixture.snapshotId())
                .param("physicalIdentity", physicalIdentity)
                .param("physicalSchemaUuid", fixture.physicalSchemaUuid())
                .param("connectionVersionUuid", fixture.connectionVersionUuid())
                .update();
        return new DatasetBinding(
                datasetId, role, DatabaseType.ORACLE, DataObjectType.TABLE,
                definitionDataObjectUuid, fixture.dataObjectUuid(),
                fixture.environmentBindingUuid(), fixture.physicalSchemaUuid(),
                fixture.connectionVersionUuid(), fixture.snapshotUuid(), 1,
                fixture.fingerprint(), physicalIdentity,
                fixture.owner(), fixture.objectName());
    }

    private PilotRuntimePlan plan(DatasetBinding sourceBinding, DatasetBinding targetBinding) {
        return new PilotRuntimePlan(
                1, RUNTIME_PLAN_HASH, RELEASE_HASH, PLAN_HASH,
                DEFINITION_UUID, DEFINITION_VERSION_UUID, 100,
                sourceBinding, targetBinding,
                List.of(new DirectColumnMapping("ID", "ID")),
                WriteStrategy.ATOMIC_DELETE_INSERT,
                objectMapper.createObjectNode());
    }

    private long insertReturning(String sql, String parameter, Object value) {
        return jdbc.sql(sql).param(parameter, value).query(Long.class).single();
    }

    private record FixtureBinding(
            String datasetId,
            String storedRole,
            String owner,
            String objectName,
            UUID connectionVersionUuid,
            UUID physicalSchemaUuid,
            UUID environmentBindingUuid,
            long environmentBindingId,
            UUID dataObjectUuid,
            long dataObjectId,
            UUID snapshotUuid,
            long snapshotId,
            String fingerprint) {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TestConfiguration {

        @Bean
        DataSource dataSource() {
            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setUrl(IntegrationTestDatabase.requireIsolatedUrl(
                    requiredEnvironment("SPRING_DATASOURCE_URL")));
            dataSource.setUsername(requiredEnvironment("SPRING_DATASOURCE_USERNAME"));
            dataSource.setPassword(requiredEnvironment("SPRING_DATASOURCE_PASSWORD"));
            return dataSource;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        JdbcClient jdbcClient(DataSource dataSource) {
            return JdbcClient.create(dataSource);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        JdbcPinnedSchemaSnapshotStore store(JdbcClient jdbc, ObjectMapper objectMapper) {
            return new JdbcPinnedSchemaSnapshotStore(jdbc, objectMapper);
        }

        @Bean
        JdbcRuntimeOracleConnectionMetadataStore connectionMetadataStore(
                JdbcClient jdbc, ObjectMapper objectMapper) {
            return new JdbcRuntimeOracleConnectionMetadataStore(jdbc, objectMapper);
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

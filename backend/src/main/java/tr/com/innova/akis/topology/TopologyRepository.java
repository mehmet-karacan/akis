package tr.com.innova.akis.topology;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.topology.TopologyModels.ConnectionCatalogRow;
import tr.com.innova.akis.topology.TopologyModels.ConnectionDependencyRow;
import tr.com.innova.akis.topology.TopologyModels.ConnectionRow;
import tr.com.innova.akis.topology.TopologyModels.ConnectionTestRow;
import tr.com.innova.akis.topology.TopologyModels.EnvironmentRow;
import tr.com.innova.akis.topology.TopologyModels.LogicalSchemaRow;
import tr.com.innova.akis.topology.TopologyModels.PhysicalSchemaRow;
import tr.com.innova.akis.topology.TopologyModels.SchemaBindingRow;

@Repository
class TopologyRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    TopologyRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------- connections

    private static final String CONNECTION_SELECT = """
            select b.id, b.uuid, b.kod, b.ad, b.aciklama, b.saglayici_turu, b.baglanti_modu,
                   b.surucu_sinifi, b.sunucu_adi, b.port, b.servis_adi, b.sid, b.veritabani_adi,
                   b.jdbc_url_ek, b.jndi_adi, b.kullanici_adi, (b.sifre is not null) as sifre_var,
                   b.getirme_boyutu, b.toplu_guncelleme_boyutu,
                   b.baglanti_zaman_asimi_ms, b.okuma_zaman_asimi_ms, b.sorgu_zaman_asimi_saniye,
                   b.baglanti_sonrasi_sql, b.kapanis_oncesi_sql,
                   b.son_test_zamani, b.son_test_basarili_mi, b.durum,
                   (select k.gorunen_ad from akis.kullanici k where k.id = b.olusturan_kullanici_id) as created_by,
                   b.olusturulma_zamani, b.guncellenme_zamani
              from akis.baglanti b
            """;

    List<ConnectionRow> listConnections() {
        return jdbc.sql(CONNECTION_SELECT + " order by b.ad, b.kod")
                .query(this::mapConnection).list();
    }

    Optional<ConnectionRow> findConnection(UUID uuid) {
        return jdbc.sql(CONNECTION_SELECT + " where b.uuid = :uuid")
                .param("uuid", uuid).query(this::mapConnection).optional();
    }

    Optional<ConnectionRow> findConnectionByCode(String code) {
        return jdbc.sql(CONNECTION_SELECT + " where b.kod = :code")
                .param("code", code).query(this::mapConnection).optional();
    }

    List<ConnectionCatalogRow> listConnectionCatalog() {
        String select = CONNECTION_SELECT.substring(0, CONNECTION_SELECT.lastIndexOf("from akis.baglanti b"));
        return jdbc.sql(select + """
                   , (select count(*) from akis.fiziksel_sema f where f.baglanti_id = b.id) as physical_schema_count
                   , (select count(distinct se.mantiksal_sema_id)
                        from akis.sema_eslemesi se
                        join akis.fiziksel_sema f on f.id = se.fiziksel_sema_id
                       where f.baglanti_id = b.id) as logical_schema_count
                  from akis.baglanti b
                 order by b.ad, b.kod
                """)
                .query((rs, rowNum) -> new ConnectionCatalogRow(
                        mapConnection(rs, rowNum),
                        rs.getInt("physical_schema_count"),
                        rs.getInt("logical_schema_count")))
                .list();
    }

    ConnectionRow createConnection(ConnectionWrite w, String encryptedPassword, Long actorId) {
        UUID uuid = jdbc.sql("""
                insert into akis.baglanti(
                    kod, ad, aciklama, saglayici_turu, baglanti_modu, surucu_sinifi,
                    sunucu_adi, port, servis_adi, sid, veritabani_adi, jdbc_url_ek, jndi_adi,
                    kullanici_adi, sifre, getirme_boyutu, toplu_guncelleme_boyutu,
                    baglanti_zaman_asimi_ms, okuma_zaman_asimi_ms, sorgu_zaman_asimi_saniye,
                    baglanti_sonrasi_sql, kapanis_oncesi_sql, olusturan_kullanici_id)
                values (:code, :name, :description, :databaseType, :mode, :driver,
                        :host, :port, :serviceName, :sid, :databaseName, :jdbcUrlExtra, :jndiName,
                        :username, :password, :fetchSize, :batchSize,
                        :connectTimeoutMs, :readTimeoutMs, :queryTimeoutSeconds,
                        :onConnectSql, :onDisconnectSql, :actorId)
                returning uuid
                """)
                .param("code", w.code()).param("name", w.name())
                .param("description", w.description(), Types.VARCHAR)
                .param("databaseType", w.databaseType()).param("mode", w.mode())
                .param("driver", w.driverReference(), Types.VARCHAR)
                .param("host", w.host(), Types.VARCHAR).param("port", w.port(), Types.INTEGER)
                .param("serviceName", w.serviceName(), Types.VARCHAR).param("sid", w.sid(), Types.VARCHAR)
                .param("databaseName", w.databaseName(), Types.VARCHAR)
                .param("jdbcUrlExtra", w.jdbcUrlExtra(), Types.VARCHAR)
                .param("jndiName", w.jndiName(), Types.VARCHAR)
                .param("username", w.username(), Types.VARCHAR)
                .param("password", encryptedPassword, Types.VARCHAR)
                .param("fetchSize", w.fetchSize()).param("batchSize", w.batchSize())
                .param("connectTimeoutMs", w.connectTimeoutMs()).param("readTimeoutMs", w.readTimeoutMs())
                .param("queryTimeoutSeconds", w.queryTimeoutSeconds())
                .param("onConnectSql", w.onConnectSql(), Types.VARCHAR)
                .param("onDisconnectSql", w.onDisconnectSql(), Types.VARCHAR)
                .param("actorId", actorId, Types.BIGINT)
                .query(UUID.class).single();
        return findConnection(uuid).orElseThrow();
    }

    /** encryptedPassword == null keeps the stored password. */
    boolean updateConnection(UUID uuid, ConnectionWrite w, String encryptedPassword, Long actorId) {
        return jdbc.sql("""
                update akis.baglanti
                   set kod = :code, ad = :name, aciklama = :description, saglayici_turu = :databaseType,
                       baglanti_modu = :mode, surucu_sinifi = :driver,
                       sunucu_adi = :host, port = :port, servis_adi = :serviceName, sid = :sid,
                       veritabani_adi = :databaseName, jdbc_url_ek = :jdbcUrlExtra, jndi_adi = :jndiName,
                       kullanici_adi = :username,
                       sifre = case when :mode = 'JNDI' then null else coalesce(:password, sifre) end,
                       getirme_boyutu = :fetchSize, toplu_guncelleme_boyutu = :batchSize,
                       baglanti_zaman_asimi_ms = :connectTimeoutMs, okuma_zaman_asimi_ms = :readTimeoutMs,
                       sorgu_zaman_asimi_saniye = :queryTimeoutSeconds,
                       baglanti_sonrasi_sql = :onConnectSql, kapanis_oncesi_sql = :onDisconnectSql,
                       durum = :status,
                       guncellenme_zamani = current_timestamp, guncelleyen_kullanici_id = :actorId
                 where uuid = :uuid
                """)
                .param("uuid", uuid)
                .param("code", w.code()).param("name", w.name())
                .param("description", w.description(), Types.VARCHAR)
                .param("databaseType", w.databaseType()).param("mode", w.mode())
                .param("driver", w.driverReference(), Types.VARCHAR)
                .param("host", w.host(), Types.VARCHAR).param("port", w.port(), Types.INTEGER)
                .param("serviceName", w.serviceName(), Types.VARCHAR).param("sid", w.sid(), Types.VARCHAR)
                .param("databaseName", w.databaseName(), Types.VARCHAR)
                .param("jdbcUrlExtra", w.jdbcUrlExtra(), Types.VARCHAR)
                .param("jndiName", w.jndiName(), Types.VARCHAR)
                .param("username", w.username(), Types.VARCHAR)
                .param("password", encryptedPassword, Types.VARCHAR)
                .param("fetchSize", w.fetchSize()).param("batchSize", w.batchSize())
                .param("connectTimeoutMs", w.connectTimeoutMs()).param("readTimeoutMs", w.readTimeoutMs())
                .param("queryTimeoutSeconds", w.queryTimeoutSeconds())
                .param("onConnectSql", w.onConnectSql(), Types.VARCHAR)
                .param("onDisconnectSql", w.onDisconnectSql(), Types.VARCHAR)
                .param("status", w.status())
                .param("actorId", actorId, Types.BIGINT)
                .update() == 1;
    }

    boolean deleteConnection(long id) {
        return jdbc.sql("delete from akis.baglanti where id = :id").param("id", id).update() == 1;
    }

    List<ConnectionDependencyRow> listConnectionDependencies(long connectionId) {
        return jdbc.sql("""
                select distinct ms.uuid, 'LOGICAL_SCHEMA'::text as type, ms.ad as name
                  from akis.sema_eslemesi se
                  join akis.fiziksel_sema fs on fs.id = se.fiziksel_sema_id
                  join akis.mantiksal_sema ms on ms.id = se.mantiksal_sema_id
                 where fs.baglanti_id = :connectionId
                 order by ms.ad
                """)
                .param("connectionId", connectionId)
                .query((rs, rowNum) -> new ConnectionDependencyRow(
                        rs.getObject("uuid", UUID.class), rs.getString("type"), rs.getString("name")))
                .list();
    }

    Optional<String> findEncryptedPassword(long connectionId) {
        return jdbc.sql("select sifre from akis.baglanti where id = :id")
                .param("id", connectionId).query(String.class).optional();
    }

    // ---------------------------------------------------------------- connection tests

    ConnectionTestRow recordTest(long connectionId, ConnectionTestWrite t, Long actorId) {
        UUID uuid = jdbc.sql("""
                insert into akis.baglanti_testi(
                    baglanti_id, deneme_no, sonuc, hata_kodu, urun_adi, urun_surumu, surucu_adi, surucu_surumu,
                    veritabani_ana_surumu, veritabani_alt_surumu, hedef_kimlik_surumu, hedef_parmak_izi,
                    baslama_zamani, tamamlanma_zamani, sure_milisaniye, olusturan_kullanici_id)
                values (:connectionId,
                        (select coalesce(max(deneme_no), 0) + 1 from akis.baglanti_testi where baglanti_id = :connectionId),
                        :outcome, :errorCode, :product, :productVersion, :driverName, :driverVersion,
                        :major, :minor, :targetIdentityVersion, :targetFingerprint, :startedAt, :completedAt, :durationMs, :actorId)
                returning uuid
                """)
                .param("connectionId", connectionId)
                .param("outcome", t.passed() ? "BASARILI" : "BASARISIZ")
                .param("errorCode", t.errorCode(), Types.VARCHAR)
                .param("product", t.databaseProduct(), Types.VARCHAR)
                .param("productVersion", t.databaseVersion(), Types.VARCHAR)
                .param("driverName", t.driverName(), Types.VARCHAR)
                .param("driverVersion", t.driverVersion(), Types.VARCHAR)
                .param("major", t.databaseMajorVersion(), Types.INTEGER)
                .param("minor", t.databaseMinorVersion(), Types.INTEGER)
                .param("targetIdentityVersion", t.targetIdentityVersion(), Types.INTEGER)
                .param("targetFingerprint", t.targetFingerprint(), Types.VARCHAR)
                .param("startedAt", t.startedAt()).param("completedAt", t.completedAt())
                .param("durationMs", t.durationMs())
                .param("actorId", actorId, Types.BIGINT)
                .query(UUID.class).single();
        jdbc.sql("""
                update akis.baglanti
                   set son_test_zamani = :completedAt, son_test_basarili_mi = :passed
                 where id = :connectionId
                """)
                .param("completedAt", t.completedAt()).param("passed", t.passed())
                .param("connectionId", connectionId).update();
        return listTests(connectionId, 1).stream().filter(row -> row.uuid().equals(uuid)).findFirst()
                .orElseGet(() -> listTests(connectionId, 50).stream().filter(row -> row.uuid().equals(uuid)).findFirst().orElseThrow());
    }

    List<ConnectionTestRow> listTests(long connectionId, int limit) {
        return jdbc.sql("""
                select id, uuid, baglanti_id, deneme_no, sonuc, hata_kodu, urun_adi, urun_surumu,
                       surucu_adi, surucu_surumu, veritabani_ana_surumu, veritabani_alt_surumu,
                       hedef_kimlik_surumu, hedef_parmak_izi, baslama_zamani, tamamlanma_zamani, sure_milisaniye
                  from akis.baglanti_testi
                 where baglanti_id = :connectionId
                 order by deneme_no desc
                 limit :limit
                """)
                .param("connectionId", connectionId).param("limit", limit)
                .query((rs, rowNum) -> new ConnectionTestRow(
                        rs.getLong("id"), rs.getObject("uuid", UUID.class), rs.getLong("baglanti_id"),
                        rs.getInt("deneme_no"),
                        "BASARILI".equals(rs.getString("sonuc")) ? "PASSED" : "FAILED",
                        rs.getString("hata_kodu"), rs.getString("urun_adi"), rs.getString("urun_surumu"),
                        rs.getString("surucu_adi"), rs.getString("surucu_surumu"),
                        rs.getObject("veritabani_ana_surumu", Integer.class),
                        rs.getObject("veritabani_alt_surumu", Integer.class),
                        rs.getObject("hedef_kimlik_surumu", Integer.class), rs.getString("hedef_parmak_izi"),
                        rs.getObject("baslama_zamani", OffsetDateTime.class),
                        rs.getObject("tamamlanma_zamani", OffsetDateTime.class),
                        rs.getLong("sure_milisaniye")))
                .list();
    }

    // ---------------------------------------------------------------- physical schemas

    private static final String PHYSICAL_SELECT = """
            select f.id, f.uuid, f.baglanti_id, b.uuid as baglanti_uuid, f.kod, f.ad, f.aciklama, f.saglayici_turu,
                   f.katalog_adi, f.sema_adi, f.calisma_katalog_adi, f.calisma_sema_adi, f.varsayilan_mi,
                   f.yukleme_prefix, f.entegrasyon_prefix, f.hata_prefix, f.gecici_prefix,
                   f.nesne_deseni, f.uzak_nesne_deseni, f.sira_deseni, f.durum
              from akis.fiziksel_sema f
              join akis.baglanti b on b.id = f.baglanti_id
            """;

    List<PhysicalSchemaRow> listPhysicalSchemas() {
        return jdbc.sql(PHYSICAL_SELECT + " order by b.ad, f.sema_adi").query(this::mapPhysical).list();
    }

    Optional<PhysicalSchemaRow> findPhysicalSchema(UUID uuid) {
        return jdbc.sql(PHYSICAL_SELECT + " where f.uuid = :uuid").param("uuid", uuid)
                .query(this::mapPhysical).optional();
    }

    PhysicalSchemaRow createPhysicalSchema(long connectionId, String databaseType, PhysicalSchemaWrite w, Long actorId) {
        UUID uuid = jdbc.sql("""
                insert into akis.fiziksel_sema(
                    baglanti_id, kod, ad, aciklama, saglayici_turu, katalog_adi, sema_adi,
                    calisma_katalog_adi, calisma_sema_adi, varsayilan_mi,
                    yukleme_prefix, entegrasyon_prefix, hata_prefix, gecici_prefix,
                    nesne_deseni, uzak_nesne_deseni, sira_deseni, olusturan_kullanici_id)
                values (:connectionId, :code, :name, :description, :databaseType, :catalogName, :schemaName,
                        :workCatalogName, :workSchemaName, :defaultSchema,
                        :loadingPrefix, :integrationPrefix, :errorPrefix, :tempPrefix,
                        :objectPattern, :remoteObjectPattern, :sequencePattern, :actorId)
                returning uuid
                """)
                .param("connectionId", connectionId).param("databaseType", databaseType)
                .params(physicalParams(w)).param("actorId", actorId, Types.BIGINT)
                .query(UUID.class).single();
        return findPhysicalSchema(uuid).orElseThrow();
    }

    boolean updatePhysicalSchema(UUID uuid, PhysicalSchemaWrite w, Long actorId) {
        return jdbc.sql("""
                update akis.fiziksel_sema
                   set kod = :code, ad = :name, aciklama = :description, katalog_adi = :catalogName,
                       sema_adi = :schemaName, calisma_katalog_adi = :workCatalogName,
                       calisma_sema_adi = :workSchemaName, varsayilan_mi = :defaultSchema,
                       yukleme_prefix = :loadingPrefix, entegrasyon_prefix = :integrationPrefix,
                       hata_prefix = :errorPrefix, gecici_prefix = :tempPrefix,
                       nesne_deseni = :objectPattern, uzak_nesne_deseni = :remoteObjectPattern,
                       sira_deseni = :sequencePattern, durum = :status,
                       guncellenme_zamani = current_timestamp, guncelleyen_kullanici_id = :actorId
                 where uuid = :uuid
                """)
                .param("uuid", uuid).params(physicalParams(w)).param("status", w.status())
                .param("actorId", actorId, Types.BIGINT).update() == 1;
    }

    void clearDefaultPhysicalSchema(long connectionId, UUID keepUuid) {
        jdbc.sql("update akis.fiziksel_sema set varsayilan_mi = false where baglanti_id = :connectionId and uuid <> :keep and varsayilan_mi")
                .param("connectionId", connectionId).param("keep", keepUuid).update();
    }

    boolean deletePhysicalSchema(UUID uuid) {
        return jdbc.sql("delete from akis.fiziksel_sema where uuid = :uuid").param("uuid", uuid).update() == 1;
    }

    boolean physicalSchemaInUse(UUID uuid) {
        return Boolean.TRUE.equals(jdbc.sql("""
                select exists (
                    select 1 from akis.fiziksel_sema f
                     where f.uuid = :uuid
                       and (exists (select 1 from akis.sema_eslemesi se where se.fiziksel_sema_id = f.id)
                         or exists (select 1 from akis.km_work_area_policy p where p.fiziksel_sema_id = f.id)
                         or exists (select 1 from akis.sema_goruntusu g where g.fiziksel_sema_id = f.id)
                         or exists (select 1 from akis.yayin_veri_bagi y where y.fiziksel_sema_id = f.id)))
                """).param("uuid", uuid).query(Boolean.class).single());
    }

    private java.util.Map<String, Object> physicalParams(PhysicalSchemaWrite w) {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("code", w.code()); m.put("name", w.name()); m.put("description", w.description());
        m.put("catalogName", w.catalogName()); m.put("schemaName", w.schemaName());
        m.put("workCatalogName", w.workCatalogName()); m.put("workSchemaName", w.workSchemaName());
        m.put("defaultSchema", w.defaultSchema());
        m.put("loadingPrefix", w.loadingPrefix()); m.put("integrationPrefix", w.integrationPrefix());
        m.put("errorPrefix", w.errorPrefix()); m.put("tempPrefix", w.tempPrefix());
        m.put("objectPattern", w.objectPattern()); m.put("remoteObjectPattern", w.remoteObjectPattern());
        m.put("sequencePattern", w.sequencePattern());
        return m;
    }

    // ---------------------------------------------------------------- logical schemas

    private static final String LOGICAL_SELECT = """
            select id, uuid, kod, ad, aciklama, saglayici_turu, durum from akis.mantiksal_sema
            """;

    List<LogicalSchemaRow> listLogicalSchemas() {
        return jdbc.sql(LOGICAL_SELECT + " order by ad").query(this::mapLogical).list();
    }

    Optional<LogicalSchemaRow> findLogicalSchema(UUID uuid) {
        return jdbc.sql(LOGICAL_SELECT + " where uuid = :uuid").param("uuid", uuid).query(this::mapLogical).optional();
    }

    LogicalSchemaRow createLogicalSchema(String code, String name, String description, String databaseType, Long actorId) {
        UUID uuid = jdbc.sql("""
                insert into akis.mantiksal_sema(kod, ad, aciklama, saglayici_turu, olusturan_kullanici_id)
                values (:code, :name, :description, :databaseType, :actorId) returning uuid
                """)
                .param("code", code).param("name", name).param("description", description, Types.VARCHAR)
                .param("databaseType", databaseType).param("actorId", actorId, Types.BIGINT)
                .query(UUID.class).single();
        return findLogicalSchema(uuid).orElseThrow();
    }

    boolean updateLogicalSchema(UUID uuid, String name, String description, String status, Long actorId) {
        return jdbc.sql("""
                update akis.mantiksal_sema
                   set ad = :name, aciklama = :description, durum = :status,
                       guncellenme_zamani = current_timestamp, guncelleyen_kullanici_id = :actorId
                 where uuid = :uuid
                """)
                .param("uuid", uuid).param("name", name).param("description", description, Types.VARCHAR)
                .param("status", status).param("actorId", actorId, Types.BIGINT).update() == 1;
    }

    boolean deleteLogicalSchema(UUID uuid) {
        return jdbc.sql("delete from akis.mantiksal_sema where uuid = :uuid").param("uuid", uuid).update() == 1;
    }

    boolean logicalSchemaInUse(UUID uuid) {
        return Boolean.TRUE.equals(jdbc.sql("""
                select exists (
                    select 1 from akis.mantiksal_sema ms
                     where ms.uuid = :uuid
                       and (exists (select 1 from akis.sema_eslemesi se where se.mantiksal_sema_id = ms.id)
                         or exists (select 1 from akis.model m where m.mantiksal_sema_id = ms.id)))
                """).param("uuid", uuid).query(Boolean.class).single());
    }

    // ---------------------------------------------------------------- environments

    private static final String ENVIRONMENT_SELECT = """
            select id, uuid, kod, ad, aciklama, risk, varsayilan_mi, politika_sema_surumu, politika, durum
              from akis.ortam
            """;

    List<EnvironmentRow> listEnvironments() {
        return jdbc.sql(ENVIRONMENT_SELECT + " order by ad").query(this::mapEnvironment).list();
    }

    Optional<EnvironmentRow> findEnvironment(UUID uuid) {
        return jdbc.sql(ENVIRONMENT_SELECT + " where uuid = :uuid").param("uuid", uuid).query(this::mapEnvironment).optional();
    }

    EnvironmentRow createEnvironment(String code, String name, String description, String risk, boolean defaultEnvironment,
            int policyVersion, JsonNode policy, Long actorId) {
        UUID uuid = jdbc.sql("""
                insert into akis.ortam(kod, ad, aciklama, uretim_mi, risk, varsayilan_mi, politika_sema_surumu, politika, olusturan_kullanici_id)
                values (:code, :name, :description, :production, :risk, :defaultEnvironment, :policyVersion, cast(:policy as jsonb), :actorId)
                returning uuid
                """)
                .param("code", code).param("name", name).param("description", description, Types.VARCHAR)
                .param("production", "URETIM".equals(risk)).param("risk", risk).param("defaultEnvironment", defaultEnvironment)
                .param("policyVersion", policyVersion).param("policy", policy.toString())
                .param("actorId", actorId, Types.BIGINT)
                .query(UUID.class).single();
        return findEnvironment(uuid).orElseThrow();
    }

    void clearDefaultEnvironment(UUID keepUuid) {
        jdbc.sql("update akis.ortam set varsayilan_mi = false where varsayilan_mi and (cast(:keep as uuid) is null or uuid <> cast(:keep as uuid))")
                .param("keep", keepUuid, Types.OTHER).update();
    }

    boolean updateEnvironment(UUID uuid, String name, String description, String risk, boolean defaultEnvironment,
            String status, Long actorId) {
        return jdbc.sql("""
                update akis.ortam
                   set ad = :name, aciklama = :description, durum = :status,
                       risk = :risk, uretim_mi = :production, varsayilan_mi = :defaultEnvironment,
                       guncellenme_zamani = current_timestamp, guncelleyen_kullanici_id = :actorId
                 where uuid = :uuid
                """)
                .param("uuid", uuid).param("name", name).param("description", description, Types.VARCHAR)
                .param("status", status).param("risk", risk).param("production", "URETIM".equals(risk))
                .param("defaultEnvironment", defaultEnvironment).param("actorId", actorId, Types.BIGINT).update() == 1;
    }

    boolean deleteEnvironment(UUID uuid) {
        return jdbc.sql("delete from akis.ortam where uuid = :uuid").param("uuid", uuid).update() == 1;
    }

    boolean environmentInUse(UUID uuid) {
        return Boolean.TRUE.equals(jdbc.sql("""
                select exists (
                    select 1 from akis.ortam o
                     where o.uuid = :uuid
                       and (exists (select 1 from akis.sema_eslemesi se where se.ortam_id = o.id)
                         or exists (select 1 from akis.yayin y where y.ortam_id = o.id)
                         or exists (select 1 from akis.dogrulama d where d.ortam_id = o.id)
                         or exists (select 1 from akis.model m where m.tersine_muhendislik_ortam_id = o.id)))
                """).param("uuid", uuid).query(Boolean.class).single());
    }

    // ---------------------------------------------------------------- schema bindings

    private static final String BINDING_SELECT = """
            select se.uuid, ms.uuid as mantiksal_uuid, o.uuid as ortam_uuid, fs.uuid as fiziksel_uuid, se.saglayici_turu
              from akis.sema_eslemesi se
              join akis.mantiksal_sema ms on ms.id = se.mantiksal_sema_id
              join akis.ortam o on o.id = se.ortam_id
              join akis.fiziksel_sema fs on fs.id = se.fiziksel_sema_id
            """;

    List<SchemaBindingRow> listSchemaBindings() {
        return jdbc.sql(BINDING_SELECT + " order by ms.ad, o.ad").query(this::mapBinding).list();
    }

    Optional<SchemaBindingRow> findSchemaBinding(UUID uuid) {
        return jdbc.sql(BINDING_SELECT + " where se.uuid = :uuid").param("uuid", uuid).query(this::mapBinding).optional();
    }

    SchemaBindingRow createSchemaBinding(long logicalId, long environmentId, long physicalId, String databaseType, Long actorId) {
        UUID uuid = jdbc.sql("""
                insert into akis.sema_eslemesi(ortam_id, mantiksal_sema_id, fiziksel_sema_id, saglayici_turu, olusturan_kullanici_id)
                values (:environmentId, :logicalId, :physicalId, :databaseType, :actorId) returning uuid
                """)
                .param("environmentId", environmentId).param("logicalId", logicalId).param("physicalId", physicalId)
                .param("databaseType", databaseType).param("actorId", actorId, Types.BIGINT)
                .query(UUID.class).single();
        return findSchemaBinding(uuid).orElseThrow();
    }

    boolean updateSchemaBinding(UUID uuid, long logicalId, long environmentId, long physicalId, String databaseType, Long actorId) {
        return jdbc.sql("""
                update akis.sema_eslemesi
                   set ortam_id = :environmentId, mantiksal_sema_id = :logicalId, fiziksel_sema_id = :physicalId,
                       saglayici_turu = :databaseType,
                       guncellenme_zamani = current_timestamp, guncelleyen_kullanici_id = :actorId
                 where uuid = :uuid
                """)
                .param("uuid", uuid).param("environmentId", environmentId).param("logicalId", logicalId)
                .param("physicalId", physicalId).param("databaseType", databaseType)
                .param("actorId", actorId, Types.BIGINT).update() == 1;
    }

    boolean deleteSchemaBinding(UUID uuid) {
        return jdbc.sql("delete from akis.sema_eslemesi where uuid = :uuid").param("uuid", uuid).update() == 1;
    }

    // ---------------------------------------------------------------- mappers

    private ConnectionRow mapConnection(ResultSet rs, int rowNum) throws SQLException {
        return new ConnectionRow(
                rs.getLong("id"), rs.getObject("uuid", UUID.class), rs.getString("kod"), rs.getString("ad"),
                rs.getString("aciklama"), rs.getString("saglayici_turu"), rs.getString("baglanti_modu"),
                rs.getString("surucu_sinifi"), rs.getString("sunucu_adi"), rs.getObject("port", Integer.class),
                rs.getString("servis_adi"), rs.getString("sid"), rs.getString("veritabani_adi"),
                rs.getString("jdbc_url_ek"), rs.getString("jndi_adi"), rs.getString("kullanici_adi"),
                rs.getBoolean("sifre_var"), rs.getInt("getirme_boyutu"), rs.getInt("toplu_guncelleme_boyutu"),
                rs.getInt("baglanti_zaman_asimi_ms"), rs.getInt("okuma_zaman_asimi_ms"),
                rs.getInt("sorgu_zaman_asimi_saniye"), rs.getString("baglanti_sonrasi_sql"),
                rs.getString("kapanis_oncesi_sql"), rs.getObject("son_test_zamani", OffsetDateTime.class),
                rs.getObject("son_test_basarili_mi", Boolean.class), rs.getString("durum"),
                rs.getString("created_by"), rs.getObject("olusturulma_zamani", OffsetDateTime.class),
                rs.getObject("guncellenme_zamani", OffsetDateTime.class));
    }

    private PhysicalSchemaRow mapPhysical(ResultSet rs, int rowNum) throws SQLException {
        return new PhysicalSchemaRow(
                rs.getLong("id"), rs.getObject("uuid", UUID.class), rs.getLong("baglanti_id"),
                rs.getObject("baglanti_uuid", UUID.class), rs.getString("kod"), rs.getString("ad"),
                rs.getString("aciklama"), rs.getString("saglayici_turu"), rs.getString("katalog_adi"),
                rs.getString("sema_adi"), rs.getString("calisma_katalog_adi"), rs.getString("calisma_sema_adi"),
                rs.getBoolean("varsayilan_mi"), rs.getString("yukleme_prefix"), rs.getString("entegrasyon_prefix"),
                rs.getString("hata_prefix"), rs.getString("gecici_prefix"), rs.getString("nesne_deseni"),
                rs.getString("uzak_nesne_deseni"), rs.getString("sira_deseni"), rs.getString("durum"));
    }

    private LogicalSchemaRow mapLogical(ResultSet rs, int rowNum) throws SQLException {
        return new LogicalSchemaRow(rs.getLong("id"), rs.getObject("uuid", UUID.class), rs.getString("kod"),
                rs.getString("ad"), rs.getString("aciklama"), rs.getString("saglayici_turu"), rs.getString("durum"));
    }

    private EnvironmentRow mapEnvironment(ResultSet rs, int rowNum) throws SQLException {
        return new EnvironmentRow(rs.getLong("id"), rs.getObject("uuid", UUID.class), rs.getString("kod"),
                rs.getString("ad"), rs.getString("aciklama"), rs.getString("risk"), rs.getBoolean("varsayilan_mi"),
                rs.getInt("politika_sema_surumu"), objectMapper.readTree(rs.getString("politika")),
                rs.getString("durum"));
    }

    private SchemaBindingRow mapBinding(ResultSet rs, int rowNum) throws SQLException {
        return new SchemaBindingRow(rs.getObject("uuid", UUID.class), rs.getObject("mantiksal_uuid", UUID.class),
                rs.getObject("ortam_uuid", UUID.class), rs.getObject("fiziksel_uuid", UUID.class),
                rs.getString("saglayici_turu"));
    }

    // ---------------------------------------------------------------- write payloads

    record ConnectionWrite(
            String code, String name, String description, String databaseType, String mode,
            String driverReference, String host, Integer port, String serviceName, String sid,
            String databaseName, String jdbcUrlExtra, String jndiName, String username,
            int fetchSize, int batchSize, int connectTimeoutMs, int readTimeoutMs, int queryTimeoutSeconds,
            String onConnectSql, String onDisconnectSql, String status) {
    }

    record ConnectionTestWrite(
            boolean passed, String errorCode, String databaseProduct, String databaseVersion,
            String driverName, String driverVersion, Integer databaseMajorVersion, Integer databaseMinorVersion,
            Integer targetIdentityVersion, String targetFingerprint,
            OffsetDateTime startedAt, OffsetDateTime completedAt, long durationMs) {
    }

    record PhysicalSchemaWrite(
            String code, String name, String description, String catalogName, String schemaName,
            String workCatalogName, String workSchemaName, boolean defaultSchema,
            String loadingPrefix, String integrationPrefix, String errorPrefix, String tempPrefix,
            String objectPattern, String remoteObjectPattern, String sequencePattern, String status) {
    }
}

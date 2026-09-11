package tr.com.innova.akis.topology;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import tr.com.innova.akis.topology.TopologyModels.ConnectionRow;
import tr.com.innova.akis.topology.TopologyModels.ConnectionVersionRow;
import tr.com.innova.akis.topology.TopologyModels.EnvironmentRow;
import tr.com.innova.akis.topology.TopologyModels.LogicalSchemaRow;
import tr.com.innova.akis.topology.TopologyModels.PhysicalSchemaRow;
import tr.com.innova.akis.topology.TopologyModels.ProjectRef;
import tr.com.innova.akis.topology.TopologyModels.SchemaBindingRow;
import tr.com.innova.akis.topology.TopologyModels.SecretReferenceRow;

@Repository
public class TopologyRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public TopologyRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    Optional<ProjectRef> findProject(UUID projectUuid) {
        return jdbc.sql("select id, uuid from entegrasyon.proje where uuid = :uuid")
                .param("uuid", projectUuid)
                .query((rs, rowNum) -> new ProjectRef(
                        rs.getLong("id"), rs.getObject("uuid", UUID.class)))
                .optional();
    }

    SecretReferenceRow createSecretReference(
            long projectId,
            UUID uuid,
            String code,
            String referencePath,
            String versionReference,
            String provider,
            String name) {
        return jdbc.sql("""
                        insert into entegrasyon.secret_referansi(
                            proje_id, uuid, kod, referans_yolu, surum_referansi,
                            saglayici_kodu, ad)
                        values (:projectId, :uuid, :code, :referencePath, :versionReference,
                                :provider, :name)
                        returning id, uuid, kod, referans_yolu, surum_referansi,
                                  saglayici_kodu, durum_kodu, ad, versiyon_no
                        """)
                .param("projectId", projectId)
                .param("uuid", uuid)
                .param("code", code)
                .param("referencePath", referencePath)
                .param("versionReference", versionReference, Types.VARCHAR)
                .param("provider", provider)
                .param("name", name)
                .query(this::mapSecretReference)
                .single();
    }

    List<SecretReferenceRow> listSecretReferences(long projectId) {
        return jdbc.sql(secretReferenceSelect() + " where proje_id = :projectId order by kod")
                .param("projectId", projectId)
                .query(this::mapSecretReference)
                .list();
    }

    Optional<SecretReferenceRow> findSecretReference(long projectId, UUID uuid) {
        return jdbc.sql(secretReferenceSelect()
                        + " where proje_id = :projectId and uuid = :uuid")
                .param("projectId", projectId)
                .param("uuid", uuid)
                .query(this::mapSecretReference)
                .optional();
    }

    ConnectionRow createConnection(
            long projectId,
            UUID uuid,
            String code,
            String databaseType,
            String name,
            String description) {
        return jdbc.sql("""
                        insert into entegrasyon.baglanti(
                            proje_id, uuid, kod, veritabani_turu, ad, aciklama)
                        values (:projectId, :uuid, :code, :databaseType, :name, :description)
                        returning id, proje_id, uuid, kod, veritabani_turu,
                                  durum_kodu, ad, aciklama, versiyon_no
                        """)
                .param("projectId", projectId)
                .param("uuid", uuid)
                .param("code", code)
                .param("databaseType", databaseType)
                .param("name", name)
                .param("description", description, Types.VARCHAR)
                .query(this::mapConnection)
                .single();
    }

    List<ConnectionRow> listConnections(long projectId) {
        return jdbc.sql(connectionSelect() + " where proje_id = :projectId order by kod")
                .param("projectId", projectId)
                .query(this::mapConnection)
                .list();
    }

    Optional<ConnectionRow> findConnection(long projectId, UUID uuid) {
        return jdbc.sql(connectionSelect() + " where proje_id = :projectId and uuid = :uuid")
                .param("projectId", projectId)
                .param("uuid", uuid)
                .query(this::mapConnection)
                .optional();
    }

    void lockConnection(long connectionId) {
        jdbc.sql("select id from entegrasyon.baglanti where id = :id for update")
                .param("id", connectionId)
                .query(Long.class)
                .single();
    }

    void activateDraftConnection(long connectionId) {
        jdbc.sql("""
                        update entegrasyon.baglanti
                           set durum_kodu = 'AKTIF',
                               guncellenme_zamani = current_timestamp,
                               versiyon_no = versiyon_no + 1
                         where id = :id and durum_kodu = 'TASLAK'
                        """)
                .param("id", connectionId)
                .update();
    }

    int nextConnectionVersion(long connectionId) {
        return jdbc.sql("""
                        select coalesce(max(surum_no), 0) + 1
                          from entegrasyon.baglanti_surumu
                         where baglanti_id = :connectionId
                        """)
                .param("connectionId", connectionId)
                .query(Integer.class)
                .single();
    }

    ConnectionVersionRow createConnectionVersion(
            long projectId,
            long connectionId,
            UUID uuid,
            int versionNumber,
            String mode,
            String driverReference,
            String host,
            String serviceName,
            String sid,
            String databaseName,
            String jndiName,
            String tlsMode,
            Integer port,
            int policyVersion,
            JsonNode policy) {
        return jdbc.sql("""
                        insert into entegrasyon.baglanti_surumu(
                            proje_id, baglanti_id, uuid, surum_no, baglanti_modu, surucu_referansi,
                            sunucu_adi, servis_adi, sid, veritabani_adi, jndi_adi, tls_modu,
                            port, politika_surumu, politika)
                        values (:projectId, :connectionId, :uuid, :versionNumber, :mode, :driverReference,
                                :host, :serviceName, :sid, :databaseName, :jndiName, :tlsMode,
                                :port, :policyVersion, cast(:policy as jsonb))
                        returning id, uuid, baglanti_id, surum_no, baglanti_modu, surucu_referansi,
                                  sunucu_adi, servis_adi, sid, veritabani_adi, jndi_adi, tls_modu,
                                  port, politika_surumu, politika, olusturulma_zamani
                        """)
                .param("projectId", projectId)
                .param("connectionId", connectionId)
                .param("uuid", uuid)
                .param("versionNumber", versionNumber)
                .param("mode", mode)
                .param("driverReference", driverReference, Types.VARCHAR)
                .param("host", host, Types.VARCHAR)
                .param("serviceName", serviceName, Types.VARCHAR)
                .param("sid", sid, Types.VARCHAR)
                .param("databaseName", databaseName, Types.VARCHAR)
                .param("jndiName", jndiName, Types.VARCHAR)
                .param("tlsMode", tlsMode)
                .param("port", port, Types.INTEGER)
                .param("policyVersion", policyVersion)
                .param("policy", policy.toString())
                .query(this::mapConnectionVersion)
                .single();
    }

    void bindSecret(
            long projectId,
            long connectionVersionId,
            long secretReferenceId,
            String role) {
        jdbc.sql("""
                        insert into entegrasyon.baglanti_secret_bagi(
                            proje_id, baglanti_surumu_id, secret_referansi_id, rol_kodu)
                        values (:projectId, :connectionVersionId, :secretReferenceId, :role)
                        """)
                .param("projectId", projectId)
                .param("connectionVersionId", connectionVersionId)
                .param("secretReferenceId", secretReferenceId)
                .param("role", role)
                .update();
    }

    List<ConnectionVersionRow> listConnectionVersions(long projectId, long connectionId) {
        return jdbc.sql(connectionVersionSelect()
                        + " where proje_id = :projectId and baglanti_id = :connectionId"
                        + " order by surum_no desc")
                .param("projectId", projectId)
                .param("connectionId", connectionId)
                .query(this::mapConnectionVersion)
                .list();
    }

    Optional<ConnectionVersionRow> findConnectionVersion(long projectId, UUID uuid) {
        return jdbc.sql(connectionVersionSelect()
                        + " where proje_id = :projectId and uuid = :uuid")
                .param("projectId", projectId)
                .param("uuid", uuid)
                .query(this::mapConnectionVersion)
                .optional();
    }

    PhysicalSchemaRow createPhysicalSchema(
            long projectId,
            long connectionId,
            UUID uuid,
            String code,
            String schemaReference,
            String name) {
        jdbc.sql("""
                        insert into entegrasyon.fiziksel_sema(
                            proje_id, baglanti_id, uuid, kod, sema_referansi, ad)
                        values (:projectId, :connectionId, :uuid, :code, :schemaReference, :name)
                        """)
                .param("projectId", projectId)
                .param("connectionId", connectionId)
                .param("uuid", uuid)
                .param("code", code)
                .param("schemaReference", schemaReference)
                .param("name", name)
                .update();
        return findPhysicalSchema(projectId, uuid).orElseThrow();
    }

    List<PhysicalSchemaRow> listPhysicalSchemas(long projectId) {
        return jdbc.sql(physicalSchemaSelect() + " where f.proje_id = :projectId order by f.kod")
                .param("projectId", projectId)
                .query(this::mapPhysicalSchema)
                .list();
    }

    Optional<PhysicalSchemaRow> findPhysicalSchema(long projectId, UUID uuid) {
        return jdbc.sql(physicalSchemaSelect()
                        + " where f.proje_id = :projectId and f.uuid = :uuid")
                .param("projectId", projectId)
                .param("uuid", uuid)
                .query(this::mapPhysicalSchema)
                .optional();
    }

    LogicalSchemaRow createLogicalSchema(
            long projectId,
            UUID uuid,
            String code,
            String name,
            String description) {
        return jdbc.sql("""
                        insert into entegrasyon.mantiksal_sema(
                            proje_id, uuid, kod, ad, aciklama)
                        values (:projectId, :uuid, :code, :name, :description)
                        returning id, uuid, kod, durum_kodu, ad, aciklama, versiyon_no
                        """)
                .param("projectId", projectId)
                .param("uuid", uuid)
                .param("code", code)
                .param("name", name)
                .param("description", description, Types.VARCHAR)
                .query(this::mapLogicalSchema)
                .single();
    }

    List<LogicalSchemaRow> listLogicalSchemas(long projectId) {
        return jdbc.sql(logicalSchemaSelect() + " where proje_id = :projectId order by kod")
                .param("projectId", projectId)
                .query(this::mapLogicalSchema)
                .list();
    }

    Optional<LogicalSchemaRow> findLogicalSchema(long projectId, UUID uuid) {
        return jdbc.sql(logicalSchemaSelect()
                        + " where proje_id = :projectId and uuid = :uuid")
                .param("projectId", projectId)
                .param("uuid", uuid)
                .query(this::mapLogicalSchema)
                .optional();
    }

    EnvironmentRow createEnvironment(
            long projectId,
            UUID uuid,
            String code,
            String risk,
            int policyVersion,
            JsonNode policy,
            String name) {
        return jdbc.sql("""
                        insert into entegrasyon.ortam(
                            proje_id, uuid, kod, risk_kodu, politika_surumu, politika, ad)
                        values (:projectId, :uuid, :code, :risk, :policyVersion,
                                cast(:policy as jsonb), :name)
                        returning id, uuid, kod, risk_kodu, durum_kodu,
                                  politika_surumu, politika, ad, versiyon_no
                        """)
                .param("projectId", projectId)
                .param("uuid", uuid)
                .param("code", code)
                .param("risk", risk)
                .param("policyVersion", policyVersion)
                .param("policy", policy.toString())
                .param("name", name)
                .query(this::mapEnvironment)
                .single();
    }

    List<EnvironmentRow> listEnvironments(long projectId) {
        return jdbc.sql(environmentSelect() + " where proje_id = :projectId order by kod")
                .param("projectId", projectId)
                .query(this::mapEnvironment)
                .list();
    }

    Optional<EnvironmentRow> findEnvironment(long projectId, UUID uuid) {
        return jdbc.sql(environmentSelect() + " where proje_id = :projectId and uuid = :uuid")
                .param("projectId", projectId)
                .param("uuid", uuid)
                .query(this::mapEnvironment)
                .optional();
    }

    SchemaBindingRow createSchemaBinding(
            long projectId,
            UUID uuid,
            long logicalSchemaId,
            long environmentId,
            long physicalSchemaId,
            long connectionVersionId) {
        jdbc.sql("""
                        insert into entegrasyon.ortam_sema_eslemesi(
                            proje_id, uuid, mantiksal_sema_id, ortam_id,
                            fiziksel_sema_id, baglanti_surumu_id)
                        values (:projectId, :uuid, :logicalSchemaId, :environmentId,
                                :physicalSchemaId, :connectionVersionId)
                        """)
                .param("projectId", projectId)
                .param("uuid", uuid)
                .param("logicalSchemaId", logicalSchemaId)
                .param("environmentId", environmentId)
                .param("physicalSchemaId", physicalSchemaId)
                .param("connectionVersionId", connectionVersionId)
                .update();
        return findSchemaBinding(projectId, uuid).orElseThrow();
    }

    List<SchemaBindingRow> listSchemaBindings(long projectId) {
        return jdbc.sql(schemaBindingSelect()
                        + " where b.proje_id = :projectId order by o.kod, l.kod")
                .param("projectId", projectId)
                .query(this::mapSchemaBinding)
                .list();
    }

    Optional<SchemaBindingRow> findSchemaBinding(long projectId, UUID uuid) {
        return jdbc.sql(schemaBindingSelect()
                        + " where b.proje_id = :projectId and b.uuid = :uuid")
                .param("projectId", projectId)
                .param("uuid", uuid)
                .query(this::mapSchemaBinding)
                .optional();
    }

    private String secretReferenceSelect() {
        return """
                select id, uuid, kod, referans_yolu, surum_referansi,
                       saglayici_kodu, durum_kodu, ad, versiyon_no
                  from entegrasyon.secret_referansi
                """;
    }

    private SecretReferenceRow mapSecretReference(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new SecretReferenceRow(
                rs.getLong("id"), rs.getObject("uuid", UUID.class), rs.getString("kod"),
                rs.getString("referans_yolu"), rs.getString("surum_referansi"),
                rs.getString("saglayici_kodu"), rs.getString("durum_kodu"),
                rs.getString("ad"), rs.getLong("versiyon_no"));
    }

    private String connectionSelect() {
        return """
                select id, proje_id, uuid, kod, veritabani_turu,
                       durum_kodu, ad, aciklama, versiyon_no
                  from entegrasyon.baglanti
                """;
    }

    private ConnectionRow mapConnection(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new ConnectionRow(
                rs.getLong("id"), rs.getLong("proje_id"),
                rs.getObject("uuid", UUID.class), rs.getString("kod"),
                rs.getString("veritabani_turu"), rs.getString("durum_kodu"),
                rs.getString("ad"), rs.getString("aciklama"), rs.getLong("versiyon_no"));
    }

    private String connectionVersionSelect() {
        return """
                select id, uuid, baglanti_id, surum_no, baglanti_modu, surucu_referansi,
                       sunucu_adi, servis_adi, sid, veritabani_adi, jndi_adi, tls_modu,
                       port, politika_surumu, politika, olusturulma_zamani
                  from entegrasyon.baglanti_surumu
                """;
    }

    private ConnectionVersionRow mapConnectionVersion(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new ConnectionVersionRow(
                rs.getLong("id"), rs.getObject("uuid", UUID.class),
                rs.getLong("baglanti_id"), rs.getInt("surum_no"),
                rs.getString("baglanti_modu"), rs.getString("surucu_referansi"), rs.getString("sunucu_adi"),
                rs.getString("servis_adi"), rs.getString("sid"),
                rs.getString("veritabani_adi"), rs.getString("jndi_adi"), rs.getString("tls_modu"),
                rs.getObject("port", Integer.class), rs.getInt("politika_surumu"),
                json(rs.getString("politika")),
                rs.getObject("olusturulma_zamani", OffsetDateTime.class));
    }

    private String physicalSchemaSelect() {
        return """
                select f.id, f.uuid, f.baglanti_id, c.uuid as connection_uuid,
                       f.kod, f.sema_referansi, f.durum_kodu, f.ad, f.versiyon_no
                  from entegrasyon.fiziksel_sema f
                  join entegrasyon.baglanti c on c.id = f.baglanti_id
                """;
    }

    private PhysicalSchemaRow mapPhysicalSchema(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new PhysicalSchemaRow(
                rs.getLong("id"), rs.getObject("uuid", UUID.class),
                rs.getLong("baglanti_id"), rs.getObject("connection_uuid", UUID.class),
                rs.getString("kod"), rs.getString("sema_referansi"),
                rs.getString("durum_kodu"), rs.getString("ad"), rs.getLong("versiyon_no"));
    }

    private String logicalSchemaSelect() {
        return """
                select id, uuid, kod, durum_kodu, ad, aciklama, versiyon_no
                  from entegrasyon.mantiksal_sema
                """;
    }

    private LogicalSchemaRow mapLogicalSchema(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new LogicalSchemaRow(
                rs.getLong("id"), rs.getObject("uuid", UUID.class), rs.getString("kod"),
                rs.getString("durum_kodu"), rs.getString("ad"), rs.getString("aciklama"),
                rs.getLong("versiyon_no"));
    }

    private String environmentSelect() {
        return """
                select id, uuid, kod, risk_kodu, durum_kodu,
                       politika_surumu, politika, ad, versiyon_no
                  from entegrasyon.ortam
                """;
    }

    private EnvironmentRow mapEnvironment(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new EnvironmentRow(
                rs.getLong("id"), rs.getObject("uuid", UUID.class), rs.getString("kod"),
                rs.getString("risk_kodu"), rs.getString("durum_kodu"),
                rs.getInt("politika_surumu"), json(rs.getString("politika")),
                rs.getString("ad"), rs.getLong("versiyon_no"));
    }

    private String schemaBindingSelect() {
        return """
                select b.uuid, l.uuid as logical_schema_uuid, o.uuid as environment_uuid,
                       f.uuid as physical_schema_uuid, v.uuid as connection_version_uuid,
                       b.durum_kodu, b.versiyon_no
                  from entegrasyon.ortam_sema_eslemesi b
                  join entegrasyon.mantiksal_sema l on l.id = b.mantiksal_sema_id
                  join entegrasyon.ortam o on o.id = b.ortam_id
                  join entegrasyon.fiziksel_sema f on f.id = b.fiziksel_sema_id
                  join entegrasyon.baglanti_surumu v on v.id = b.baglanti_surumu_id
                """;
    }

    private SchemaBindingRow mapSchemaBinding(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new SchemaBindingRow(
                rs.getObject("uuid", UUID.class),
                rs.getObject("logical_schema_uuid", UUID.class),
                rs.getObject("environment_uuid", UUID.class),
                rs.getObject("physical_schema_uuid", UUID.class),
                rs.getObject("connection_version_uuid", UUID.class),
                rs.getString("durum_kodu"), rs.getLong("versiyon_no"));
    }

    private JsonNode json(String value) {
        try {
            return objectMapper.readTree(value);
        }
        catch (JacksonException exception) {
            throw new IllegalStateException("Stored JSON could not be read.", exception);
        }
    }
}

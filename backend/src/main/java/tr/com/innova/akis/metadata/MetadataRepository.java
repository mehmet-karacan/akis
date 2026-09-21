package tr.com.innova.akis.metadata;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tr.com.innova.akis.metadata.MetadataModels.DefinitionRow;
import tr.com.innova.akis.metadata.MetadataModels.DraftRow;
import tr.com.innova.akis.metadata.MetadataModels.FolderRow;
import tr.com.innova.akis.metadata.MetadataModels.ProjectRow;
import tr.com.innova.akis.metadata.MetadataModels.VersionRow;

@Repository
public class MetadataRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public MetadataRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    void lockProjectCodeNamespace() {
        jdbc.sql("select pg_advisory_xact_lock(hashtext('akis.projectbundle.project-code'))")
                .query((rs, rowNum) -> 1)
                .single();
    }

    void lockFolderHierarchy(long projectId) {
        jdbc.sql("select id from akis.proje where id = :projectId for update")
                .param("projectId", projectId)
                .query(Long.class)
                .single();
    }

    ProjectRow createProject(
            UUID uuid,
            String code,
            String name,
            String description,
            String actorProvider,
            String actorSubject) {
        Long actorId = findActorId(actorProvider, actorSubject).orElse(null);
        ProjectRow project = jdbc.sql("""
                        insert into akis.proje(
                            uuid, kod, ad, aciklama, olusturan_kullanici_id)
                        values (:uuid, :code, :name, :description, :actorId)
                        returning id, uuid, kod, 'AKTIF' as durum, ad, aciklama,
                                  versiyon_no, olusturulma_zamani
                        """)
                .param("uuid", uuid)
                .param("code", code)
                .param("name", name)
                .param("description", description, Types.VARCHAR)
                .param("actorId", actorId, Types.BIGINT)
                .query((rs, rowNum) -> new ProjectRow(
                        rs.getLong("id"),
                        rs.getObject("uuid", UUID.class),
                        rs.getString("kod"),
                        rs.getString("durum"),
                        rs.getString("ad"),
                        rs.getString("aciklama"),
                        rs.getLong("versiyon_no"),
                        rs.getObject("olusturulma_zamani", OffsetDateTime.class)))
                .single();
        if (actorId != null) {
            assignProjectManager(project.id(), actorId);
        }
        return project;
    }

    private Optional<Long> findActorId(String provider, String subject) {
        if (provider == null || provider.isBlank() || subject == null || subject.isBlank()) {
            return Optional.empty();
        }
        return jdbc.sql("""
                        select k.id
                          from akis.kullanici k
                          join akis.harici_kimlik hk on hk.kullanici_id = k.id
                         where k.devre_disi_birakilma_zamani is null
                           and hk.harici_kullanici_anahtari = :subject
                           and ((:provider = 'LOCAL_BASIC'
                                 and hk.saglayici_turu = 'YEREL'
                                 and hk.yayinlayici is null)
                                or (hk.saglayici_turu = 'OIDC'
                                    and hk.yayinlayici = :provider))
                        """)
                .param("provider", provider)
                .param("subject", subject)
                .query(Long.class)
                .optional();
    }

    private void assignProjectManager(long projectId, long actorId) {
        jdbc.sql("""
                        insert into akis.proje_uyeligi(
                            proje_id, kullanici_id, olusturan_kullanici_id)
                        values (:projectId, :actorId, :actorId)
                        """)
                .param("projectId", projectId)
                .param("actorId", actorId)
                .update();
        jdbc.sql("""
                        insert into akis.kullanici_rol(
                            kullanici_id, rol_id, rol_kapsami, proje_id,
                            atayan_kullanici_id, olusturan_kullanici_id)
                        select :actorId, id, 'PROJE', :projectId, :actorId, :actorId
                          from akis.rol
                         where kapsam = 'PROJE'
                           and kod = 'PROJE_YONETICISI'
                           and etkin_mi
                        """)
                .param("actorId", actorId)
                .param("projectId", projectId)
                .update();
    }

    List<ProjectRow> listProjects() {
        return jdbc.sql("""
                        select id, uuid, kod,
                               case when arsivlenme_zamani is null
                                    then 'AKTIF' else 'ARSIVLENDI' end as durum,
                               ad, aciklama,
                               versiyon_no, olusturulma_zamani
                          from akis.proje
                         order by kod
                        """)
                .query((rs, rowNum) -> new ProjectRow(
                        rs.getLong("id"),
                        rs.getObject("uuid", UUID.class),
                        rs.getString("kod"),
                        rs.getString("durum"),
                        rs.getString("ad"),
                        rs.getString("aciklama"),
                        rs.getLong("versiyon_no"),
                        rs.getObject("olusturulma_zamani", OffsetDateTime.class)))
                .list();
    }

    Optional<ProjectRow> findProject(UUID uuid) {
        return jdbc.sql("""
                        select id, uuid, kod,
                               case when arsivlenme_zamani is null
                                    then 'AKTIF' else 'ARSIVLENDI' end as durum,
                               ad, aciklama,
                               versiyon_no, olusturulma_zamani
                          from akis.proje
                         where uuid = :uuid
                        """)
                .param("uuid", uuid)
                .query((rs, rowNum) -> new ProjectRow(
                        rs.getLong("id"),
                        rs.getObject("uuid", UUID.class),
                        rs.getString("kod"),
                        rs.getString("durum"),
                        rs.getString("ad"),
                        rs.getString("aciklama"),
                        rs.getLong("versiyon_no"),
                        rs.getObject("olusturulma_zamani", OffsetDateTime.class)))
                .optional();
    }

    FolderRow createFolder(
            long projectId,
            UUID uuid,
            Long parentId,
            String code,
            String name,
            String description) {
        jdbc.sql("""
                        insert into akis.klasor(
                            proje_id, uuid, ust_klasor_id, kod, ad, aciklama)
                        values (:projectId, :uuid, :parentId, :code, :name, :description)
                        """)
                .param("projectId", projectId)
                .param("uuid", uuid)
                .param("parentId", parentId, Types.BIGINT)
                .param("code", code)
                .param("name", name)
                .param("description", description, Types.VARCHAR)
                .update();
        return findFolder(projectId, uuid).orElseThrow();
    }

    List<FolderRow> listFolders(long projectId) {
        return jdbc.sql(folderSelect() + " where k.proje_id = :projectId order by k.kod")
                .param("projectId", projectId)
                .query(this::mapFolder)
                .list();
    }

    Optional<FolderRow> findFolder(long projectId, UUID uuid) {
        return jdbc.sql(folderSelect() + " where k.proje_id = :projectId and k.uuid = :uuid")
                .param("projectId", projectId)
                .param("uuid", uuid)
                .query(this::mapFolder)
                .optional();
    }

    FolderRow moveFolder(
            long projectId,
            long folderId,
            Long parentId,
            long expectedVersion) {
        int changed = jdbc.sql("""
                        update akis.klasor
                           set ust_klasor_id = :parentId,
                               guncellenme_zamani = current_timestamp,
                               versiyon_no = versiyon_no + 1
                         where proje_id = :projectId
                           and id = :folderId
                           and versiyon_no = :expectedVersion
                        """)
                .param("projectId", projectId)
                .param("folderId", folderId)
                .param("parentId", parentId, Types.BIGINT)
                .param("expectedVersion", expectedVersion)
                .update();
        if (changed != 1) {
            throw new ApiException(
                    org.springframework.http.HttpStatus.PRECONDITION_FAILED,
                    "STALE_VERSION",
                    "Klasör sürümü istekle uyuşmuyor.");
        }
        return jdbc.sql(folderSelect() + " where k.proje_id = :projectId and k.id = :folderId")
                .param("projectId", projectId)
                .param("folderId", folderId)
                .query(this::mapFolder)
                .single();
    }

    DefinitionRow createDefinition(
            long projectId,
            UUID uuid,
            Long folderId,
            DefinitionType type,
            String code,
            String name,
            String description) {
        jdbc.sql("""
                        insert into akis.tanim(
                            proje_id, klasor_id, tur, kod, ad, aciklama, uuid)
                        values (:projectId, :folderId, :type,
                                :code, :name, :description, :uuid)
                        """)
                .param("projectId", projectId)
                .param("folderId", folderId, Types.BIGINT)
                .param("type", storedDefinitionType(type))
                .param("code", code)
                .param("name", name)
                .param("description", description, Types.VARCHAR)
                .param("uuid", uuid)
                .update();
        return findDefinition(projectId, uuid).orElseThrow();
    }

    DefinitionRow createGlobalDefinition(
            UUID uuid,
            DefinitionType type,
            String code,
            String name,
            String description) {
        jdbc.sql("""
                        insert into akis.tanim(
                            kapsam, tur, kod, ad, aciklama, uuid)
                        values ('SISTEM', :type, :code, :name, :description, :uuid)
                        """)
                .param("type", storedDefinitionType(type))
                .param("code", code)
                .param("name", name)
                .param("description", description, Types.VARCHAR)
                .param("uuid", uuid)
                .update();
        return findGlobalDefinition(uuid).orElseThrow();
    }

    List<DefinitionRow> listDefinitions(long projectId, DefinitionType type) {
        String sql = definitionSelect() + " where t.proje_id = :projectId";
        if (type != null) {
            sql += " and t.tur = :type";
        }
        sql += " order by t.tur, t.kod";
        JdbcClient.StatementSpec query = jdbc.sql(sql).param("projectId", projectId);
        if (type != null) {
            query = query.param("type", storedDefinitionType(type));
        }
        return query.query(this::mapDefinition).list();
    }

    Optional<DefinitionRow> findDefinition(long projectId, UUID uuid) {
        return jdbc.sql(definitionSelect() + " where t.proje_id = :projectId and t.uuid = :uuid")
                .param("projectId", projectId)
                .param("uuid", uuid)
                .query(this::mapDefinition)
                .optional();
    }

    DefinitionRow updateDefinition(
            long projectId,
            long definitionId,
            String name,
            String description,
            long expectedVersion) {
        int changed = jdbc.sql("""
                        update akis.tanim
                           set ad = :name,
                               aciklama = :description,
                               guncellenme_zamani = current_timestamp,
                               versiyon_no = versiyon_no + 1
                         where proje_id = :projectId
                           and id = :definitionId
                           and versiyon_no = :expectedVersion
                        """)
                .param("projectId", projectId)
                .param("definitionId", definitionId)
                .param("name", name)
                .param("description", description, Types.VARCHAR)
                .param("expectedVersion", expectedVersion)
                .update();
        if (changed != 1) {
            throw new ApiException(
                    org.springframework.http.HttpStatus.PRECONDITION_FAILED,
                    "STALE_VERSION",
                    "Tanım sürümü istekle uyuşmuyor.");
        }
        return jdbc.sql(definitionSelect() + " where t.proje_id = :projectId and t.id = :definitionId")
                .param("projectId", projectId)
                .param("definitionId", definitionId)
                .query(this::mapDefinition)
                .single();
    }

    /** Active packages whose draft or any version still references the definition (ODI: an object cannot be deleted while a package step uses it). */
    List<String> findPackagesReferencing(long projectId, UUID definitionUuid) {
        return jdbc.sql("""
                        select distinct p.ad || ' (' || p.kod || ')' as etiket
                          from akis.tanim p
                         where p.proje_id = :projectId
                           and p.tur = 'PACKAGE'
                           and p.arsivlenme_zamani is null
                           and (exists (select 1 from akis.tanim_taslagi d
                                         where d.tanim_id = p.id and d.icerik::text like :needle)
                             or exists (select 1 from akis.tanim_surumu v
                                         where v.tanim_id = p.id and v.icerik::text like :needle))
                         order by 1
                        """)
                .param("projectId", projectId)
                .param("needle", "%" + definitionUuid + "%")
                .query(String.class)
                .list();
    }

    /** Soft delete: archived definitions drop out of every listing while versions, scenarios and run evidence stay intact. */
    boolean archiveDefinition(long projectId, long definitionId, long expectedVersion) {
        return jdbc.sql("""
                        update akis.tanim
                           set arsivlenme_zamani = current_timestamp,
                               guncellenme_zamani = current_timestamp,
                               versiyon_no = versiyon_no + 1
                         where proje_id = :projectId
                           and id = :definitionId
                           and versiyon_no = :expectedVersion
                           and arsivlenme_zamani is null
                        """)
                .param("projectId", projectId)
                .param("definitionId", definitionId)
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    DefinitionRow moveDefinition(
            long projectId,
            long definitionId,
            Long folderId,
            long expectedVersion) {
        int changed = jdbc.sql("""
                        update akis.tanim
                           set klasor_id = :folderId,
                               guncellenme_zamani = current_timestamp,
                               versiyon_no = versiyon_no + 1
                         where proje_id = :projectId
                           and id = :definitionId
                           and versiyon_no = :expectedVersion
                        """)
                .param("projectId", projectId)
                .param("definitionId", definitionId)
                .param("folderId", folderId, Types.BIGINT)
                .param("expectedVersion", expectedVersion)
                .update();
        if (changed != 1) {
            throw new ApiException(
                    org.springframework.http.HttpStatus.PRECONDITION_FAILED,
                    "STALE_VERSION",
                    "Tanım sürümü istekle uyuşmuyor.");
        }
        return jdbc.sql(definitionSelect() + " where t.proje_id = :projectId and t.id = :definitionId")
                .param("projectId", projectId)
                .param("definitionId", definitionId)
                .query(this::mapDefinition)
                .single();
    }

    List<DefinitionRow> listGlobalDefinitions(DefinitionType type) {
        String sql = definitionSelect() + " where t.kapsam = 'SISTEM'";
        if (type != null) {
            sql += " and t.tur = :type";
        }
        sql += " order by t.tur, t.kod";
        JdbcClient.StatementSpec query = jdbc.sql(sql);
        if (type != null) {
            query = query.param("type", storedDefinitionType(type));
        }
        return query.query(this::mapDefinition).list();
    }

    Optional<DefinitionRow> findGlobalDefinition(UUID uuid) {
        return jdbc.sql(definitionSelect() + " where t.kapsam = 'SISTEM' and t.uuid = :uuid")
                .param("uuid", uuid)
                .query(this::mapDefinition)
                .optional();
    }

    void lockDefinition(long definitionId) {
        jdbc.sql("select id from akis.tanim where id = :definitionId for update")
                .param("definitionId", definitionId)
                .query(Long.class)
                .single();
    }

    Optional<DraftRow> findDraft(long definitionId) {
        return jdbc.sql("""
                        select uuid, tanim_id, sema_surumu, icerik,
                               versiyon_no
                          from akis.tanim_taslagi
                         where tanim_id = :definitionId
                        """)
                .param("definitionId", definitionId)
                .query((rs, rowNum) -> new DraftRow(
                        rs.getObject("uuid", UUID.class),
                        rs.getLong("tanim_id"),
                        rs.getInt("sema_surumu"),
                        json(rs.getString("icerik")),
                        rs.getLong("versiyon_no")))
                .optional();
    }

    DraftRow createDraft(long definitionId, int schemaVersion, JsonNode content) {
        jdbc.sql("""
                        insert into akis.tanim_taslagi(
                            proje_id, tanim_id, sema_surumu, icerik)
                        select proje_id, id, :schemaVersion, cast(:content as jsonb)
                          from akis.tanim where id = :definitionId
                        """)
                .param("definitionId", definitionId)
                .param("schemaVersion", schemaVersion)
                .param("content", content.toString())
                .update();
        return findDraft(definitionId).orElseThrow();
    }

    DraftRow updateDraft(
            long definitionId,
            long expectedVersion,
            int schemaVersion,
            JsonNode content) {
        int changed = jdbc.sql("""
                        update akis.tanim_taslagi
                           set sema_surumu = :schemaVersion,
                               icerik = cast(:content as jsonb),
                               guncellenme_zamani = current_timestamp,
                               versiyon_no = versiyon_no + 1
                         where tanim_id = :definitionId
                           and versiyon_no = :expectedVersion
                        """)
                .param("schemaVersion", schemaVersion)
                .param("content", content.toString())
                .param("definitionId", definitionId)
                .param("expectedVersion", expectedVersion)
                .update();
        if (changed != 1) {
            throw new ApiException(
                    org.springframework.http.HttpStatus.PRECONDITION_FAILED,
                    "STALE_VERSION",
                    "Taslak başka bir işlem tarafından değiştirilmiş.");
        }
        return findDraft(definitionId).orElseThrow();
    }

    VersionRow createVersion(
            long definitionId,
            int schemaVersion,
            String contentHash,
            JsonNode content,
            String description) {
        Integer nextVersion = jdbc.sql("""
                        select coalesce(max(surum_no), 0) + 1
                          from akis.tanim_surumu
                         where tanim_id = :definitionId
                        """)
                .param("definitionId", definitionId)
                .query(Integer.class)
                .single();
        return jdbc.sql("""
                        insert into akis.tanim_surumu(
                            proje_id, tanim_id, surum_no, sema_surumu, icerik_ozeti,
                            icerik, aciklama)
                        select proje_id, id, :versionNumber, :schemaVersion,
                               :contentHash, cast(:content as jsonb), :description
                          from akis.tanim where id = :definitionId
                        returning uuid, surum_no, sema_surumu, icerik_ozeti,
                                  icerik, aciklama, olusturulma_zamani
                        """)
                .param("definitionId", definitionId)
                .param("versionNumber", nextVersion)
                .param("schemaVersion", schemaVersion)
                .param("contentHash", contentHash)
                .param("content", content.toString())
                .param("description", description, Types.VARCHAR)
                .query((rs, rowNum) -> new VersionRow(
                        rs.getObject("uuid", UUID.class),
                        rs.getInt("surum_no"),
                        rs.getInt("sema_surumu"),
                        rs.getString("icerik_ozeti"),
                        json(rs.getString("icerik")),
                        rs.getString("aciklama"),
                        rs.getObject("olusturulma_zamani", OffsetDateTime.class)))
                .single();
    }

    /**
     * Procedure tasks name their tables in SQL, not through a picker. Bind each task to the one registered data object of its
     * logical schema's models whose reference appears in the command (ODI-style implicit datastore); the publication later
     * pins that object's newest schema snapshot as the task's target identity. Ambiguous or unmatched tasks stay unbound and
     * the publication reports it.
     */
    void createProcedureObjectReferences(long definitionId, UUID versionUuid, JsonNode content) {
        for (JsonNode task : content.path("tasks")) {
            String logical = task.path("logicalSchemaUuid").asText("");
            String command = task.path("command").asText("").toUpperCase(java.util.Locale.ROOT);
            if (logical.isBlank() || command.isBlank()) continue;
            var matches = jdbc.sql("""
                    select vn.uuid, vn.nesne_referansi from akis.veri_nesnesi vn
                      join akis.model m on m.id = vn.model_id and m.arsivlenme_zamani is null
                      join akis.mantiksal_sema ms on ms.id = m.mantiksal_sema_id
                     where ms.uuid = :logical and vn.arsivlenme_zamani is null
                       and exists (select 1 from akis.sema_goruntusu sg where sg.veri_nesnesi_id = vn.id)
                    """).param("logical", UUID.fromString(logical))
                    .query((rs, n) -> new String[]{rs.getString("uuid"), rs.getString("nesne_referansi")}).list().stream()
                    .filter(row -> java.util.regex.Pattern.compile("(?<![A-Z0-9_$#])" + java.util.regex.Pattern.quote(row[1].toUpperCase(java.util.Locale.ROOT)) + "(?![A-Z0-9_$#])").matcher(command).find())
                    .toList();
            if (matches.size() != 1) continue;
            jdbc.sql("""
                    insert into akis.tanim_veri_nesnesi(proje_id,tanim_surumu_id,veri_nesnesi_id,sema_goruntusu_id,dugum_kodu,rol)
                    select ts.proje_id, ts.id, vn.id, sg.id, :nodeCode, :role
                      from akis.tanim_surumu ts
                      join akis.veri_nesnesi vn on vn.proje_id = ts.proje_id and vn.uuid = :objectUuid
                      join lateral (select id from akis.sema_goruntusu g where g.veri_nesnesi_id = vn.id order by g.kesif_zamani desc limit 1) sg on true
                     where ts.tanim_id = :definitionId and ts.uuid = :versionUuid
                    """).param("nodeCode", task.path("id").asText()).param("role", "SOURCE".equals(task.path("connectionRole").asText()) ? "KAYNAK" : "HEDEF")
                    .param("objectUuid", UUID.fromString(matches.getFirst()[0])).param("definitionId", definitionId).param("versionUuid", versionUuid).update();
        }
    }

    void createDirectObjectReferences(long definitionId, UUID versionUuid, JsonNode content) {
        var references = new java.util.ArrayList<JsonNode>();
        content.path("sources").forEach(references::add);
        references.add(content.path("target"));
        for (int index = 0; index < references.size(); index++) {
            JsonNode reference = references.get(index);
            String role = index == references.size() - 1 ? "HEDEF" : "KAYNAK";
            jdbc.sql("""
                    insert into akis.tanim_veri_nesnesi(
                        proje_id,tanim_surumu_id,veri_nesnesi_id,sema_goruntusu_id,dugum_kodu,rol)
                    select ts.proje_id,ts.id,vn.id,sg.id,:nodeCode,:role
                      from akis.tanim_surumu ts
                      join akis.veri_nesnesi vn on vn.proje_id=ts.proje_id and vn.uuid=:objectUuid
                      join akis.sema_goruntusu sg on sg.proje_id=ts.proje_id and sg.uuid=:snapshotUuid
                         and sg.veri_nesnesi_id=vn.id
                     where ts.tanim_id=:definitionId and ts.uuid=:versionUuid
                    returning id
                    """)
                    .param("nodeCode", reference.path("id").asText())
                    .param("role", role)
                    .param("objectUuid", UUID.fromString(reference.path("dataObjectUuid").asText()))
                    .param("snapshotUuid", UUID.fromString(reference.path("schemaSnapshotUuid").asText()))
                    .param("definitionId", definitionId)
                    .param("versionUuid", versionUuid)
                    .query(Long.class)
                    .optional()
                    .orElseThrow(() -> new IllegalArgumentException("Kaynak veya hedef veri nesnesi ile şema görüntüsü uyuşmuyor."));
        }
    }

    void activateDraftDefinition(long definitionId) {
        jdbc.sql("""
                        update akis.tanim
                           set guncellenme_zamani = current_timestamp,
                               versiyon_no = versiyon_no + 1
                         where id = :id and arsivlenme_zamani is null
                        """)
                .param("id", definitionId)
                .update();
    }

    List<VersionRow> listVersions(long definitionId) {
        return jdbc.sql("""
                        select uuid, surum_no, sema_surumu, icerik_ozeti,
                               icerik, aciklama, olusturulma_zamani
                          from akis.tanim_surumu
                         where tanim_id = :definitionId
                         order by surum_no desc
                        """)
                .param("definitionId", definitionId)
                .query((rs, rowNum) -> new VersionRow(
                        rs.getObject("uuid", UUID.class),
                        rs.getInt("surum_no"),
                        rs.getInt("sema_surumu"),
                        rs.getString("icerik_ozeti"),
                        json(rs.getString("icerik")),
                        rs.getString("aciklama"),
                        rs.getObject("olusturulma_zamani", OffsetDateTime.class)))
                .list();
    }

    private String folderSelect() {
        return """
                select k.id, k.proje_id, k.uuid, p.uuid as parent_uuid,
                       k.kod,
                       case when k.arsivlenme_zamani is null then 'AKTIF' else 'ARSIVLENDI' end as durum,
                       k.ad, k.aciklama,
                       k.versiyon_no
                  from akis.klasor k
                  left join akis.klasor p on p.id = k.ust_klasor_id
                """;
    }

    private FolderRow mapFolder(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new FolderRow(
                rs.getLong("id"),
                rs.getLong("proje_id"),
                rs.getObject("uuid", UUID.class),
                rs.getObject("parent_uuid", UUID.class),
                rs.getString("kod"),
                rs.getString("durum"),
                rs.getString("ad"),
                rs.getString("aciklama"),
                rs.getLong("versiyon_no"));
    }

    private String definitionSelect() {
        return """
                select t.id, t.proje_id, t.uuid, k.uuid as folder_uuid,
                       t.tur, t.kod,
                       case when t.arsivlenme_zamani is null then 'AKTIF' else 'ARSIVLENDI' end as durum,
                       t.ad, t.aciklama,
                       t.versiyon_no
                  from akis.tanim t
                  left join akis.klasor k on k.id = t.klasor_id
                """;
    }

    private DefinitionRow mapDefinition(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new DefinitionRow(
                rs.getLong("id"),
                rs.getObject("proje_id", Long.class),
                rs.getObject("uuid", UUID.class),
                rs.getObject("folder_uuid", UUID.class),
                apiDefinitionType(rs.getString("tur")),
                rs.getString("kod"),
                rs.getString("durum"),
                rs.getString("ad"),
                rs.getString("aciklama"),
                rs.getLong("versiyon_no"));
    }

    private String storedDefinitionType(DefinitionType type) {
        return switch (type) {
            case MAPPING -> "MAPPING";
            case REUSABLE_MAPPING -> "YENIDEN_KULLANILABILIR_MAPPING";
            case PACKAGE -> "PAKET";
            case PROCEDURE -> "PROSEDUR";
            case VARIABLE -> "DEGISKEN";
            case SEQUENCE -> "SEQUENCE";
            case KNOWLEDGE_MODULE -> "KNOWLEDGE_MODULE";
            case LOAD_PLAN -> "LOAD_PLAN";
        };
    }

    private DefinitionType apiDefinitionType(String type) {
        return switch (type) {
            case "MAPPING" -> DefinitionType.MAPPING;
            case "YENIDEN_KULLANILABILIR_MAPPING" -> DefinitionType.REUSABLE_MAPPING;
            case "PAKET" -> DefinitionType.PACKAGE;
            case "PROSEDUR" -> DefinitionType.PROCEDURE;
            case "DEGISKEN" -> DefinitionType.VARIABLE;
            case "SEQUENCE" -> DefinitionType.SEQUENCE;
            case "KNOWLEDGE_MODULE" -> DefinitionType.KNOWLEDGE_MODULE;
            case "LOAD_PLAN" -> DefinitionType.LOAD_PLAN;
            default -> throw new IllegalStateException("Bilinmeyen tanım türü: " + type);
        };
    }

    private JsonNode json(String value) {
        try {
            return objectMapper.readTree(value);
        }
        catch (tools.jackson.core.JacksonException exception) {
            throw new IllegalStateException("Stored JSON could not be read.", exception);
        }
    }
}

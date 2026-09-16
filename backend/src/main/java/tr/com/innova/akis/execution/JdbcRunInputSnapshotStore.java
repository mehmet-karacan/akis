package tr.com.innova.akis.execution;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tr.com.innova.akis.knowledge.KmCanonical;

@Repository
final class JdbcRunInputSnapshotStore implements RunInputSnapshotStore {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    JdbcRunInputSnapshotStore(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public SnapshotClaim claim(SnapshotIdentity identity, UUID resolveToken) {
        required(identity, resolveToken);
        jdbc.sql("""
                insert into akis.calistirma_girdi_goruntusu(
                    proje_id,is_talebi_id,calistirma_id,sozlesme_surumu,yayin_ozeti,
                    runtime_plan_ozeti,tanim_surumu_uuid,girdi_ozeti,durum,
                    cozumleme_tokeni,olusturan_kullanici_id)
                select r.proje_id,r.is_talebi_id,r.id,1,:releaseHash,:runtimePlanHash,
                       :definitionVersionUuid,null,'RESOLVING',:resolveToken,r.olusturan_kullanici_id
                  from akis.calistirma r join akis.proje p on p.id=r.proje_id
                 where p.uuid=:projectUuid and r.uuid=:runUuid
                on conflict (calistirma_id) do nothing
                """)
                .param("releaseHash", identity.releaseHash())
                .param("runtimePlanHash", identity.runtimePlanHash())
                .param("definitionVersionUuid", identity.definitionVersionUuid())
                .param("resolveToken", resolveToken)
                .param("projectUuid", identity.projectUuid())
                .param("runUuid", identity.runUuid()).update();
        StoredClaim stored = jdbc.sql("""
                select g.uuid,g.cozumleme_tokeni,g.versiyon_no,g.durum,
                       g.yayin_ozeti,g.runtime_plan_ozeti,g.tanim_surumu_uuid
                  from akis.calistirma_girdi_goruntusu g
                              join akis.calistirma r on r.is_talebi_id=g.is_talebi_id
                  join akis.proje p on p.id=r.proje_id
                 where p.uuid=:projectUuid and r.uuid=:runUuid for update of g
                """).param("projectUuid", identity.projectUuid())
                .param("runUuid", identity.runUuid())
                .query((rs, row) -> new StoredClaim(new SnapshotClaim(
                                rs.getObject("uuid", UUID.class),
                                rs.getObject("cozumleme_tokeni", UUID.class),
                                rs.getLong("versiyon_no"), "COMPLETE".equals(rs.getString("durum"))),
                        rs.getString("yayin_ozeti"), rs.getString("runtime_plan_ozeti"),
                        rs.getObject("tanim_surumu_uuid", UUID.class)))
                .optional().orElseThrow(() -> new IllegalArgumentException("Run was not found."));
        if (!stored.releaseHash().equals(identity.releaseHash())
                || !stored.runtimePlanHash().equals(identity.runtimePlanHash())
                || !stored.definitionVersionUuid().equals(identity.definitionVersionUuid())) {
            throw new InputSnapshotIdentityConflictException();
        }
        SnapshotClaim claim = stored.claim();
        if (!claim.alreadyComplete() && !claim.resolveToken().equals(resolveToken)) {
            throw new InputSnapshotResolutionInProgressException();
        }
        return claim;
    }

    @Override
    @Transactional
    public Snapshot finalizeSnapshot(
            UUID projectUuid, UUID runUuid, UUID resolveToken,
            long expectedVersion, ResolvedInput input) {
        String inputHash = canonicalHash(input);
        int updated = jdbc.sql("""
                update akis.calistirma_girdi_goruntusu g
                   set degisken_surumleri=cast(:variableVersions as jsonb),
                       tipli_parametreler=cast(:typedParameters as jsonb),
                       cozulmus_degiskenler=cast(:resolvedVariables as jsonb),
                       binding_surumu_uuids=cast(:bindingVersions as jsonb),
                       kaynak_scn=:sourceScn,kaynak_goruntu_ozeti=:sourceHash,
                       girdi_ozeti=:inputHash,durum='COMPLETE',
                       tamamlanma_zamani=clock_timestamp(),versiyon_no=versiyon_no+1
                  from akis.calistirma r, akis.proje p
                 where r.id=g.calistirma_id and p.id=r.proje_id
                   and p.uuid=:projectUuid and r.uuid=:runUuid
                   and g.durum='RESOLVING' and g.cozumleme_tokeni=:resolveToken
                   and g.versiyon_no=:expectedVersion
                """)
                .param("variableVersions", json(input.variableVersions()))
                .param("typedParameters", json(input.typedParameters()))
                .param("resolvedVariables", json(input.resolvedVariables()))
                .param("bindingVersions", json(input.bindingVersionUuids()))
                .param("sourceScn", input.sourceScn(), java.sql.Types.VARCHAR)
                .param("sourceHash", input.sourceSnapshotHash(), java.sql.Types.CHAR)
                .param("inputHash", inputHash).param("projectUuid", projectUuid)
                .param("runUuid", runUuid).param("resolveToken", resolveToken)
                .param("expectedVersion", expectedVersion).update();
        if (updated != 1) throw new InputSnapshotConflictException();
        return find(projectUuid, runUuid).orElseThrow();
    }

    @Override
    public Optional<Snapshot> find(UUID projectUuid, UUID runUuid) {
        return jdbc.sql("""
                select g.uuid,r.uuid as run_uuid,g.girdi_ozeti,g.durum,g.versiyon_no,
                       g.degisken_surumleri,g.tipli_parametreler,g.cozulmus_degiskenler,
                       g.binding_surumu_uuids,g.kaynak_scn,g.kaynak_goruntu_ozeti
                  from akis.calistirma_girdi_goruntusu g
                  join akis.calistirma r on r.is_talebi_id=g.is_talebi_id
                  join akis.proje p on p.id=r.proje_id
                 where p.uuid=:projectUuid and r.uuid=:runUuid
                """).param("projectUuid", projectUuid).param("runUuid", runUuid)
                .query((rs, row) -> new Snapshot(
                        rs.getObject("uuid", UUID.class), rs.getObject("run_uuid", UUID.class),
                        rs.getString("girdi_ozeti"), rs.getString("durum"),
                        rs.getLong("versiyon_no"), new ResolvedInput(
                                node(rs.getString("degisken_surumleri")),
                                node(rs.getString("tipli_parametreler")),
                                node(rs.getString("cozulmus_degiskenler")),
                                node(rs.getString("binding_surumu_uuids")),
                                rs.getString("kaynak_scn"),
                                rs.getString("kaynak_goruntu_ozeti"))))
                .optional();
    }

    private void required(SnapshotIdentity identity, UUID token) {
        if (identity == null || identity.projectUuid() == null || identity.runUuid() == null
                || identity.definitionVersionUuid() == null || token == null
                || !hash(identity.releaseHash()) || !hash(identity.runtimePlanHash())) {
            throw new IllegalArgumentException("Input snapshot identity is invalid.");
        }
    }

    String canonicalHash(ResolvedInput input) {
        if (input == null || input.variableVersions() == null || input.typedParameters() == null
                || input.resolvedVariables() == null || input.bindingVersionUuids() == null
                || (input.sourceScn() == null) != (input.sourceSnapshotHash() == null)
                || (input.sourceSnapshotHash() != null && !hash(input.sourceSnapshotHash()))) {
            throw new IllegalArgumentException("Resolved run input is incomplete.");
        }
        var canonical = objectMapper.createObjectNode();
        canonical.set("variableVersions", input.variableVersions());
        canonical.set("typedParameters", input.typedParameters());
        canonical.set("resolvedVariables", input.resolvedVariables());
        canonical.set("bindingVersionUuids", input.bindingVersionUuids());
        if (input.sourceScn() == null) canonical.putNull("sourceScn");
        else canonical.put("sourceScn", input.sourceScn());
        if (input.sourceSnapshotHash() == null) canonical.putNull("sourceSnapshotHash");
        else canonical.put("sourceSnapshotHash", input.sourceSnapshotHash());
        return KmCanonical.hash(objectMapper, canonical);
    }

    private JsonNode node(String value) {
        if (value == null) return null;
        try { return objectMapper.readTree(value); }
        catch (Exception exception) { throw new IllegalStateException("Stored input JSON is invalid."); }
    }

    private String json(JsonNode value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalArgumentException("Input JSON is invalid."); }
    }

    private boolean hash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private record StoredClaim(SnapshotClaim claim, String releaseHash,
            String runtimePlanHash, UUID definitionVersionUuid) { }
}

final class InputSnapshotResolutionInProgressException extends RuntimeException {
    private static final long serialVersionUID = 1L;
}

final class InputSnapshotConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;
}

final class InputSnapshotIdentityConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;
}

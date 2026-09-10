package tr.com.innova.akis.identity;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tr.com.innova.akis.identity.IdentityModels.MembershipRow;
import tr.com.innova.akis.identity.IdentityModels.ProjectRef;
import tr.com.innova.akis.identity.IdentityModels.ProjectRoleRef;
import tr.com.innova.akis.identity.IdentityModels.ProjectRoleView;
import tr.com.innova.akis.identity.IdentityModels.UserRow;

@Repository
public class JdbcIdentityStore implements IdentityStore {

    private final JdbcClient jdbc;

    public JdbcIdentityStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UserRow createUser(
            UUID uuid,
            String issuer,
            String subject,
            String name,
            String email) {
        jdbc.sql("""
                        insert into entegrasyon.kullanici(
                            uuid, oidc_saglayici, oidc_ozne, ad, eposta)
                        values (:uuid, :issuer, :subject, :name, :email)
                        """)
                .param("uuid", uuid)
                .param("issuer", issuer)
                .param("subject", subject)
                .param("name", name)
                .param("email", email, Types.VARCHAR)
                .update();
        return findUser(uuid).orElseThrow();
    }

    @Override
    public List<UserRow> listUsers() {
        return jdbc.sql(userSelect() + " order by k.ad, k.uuid")
                .query(this::mapUser)
                .list();
    }

    @Override
    public Optional<UserRow> findUser(UUID userUuid) {
        return jdbc.sql(userSelect() + " where k.uuid = :uuid")
                .param("uuid", userUuid)
                .query(this::mapUser)
                .optional();
    }

    @Override
    public Optional<UserRow> findUser(String issuer, String subject) {
        return jdbc.sql(userSelect()
                        + " where k.oidc_saglayici = :issuer and k.oidc_ozne = :subject")
                .param("issuer", issuer)
                .param("subject", subject)
                .query(this::mapUser)
                .optional();
    }

    @Override
    public Optional<ProjectRef> findProject(UUID projectUuid) {
        return jdbc.sql("""
                        select id, uuid, durum_kodu
                          from entegrasyon.proje
                         where uuid = :uuid
                        """)
                .param("uuid", projectUuid)
                .query((rs, rowNum) -> new ProjectRef(
                        rs.getLong("id"),
                        rs.getObject("uuid", UUID.class),
                        rs.getString("durum_kodu")))
                .optional();
    }

    @Override
    public Optional<ProjectRoleRef> findProjectRole(long projectId, String roleCode) {
        return jdbc.sql("""
                        select id, uuid, proje_id, kod, durum_kodu
                          from entegrasyon.proje_rolu
                         where proje_id = :projectId and kod = :roleCode
                        """)
                .param("projectId", projectId)
                .param("roleCode", roleCode)
                .query((rs, rowNum) -> new ProjectRoleRef(
                        rs.getLong("id"),
                        rs.getObject("uuid", UUID.class),
                        rs.getLong("proje_id"),
                        rs.getString("kod"),
                        rs.getString("durum_kodu")))
                .optional();
    }

    @Override
    public boolean membershipExists(long projectId, long userId) {
        return jdbc.sql("""
                        select exists(
                            select 1 from entegrasyon.proje_uyeligi
                             where proje_id = :projectId and kullanici_id = :userId)
                        """)
                .param("projectId", projectId)
                .param("userId", userId)
                .query(Boolean.class)
                .single();
    }

    @Override
    public MembershipRow createMembership(
            long projectId,
            long userId,
            long projectRoleId,
            UUID membershipUuid,
            UUID membershipRoleUuid,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt) {
        jdbc.sql("""
                        insert into entegrasyon.proje_uyeligi(
                            proje_id, kullanici_id, uuid, baslangic_zamani, bitis_zamani)
                        values (:projectId, :userId, :uuid, :startsAt, :endsAt)
                        """)
                .param("projectId", projectId)
                .param("userId", userId)
                .param("uuid", membershipUuid)
                .param("startsAt", startsAt)
                .param("endsAt", endsAt, Types.TIMESTAMP_WITH_TIMEZONE)
                .update();

        long membershipId = jdbc.sql("""
                        select id from entegrasyon.proje_uyeligi
                         where proje_id = :projectId and uuid = :uuid
                        """)
                .param("projectId", projectId)
                .param("uuid", membershipUuid)
                .query(Long.class)
                .single();

        jdbc.sql("""
                        insert into entegrasyon.proje_uyeligi_rolu(
                            proje_id, proje_uyeligi_id, proje_rolu_id, uuid)
                        values (:projectId, :membershipId, :projectRoleId, :uuid)
                        """)
                .param("projectId", projectId)
                .param("membershipId", membershipId)
                .param("projectRoleId", projectRoleId)
                .param("uuid", membershipRoleUuid)
                .update();

        return findMembership(projectId, membershipUuid).orElseThrow();
    }

    @Override
    public List<MembershipRow> listMemberships(long projectId) {
        return jdbc.sql(membershipSelect()
                        + " where pu.proje_id = :projectId order by k.ad, pu.uuid")
                .param("projectId", projectId)
                .query(this::mapMembershipBase)
                .list()
                .stream()
                .map(this::withRoles)
                .toList();
    }

    @Override
    public Optional<MembershipRow> findMembership(long projectId, UUID membershipUuid) {
        return jdbc.sql(membershipSelect()
                        + " where pu.proje_id = :projectId and pu.uuid = :membershipUuid")
                .param("projectId", projectId)
                .param("membershipUuid", membershipUuid)
                .query(this::mapMembershipBase)
                .optional()
                .map(this::withRoles);
    }

    private String userSelect() {
        return """
                select k.id, k.uuid, k.oidc_saglayici, k.oidc_ozne,
                       k.durum_kodu, k.ad, k.eposta, k.olusturulma_zamani
                  from entegrasyon.kullanici k
                """;
    }

    private UserRow mapUser(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new UserRow(
                rs.getLong("id"),
                rs.getObject("uuid", UUID.class),
                rs.getString("oidc_saglayici"),
                rs.getString("oidc_ozne"),
                rs.getString("durum_kodu"),
                rs.getString("ad"),
                rs.getString("eposta"),
                rs.getObject("olusturulma_zamani", OffsetDateTime.class));
    }

    private String membershipSelect() {
        return """
                select pu.id, pu.uuid, pu.proje_id, p.uuid as proje_uuid,
                       pu.kullanici_id, k.uuid as kullanici_uuid,
                       pu.durum_kodu, pu.baslangic_zamani, pu.bitis_zamani,
                       pu.versiyon_no
                  from entegrasyon.proje_uyeligi pu
                  join entegrasyon.proje p on p.id = pu.proje_id
                  join entegrasyon.kullanici k on k.id = pu.kullanici_id
                """;
    }

    private MembershipRow mapMembershipBase(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        return new MembershipRow(
                rs.getLong("id"),
                rs.getObject("uuid", UUID.class),
                rs.getLong("proje_id"),
                rs.getObject("proje_uuid", UUID.class),
                rs.getLong("kullanici_id"),
                rs.getObject("kullanici_uuid", UUID.class),
                rs.getString("durum_kodu"),
                rs.getObject("baslangic_zamani", OffsetDateTime.class),
                rs.getObject("bitis_zamani", OffsetDateTime.class),
                rs.getLong("versiyon_no"),
                List.of());
    }

    private MembershipRow withRoles(MembershipRow membership) {
        List<ProjectRoleView> roles = jdbc.sql("""
                        select pr.uuid, pr.kod
                          from entegrasyon.proje_uyeligi_rolu pur
                          join entegrasyon.proje_rolu pr
                            on pr.proje_id = pur.proje_id
                           and pr.id = pur.proje_rolu_id
                         where pur.proje_id = :projectId
                           and pur.proje_uyeligi_id = :membershipId
                         order by pr.kod
                        """)
                .param("projectId", membership.projectId())
                .param("membershipId", membership.id())
                .query((roleRs, roleRowNum) -> new ProjectRoleView(
                        roleRs.getObject("uuid", UUID.class), roleRs.getString("kod")))
                .list();
        return new MembershipRow(
                membership.id(), membership.uuid(), membership.projectId(),
                membership.projectUuid(), membership.userId(), membership.userUuid(),
                membership.status(), membership.startsAt(), membership.endsAt(),
                membership.version(),
                roles);
    }
}

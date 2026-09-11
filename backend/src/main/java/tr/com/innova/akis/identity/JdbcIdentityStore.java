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

    private static final String LOCAL_BASIC_PROVIDER = "LOCAL_BASIC";

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
        long userId = jdbc.sql("""
                        insert into akis.kullanici(uuid, gorunen_ad, eposta)
                        values (:uuid, :name, :email)
                        returning id
                        """)
                .param("uuid", uuid)
                .param("name", name)
                .param("email", email, Types.VARCHAR)
                .query(Long.class)
                .single();

        boolean local = LOCAL_BASIC_PROVIDER.equals(issuer);
        jdbc.sql("""
                        insert into akis.harici_kimlik(
                            kullanici_id, saglayici_turu, yayinlayici,
                            harici_kullanici_anahtari)
                        values (:userId, :providerType, :issuer, :subject)
                        """)
                .param("userId", userId)
                .param("providerType", local ? "YEREL" : "OIDC")
                .param("issuer", local ? null : issuer, Types.VARCHAR)
                .param("subject", subject)
                .update();
        return findUser(uuid).orElseThrow();
    }

    @Override
    public List<UserRow> listUsers() {
        return jdbc.sql(userSelect() + " order by k.gorunen_ad, k.uuid, hk.id")
                .query(this::mapUser)
                .list();
    }

    @Override
    public Optional<UserRow> findUser(UUID userUuid) {
        return jdbc.sql(userSelect() + " where k.uuid = :uuid order by hk.id limit 1")
                .param("uuid", userUuid)
                .query(this::mapUser)
                .optional();
    }

    @Override
    public Optional<UserRow> findUser(String issuer, String subject) {
        boolean local = LOCAL_BASIC_PROVIDER.equals(issuer);
        return jdbc.sql(userSelect() + """
                         where hk.harici_kullanici_anahtari = :subject
                           and ((:local and hk.saglayici_turu = 'YEREL'
                                 and hk.yayinlayici is null)
                                or (not :local and hk.saglayici_turu = 'OIDC'
                                    and hk.yayinlayici = :issuer))
                        """)
                .param("subject", subject)
                .param("local", local)
                .param("issuer", local ? null : issuer, Types.VARCHAR)
                .query(this::mapUser)
                .optional();
    }

    @Override
    public Optional<ProjectRef> findProject(UUID projectUuid) {
        return jdbc.sql("""
                        select id, uuid,
                               case when arsivlenme_zamani is null
                                    then 'AKTIF' else 'ARSIVLENDI' end as durum
                          from akis.proje
                         where uuid = :uuid
                        """)
                .param("uuid", projectUuid)
                .query((rs, rowNum) -> new ProjectRef(
                        rs.getLong("id"),
                        rs.getObject("uuid", UUID.class),
                        rs.getString("durum")))
                .optional();
    }

    @Override
    public Optional<ProjectRoleRef> findProjectRole(String roleCode) {
        return jdbc.sql("""
                        select id, uuid, kod, etkin_mi
                          from akis.rol
                         where kapsam = 'PROJE' and kod = :roleCode
                        """)
                .param("roleCode", roleCode)
                .query((rs, rowNum) -> new ProjectRoleRef(
                        rs.getLong("id"),
                        rs.getObject("uuid", UUID.class),
                        rs.getString("kod"),
                        rs.getBoolean("etkin_mi")))
                .optional();
    }

    @Override
    public boolean membershipExists(long projectId, long userId) {
        return jdbc.sql("""
                        select exists(
                            select 1 from akis.proje_uyeligi
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
        long membershipId = jdbc.sql("""
                        insert into akis.proje_uyeligi(
                            proje_id, kullanici_id, uuid,
                            gecerlilik_baslangici, gecerlilik_sonu)
                        values (:projectId, :userId, :uuid, :startsAt, :endsAt)
                        returning id
                        """)
                .param("projectId", projectId)
                .param("userId", userId)
                .param("uuid", membershipUuid)
                .param("startsAt", startsAt)
                .param("endsAt", endsAt, Types.TIMESTAMP_WITH_TIMEZONE)
                .query(Long.class)
                .single();

        jdbc.sql("""
                        insert into akis.kullanici_rol(
                            kullanici_id, rol_id, rol_kapsami, proje_id, uuid)
                        values (:userId, :projectRoleId, 'PROJE', :projectId, :uuid)
                        """)
                .param("userId", userId)
                .param("projectRoleId", projectRoleId)
                .param("projectId", projectId)
                .param("uuid", membershipRoleUuid)
                .update();

        return findMembershipById(membershipId).orElseThrow();
    }

    @Override
    public List<MembershipRow> listMemberships(long projectId) {
        return jdbc.sql(membershipSelect()
                        + " where pu.proje_id = :projectId order by k.gorunen_ad, pu.uuid")
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

    private Optional<MembershipRow> findMembershipById(long membershipId) {
        return jdbc.sql(membershipSelect() + " where pu.id = :membershipId")
                .param("membershipId", membershipId)
                .query(this::mapMembershipBase)
                .optional()
                .map(this::withRoles);
    }

    private String userSelect() {
        return """
                select k.id, k.uuid,
                       case when hk.saglayici_turu = 'YEREL'
                            then 'LOCAL_BASIC' else hk.yayinlayici end as yayinlayici,
                       hk.harici_kullanici_anahtari,
                       case when k.devre_disi_birakilma_zamani is null
                            then 'AKTIF' else 'DEVRE_DISI' end as durum,
                       k.gorunen_ad, k.eposta, k.olusturulma_zamani
                  from akis.kullanici k
                  join akis.harici_kimlik hk on hk.kullanici_id = k.id
                """;
    }

    private UserRow mapUser(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new UserRow(
                rs.getLong("id"),
                rs.getObject("uuid", UUID.class),
                rs.getString("yayinlayici"),
                rs.getString("harici_kullanici_anahtari"),
                rs.getString("durum"),
                rs.getString("gorunen_ad"),
                rs.getString("eposta"),
                rs.getObject("olusturulma_zamani", OffsetDateTime.class));
    }

    private String membershipSelect() {
        return """
                select pu.id, pu.uuid, pu.proje_id, p.uuid as proje_uuid,
                       pu.kullanici_id, k.uuid as kullanici_uuid,
                       pu.durum, pu.gecerlilik_baslangici, pu.gecerlilik_sonu,
                       pu.versiyon_no
                  from akis.proje_uyeligi pu
                  join akis.proje p on p.id = pu.proje_id
                  join akis.kullanici k on k.id = pu.kullanici_id
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
                rs.getString("durum"),
                rs.getObject("gecerlilik_baslangici", OffsetDateTime.class),
                rs.getObject("gecerlilik_sonu", OffsetDateTime.class),
                rs.getLong("versiyon_no"),
                List.of());
    }

    private MembershipRow withRoles(MembershipRow membership) {
        List<ProjectRoleView> roles = jdbc.sql("""
                        select r.uuid, r.kod
                          from akis.kullanici_rol kr
                          join akis.rol r
                            on r.id = kr.rol_id and r.kapsam = kr.rol_kapsami
                         where kr.proje_id = :projectId
                           and kr.kullanici_id = :userId
                           and kr.rol_kapsami = 'PROJE'
                           and kr.iptal_zamani is null
                         order by r.kod
                        """)
                .param("projectId", membership.projectId())
                .param("userId", membership.userId())
                .query((roleRs, roleRowNum) -> new ProjectRoleView(
                        roleRs.getObject("uuid", UUID.class), roleRs.getString("kod")))
                .list();
        return new MembershipRow(
                membership.id(), membership.uuid(), membership.projectId(),
                membership.projectUuid(), membership.userId(), membership.userUuid(),
                membership.status(), membership.startsAt(), membership.endsAt(),
                membership.version(), roles);
    }
}

package tr.com.innova.akis.security;

import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Performs authorization checks directly against the relational RBAC model.
 * Keeping these joins here prevents feature repositories from implementing
 * subtly different membership and role-validity rules.
 */
@Repository
public class AuthorizationRepository {

    private final JdbcClient jdbc;

    public AuthorizationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public ProjectAccess projectAccess(
            PrincipalIdentity principal,
            UUID projectUuid,
            String permissionCode) {
        return jdbc.sql("""
                        with active_membership as (
                            select p.id as project_id, pu.kullanici_id
                              from akis.proje p
                              join akis.proje_uyeligi pu on pu.proje_id = p.id
                              join akis.kullanici k on k.id = pu.kullanici_id
                              join akis.harici_kimlik hk on hk.kullanici_id = k.id
                             where p.uuid = :projectUuid
                               and p.arsivlenme_zamani is null
                               and ((:provider = 'LOCAL_BASIC'
                                     and hk.saglayici_turu = 'YEREL'
                                     and hk.yayinlayici is null)
                                    or (hk.saglayici_turu = 'OIDC'
                                        and hk.yayinlayici = :provider))
                               and hk.harici_kullanici_anahtari = :subject
                               and k.devre_disi_birakilma_zamani is null
                               and pu.durum = 'AKTIF'
                               and pu.gecerlilik_baslangici <= current_timestamp
                               and (pu.gecerlilik_sonu is null
                                    or pu.gecerlilik_sonu >= current_timestamp)
                        )
                        select exists(select 1 from active_membership) as visible,
                               exists(
                                   select 1
                                     from active_membership am
                                     join akis.kullanici_rol kr
                                       on kr.proje_id = am.project_id
                                      and kr.kullanici_id = am.kullanici_id
                                      and kr.rol_kapsami = 'PROJE'
                                      and kr.iptal_zamani is null
                                     join akis.rol r
                                       on r.id = kr.rol_id
                                      and r.kapsam = kr.rol_kapsami
                                      and r.etkin_mi
                                     join akis.rol_yetki ry
                                       on ry.rol_id = r.id
                                      and ry.kapsam = r.kapsam
                                     join akis.yetki y
                                       on y.id = ry.yetki_id
                                      and y.kapsam = ry.kapsam
                                    where y.kod = :permissionCode
                               ) as permitted
                        """)
                .param("projectUuid", projectUuid)
                .param("provider", principal.provider())
                .param("subject", principal.subject())
                .param("permissionCode", permissionCode)
                .query((rs, rowNum) -> new ProjectAccess(
                        rs.getBoolean("visible"), rs.getBoolean("permitted")))
                .single();
    }

    public boolean hasSystemPermission(
            PrincipalIdentity principal,
            String permissionCode) {
        return jdbc.sql("""
                        select exists(
                            select 1
                              from akis.kullanici k
                              join akis.harici_kimlik hk on hk.kullanici_id = k.id
                              join akis.kullanici_rol kr
                                on kr.kullanici_id = k.id
                               and kr.rol_kapsami = 'SISTEM'
                               and kr.proje_id is null
                               and kr.iptal_zamani is null
                              join akis.rol r
                                on r.id = kr.rol_id
                               and r.kapsam = kr.rol_kapsami
                               and r.etkin_mi
                              join akis.rol_yetki ry
                                on ry.rol_id = r.id
                               and ry.kapsam = r.kapsam
                              join akis.yetki y
                                on y.id = ry.yetki_id
                               and y.kapsam = ry.kapsam
                             where ((:provider = 'LOCAL_BASIC'
                                     and hk.saglayici_turu = 'YEREL'
                                     and hk.yayinlayici is null)
                                    or (hk.saglayici_turu = 'OIDC'
                                        and hk.yayinlayici = :provider))
                               and hk.harici_kullanici_anahtari = :subject
                               and k.devre_disi_birakilma_zamani is null
                               and y.kod = :permissionCode
                        )
                        """)
                .param("provider", principal.provider())
                .param("subject", principal.subject())
                .param("permissionCode", permissionCode)
                .query(Boolean.class)
                .single();
    }

    public Set<UUID> visibleProjectUuids(PrincipalIdentity principal) {
        return jdbc.sql("""
                        select p.uuid
                          from akis.proje p
                          join akis.proje_uyeligi pu on pu.proje_id = p.id
                          join akis.kullanici k on k.id = pu.kullanici_id
                          join akis.harici_kimlik hk on hk.kullanici_id = k.id
                         where p.arsivlenme_zamani is null
                           and ((:provider = 'LOCAL_BASIC'
                                 and hk.saglayici_turu = 'YEREL'
                                 and hk.yayinlayici is null)
                                or (hk.saglayici_turu = 'OIDC'
                                    and hk.yayinlayici = :provider))
                           and hk.harici_kullanici_anahtari = :subject
                           and k.devre_disi_birakilma_zamani is null
                           and pu.durum = 'AKTIF'
                           and pu.gecerlilik_baslangici <= current_timestamp
                           and (pu.gecerlilik_sonu is null
                                or pu.gecerlilik_sonu >= current_timestamp)
                        """)
                .param("provider", principal.provider())
                .param("subject", principal.subject())
                .query(UUID.class)
                .set();
    }

    public Set<UUID> activeProjectUuids() {
        return jdbc.sql("select uuid from akis.proje where arsivlenme_zamani is null")
                .query(UUID.class)
                .set();
    }

    public record PrincipalIdentity(String provider, String subject, String name) {
    }

    public record ProjectAccess(boolean visible, boolean permitted) {
    }
}

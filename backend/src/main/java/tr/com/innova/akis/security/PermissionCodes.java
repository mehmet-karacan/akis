package tr.com.innova.akis.security;

public final class PermissionCodes {

    public static final String PROJECT_CREATE = "PROJE_OLUSTUR";
    public static final String PROJECT_READ = "PROJE_GORUNTULE";
    public static final String PROJECT_WRITE = "PROJE_YONET";
    public static final String DEFINITION_READ = "TANIM_GORUNTULE";
    public static final String DEFINITION_WRITE = "TANIM_DUZENLE";
    public static final String DEFINITION_VALIDATE = "TANIM_DOGRULA";
    public static final String GLOBAL_DEFINITION_READ = "GLOBAL_DEFINITION_READ";
    public static final String GLOBAL_DEFINITION_WRITE = "GLOBAL_DEFINITION_WRITE";
    public static final String TOPOLOGY_READ = "BAGLANTI_GORUNTULE";
    public static final String TOPOLOGY_WRITE = "BAGLANTI_YONET";
    public static final String SECRET_READ = "SECRET_READ";
    public static final String SECRET_WRITE = "SECRET_WRITE";
    public static final String CATALOG_READ = "KATALOG_GORUNTULE";
    public static final String CATALOG_WRITE = "KATALOG_KESFET";
    public static final String DISCOVERY_READ = "KATALOG_GORUNTULE";
    public static final String DISCOVERY_WRITE = "KATALOG_KESFET";
    public static final String SCENARIO_READ = "CALISTIRILABILIR_SURUM_GORUNTULE";
    public static final String SCENARIO_COMPILE = "CALISTIRILABILIR_SURUM_OLUSTUR";
    public static final String PUBLICATION_READ = "CALISTIRILABILIR_SURUM_GORUNTULE";
    public static final String PUBLICATION_CREATE = "CALISTIRILABILIR_SURUM_OLUSTUR";
    public static final String PUBLICATION_APPROVE = "CALISTIRILABILIR_SURUM_ONAYLA";
    public static final String RUN_READ = "CALISTIRMA_GORUNTULE";
    public static final String RUN_START = "CALISTIRMA_BASLAT";
    public static final String RUN_CANCEL = "CALISTIRMA_IPTAL_ET";
    public static final String PRODUCTION_RUN = "CALISTIRMA_BASLAT";
    public static final String IDENTITY_USER_PROVISION = "KULLANICI_YONET";
    public static final String PROJECT_MEMBERSHIP_MANAGE = "UYE_YONET";
    public static final String SCHEMA_METADATA_READ = "SEMA_METADATA_GORUNTULE";
    public static final String SCHEMA_METADATA_WRITE = "SEMA_METADATA_YONET";
    public static final String SCHEDULE_READ = "ZAMANLAMA_GORUNTULE";
    public static final String SCHEDULE_WRITE = "ZAMANLAMA_YONET";

    private PermissionCodes() {
    }
}

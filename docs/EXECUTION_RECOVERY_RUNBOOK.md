# Execution recovery runbook

## Operatör eylemleri

- **Retry failed unit:** Yalnız rollback'i veya dispatch edilmediği kanıtlanan en
  küçük transaction grubu/chunk tekrar edilir.
- **Resume:** Aynı logical job, source SCN, çözülmüş değişkenler, workspace ve
  committed chunk'larla yeni attempt oluşturur.
- **Restart:** Yeni logical job, snapshot ve workspace oluşturur. Hedef reset
  etkisi ayrıca onaylanır; eski workspace otomatik silinmez.
- **Reconcile:** Yeni business DML çalıştırmadan receipt/fence okunur.
- **Cancel:** İptal isteği rollback kanıtı değildir; commit yarışı unknown olabilir.

## Unknown commit

`OUTCOME_UNKNOWN`, `SONUC_BELIRSIZ` veya `MUDAHALE_GEREKLI` durumunda retry,
resume ve restart kapalıdır. Önce aynı target-local receipt ve fence ile mutabakat
yapılır. Receipt bulunursa yalnız PostgreSQL projection onarılır; DML tekrarlanmaz.
Receipt yokluğu eski Oracle session'ın bittiğini tek başına kanıtlamaz.

## Snapshot ve stage

`ORA-01555` veya flashback erişim kaybı `SOURCE_SNAPSHOT_EXPIRED` ile durur;
güncel veriye fallback yoktur. `UNDO_RETENTION` garanti değildir ve worker
production undo ayarını değiştirmez.

Resume-eligible stage `finally` içinde drop edilmez. Retention sonrası önce
recovery eligibility auditable kararla kapanır. Cleanup registry identity,
database/owner, object id, structure hash ve lifecycle lock uyuşmazsa karantinaya
alınır. Kullanıcı tablosunu elle silmek varsayılan onarım değildir.

## Oracle ve migration

Worker Oracle kontrol nesnelerini kurmaz/yükseltmez. Bilinen marker/hash için ayrı
upgrade, validate ve disposable owner fault-injection kabulü gerekir. V2 ledger
mevcuttur. DDL kod yolu session-scoped `DBMS_LOCK` kullanır; Oracle sahibi bu paket
yetkisine sahip değilse işlem fail-closed olur. Gerçek Oracle kabulü ve worker
handler kurulumu tamamlanmadan yeni flag'ler açılsa dahi capability ilan edilmez.
PostgreSQL migration'ları forward-only'dir. Oracle DDL implicit commit nedeniyle
genel otomatik downgrade yoktur; hata halinde worker kapalı tutulup yeni sürümlü
repair hazırlanır.

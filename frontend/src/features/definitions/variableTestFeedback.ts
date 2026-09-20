/** Only safe error categories are displayed; driver messages can contain credentials. */
export function variableTestFeedback(code: string | null, tr: boolean): string {
  const messages: Record<string, [string, string]> = {
    ORACLE_1017: ['Veritabanı kimlik bilgilerini kabul etmedi.', 'The database rejected the connection credentials.'],
    ORACLE_28000: ['Veritabanı hesabı kilitli.', 'The database account is locked.'],
    ORACLE_28001: ['Veritabanı hesabının şifre süresi dolmuş. DBA ile şifreyi yenileyip bağlantı bilgisini güncelleyin.', 'The database password has expired. Ask your DBA to renew it, then update the connection credentials.'],
    ORACLE_12514: ['Oracle servis adı dinleyicide bulunamadı.', 'The Oracle service name is not registered with the listener.'],
    ORACLE_17002: ['Oracle bağlantısında ağ iletişim hatası oluştu.', 'An Oracle network communication error occurred.'],
    VARIABLE_CREDENTIAL_UNAVAILABLE: ['Bağlantı kimlik bilgilerine erişilemiyor. Bağlantı tanımını kontrol edin.', 'Connection credentials are unavailable. Check the connection configuration.'],
    VARIABLE_CONNECTION_FAILED: ['Veritabanına bağlanılamadı. Erişim ve bağlantı ayarlarını kontrol edin.', 'Could not connect to the database. Check connectivity and connection settings.'],
    VARIABLE_SESSION_OPERATION_FAILED: ['Salt okunur test oturumu hazırlanamadı. Şema ve oturum yetkilerini kontrol edin.', 'Could not prepare the read-only test session. Check schema and session permissions.'],
    VARIABLE_UNSUPPORTED_PROFILE: ['Bağlantı profili test için desteklenmiyor.', 'The connection profile is not supported for testing.'],
    VARIABLE_METADATA_NOT_FOUND: ['Bağlantı metaverisi bulunamadı.', 'Connection metadata was not found.'],
    VARIABLE_INVALID_CONTRACT: ['Bağlantı veya şema tanımı geçersiz.', 'The connection or schema definition is invalid.'],
    VARIABLE_ROLLBACK_NOT_CONFIRMED: ['Test oturumunun kapatılması doğrulanamadı.', 'Test session cleanup could not be confirmed.'],
    VARIABLE_RESULT_INVALID: ['Sorgu seçilen veri tipine uygun, boş olmayan tek satır ve tek kolon döndürmelidir.', 'The query must return one non-null row and column compatible with the selected data type.'],
  }
  const message = code ? messages[code]?.[tr ? 0 : 1] : undefined
  return message ? `${message} (${code})` : code ?? (tr ? 'Test başarısız.' : 'Test failed.')
}

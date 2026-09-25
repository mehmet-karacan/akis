import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Direct, dependency-ordered refresh of the small TTBP configuration tables. */
public final class ConfigTableDirectTransfer {
  private static final List<String> TABLES = List.of(
      "SATIS_KANALI_GRUBU", "HESAP_TIPI", "PRIM_BASLIGI", "ISLEM_ONCESI_TIPI",
      "ISLEM_ONCESI_DETAY_TIPI", "ISLEM_PARAMETRE", "UST_CEZA_KAYIT_TIPI",
      "EMAIL_SUREC_TIPI", "KAMPANYA_TIPI", "TARIFE_AGACI_DUGUMU", "SATIS_KANALI",
      "EMAIL_SABLON", "ISLEM_ONCESI_TIPI_DETAY_ILS", "URETILECEK_ISLEM_ONCESI_TIPI",
      "BEYAN_DOSYASI", "HAKEDIS_TIPI", "SATIS_KANALI_TIPI", "KAMPANYA",
      "PRIM_KALEMI_TIPI", "DYNAMIC_SP_CALL_CONFIG", "HAKEDIS_TIPI_KANAL_TIPI",
      "HAKEDIS_TIPI_EMAIL_SABLON", "ENT_KURAL_SURUMU", "ISLEM_TIPI",
      "ENT_KURAL_MUAFIYET", "ISLEM_PARAMETRE_GELIS_SURUM", "ENT_KURAL", "TARIFE",
      "HEDEF_TIPI", "TTS_TAHSILAT_KADEME", "ISLEM_HESAP", "SAYIM_KURALI",
      "ENTEGRASYON_KONTROL", "CEZA_KAYIT_TIPI", "HEDEF_TIPI_FORMUL",
      "HEDEF_TIPI_SURUMU", "SAYIM_KURALI_FORMUL", "TARIFE_SURUMU");

  record Source(String url, String username, String password) {}
  record SelfFk(List<String> child, List<String> parent) {}

  public static void main(String[] args) throws Exception {
    String pgUrl = required("AKIS_DB_URL");
    String pgUser = required("AKIS_DB_USERNAME");
    String pgPassword = required("AKIS_DB_PASSWORD");
    try (Connection pg = DriverManager.getConnection(pgUrl, pgUser, pgPassword)) {
      pg.setAutoCommit(false);
      if (args.length == 1 && "--list-connections".equals(args[0])) {
        listConnections(pg);
        return;
      }
      if (args.length == 1 && "--check-external-inbound-fks".equals(args[0])) {
        checkExternalInboundForeignKeys(pg);
        return;
      }
      if (args.length == 1 && "--check-procedure-object-matches".equals(args[0])) {
        checkProcedureObjectMatches(pg);
        return;
      }
      if (args.length == 1 && "--run-diagnostics".equals(args[0])) {
        runDiagnostics(pg);
        return;
      }
      Source source = source(pg);
      try (Connection oracle = DriverManager.getConnection(source.url(), source.username(), source.password())) {
        oracle.setReadOnly(true);
        oracle.setAutoCommit(false);
        if (args.length == 1 && "--beyan-blob-stats".equals(args[0])) {
          try (Statement s = oracle.createStatement(); ResultSet r = s.executeQuery(
              "select count(*), coalesce(max(dbms_lob.getlength(EK_ICERIK)),0), coalesce(sum(dbms_lob.getlength(EK_ICERIK)),0) from TTBP.BEYAN_DOSYASI")) {
            r.next(); System.out.printf("ROWS=%d MAX_BLOB_BYTES=%d TOTAL_BLOB_BYTES=%d%n", r.getLong(1), r.getLong(2), r.getLong(3));
          }
          return;
        }
        if (args.length == 1 && "--islem-tipi-live-metadata".equals(args[0])) {
          String sql = "select column_name,data_type,data_precision,case when data_type='NUMBER' then data_scale end,"+
              "case when char_used is not null then char_length when data_type='RAW' then data_length end,"+
              "case when data_type like 'TIMESTAMP%' or data_type like 'INTERVAL%' then data_scale end,"+
              "nullable,data_default,column_id from all_tab_cols where owner='TTBP' and table_name='ISLEM_TIPI' "+
              "and hidden_column='NO' order by column_id";
          try (Statement s=oracle.createStatement(); ResultSet r=s.executeQuery(sql)) {
            while(r.next()) System.out.printf("COL=%s TYPE=%s PREC=%s SCALE=%s LEN=%s TIME=%s NULL=%s DEFAULT=%s ORD=%s%n",
                r.getString(1),r.getString(2),r.getObject(3),r.getObject(4),r.getObject(5),r.getObject(6),r.getString(7),r.getString(8),r.getInt(9));
          }
          return;
        }
        if (args.length == 1 && "--source-counts".equals(args[0])) {
          sourceCounts(oracle).forEach((table,count)->System.out.printf("TABLE=%s ROWS=%d%n",table,count));
          return;
        }
        verifyTables(pg);
        Map<String, Long> sourceCounts = sourceCounts(oracle);
        if (args.length == 1 && "--verify-counts".equals(args[0])) {
          long total=0;
          for(String table:TABLES) {
            long sourceCount=sourceCounts.get(table), targetCount=count(pg,"ttbp",table.toLowerCase(Locale.ROOT));
            if(sourceCount!=targetCount) throw new SQLException(table+" count mismatch source="+sourceCount+" target="+targetCount);
            total+=targetCount;
          }
          System.out.printf("VERIFY_OK TABLES=%d ROWS=%d%n",TABLES.size(),total);
          return;
        }
        truncate(pg);
        for (String table : TABLES) {
          long inserted = copy(oracle, pg, table);
          long target = count(pg, "ttbp", table.toLowerCase(Locale.ROOT));
          long expected = sourceCounts.get(table);
          if (inserted != expected || target != expected) {
            throw new SQLException(table + " count mismatch source=" + expected
                + " inserted=" + inserted + " target=" + target);
          }
          System.out.println("TABLE=" + table + " SOURCE=" + expected + " TARGET=" + target);
        }
        pg.commit();
        System.out.println("TRANSFER_OK TABLES=" + TABLES.size()
            + " ROWS=" + sourceCounts.values().stream().mapToLong(Long::longValue).sum());
      } catch (Exception failure) {
        pg.rollback();
        throw failure;
      }
    }
  }

  private static void runDiagnostics(Connection pg) throws SQLException {
    try (Statement s = pg.createStatement(); ResultSet r = s.executeQuery(
        "select c.uuid,cd.durum,cd.hedef_kaynagi_id,cd.isleyici_referansi,cd.kiralama_bitis_zamani "
            + "from akis.calistirma c join akis.calistirma_durumu cd on cd.calistirma_id=c.id "
            + "where cd.durum in ('BEKLIYOR','SAHIPLENILDI','CALISIYOR','YAYINLANIYOR') order by c.olusturulma_zamani")) {
      while (r.next()) System.out.printf("RUN=%s STATUS=%s TARGET=%s WORKER=%s LEASE=%s%n",
          r.getObject(1), r.getString(2), r.getObject(3), r.getString(4), r.getObject(5));
    }
    try (Statement s = pg.createStatement(); ResultSet r = s.executeQuery("select uuid,etkin from akis.worker_profili order by id")) {
      while (r.next()) System.out.printf("WORKER_PROFILE=%s ENABLED=%s%n", r.getObject(1), r.getBoolean(2));
    }
  }

  private static void checkProcedureObjectMatches(Connection pg) throws SQLException {
    String sql = "select vn.nesne_referansi from akis.veri_nesnesi vn join akis.model m on m.id=vn.model_id "
        + "join akis.mantiksal_sema ms on ms.id=m.mantiksal_sema_id where ms.uuid=? and vn.arsivlenme_zamani is null "
        + "and m.arsivlenme_zamani is null and exists(select 1 from akis.sema_goruntusu sg where sg.veri_nesnesi_id=vn.id)";
    List<String> references = new ArrayList<>();
    try (PreparedStatement s = pg.prepareStatement(sql)) {
      s.setObject(1, UUID.fromString("5553eef5-9e30-4349-b46d-835f7d226a24"));
      try (ResultSet r = s.executeQuery()) { while (r.next()) references.add(r.getString(1)); }
    }
    for (String table : TABLES) {
      String command = "TRUNCATE TABLE TTBP." + table + " RESTART IDENTITY CASCADE";
      List<String> matches = references.stream().filter(reference -> java.util.regex.Pattern.compile(
          "(?<![A-Z0-9_$#])" + java.util.regex.Pattern.quote(reference.toUpperCase(Locale.ROOT)) + "(?![A-Z0-9_$#])")
          .matcher(command).find()).toList();
      System.out.println(table + "=" + matches.size() + (matches.size() == 1 ? "" : " " + matches));
    }
  }

  private static void checkExternalInboundForeignKeys(Connection pg) throws SQLException {
    String sql = "select pn.nspname||'.'||pc.relname, rn.nspname||'.'||rc.relname, con.conname "
        + "from pg_constraint con join pg_class pc on pc.oid=con.conrelid "
        + "join pg_namespace pn on pn.oid=pc.relnamespace join pg_class rc on rc.oid=con.confrelid "
        + "join pg_namespace rn on rn.oid=rc.relnamespace where con.contype='f' and rn.nspname='ttbp' "
        + "and rc.relname = any (?) and not (pn.nspname='ttbp' and pc.relname = any (?)) order by 1,2,3";
    String[] names = TABLES.stream().map(value -> value.toLowerCase(Locale.ROOT)).toArray(String[]::new);
    int count = 0;
    try (PreparedStatement s = pg.prepareStatement(sql)) {
      Array values = pg.createArrayOf("text", names); s.setArray(1, values); s.setArray(2, values);
      try (ResultSet r = s.executeQuery()) {
        while (r.next()) { count++; System.out.printf("EXTERNAL_FK=%s -> %s (%s)%n", r.getString(1), r.getString(2), r.getString(3)); }
      }
    }
    System.out.println("EXTERNAL_INBOUND_FKS=" + count);
  }

  private static void listConnections(Connection pg) throws SQLException {
    String sql = "select id,kod,ad,coalesce(aciklama,''),sunucu_adi,port,coalesce(servis_adi,''),"
        + "coalesce(sid,''),kullanici_adi,durum from akis.baglanti "
        + "where upper(kod)='SKY' or upper(ad)='SKY' order by id";
    try (Statement s = pg.createStatement(); ResultSet r = s.executeQuery(sql)) {
      while (r.next()) System.out.printf(
          "ID=%d CODE=%s NAME=%s DESCRIPTION=%s HOST=%s PORT=%d SERVICE=%s SID=%s USER=%s STATUS=%s%n",
          r.getLong(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getInt(6),
          r.getString(7), r.getString(8), r.getString(9), r.getString(10));
    }
  }

  private static Source source(Connection pg) throws Exception {
    String sql = "select sunucu_adi,port,servis_adi,sid,kullanici_adi,sifre,jdbc_url_ek "
        + "from akis.baglanti where upper(kod)='SKY' or upper(ad)='SKY' "
        + "order by id desc fetch first 1 row only";
    try (Statement s = pg.createStatement(); ResultSet r = s.executeQuery(sql)) {
      if (!r.next()) throw new SQLException("Active SKY connection not found");
      String host = r.getString(1), service = r.getString(3), sid = r.getString(4);
      int port = r.getInt(2);
      String url = service != null && !service.isBlank()
          ? "jdbc:oracle:thin:@//" + host + ":" + port + "/" + service
          : "jdbc:oracle:thin:@" + host + ":" + port + ":" + sid;
      String extra = r.getString(7);
      if (extra != null && !extra.isBlank()) url += extra;
      return new Source(url, r.getString(5), decrypt(r.getString(6)));
    }
  }

  private static void verifyTables(Connection pg) throws SQLException {
    try (PreparedStatement q = pg.prepareStatement(
        "select count(*) from information_schema.tables where table_schema='ttbp' and table_name=?")) {
      for (String table : TABLES) {
        q.setString(1, table.toLowerCase(Locale.ROOT));
        try (ResultSet r = q.executeQuery()) {
          r.next();
          if (r.getInt(1) != 1) throw new SQLException("Missing target table: ttbp." + table);
        }
      }
    }
  }

  private static Map<String, Long> sourceCounts(Connection oracle) throws SQLException {
    Map<String, Long> counts = new LinkedHashMap<>();
    try (Statement s = oracle.createStatement()) {
      s.setQueryTimeout(120);
      for (String table : TABLES) {
        try (ResultSet r = s.executeQuery("select count(*) from TTBP." + table)) {
          r.next(); counts.put(table, r.getLong(1));
        }
      }
    }
    return counts;
  }

  private static void truncate(Connection pg) throws SQLException {
    String names = TABLES.stream().map(t -> "ttbp." + t.toLowerCase(Locale.ROOT))
        .reduce((a, b) -> a + "," + b).orElseThrow();
    try (Statement s = pg.createStatement()) { s.executeUpdate("truncate table " + names); }
  }

  private static long copy(Connection oracle, Connection pg, String table) throws SQLException {
    List<String> columns = targetColumns(pg, table);
    List<SelfFk> selfFks = selfFks(pg, table);
    String sourceSql = "select " + String.join(",", columns) + " from TTBP." + table;
    List<Object[]> rows = new ArrayList<>();
    try (Statement s = oracle.createStatement()) {
      s.setFetchSize(1000); s.setQueryTimeout(300);
      try (ResultSet r = s.executeQuery(sourceSql)) {
        while (r.next()) {
          Object[] row = new Object[columns.size()];
          for (int i = 0; i < row.length; i++) row[i] = oracleValue(r, i + 1);
          rows.add(row);
        }
      }
    }
    if (!selfFks.isEmpty()) rows = parentFirst(rows, columns, selfFks, table);
    String marks = String.join(",", Collections.nCopies(columns.size(), "?"));
    String insert = "insert into ttbp." + table.toLowerCase(Locale.ROOT) + " ("
        + String.join(",", columns) + ") values (" + marks + ")";
    try (PreparedStatement p = pg.prepareStatement(insert)) {
      int pending = 0;
      for (Object[] row : rows) {
        for (int i = 0; i < row.length; i++) p.setObject(i + 1, row[i]);
        p.addBatch();
        if (++pending == 1000) { p.executeBatch(); pending = 0; }
      }
      if (pending > 0) p.executeBatch();
    }
    return rows.size();
  }

  private static List<Object[]> parentFirst(List<Object[]> input, List<String> columns,
      List<SelfFk> fks, String table) throws SQLException {
    List<Object[]> pending = new ArrayList<>(input), ordered = new ArrayList<>(input.size());
    Set<List<Object>> available = new HashSet<>();
    while (!pending.isEmpty()) {
      int before = pending.size();
      for (Iterator<Object[]> it = pending.iterator(); it.hasNext();) {
        Object[] row = it.next();
        boolean ready = true;
        for (SelfFk fk : fks) {
          List<Object> parent = values(row, columns, fk.child());
          if (parent.stream().anyMatch(Objects::nonNull) && !available.contains(parent)) { ready = false; break; }
        }
        if (ready) {
          ordered.add(row); it.remove();
          for (SelfFk fk : fks) available.add(values(row, columns, fk.parent()));
        }
      }
      if (pending.size() == before) throw new SQLException("Self-reference cycle/unresolved parent in " + table);
    }
    return ordered;
  }

  private static List<Object> values(Object[] row, List<String> columns, List<String> names) {
    return names.stream().map(n -> row[columns.indexOf(n)]).map(ConfigTableDirectTransfer::keyValue).toList();
  }

  private static Object keyValue(Object value) {
    return value instanceof BigDecimal n ? n.stripTrailingZeros() : value;
  }

  private static List<String> targetColumns(Connection pg, String table) throws SQLException {
    List<String> result = new ArrayList<>();
    try (PreparedStatement q = pg.prepareStatement("select column_name from information_schema.columns "
        + "where table_schema='ttbp' and table_name=? order by ordinal_position")) {
      q.setString(1, table.toLowerCase(Locale.ROOT));
      try (ResultSet r = q.executeQuery()) { while (r.next()) result.add(r.getString(1)); }
    }
    if (result.isEmpty()) throw new SQLException("No target columns: " + table);
    return result;
  }

  private static List<SelfFk> selfFks(Connection pg, String table) throws SQLException {
    String sql = """
        select array_agg(a.attname order by x.ord), array_agg(pa.attname order by x.ord)
          from pg_constraint c
          join pg_class t on t.oid=c.conrelid join pg_namespace n on n.oid=t.relnamespace
          join lateral unnest(c.conkey,c.confkey) with ordinality x(child,parent,ord) on true
          join pg_attribute a on a.attrelid=c.conrelid and a.attnum=x.child
          join pg_attribute pa on pa.attrelid=c.confrelid and pa.attnum=x.parent
         where c.contype='f' and c.conrelid=c.confrelid and n.nspname='ttbp' and t.relname=?
         group by c.oid
        """;
    List<SelfFk> result = new ArrayList<>();
    try (PreparedStatement q = pg.prepareStatement(sql)) {
      q.setString(1, table.toLowerCase(Locale.ROOT));
      try (ResultSet r = q.executeQuery()) {
        while (r.next()) result.add(new SelfFk(Arrays.asList((String[]) r.getArray(1).getArray()),
            Arrays.asList((String[]) r.getArray(2).getArray())));
      }
    }
    return result;
  }

  private static Object oracleValue(ResultSet r, int index) throws SQLException {
    Object value = r.getObject(index);
    if (value instanceof Clob c) return c.getSubString(1, Math.toIntExact(c.length()));
    if (value instanceof Blob b) return b.getBytes(1, Math.toIntExact(b.length()));
    if (value instanceof oracle.sql.TIMESTAMP t) return t.timestampValue();
    return value;
  }

  private static long count(Connection connection, String schema, String table) throws SQLException {
    try (Statement s = connection.createStatement();
         ResultSet r = s.executeQuery("select count(*) from " + schema + "." + table)) {
      r.next(); return r.getLong(1);
    }
  }

  private static String decrypt(String encoded) throws Exception {
    byte[] combined = Base64.getDecoder().decode(encoded), iv = Arrays.copyOfRange(combined, 0, 12);
    byte[] key = MessageDigest.getInstance("SHA-256").digest(
        System.getenv().getOrDefault("AKIS_CREDENTIAL_KEY", "akis-local-development-credential-key")
            .getBytes(StandardCharsets.UTF_8));
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
    String raw = new String(cipher.doFinal(Arrays.copyOfRange(combined, 12, combined.length)), StandardCharsets.UTF_8);
    if (!raw.startsWith("{")) return raw;
    java.util.regex.Matcher matcher = java.util.regex.Pattern
        .compile("\\\"password\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"").matcher(raw);
    if (!matcher.find()) throw new IllegalStateException("Stored credential envelope has no password");
    return matcher.group(1).replace("\\\\\\\"", "\\\"").replace("\\\\\\\\", "\\\\");
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
    return value;
  }
}

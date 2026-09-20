import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

public final class OracleStressDataGenerator {
  private static final long DEFAULT_TARGET_ROWS = 200_000_000L;
  private static final long DEFAULT_BATCH_ROWS = 1_000_000L;
  private static final String OWNER = "INNOVA_ODI";
  private static final String TABLE = "AKIS_STRESS_200M";

  public static void main(String[] args) throws Exception {
    long targetRows = args.length > 0 ? Long.parseLong(args[0]) : DEFAULT_TARGET_ROWS;
    long batchRows = args.length > 1 ? Long.parseLong(args[1]) : DEFAULT_BATCH_ROWS;
    try (Connection connection = DriverManager.getConnection(
        required("AKIS_ORACLE_SOURCE_URL"),
        required("AKIS_ORACLE_SOURCE_USERNAME"),
        required("AKIS_ORACLE_SOURCE_PASSWORD"))) {
      connection.setAutoCommit(false);
      try (Statement statement = connection.createStatement()) {
        createTable(statement);
      }
      while (currentRows(connection) < targetRows) {
        long current = currentRows(connection);
        long next = Math.min(targetRows, current + batchRows);
        insertRange(connection, current + 1, next);
        connection.commit();
        System.out.println("ORACLE_STRESS_ROWS=" + currentRows(connection));
      }
    }
  }

  private static void createTable(Statement statement) throws Exception {
    try {
      statement.executeUpdate("alter table " + OWNER + "." + TABLE + " nologging");
      return;
    } catch (Exception ignored) {
      // Table does not exist yet.
    }
    statement.executeUpdate("""
        create table INNOVA_ODI.AKIS_STRESS_200M (
          ID number(18) not null,
          BATCH_NO number(10) not null,
          CUSTOMER_NO number(18) not null,
          ACCOUNT_NO number(18) not null,
          TXN_NO number(18) not null,
          STATUS_CODE number(5) not null,
          REGION_CODE number(5) not null,
          CHANNEL_CODE number(5) not null,
          PRODUCT_CODE number(10) not null,
          BRANCH_CODE number(10) not null,
          AMOUNT number(18,2) not null,
          TAX_AMOUNT number(18,2) not null,
          DISCOUNT_AMOUNT number(18,2) not null,
          BALANCE_BEFORE number(18,2) not null,
          BALANCE_AFTER number(18,2) not null,
          RATIO_VALUE number(12,6) not null,
          SCORE_VALUE number(10,4) not null,
          QUANTITY number(10) not null,
          RETRY_COUNT number(3) not null,
          PRIORITY_LEVEL number(3) not null,
          IS_ACTIVE number(1) not null,
          IS_RECONCILED number(1) not null,
          HAS_ERROR number(1) not null,
          CREATED_AT timestamp not null,
          UPDATED_AT timestamp not null,
          BUSINESS_DATE date not null,
          DUE_DATE date not null,
          SOURCE_SYSTEM varchar2(24) not null,
          TARGET_SYSTEM varchar2(24) not null,
          REFERENCE_CODE varchar2(64) not null,
          EXTERNAL_ID varchar2(64) not null,
          CUSTOMER_NAME varchar2(120) not null,
          DESCRIPTION varchar2(240) not null,
          NOTE_TEXT varchar2(1000) not null,
          HASH_VALUE char(32) not null,
          PAYLOAD_JSON clob not null,
          IP_ADDRESS varchar2(45) not null,
          MAC_ADDRESS varchar2(17) not null,
          UUID_TEXT varchar2(36) not null,
          CURRENCY_CODE char(3) not null,
          COUNTRY_CODE char(2) not null,
          TIMEZONE_NAME varchar2(64) not null,
          SOURCE_TABLE varchar2(64) not null,
          TARGET_TABLE varchar2(64) not null,
          RUN_ID number(18) not null,
          PARTITION_KEY number(10) not null,
          NULLABLE_TEXT varchar2(120),
          NULLABLE_NUMBER number(18,4),
          NULLABLE_DATE date,
          LOAD_MARKER varchar2(32) not null
        ) nologging parallel 8
        """);
  }

  private static long currentRows(Connection connection) throws Exception {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("select count(*) from " + OWNER + "." + TABLE)) {
      rows.next();
      return rows.getLong(1);
    }
  }

  private static void insertRange(Connection connection, long from, long to) throws Exception {
    String sql = """
        insert /*+ append parallel(8) */ into INNOVA_ODI.AKIS_STRESS_200M
        select
          g,
          trunc(g / 100000),
          g * 10,
          g * 20,
          g * 30,
          mod(g, 9),
          mod(g, 81),
          mod(g, 12),
          mod(g, 500),
          mod(g, 1000),
          round(g * 1.11, 2),
          round(g * 0.18, 2),
          round(g * 0.03, 2),
          round(g * 2.00, 2),
          round(g * 2.05, 2),
          round(mod(g, 1000) / 1000, 6),
          round(mod(g, 10000) / 10, 4),
          mod(g, 50),
          mod(g, 5),
          mod(g, 3),
          case when mod(g, 2) = 0 then 1 else 0 end,
          case when mod(g, 3) = 0 then 1 else 0 end,
          case when mod(g, 97) = 0 then 1 else 0 end,
          systimestamp - numtodsinterval(mod(g, 86400), 'SECOND'),
          systimestamp,
          trunc(sysdate) - mod(g, 365),
          trunc(sysdate) + mod(g, 90),
          'ORACLE',
          'POSTGRESQL',
          'REF_' || g,
          'EXT_' || standard_hash(to_char(g), 'MD5'),
          'MUSTERI_' || g,
          'stress row ' || g,
          rpad('note-' || g || ' ', 120, 'x'),
          standard_hash(to_char(g), 'MD5'),
          to_clob('{"id":' || g || ',"bucket":' || mod(g,100) || ',"flag":' ||
            case when mod(g,2)=0 then 'true' else 'false' end || '}'),
          '10.' || mod(g,255) || '.' || mod(trunc(g/255),255) || '.' || mod(trunc(g/65025),255),
          '08:00:2b:' ||
            lpad(to_char(mod(g,256), 'FMXX'),2,'0') || ':' ||
            lpad(to_char(mod(trunc(g/256),256), 'FMXX'),2,'0') || ':' ||
            lpad(to_char(mod(trunc(g/65536),256), 'FMXX'),2,'0'),
          regexp_replace(rawtohex(sys_guid()), '(.{8})(.{4})(.{4})(.{4})(.{12})', '\\1-\\2-\\3-\\4-\\5'),
          'TRY',
          'TR',
          'Europe/Istanbul',
          'AKIS_STRESS_SRC',
          'AKIS_STRESS_TGT',
          1,
          mod(g, 64),
          case when mod(g,10)=0 then null else 'optional_' || g end,
          case when mod(g,7)=0 then null else round(g * 0.77, 4) end,
          case when mod(g,11)=0 then null else trunc(sysdate) end,
          'STRESS'
        from (
          select ? + level - 1 g
          from dual
          connect by level <= ?
        )
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, from);
      statement.setLong(2, to - from + 1);
      statement.executeUpdate();
    }
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " is required.");
    }
    return value;
  }
}

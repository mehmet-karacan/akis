import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

public final class PostgresStressDataGenerator {
  private static final long DEFAULT_TARGET_ROWS = 500_000_000L;
  private static final long DEFAULT_BATCH_ROWS = 1_000_000L;
  private static final long RESERVED_BYTES = 25L * 1024 * 1024 * 1024;
  private static final long ESTIMATED_BYTES_PER_ROW = 582L;

  public static void main(String[] args) throws Exception {
    long targetRows = args.length > 0 ? Long.parseLong(args[0]) : DEFAULT_TARGET_ROWS;
    long batchRows = args.length > 1 ? Long.parseLong(args[1]) : DEFAULT_BATCH_ROWS;
    String url = required("AKIS_DB_URL");
    String user = required("AKIS_DB_USERNAME");
    String password = required("AKIS_DB_PASSWORD");
    boolean allowFull = "true".equalsIgnoreCase(System.getenv("AKIS_STRESS_ALLOW_FULL"));
    long freeBytes = new java.io.File("C:\\").getFreeSpace();
    long estimatedNeed = Math.multiplyExact(targetRows, ESTIMATED_BYTES_PER_ROW);
    if (!allowFull && targetRows > 10_000_000L) {
      throw new IllegalStateException(
          "Full stress load is guarded. Set AKIS_STRESS_ALLOW_FULL=true after confirming disk capacity.");
    }
    if (estimatedNeed + RESERVED_BYTES > freeBytes) {
      throw new IllegalStateException(
          "Not enough free disk for requested stress load. estimatedBytes=" + estimatedNeed
              + " freeBytes=" + freeBytes);
    }
    try (Connection connection = DriverManager.getConnection(url, user, password);
        Statement statement = connection.createStatement()) {
      createTable(statement);
      while (currentRows(statement) < targetRows) {
        long current = currentRows(statement);
        long next = Math.min(targetRows, current + batchRows);
        insertRange(statement, current + 1, next);
        System.out.println("STRESS_ROWS=" + currentRows(statement));
      }
    }
  }

  private static void createTable(Statement statement) throws Exception {
    statement.executeUpdate("""
        create unlogged table if not exists akis_pg_target.akis_stress_500m (
          id bigint not null,
          batch_no integer not null,
          customer_no bigint not null,
          account_no bigint not null,
          txn_no bigint not null,
          status_code integer not null,
          region_code integer not null,
          channel_code integer not null,
          product_code integer not null,
          branch_code integer not null,
          amount numeric(18,2) not null,
          tax_amount numeric(18,2) not null,
          discount_amount numeric(18,2) not null,
          balance_before numeric(18,2) not null,
          balance_after numeric(18,2) not null,
          ratio_value numeric(12,6) not null,
          score_value numeric(10,4) not null,
          quantity integer not null,
          retry_count smallint not null,
          priority_level smallint not null,
          is_active boolean not null,
          is_reconciled boolean not null,
          has_error boolean not null,
          created_at timestamp not null,
          updated_at timestamp not null,
          business_date date not null,
          due_date date not null,
          source_system varchar(24) not null,
          target_system varchar(24) not null,
          reference_code varchar(64) not null,
          external_id varchar(64) not null,
          customer_name varchar(120) not null,
          description varchar(240) not null,
          note_text text not null,
          hash_value char(32) not null,
          payload_json jsonb not null,
          ip_address inet not null,
          mac_address macaddr not null,
          uuid_text uuid not null,
          currency_code char(3) not null,
          country_code char(2) not null,
          timezone_name varchar(64) not null,
          source_table varchar(64) not null,
          target_table varchar(64) not null,
          run_id bigint not null,
          partition_key integer not null,
          nullable_text varchar(120),
          nullable_number numeric(18,4),
          nullable_date date,
          load_marker varchar(32) not null
        )
        """);
  }

  private static long currentRows(Statement statement) throws Exception {
    try (ResultSet resultSet = statement.executeQuery("select count(*) from akis_pg_target.akis_stress_500m")) {
      resultSet.next();
      return resultSet.getLong(1);
    }
  }

  private static void insertRange(Statement statement, long from, long to) throws Exception {
    String sql = """
        insert into akis_pg_target.akis_stress_500m
        select g, (g/100000)::int, g*10, g*20, g*30,
          (g%9)::int, (g%81)::int, (g%12)::int, (g%500)::int, (g%1000)::int,
          (g*1.11)::numeric(18,2), (g*0.18)::numeric(18,2), (g*0.03)::numeric(18,2),
          (g*2.00)::numeric(18,2), (g*2.05)::numeric(18,2),
          (g%1000/1000.0)::numeric(12,6), (g%10000/10.0)::numeric(10,4),
          (g%50)::int, (g%5)::smallint, (g%3)::smallint,
          g%2=0, g%3=0, g%97=0, now() - (g||' seconds')::interval, now(),
          current_date - ((g%365)::int), current_date + ((g%90)::int),
          'ORACLE', 'POSTGRESQL', 'REF_'||g, 'EXT_'||md5(g::text), 'MUSTERI_'||g,
          'stress row '||g, repeat('note-'||g||' ', 4), md5(g::text),
          jsonb_build_object('id',g,'bucket',g%100,'flag',g%2=0),
          ('10.'||(g%255)||'.'||((g/255)%255)||'.'||((g/65025)%255))::inet,
          ('08:00:2b:'||lpad(to_hex((g%256)::int),2,'0')||':'||
            lpad(to_hex(((g/256)%256)::int),2,'0')||':'||
            lpad(to_hex(((g/65536)%256)::int),2,'0'))::macaddr,
          (('00000000-0000-0000-0000-'||lpad(to_hex(g),12,'0'))::uuid),
          'TRY', 'TR', 'Europe/Istanbul', 'AKIS_STRESS_SRC', 'AKIS_STRESS_TGT',
          1, (g%64)::int,
          case when g%10=0 then null else 'optional_'||g end,
          case when g%7=0 then null else (g*0.77)::numeric(18,4) end,
          case when g%11=0 then null else current_date end,
          'STRESS'
        from generate_series(__FROM_ROW__, __TO_ROW__) g
        """.replace("__FROM_ROW__", Long.toString(from))
        .replace("__TO_ROW__", Long.toString(to));
    statement.executeUpdate(sql);
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " is required.");
    }
    return value;
  }
}

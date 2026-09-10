package tr.com.innova.akis.spike.oracle;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

import org.springframework.boot.Banner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class OracleJdbcSpikeApplication implements CommandLineRunner {

    private static final Pattern SAFE_IDENTIFIER =
            Pattern.compile("[A-Z][A-Z0-9_$#]{0,127}");

    private static final String OBJECT_COUNT_SQL = """
            select
                (select count(*) from user_tables) as table_count,
                (select count(*) from user_views) as view_count
            from dual
            """;

    private final OracleProbeConfig config;

    public OracleJdbcSpikeApplication() {
        this.config = OracleProbeConfig.fromEnvironment(System.getenv());
    }

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(OracleJdbcSpikeApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);

        try (ConfigurableApplicationContext ignored = application.run(args)) {
            // The probe runs through CommandLineRunner and then exits.
        }
    }

    @Override
    public void run(String... args) throws Exception {
        probe(config.source());
        probe(config.target());
        inspectRequestedTable();
        System.out.println("oracle_probe status=SUCCESS");
    }

    private void probe(OracleEndpoint endpoint) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", endpoint.username());
        properties.setProperty("password", endpoint.password());
        properties.setProperty(
                "oracle.net.CONNECT_TIMEOUT",
                Integer.toString(config.connectTimeoutMs()));
        properties.setProperty(
                "oracle.jdbc.ReadTimeout",
                Integer.toString(config.readTimeoutMs()));

        try (Connection connection = DriverManager.getConnection(endpoint.url(), properties)) {
            connection.setReadOnly(true);

            DatabaseMetaData metadata = connection.getMetaData();
            int actualMajor = metadata.getDatabaseMajorVersion();
            if (actualMajor != config.expectedMajor()) {
                throw new IllegalStateException(
                        "Oracle " + endpoint.role() + " major version mismatch: expected "
                                + config.expectedMajor() + " but received " + actualMajor);
            }

            ObjectCounts counts = readObjectCounts(connection);
            System.out.printf(
                    "oracle_probe role=%s status=CONNECTED database=%s version=%s driver=%s "
                            + "driver_version=%s username=%s tables=%d views=%d%n",
                    safe(endpoint.role()),
                    safe(metadata.getDatabaseProductName()),
                    safe(metadata.getDatabaseProductVersion()),
                    safe(metadata.getDriverName()),
                    safe(metadata.getDriverVersion()),
                    safe(metadata.getUserName()),
                    counts.tables(),
                    counts.views());
        }
    }

    private ObjectCounts readObjectCounts(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(OBJECT_COUNT_SQL);
                ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                throw new SQLException("Oracle discovery count query returned no row.");
            }
            return new ObjectCounts(result.getLong("table_count"), result.getLong("view_count"));
        }
    }

    private void inspectRequestedTable() throws SQLException {
        TableCopyRequest request = config.tableCopyRequest();
        try (Connection source = open(config.source());
                Connection target = open(config.target())) {
            source.setReadOnly(true);
            target.setReadOnly(true);

            List<SourceColumn> columns = readColumns(source, request);
            if (columns.isEmpty()) {
                throw new IllegalStateException(
                        "Source table was not found or is not visible: "
                                + request.sourceOwner() + "." + request.sourceTable());
            }

            long sourceRows = countRows(
                    source,
                    request.sourceOwner(),
                    request.sourceTable());
            boolean targetExists = tableExists(
                    target,
                    request.targetOwner(),
                    request.targetTable());
            Long targetRows = targetExists
                    ? countRows(target, request.targetOwner(), request.targetTable())
                    : null;

            System.out.printf(
                    "oracle_table role=source owner=%s table=%s rows=%d columns=%d%n",
                    request.sourceOwner(),
                    request.sourceTable(),
                    sourceRows,
                    columns.size());
            for (SourceColumn column : columns) {
                System.out.printf(
                        "oracle_column position=%d name=%s type=%s length=%d "
                                + "char_length=%d char_used=%s precision=%s scale=%s "
                                + "nullable=%s virtual=%s%n",
                        column.position(),
                        column.name(),
                        column.type(),
                        column.length(),
                        column.charLength(),
                        safe(column.charUsed()),
                        column.precision() == null ? "" : column.precision(),
                        column.scale() == null ? "" : column.scale(),
                        column.nullable(),
                        column.virtual());
            }
            System.out.printf(
                    "oracle_table role=target owner=%s table=%s exists=%s rows=%s%n",
                    request.targetOwner(),
                    request.targetTable(),
                    targetExists,
                    targetRows == null ? "" : targetRows);

            if (config.copyEnabled()) {
                if (targetExists) {
                    throw new IllegalStateException(
                            "Refusing to copy because target table already exists: "
                                    + request.targetOwner() + "." + request.targetTable());
                }
                copyTable(request, columns, sourceRows);
            }
        }
    }

    private void copyTable(
            TableCopyRequest request,
            List<SourceColumn> columns,
            long expectedSourceRows) throws SQLException {
        try (Connection source = open(config.source());
                Connection target = open(config.target())) {
            source.setReadOnly(true);
            source.setAutoCommit(false);
            target.setAutoCommit(true);

            String connectedTargetUser = target.getMetaData().getUserName().toUpperCase();
            if (!connectedTargetUser.equals(request.targetOwner())) {
                throw new IllegalStateException(
                        "Target owner must match connected user. Connected="
                                + connectedTargetUser + ", requested=" + request.targetOwner());
            }

            String createSql = createTableSql(request, columns);
            try (Statement statement = target.createStatement()) {
                statement.executeUpdate(createSql);
            }
            System.out.printf(
                    "oracle_copy phase=CREATE_TABLE status=SUCCESS target=%s.%s%n",
                    request.targetOwner(),
                    request.targetTable());

            target.setAutoCommit(false);
            long copiedRows;
            try {
                copiedRows = copyRows(source, target, request, columns);
                long targetRows = countRows(
                        target,
                        request.targetOwner(),
                        request.targetTable());
                if (copiedRows != expectedSourceRows || targetRows != copiedRows) {
                    target.rollback();
                    throw new IllegalStateException(
                            "Row reconciliation failed before commit. expected="
                                    + expectedSourceRows + ", copied=" + copiedRows
                                    + ", target=" + targetRows);
                }
                target.commit();
            }
            catch (SQLException | RuntimeException exception) {
                target.rollback();
                throw exception;
            }

            long committedRows = countRows(
                    target,
                    request.targetOwner(),
                    request.targetTable());
            if (committedRows != copiedRows) {
                throw new IllegalStateException(
                        "Committed target row count mismatch. copied="
                                + copiedRows + ", target=" + committedRows);
            }
            System.out.printf(
                    "oracle_copy phase=COMMIT status=SUCCESS source_rows=%d "
                            + "copied_rows=%d target_rows=%d batch_size=%d%n",
                    expectedSourceRows,
                    copiedRows,
                    committedRows,
                    config.batchSize());
        }
    }

    private long copyRows(
            Connection source,
            Connection target,
            TableCopyRequest request,
            List<SourceColumn> columns) throws SQLException {
        String columnList = columns.stream()
                .map(SourceColumn::name)
                .map(this::quotedIdentifier)
                .reduce((left, right) -> left + ", " + right)
                .orElseThrow();
        String bindList = String.join(", ", java.util.Collections.nCopies(columns.size(), "?"));
        String selectSql = "select " + columnList + " from "
                + qualifiedName(request.sourceOwner(), request.sourceTable());
        String insertSql = "insert into "
                + qualifiedName(request.targetOwner(), request.targetTable())
                + " (" + columnList + ") values (" + bindList + ")";

        long copiedRows = 0;
        int pendingBatch = 0;
        try (PreparedStatement select = source.prepareStatement(
                        selectSql,
                        ResultSet.TYPE_FORWARD_ONLY,
                        ResultSet.CONCUR_READ_ONLY);
                PreparedStatement insert = target.prepareStatement(insertSql)) {
            select.setFetchSize(config.batchSize());
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    for (int columnIndex = 1; columnIndex <= columns.size(); columnIndex++) {
                        insert.setObject(columnIndex, rows.getObject(columnIndex));
                    }
                    insert.addBatch();
                    pendingBatch++;
                    copiedRows++;

                    if (pendingBatch == config.batchSize()) {
                        insert.executeBatch();
                        insert.clearBatch();
                        pendingBatch = 0;
                    }
                }
            }
            if (pendingBatch > 0) {
                insert.executeBatch();
            }
        }
        return copiedRows;
    }

    private String createTableSql(
            TableCopyRequest request,
            List<SourceColumn> columns) {
        String definitions = columns.stream()
                .map(column -> quotedIdentifier(column.name()) + " "
                        + renderType(column)
                        + (column.nullable() ? "" : " NOT NULL"))
                .reduce((left, right) -> left + ", " + right)
                .orElseThrow();
        return "create table "
                + qualifiedName(request.targetOwner(), request.targetTable())
                + " (" + definitions + ")";
    }

    private String renderType(SourceColumn column) {
        if (column.virtual()) {
            throw new IllegalArgumentException(
                    "Virtual columns are not supported by this copy spike: " + column.name());
        }
        return switch (column.type()) {
            case "NUMBER" -> renderNumber(column);
            case "TIMESTAMP(6)" -> "TIMESTAMP(6)";
            case "VARCHAR2" -> "VARCHAR2(" + column.charLength() + " "
                    + ("C".equals(column.charUsed()) ? "CHAR" : "BYTE") + ")";
            default -> throw new IllegalArgumentException(
                    "Unsupported Oracle type in this copy spike: "
                            + column.name() + " " + column.type());
        };
    }

    private String renderNumber(SourceColumn column) {
        if (column.precision() == null) {
            return column.scale() == null
                    ? "NUMBER"
                    : "NUMBER(*," + column.scale() + ")";
        }
        return column.scale() == null
                ? "NUMBER(" + column.precision() + ")"
                : "NUMBER(" + column.precision() + "," + column.scale() + ")";
    }

    private Connection open(OracleEndpoint endpoint) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", endpoint.username());
        properties.setProperty("password", endpoint.password());
        properties.setProperty(
                "oracle.net.CONNECT_TIMEOUT",
                Integer.toString(config.connectTimeoutMs()));
        properties.setProperty(
                "oracle.jdbc.ReadTimeout",
                Integer.toString(config.readTimeoutMs()));
        return DriverManager.getConnection(endpoint.url(), properties);
    }

    private List<SourceColumn> readColumns(
            Connection connection,
            TableCopyRequest request) throws SQLException {
        String sql = """
                select column_id,
                       column_name,
                       data_type,
                       data_length,
                       char_length,
                       char_used,
                       data_precision,
                       data_scale,
                       nullable,
                       virtual_column
                  from all_tab_cols
                 where owner = ?
                   and table_name = ?
                   and hidden_column = 'NO'
                 order by column_id
                """;
        List<SourceColumn> columns = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, request.sourceOwner());
            statement.setString(2, request.sourceTable());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    columns.add(new SourceColumn(
                            result.getInt("column_id"),
                            result.getString("column_name"),
                            result.getString("data_type"),
                            result.getInt("data_length"),
                            result.getInt("char_length"),
                            result.getString("char_used"),
                            nullableInteger(result, "data_precision"),
                            nullableInteger(result, "data_scale"),
                            "Y".equals(result.getString("nullable")),
                            "YES".equals(result.getString("virtual_column"))));
                }
            }
        }
        return columns;
    }

    private Integer nullableInteger(ResultSet result, String column) throws SQLException {
        int value = result.getInt(column);
        return result.wasNull() ? null : value;
    }

    private boolean tableExists(
            Connection connection,
            String owner,
            String table) throws SQLException {
        String sql = """
                select count(*)
                  from all_tables
                 where owner = ?
                   and table_name = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, owner);
            statement.setString(2, table);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1) == 1;
            }
        }
    }

    private long countRows(Connection connection, String owner, String table)
            throws SQLException {
        String qualifiedName = qualifiedName(owner, table);
        try (PreparedStatement statement =
                        connection.prepareStatement("select count(*) from " + qualifiedName);
                ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getLong(1);
        }
    }

    private String qualifiedName(String owner, String table) {
        if (!SAFE_IDENTIFIER.matcher(owner).matches()
                || !SAFE_IDENTIFIER.matcher(table).matches()) {
            throw new IllegalArgumentException("Unsafe Oracle owner or table identifier.");
        }
        return "\"" + owner + "\".\"" + table + "\"";
    }

    private String quotedIdentifier(String identifier) {
        if (!SAFE_IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException("Unsafe Oracle identifier: " + identifier);
        }
        return "\"" + identifier + "\"";
    }

    private String safe(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n\\t]", " ");
    }

    private record ObjectCounts(long tables, long views) {
    }

    private record SourceColumn(
            int position,
            String name,
            String type,
            int length,
            int charLength,
            String charUsed,
            Integer precision,
            Integer scale,
            boolean nullable,
            boolean virtual) {
    }

    record TableCopyRequest(
            String sourceOwner,
            String sourceTable,
            String targetOwner,
            String targetTable) {
    }

    record OracleEndpoint(String role, String url, String username, String password) {
    }

    record OracleProbeConfig(
            int expectedMajor,
            int connectTimeoutMs,
            int readTimeoutMs,
            int batchSize,
            boolean copyEnabled,
            OracleEndpoint source,
            OracleEndpoint target,
            TableCopyRequest tableCopyRequest) {

        static OracleProbeConfig fromEnvironment(Map<String, String> environment) {
            int expectedMajor = positiveInt(environment, "AKIS_ORACLE_EXPECTED_MAJOR");
            int connectTimeoutMs = positiveInt(environment, "AKIS_ORACLE_CONNECT_TIMEOUT_MS");
            int readTimeoutMs = positiveInt(environment, "AKIS_ORACLE_READ_TIMEOUT_MS");
            int batchSize = positiveInt(environment, "AKIS_ORACLE_BATCH_SIZE");
            boolean copyEnabled = booleanValue(environment, "AKIS_ORACLE_COPY_ENABLED");

            OracleEndpoint source = endpoint(environment, "SOURCE");
            OracleEndpoint target = endpoint(environment, "TARGET");
            TableCopyRequest tableCopyRequest = new TableCopyRequest(
                    identifier(environment, "AKIS_ORACLE_SOURCE_OWNER"),
                    identifier(environment, "AKIS_ORACLE_SOURCE_TABLE"),
                    identifier(environment, "AKIS_ORACLE_TARGET_OWNER"),
                    identifier(environment, "AKIS_ORACLE_TARGET_TABLE"));
            return new OracleProbeConfig(
                    expectedMajor,
                    connectTimeoutMs,
                    readTimeoutMs,
                    batchSize,
                    copyEnabled,
                    source,
                    target,
                    tableCopyRequest);
        }

        private static OracleEndpoint endpoint(
                Map<String, String> environment,
                String role) {
            String prefix = "AKIS_ORACLE_" + role + "_";
            return new OracleEndpoint(
                    role.toLowerCase(),
                    required(environment, prefix + "URL"),
                    required(environment, prefix + "USERNAME"),
                    required(environment, prefix + "PASSWORD"));
        }

        private static int positiveInt(Map<String, String> environment, String name) {
            String value = required(environment, name);
            try {
                int parsed = Integer.parseInt(value);
                if (parsed <= 0) {
                    throw new IllegalArgumentException(name + " must be greater than zero.");
                }
                return parsed;
            }
            catch (NumberFormatException exception) {
                throw new IllegalArgumentException(name + " must be an integer.", exception);
            }
        }

        private static String required(Map<String, String> environment, String name) {
            String value = environment.get(name);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                        "Missing required local environment value: " + name);
            }
            return value;
        }

        private static String identifier(Map<String, String> environment, String name) {
            String value = required(environment, name).toUpperCase();
            if (!SAFE_IDENTIFIER.matcher(value).matches()) {
                throw new IllegalArgumentException(name + " is not a safe Oracle identifier.");
            }
            return value;
        }

        private static boolean booleanValue(
                Map<String, String> environment,
                String name) {
            String value = required(environment, name);
            if ("true".equalsIgnoreCase(value)) {
                return true;
            }
            if ("false".equalsIgnoreCase(value)) {
                return false;
            }
            throw new IllegalArgumentException(name + " must be true or false.");
        }
    }
}

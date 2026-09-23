package tr.com.innova.akis.postgres;

import java.util.Objects;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.ColumnRow;

/**
 * Oracle column (from a pinned/captured snapshot) to PostgreSQL DDL type text, for provisioning a target table. The rule
 * is the forward direction of {@code JdbcPostgresSchemaPreflight#requireCompatible}: whatever this mapper produces must
 * stay compatible with that check, or a mapping published straight after provisioning would fail preflight.
 * Only the codec's three transferable canonical types are handled (INTEGER, DECIMAL, STRING, TIMESTAMP); a column with any
 * other canonical type — CATALOG_ONLY/UNSUPPORTED in the discovery view — cannot be provisioned and must be excluded or
 * hand-authored by a DBA.
 */
public final class OracleToPostgresTypeMapper {
    private OracleToPostgresTypeMapper() { }

    /** {@code true} when {@link #ddlType} would succeed for this column. */
    public static boolean supported(ColumnRow column) {
        return switch (column.canonicalType()) {
            case "INTEGER", "DECIMAL", "STRING", "TIMESTAMP" -> true;
            default -> false;
        };
    }

    /** PostgreSQL DDL type for one Oracle column; NUMBER(p,0) with p<=18 maps to bigint, matching the preflight's accepted range. */
    public static String ddlType(ColumnRow column) {
        Objects.requireNonNull(column, "Column is required.");
        return switch (column.canonicalType()) {
            case "INTEGER" -> {
                if (column.precision() == null) yield "bigint";
                yield column.precision() <= 18 ? "bigint" : "numeric(" + column.precision() + ",0)";
            }
            case "DECIMAL" -> column.precision() == null ? "numeric"
                    : "numeric(" + column.precision() + "," + (column.scale() == null ? 0 : column.scale()) + ")";
            case "STRING" -> {
                if (column.length() == null) yield "text";
                yield "varchar(" + column.length() + ")";
            }
            case "TIMESTAMP" -> "timestamp(" + (column.timePrecision() == null ? 6 : column.timePrecision()) + ")";
            default -> throw new IllegalArgumentException("Sağlama için desteklenmeyen kanonik tip: " + column.canonicalType()
                    + " (kolon " + column.reference() + ").");
        };
    }

}

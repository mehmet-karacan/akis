package tr.com.innova.akis.knowledge;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PostgresWorkStructureTest {
    @Test void acceptsBenchmarkCharacterBinary32AndOffsetTimestampShapesWithoutCollapsingThem() {
        for(String ddl:List.of("REAL","CHAR(8)","TIMESTAMPTZ(6)"))assertTrue(PostgresWorkStructure.supported(ddl),ddl);
        assertEquals("CHAR(8)",PostgresWorkStructure.ddlOf("character(8)"));
        assertEquals("TIMESTAMPTZ(6)",PostgresWorkStructure.ddlOf("timestamp(6) with time zone"));
        assertEquals(PostgresWorkStructure.expected(List.of(new WorkTableManagerPort.Column("c","REAL"))),
                PostgresWorkStructure.expected(List.of(new WorkTableManagerPort.Column("c","FLOAT4"))));
        assertNotEquals(PostgresWorkStructure.expected(List.of(new WorkTableManagerPort.Column("c","REAL"))),
                PostgresWorkStructure.expected(List.of(new WorkTableManagerPort.Column("c","DOUBLE PRECISION"))));
        assertNotEquals(PostgresWorkStructure.expected(List.of(new WorkTableManagerPort.Column("c","TIMESTAMPTZ(6)"))),
                PostgresWorkStructure.expected(List.of(new WorkTableManagerPort.Column("c","TIMESTAMP(6)"))));
    }
    @Test void acceptsCanonicalDoublePrecisionSpelling() {
        assertTrue(PostgresWorkStructure.supported("DOUBLE PRECISION"));
        assertDoesNotThrow(() -> PostgresWorkStructure.expected(
                List.of(new WorkTableManagerPort.Column("ratio", "DOUBLE PRECISION"))));
    }

    @Test void acceptsPostgresFloat8AliasAsTheSameShape() {
        var canonical = PostgresWorkStructure.expected(List.of(new WorkTableManagerPort.Column("ratio", "DOUBLE PRECISION")));
        var alias = PostgresWorkStructure.expected(List.of(new WorkTableManagerPort.Column("ratio", "FLOAT8")));
        assertEquals(canonical, alias);
    }
}

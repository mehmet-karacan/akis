package tr.com.innova.akis.knowledge;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PostgresWorkStructureTest {
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

package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class OracleRangeTransferReaderTest {

    @Test
    void emptyRangeProducesAnExplicitCommittedReceipt() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.rows.next()).thenReturn(false);
        List<StreamingRowReader.Batch> batches = new ArrayList<>();

        StreamingRowReader.ReadResult result = fixture.reader().read(
                new StreamingRowReader.Range(null, new BigDecimal("100")), batch -> {
                    batches.add(batch); return true;
                });

        assertEquals(0, result.rows());
        assertEquals(new BigDecimal("100"), result.lastCommittedKey());
        assertEquals(1, batches.size());
        assertEquals(List.of(), batches.getFirst().rows());
        assertEquals(0, batches.getFirst().canonicalBytes());
        assertNotNull(batches.getFirst().payloadHash());
        assertEquals(64, batches.getFirst().payloadHash().length());
    }

    @Test
    void duplicateOrderingKeyFailsClosedBeforeAReceiptCanAdvance() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.rows.next()).thenReturn(true, true, false);
        when(fixture.rows.getBigDecimal(1)).thenReturn(
                new BigDecimal("7"), new BigDecimal("7"));
        when(fixture.rows.getBigDecimal(2)).thenReturn(
                new BigDecimal("70"), new BigDecimal("71"));

        OracleRangeReadException failure = assertThrows(
                OracleRangeReadException.class, () -> fixture.reader().read(
                        new StreamingRowReader.Range(null, new BigDecimal("100")),
                        batch -> true));

        assertEquals("ORACLE_RANGE_READ_FAILED", failure.code());
    }

    private static TransferExecutionPlan plan() {
        return new TransferExecutionPlan(1, "a".repeat(64), UUID.randomUUID(),
                UUID.randomUUID(), "123456", "SOURCE_OWNER", "SOURCE_TABLE",
                "TARGET_OWNER", "TARGET_TABLE", "ID",
                List.of(new TransferExecutionPlan.ColumnMapping("VALUE", "VALUE", "NUMBER")),
                10, 1024, "AKIS_RANGE_BATCH/1");
    }

    private static final class Fixture {
        private final Connection connection = mock(Connection.class);
        private final PreparedStatement statement = mock(PreparedStatement.class);
        private final ResultSet rows = mock(ResultSet.class);
        private final ResultSetMetaData metadata = mock(ResultSetMetaData.class);

        private Fixture() throws Exception {
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            when(statement.executeQuery()).thenReturn(rows);
            when(rows.getMetaData()).thenReturn(metadata);
            when(metadata.getColumnCount()).thenReturn(2);
            when(metadata.getColumnType(2)).thenReturn(Types.NUMERIC);
        }

        private OracleRangeTransferReader reader() {
            return new OracleRangeTransferReader(connection, plan());
        }
    }
}

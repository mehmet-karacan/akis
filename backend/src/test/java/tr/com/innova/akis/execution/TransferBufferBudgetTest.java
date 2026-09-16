package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TransferBufferBudgetTest {

    @Test
    void stopsOnEitherRowOrByteLimit() {
        TransferBufferBudget budget = new TransferBufferBudget(2, 10);
        budget.accept(4);
        assertTrue(budget.canAccept(6));
        budget.accept(6);

        assertFalse(budget.canAccept(0));
        assertEquals(2, budget.rows());
        assertEquals(10, budget.bytes());
    }

    @Test
    void rejectsOneRowLargerThanTheWholeBudget() {
        TransferBufferBudget budget = new TransferBufferBudget(100, 10);
        assertThrows(RowExceedsTransferBudgetException.class, () -> budget.canAccept(11));
    }

    @Test
    void countersUseExactLongArithmetic() {
        TransferBufferBudget budget = new TransferBufferBudget(
                3_000_000_000L, Long.MAX_VALUE);
        budget.accept(Integer.MAX_VALUE + 42L);
        assertEquals(Integer.MAX_VALUE + 42L, budget.bytes());
    }
}

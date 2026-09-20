package tr.com.innova.akis.knowledge;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static tr.com.innova.akis.knowledge.StagedMappingDefinition.*;

class StagedQueryGraphTest {
    private Join join(String type, String left, String right) {
        return new Join(type, new ColumnRef(left,"ID"), new ColumnRef(right,"ID"));
    }

    @Test void plansEachSourceOnceWithReversedOuterDirectionAndCompositeConditions() {
        var first=join("LEFT","B","A");
        var second=new Join("RIGHT",new ColumnRef("A","TENANT"),new ColumnRef("B","TENANT"));
        var plan=StagedQueryGraph.compile(List.of("A","B","C"),
                List.of(join("INNER","B","C"),first,second));
        assertEquals("A",plan.firstSource());
        assertEquals(List.of("B","C"),plan.steps().stream().map(StagedQueryGraph.Step::source).toList());
        assertEquals("RIGHT",plan.steps().getFirst().type());
        assertEquals(List.of(first,second),plan.steps().getFirst().conditions());
        assertTrue(plan.remainingConditions().isEmpty());
    }

    @Test void preservesInnerCycleAsAdditionalCondition() {
        var cycle=join("INNER","C","A");
        var plan=StagedQueryGraph.compile(List.of("A","B","C"),
                List.of(join("INNER","A","B"),join("INNER","B","C"),cycle));
        assertEquals(List.of(cycle),plan.remainingConditions());
        assertEquals(2,plan.steps().size());
    }

    @Test void rejectsOuterCycleInsteadOfChangingItsMeaning() {
        assertThrows(IllegalArgumentException.class,()->StagedQueryGraph.compile(List.of("A","B","C"),
                List.of(join("LEFT","A","B"),join("INNER","B","C"),join("INNER","C","A"))));
    }

    @Test void rejectsDisconnectedGraphAndConflictingCompositeJoin() {
        assertThrows(IllegalArgumentException.class,()->StagedQueryGraph.compile(List.of("A","B","C"),List.of(join("INNER","A","B"))));
        assertThrows(IllegalArgumentException.class,()->StagedQueryGraph.compile(List.of("A","B"),
                List.of(join("LEFT","A","B"),join("LEFT","B","A"))));
    }

    @Test void validatesEvenUnusedEdgesWhenAllSourcesAreAlreadyVisited() {
        for (Join invalid:List.of(join("INNER","A","A"),join("INNER","A","MISSING"),join("INVALID","A","B"))) {
            assertThrows(IllegalArgumentException.class,()->StagedQueryGraph.compile(List.of("A","B"),List.of(join("INNER","A","B"),invalid)));
        }
    }

    @Test void boundsInputsAndReturnsImmutablePlan() {
        assertThrows(IllegalArgumentException.class,()->StagedQueryGraph.compile(List.of(),List.of()));
        assertThrows(IllegalArgumentException.class,()->StagedQueryGraph.compile(List.of("A","A"),List.of()));
        assertThrows(IllegalArgumentException.class,()->StagedQueryGraph.compile(List.of("A","B"),Collections.nCopies(257,join("INNER","A","B"))));
        var joins=new ArrayList<>(List.of(join("INNER","A","B")));
        var plan=StagedQueryGraph.compile(List.of("A","B"),joins);
        joins.clear();
        assertEquals(1,plan.steps().getFirst().conditions().size());
        assertThrows(UnsupportedOperationException.class,()->plan.steps().clear());
        assertThrows(UnsupportedOperationException.class,()->plan.steps().getFirst().conditions().clear());
    }
}

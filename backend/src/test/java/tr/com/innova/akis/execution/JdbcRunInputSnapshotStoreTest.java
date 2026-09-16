package tr.com.innova.akis.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import tools.jackson.databind.ObjectMapper;
import tr.com.innova.akis.execution.RunInputSnapshotStore.ResolvedInput;

class JdbcRunInputSnapshotStoreTest {

    @Test
    void semanticJsonOrderDoesNotChangeTheImmutableInputHash() {
        ObjectMapper mapper = new ObjectMapper();
        var left = mapper.createObjectNode().put("b", 2).put("a", 1);
        var right = mapper.createObjectNode().put("a", 1).put("b", 2);
        var store = new JdbcRunInputSnapshotStore(mock(JdbcClient.class), mapper);

        String first = store.canonicalHash(new ResolvedInput(
                mapper.createArrayNode().add(left), left, left,
                mapper.createArrayNode().add("binding"), "42", "f".repeat(64)));
        String second = store.canonicalHash(new ResolvedInput(
                mapper.createArrayNode().add(right), right, right,
                mapper.createArrayNode().add("binding"), "42", "f".repeat(64)));

        assertEquals(first, second);
    }
}

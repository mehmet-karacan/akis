package tr.com.innova.akis.topology;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tr.com.innova.akis.knowledge.TransferMemoryPolicy;

class EnvironmentTransferBudgetTest {
    @Test void metadataOnlyUpdateMustNotRewriteAnEnvironmentBudgetSnapshot() {
        var repository=mock(TopologyRepository.class);var mapper=new ObjectMapper();
        var service=new TopologyService(repository,mapper,null,null);
        var current=mock(TopologyModels.EnvironmentRow.class);
        var uuid=java.util.UUID.randomUUID();
        when(current.policy()).thenReturn(mapper.readTree("{\"transferMemoryBudgetMiB\":1024}"));
        when(current.risk()).thenReturn("DUSUK");when(current.status()).thenReturn("ETKIN");
        when(repository.findEnvironment(uuid)).thenReturn(java.util.Optional.of(current));
        service.updateEnvironment(uuid,"Renamed",null,null,false,null);
        verify(repository,never()).updateEnvironmentMemoryPolicy(any(),any());
    }
    @Test void creationPersistsDefaultBudgetAndPreservesUnrelatedPolicy() {
        var repository=mock(TopologyRepository.class);
        var mapper=new ObjectMapper();
        var service=new TopologyService(repository,mapper,null,null);
        service.createEnvironment("TEST","Test",null,"DUSUK",false,mapper.readTree("{\"approval\":true}"));
        var policy=ArgumentCaptor.forClass(JsonNode.class);
        verify(repository).createEnvironment(eq("TEST"),eq("Test"),isNull(),eq("DUSUK"),eq(false),eq(1),policy.capture(),isNull());
        assertEquals(1024,policy.getValue().path(TransferMemoryPolicy.KEY).asLong());
        assertTrue(policy.getValue().path("approval").asBoolean());
    }
    @Test void invalidOrFractionalBudgetNeverReachesPersistence() {
        var repository=mock(TopologyRepository.class);var mapper=new ObjectMapper();
        var service=new TopologyService(repository,mapper,null,null);
        for(String value:new String[]{"0","63","65537","1.5","\"1024\"","null"}) {
            assertThrows(RuntimeException.class,()->service.createEnvironment("TEST","Test",null,"DUSUK",false,
                    mapper.readTree("{\"transferMemoryBudgetMiB\":"+value+"}")));
        }
        verifyNoInteractions(repository);
    }
}

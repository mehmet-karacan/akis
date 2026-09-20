package tr.com.innova.akis.publication;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tr.com.innova.akis.knowledge.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static tr.com.innova.akis.publication.PublicationModels.*;

class StagedMappingPlannerTest {
    private final JsonMapper mapper = new JsonMapper();
    private ObjectNode plan(Map<String,Object> resolvedIntegration, Map<String,Object> suppliedIntegration) {
        var modules = mock(KnowledgeModuleRegistry.class);
        var workAreas = mock(WorkAreaPolicyService.class);
        var jdbc = mock(JdbcClient.class);
        var statement = mock(JdbcClient.StatementSpec.class, RETURNS_SELF);
        @SuppressWarnings("unchecked")
        JdbcClient.MappedQuerySpec<ObjectNode> query = mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.query(org.mockito.ArgumentMatchers.<RowMapper<ObjectNode>>any())).thenReturn(query);
        UUID project = UUID.randomUUID(), connection = UUID.randomUUID(), physical = UUID.randomUUID();
        var staging = mapper.createObjectNode().put("projectUuid", project.toString()).put("connectionUuid", connection.toString())
                .put("connectionVersionUuid", UUID.randomUUID().toString()).put("physicalSchemaUuid", physical.toString())
                .put("bindingUuid", physical.toString()).put("bindingVersion", 1).put("owner", "WORK").put("logicalSchemaUuid", UUID.randomUUID().toString()).put("prefixOrigin", "PHYSICAL_SCHEMA").put("prefixVersion", 1);
        staging.putObject("prefixes").put("loading", "C$_").put("integration", "I$_").put("error", "E$_");
        when(query.optional()).thenReturn(Optional.of(staging));
        when(workAreas.get(project, physical)).thenReturn(new WorkAreaPolicyService.View(new WorkAreaPolicyService.Policy(true, false, 10, 1000000, 536870912, 24), 1));
        var content = (ObjectNode) mapper.readTree("""
          {"sources":[{"id":"S","alias":"SRC","dataObjectUuid":"11111111-1111-1111-1111-111111111111","schemaSnapshotUuid":"55555555-5555-5555-5555-555555555555"}],
           "target":{"id":"T","alias":"TGT","dataObjectUuid":"22222222-2222-2222-2222-222222222222","schemaSnapshotUuid":"66666666-6666-6666-6666-666666666666"},
           "joins":[],"filters":[],
           "columnMappings":[{"source":{"object":"S","column":"ID"},"target":{"object":"T","column":"ID"}}],
           "modules":{"loading":{"versionUuid":"33333333-3333-3333-3333-333333333333","contentHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
                      "integration":{"versionUuid":"44444444-4444-4444-4444-444444444444","contentHash":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}},
           "options":{"batchRows":500,"fetchRows":500,"maxRows":100000,"maxBytes":268435456,"allowEmptySource":false}}
          """);
        content.putObject("moduleOptions").set("integration", mapper.valueToTree(suppliedIntegration));
        String loading = AkisKmLanguage.example(AkisKmLanguage.Kind.LKM);
        String integration = AkisKmLanguage.example(AkisKmLanguage.Kind.IKM);
        var bundle = new KnowledgeModuleRegistry.Bundle(Map.of(
                "loading", new KnowledgeModuleRegistry.Module(1, UUID.randomUUID(), "a".repeat(64), loading, "LKM", mapper.createArrayNode(), Map.of("DISTINCT", true)),
                "integration", new KnowledgeModuleRegistry.Module(2, UUID.randomUUID(), "b".repeat(64), integration, "IKM", mapper.createArrayNode(), resolvedIntegration)),
                AkisKmInterpreter.compile(new AkisKmInterpreter.Modules(loading, null, integration)));
        when(modules.resolve(eq(1L), any())).thenReturn(bundle);
        var scenario = mapper.createObjectNode();
        scenario.putObject("executable").set("definition", content);
        var context = new PublicationContext(1, 2, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 4,
                KmCanonical.hash(mapper, content), KmCanonical.hash(mapper, scenario), scenario, 3, UUID.randomUUID(), "TEST", "LOW", 1, mapper.createObjectNode());
        return (ObjectNode) new StagedMappingPlanner(modules, jdbc, mapper, workAreas)
                .compile(context, List.of(binding("S", "KAYNAK"), binding("T", "HEDEF")));
    }
    private ResolvedBinding binding(String code, String role) {
        return new ResolvedBinding(1, UUID.randomUUID(), code, role, UUID.randomUUID(), "ITEMS", "TABLE", 2L,
                UUID.randomUUID(), 3L, UUID.randomUUID(), "DATA", 4L, UUID.randomUUID(), "ORACLE", 5L,
                UUID.randomUUID(), "e".repeat(64), 1, "AKTIF", "AKTIF", "AKTIF", "AKTIF");
    }
    @Test void flagsNonReversibleDdlWhenTruncateComesFromResolvedModuleDefaults() {
        var result = plan(Map.of("WRITE_MODE", "TRUNCATE_LOAD", "TRUNCATE_TARGET", true), Map.of());
        assertTrue(result.path("staging").path("nonReversibleDdl").asBoolean());
        assertTrue(result.path("modules").path("loading").path("options").path("DISTINCT").asBoolean());
        assertEquals("TRUNCATE_LOAD", result.path("modules").path("integration").path("options").path("WRITE_MODE").asText());
        String expected = result.remove("physicalPlanHash").asText();
        assertEquals(expected, KmCanonical.hash(mapper, result));
    }
    @Test void usesResolvedOverridesRatherThanAnUnrelatedBooleanForDdlRisk() {
        var result = plan(Map.of("WRITE_MODE", "APPEND", "TRUNCATE_TARGET", true), Map.of("WRITE_MODE", "APPEND"));
        assertFalse(result.path("staging").path("nonReversibleDdl").asBoolean());
    }
}

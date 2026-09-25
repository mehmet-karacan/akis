package tr.com.innova.akis.knowledge;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tr.com.innova.akis.metadata.ApiException;
import tr.com.innova.akis.metadata.DefinitionContentValidator;
import tr.com.innova.akis.metadata.DefinitionType;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeModuleRegistryTest {
    private final JsonMapper mapper = new JsonMapper();
    private JsonNode schema(String key, String type) {
        var options = mapper.createArrayNode();
        options.addObject().put("key", key).put("label", key).put("type", type);
        return options;
    }

    @Test void exactIntegerStringsSurvivePublicationWithoutChangingWireRepresentation() {
        var schema = schema("LIMIT", "INTEGER");
        for (String text : new String[]{"9007199254740993", "9223372036854775807", "-9223372036854775808", "0"}) {
            var resolved = KnowledgeModuleRegistry.validateOptions("LKM", schema, Map.of("LIMIT", text));
            assertEquals(text, resolved.get("LIMIT"));
            assertEquals(text, mapper.readTree(mapper.writeValueAsString(resolved)).path("LIMIT").asText());
        }
        assertEquals(42L, KnowledgeModuleRegistry.validateOptions("LKM", schema, Map.of("LIMIT", 42L)).get("LIMIT"));
        assertTrue(KmIntegerValue.valid(BigInteger.valueOf(Long.MIN_VALUE)));
        assertFalse(KmIntegerValue.valid(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)));
    }

    @Test void rejectsFractionalOverflowNonFiniteAndMalformedIntegers() {
        var schema = schema("LIMIT", "INTEGER");
        for (Object invalid : new Object[]{1.5, 1.0, Double.NaN, Double.POSITIVE_INFINITY, new BigDecimal("1.5"),
                "1.5", "1e3", "9223372036854775808", "-9223372036854775809", "", " 1", "1;DROP TABLE X", true}) {
            assertThrows(ApiException.class, () -> KnowledgeModuleRegistry.validateOptions("LKM", schema, Map.of("LIMIT", invalid)), invalid.toString());
        }
    }

    @Test void defaultsRequiredAndUnknownOptionsKeepTheirContract() {
        var schema = (tools.jackson.databind.node.ArrayNode) schema("LIMIT", "INTEGER");
        ((tools.jackson.databind.node.ObjectNode) schema.get(0)).put("defaultValue", "9223372036854775807").put("required", true);
        assertEquals("9223372036854775807", KnowledgeModuleRegistry.validateOptions("LKM", schema, Map.of()).get("LIMIT"));
        assertThrows(ApiException.class, () -> KnowledgeModuleRegistry.validateOptions("LKM", schema, Map.of("OTHER", "1")));
        ((tools.jackson.databind.node.ObjectNode) schema.get(0)).remove("defaultValue");
        assertThrows(ApiException.class, () -> KnowledgeModuleRegistry.validateOptions("LKM", schema, Map.of()));
    }

    @Test void builtInOptionsCannotBypassRoleOrHintValidationWithAnotherDeclaredType() {
        assertThrows(ApiException.class, () -> KnowledgeModuleRegistry.validateOptions("LKM", schema("ORACLE_HINT", "STRING"), Map.of("ORACLE_HINT", "x */ DELETE FROM T --")));
        assertThrows(ApiException.class, () -> KnowledgeModuleRegistry.validateOptions("LKM", schema("ORACLE_HINT", "BOOLEAN"), Map.of("ORACLE_HINT", true)));
        assertThrows(ApiException.class, () -> KnowledgeModuleRegistry.validateOptions("CKM", schema("ORACLE_HINT", "SQL_HINT"), Map.of("ORACLE_HINT", "PARALLEL(4)")));
        assertThrows(ApiException.class, () -> KnowledgeModuleRegistry.validateOptions("LKM", schema("TRUNCATE_TARGET", "BOOLEAN"), Map.of("TRUNCATE_TARGET", true)));
        assertThrows(ApiException.class, () -> KnowledgeModuleRegistry.validateOptions("IKM", schema("TRUNCATE_TARGET", "STRING"), Map.of("TRUNCATE_TARGET", "true")));
        assertThrows(ApiException.class, () -> KnowledgeModuleRegistry.validateOptions("IKM", schema("KEY_COLUMNS", "STRING"), Map.of("KEY_COLUMNS", "ID;DROP TABLE T")));
        assertEquals("FULL(T) PARALLEL(4)", KnowledgeModuleRegistry.validateOptions("LKM", schema("ORACLE_HINT", "SQL_HINT"), Map.of("ORACLE_HINT", "FULL(T) PARALLEL(4)")).get("ORACLE_HINT"));
    }

    @Test void truncationRequiresAnExplicitBooleanAndMergeRequiresKeys() {
        var schema = mapper.readTree("""
          [{"key":"WRITE_MODE","type":"ENUM","values":["APPEND","MERGE","TRUNCATE_LOAD","ATOMIC_DELETE_INSERT"]},
           {"key":"TRUNCATE_TARGET","type":"BOOLEAN"},{"key":"KEY_COLUMNS","type":"COLUMN_LIST"}]
          """);
        assertThrows(ApiException.class, () -> KnowledgeModuleRegistry.validateOptions("IKM", schema, Map.of("WRITE_MODE", "TRUNCATE_LOAD", "TRUNCATE_TARGET", false)));
        assertDoesNotThrow(() -> KnowledgeModuleRegistry.validateOptions("IKM", schema, Map.of("WRITE_MODE", "TRUNCATE_LOAD", "TRUNCATE_TARGET", true)));
        assertThrows(ApiException.class, () -> KnowledgeModuleRegistry.validateOptions("IKM", schema, Map.of("WRITE_MODE", "MERGE")));
        assertDoesNotThrow(() -> KnowledgeModuleRegistry.validateOptions("IKM", schema, Map.of("WRITE_MODE", "MERGE", "KEY_COLUMNS", "ID,VERSION")));
    }

    @Test void legacyOptionSchemaAcceptsExactDecimalStringsWithoutAllowingDecimals() {
        var content = mapper.createObjectNode().put("kmType", "LKM").put("language", "AKIS_KM/1")
                .put("source", AkisKmLanguage.example(AkisKmLanguage.Kind.LKM).replace(AkisKmLanguage.VERSION, AkisKmLanguage.LEGACY_VERSION)
                        .replaceAll("(?m)^SECENEK.*\\R", "").replaceAll("(?ms)^KOMUT.*?^>>>\\R?", ""));
        content.putArray("tasks"); content.putArray("options");
        var option = content.putArray("optionSchema").addObject().put("key", "LIMIT").put("label", "Limit").put("type", "INTEGER").put("defaultValue", "9223372036854775807");
        assertDoesNotThrow(() -> new DefinitionContentValidator().validate(DefinitionType.KNOWLEDGE_MODULE, 2, content));
        option.put("defaultValue", "9223372036854775808");
        assertThrows(ApiException.class, () -> new DefinitionContentValidator().validate(DefinitionType.KNOWLEDGE_MODULE, 2, content));
    }
    @Test void pinnedRuntimeValidatesExactIntegersAndUsesDeclarationsNotDisplayMetadata() {
        String source=AkisKmLanguage.example(AkisKmLanguage.Kind.LKM).replace("ADIM ONCEKI_CALISMAYI_TEMIZLE",
                "SECENEK LIMIT INTEGER ZORUNLU YOK YOK\nADIM ONCEKI_CALISMAYI_TEMIZLE");
        var fakeSchema=schema("LIMIT","STRING");
        assertDoesNotThrow(()->KnowledgeModuleRegistry.validatePinnedOptions(mapper,"LKM",source,fakeSchema,
                Map.of("DISTINCT",false,"LIMIT","9223372036854775807")));
        for(String invalid:new String[]{"9223372036854775808","1.5","1e3"})
            assertThrows(ApiException.class,()->KnowledgeModuleRegistry.validatePinnedOptions(mapper,"LKM",source,fakeSchema,
                    Map.of("DISTINCT",false,"LIMIT",invalid)));
        assertThrows(ApiException.class,()->KnowledgeModuleRegistry.validatePinnedOptions(mapper,"IKM",source,fakeSchema,Map.of()));
    }
    @Test void legacyRuntimeRequiresDeclarationsForNonemptyOptionsButPreservesOptionlessPlans() {
        String source=AkisKmLanguage.example(AkisKmLanguage.Kind.LKM).replace(AkisKmLanguage.VERSION,AkisKmLanguage.LEGACY_VERSION)
                .replaceAll("(?m)^SECENEK.*\\R","").replaceAll("(?ms)^KOMUT.*?^>>>\\R?","");
        var missing=mapper.createObjectNode().path("absent");
        assertDoesNotThrow(()->KnowledgeModuleRegistry.validatePinnedOptions(mapper,"LKM",source,missing,Map.of()));
        assertThrows(ApiException.class,()->KnowledgeModuleRegistry.validatePinnedOptions(mapper,"LKM",source,missing,Map.of("LIMIT",5L)));
        assertDoesNotThrow(()->KnowledgeModuleRegistry.validatePinnedOptions(mapper,"LKM",source,schema("LIMIT","INTEGER"),Map.of("LIMIT",5L)));
        assertThrows(ApiException.class,()->KnowledgeModuleRegistry.validatePinnedOptions(mapper,"LKM",source,schema("LIMIT","INTEGER"),Map.of("LIMIT","bad")));
    }
    @Test void rejectsNumericDefaultOverflowAndDuplicateDeclarations() {
        var schema=(tools.jackson.databind.node.ArrayNode)schema("LIMIT","INTEGER");
        ((tools.jackson.databind.node.ObjectNode)schema.get(0)).put("defaultValue",new BigInteger("9223372036854775808"));
        assertThrows(ApiException.class,()->KnowledgeModuleRegistry.validateOptions("LKM",schema,Map.of()));
        ((tools.jackson.databind.node.ObjectNode)schema.get(0)).remove("defaultValue");
        schema.add(schema.get(0).deepCopy());
        assertThrows(ApiException.class,()->KnowledgeModuleRegistry.validateOptions("LKM",schema,Map.of("LIMIT",1L)));
    }

    @Test void rejectsKnowledgeModulesForAnotherDatabaseRoute() {
        var module = new KnowledgeModuleRegistry.Module(1, UUID.randomUUID(), "hash", "source", "LKM",
                "ORACLE", "POSTGRESQL", mapper.createArrayNode(), Map.of());
        var bundle = new KnowledgeModuleRegistry.Bundle(Map.of("loading", module), null);
        assertDoesNotThrow(() -> KnowledgeModuleRegistry.requireCompatible(bundle, Set.of("ORACLE"), "POSTGRESQL"));
        assertThrows(ApiException.class, () -> KnowledgeModuleRegistry.requireCompatible(bundle, Set.of("POSTGRESQL"), "POSTGRESQL"));
        assertThrows(ApiException.class, () -> KnowledgeModuleRegistry.requireCompatible(bundle, Set.of("ORACLE"), "ORACLE"));
    }
}

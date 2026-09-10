package tr.com.innova.akis.metadata;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class DefinitionContentValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DefinitionContentValidator validator = new DefinitionContentValidator();

    @Test
    void acceptsConnectedAcyclicPackage() {
        assertDoesNotThrow(() -> validate(DefinitionType.PACKAGE, """
                {
                  "firstStepId":"extract",
                  "steps":[
                    {"id":"extract","type":"MAPPING"},
                    {"id":"evaluate","type":"VARIABLE_EVALUATE"},
                    {"id":"finish","type":"PROCEDURE"}
                  ],
                  "transitions":[
                    {"fromStepId":"extract","toStepId":"evaluate","outcome":"SUCCESS"},
                    {"fromStepId":"evaluate","toStepId":"finish","outcome":"TRUE"}
                  ]
                }
                """));
    }

    @Test
    void rejectsDuplicatePackageStepIds() {
        assertValidationContains(DefinitionType.PACKAGE, """
                {"firstStepId":"a",
                 "steps":[{"id":"a","type":"MAPPING"},{"id":"a","type":"PROCEDURE"}],
                 "transitions":[]}
                """, "benzersiz");
    }

    @Test
    void rejectsMissingPackageFirstStepReference() {
        assertValidationContains(DefinitionType.PACKAGE, """
                {"firstStepId":"missing","steps":[{"id":"a","type":"MAPPING"}],"transitions":[]}
                """, "firstStepId");
    }

    @Test
    void rejectsPackageTransitionToUnknownStep() {
        assertValidationContains(DefinitionType.PACKAGE, """
                {"firstStepId":"a","steps":[{"id":"a","type":"MAPPING"}],
                 "transitions":[{"fromStepId":"a","toStepId":"missing"}]}
                """, "var olmayan");
    }

    @Test
    void rejectsUnreachablePackageStep() {
        assertValidationContains(DefinitionType.PACKAGE, """
                {"firstStepId":"a",
                 "steps":[{"id":"a","type":"MAPPING"},{"id":"orphan","type":"PROCEDURE"}],
                 "transitions":[]}
                """, "erişilemeyen");
    }

    @Test
    void rejectsPackageCycleWithoutRecursiveTraversal() {
        assertValidationContains(DefinitionType.PACKAGE, """
                {"firstStepId":"a",
                 "steps":[{"id":"a","type":"MAPPING"},{"id":"b","type":"PROCEDURE"}],
                 "transitions":[
                   {"fromStepId":"a","toStepId":"b"},
                   {"fromStepId":"b","toStepId":"a"}
                 ]}
                """, "cycle");
    }

    @Test
    void acceptsAllowlistedReadOnlyProcedureTask() {
        assertDoesNotThrow(() -> validate(DefinitionType.PROCEDURE, """
                {"tasks":[{"id":"read","type":"SQL","connectionRole":"SOURCE",
                            "riskClass":"READ_ONLY","command":"select * from T"}]}
                """));
    }

    @Test
    void rejectsProcedureTaskOutsideAllowlist() {
        assertValidationContains(DefinitionType.PROCEDURE, """
                {"tasks":[{"id":"shell","type":"OPERATING_SYSTEM","connectionRole":"SOURCE",
                            "riskClass":"READ_ONLY","command":"whoami"}]}
                """, "desteklenmeyen");
    }

    @Test
    void requiresDestructiveClassificationAndExplicitApproval() {
        assertValidationContains(DefinitionType.PROCEDURE, """
                {"tasks":[{"id":"truncate","type":"SQL","connectionRole":"TARGET",
                            "riskClass":"DDL","requiresApproval":true,
                            "command":"/* maintenance */ TRUNCATE TABLE STAGE_T"}]}
                """, "DESTRUCTIVE");

        assertValidationContains(DefinitionType.PROCEDURE, """
                {"tasks":[{"id":"truncate","type":"SQL","connectionRole":"TARGET",
                            "riskClass":"DESTRUCTIVE","requiresApproval":false,
                            "command":"TRUNCATE TABLE STAGE_T"}]}
                """, "açık onay");

        assertDoesNotThrow(() -> validate(DefinitionType.PROCEDURE, """
                {"tasks":[{"id":"truncate","type":"SQL","connectionRole":"TARGET",
                            "riskClass":"DESTRUCTIVE","requiresApproval":true,
                            "command":"TRUNCATE TABLE STAGE_T"}]}
                """));
    }

    @Test
    void acceptsMappingWithSourceTargetAndMergeKey() {
        assertDoesNotThrow(() -> validate(DefinitionType.MAPPING, validMapping()));
    }

    @Test
    void rejectsDuplicateDatasetIdsAndUnsupportedRoles() {
        assertValidationContains(DefinitionType.MAPPING, """
                {"datasets":[{"id":"same","role":"SOURCE"},{"id":"same","role":"TARGET"}],
                 "columnMappings":[],"writeStrategy":{"kind":"APPEND"}}
                """, "benzersiz");
        assertValidationContains(DefinitionType.MAPPING, """
                {"datasets":[{"id":"s","role":"LOOKUP"},{"id":"t","role":"TARGET"}],
                 "columnMappings":[],"writeStrategy":{"kind":"APPEND"}}
                """, "desteklenmeyen");
    }

    @Test
    void rejectsMappingThatWritesTargetColumnTwice() {
        assertValidationContains(DefinitionType.MAPPING, """
                {"datasets":[{"id":"s","role":"SOURCE"},{"id":"t","role":"TARGET"}],
                 "columnMappings":[
                   {"source":{"dataset":"s","column":"A"},"target":{"dataset":"t","column":"ID"}},
                   {"source":{"dataset":"s","column":"B"},"target":{"dataset":"t","column":"id"}}
                 ],"writeStrategy":{"kind":"APPEND"}}
                """, "birden fazla");
    }

    @Test
    void rejectsUnsupportedWriteStrategyAndMergeWithoutKey() {
        assertValidationContains(DefinitionType.MAPPING,
                validMapping().replace("\"MERGE\"", "\"UPSERT\""), "desteklenmeyen");
        assertValidationContains(DefinitionType.MAPPING,
                validMapping().replace("\"key\":[\"ID\"]", "\"key\":[]"), "en az bir key");
    }

    @Test
    void acceptsNestedLoadPlanStructure() {
        assertDoesNotThrow(() -> validate(DefinitionType.LOAD_PLAN, """
                {"restartPolicy":"FAILED_STEP","steps":[
                  {"id":"root","type":"SERIAL","steps":[
                    {"id":"parallel","type":"PARALLEL","steps":[
                      {"id":"s1","type":"SCENARIO","scenarioVersionUuid":"v1"},
                      {"id":"s2","type":"SCENARIO","scenarioVersionUuid":"v2"}
                    ]}
                  ]}
                ]}
                """));
    }

    @Test
    void rejectsMalformedLoadPlanSteps() {
        assertValidationContains(DefinitionType.LOAD_PLAN, """
                {"restartPolicy":"FAILED_STEP","steps":[{"id":"s","type":"SCENARIO"}]}
                """, "scenarioVersionUuid");
        assertValidationContains(DefinitionType.LOAD_PLAN, """
                {"restartPolicy":"FAILED_STEP","steps":[{"id":"group","type":"SERIAL","steps":[]}]}
                """, "en az bir alt adım");
        assertValidationContains(DefinitionType.LOAD_PLAN, """
                {"restartPolicy":"FAILED_STEP","steps":[
                  {"id":"same","type":"SCENARIO","scenarioVersionUuid":"v1"},
                  {"id":"same","type":"SCENARIO","scenarioVersionUuid":"v2"}]}
                """, "benzersiz");
    }

    @Test
    void preservesVariableAndSequenceContracts() {
        assertDoesNotThrow(() -> validate(DefinitionType.VARIABLE, """
                {"dataType":"TIMESTAMP","scope":"PACKAGE_RUN","historyMode":"LATEST",
                 "valueSource":"STEP_OUTPUT"}
                """));
        assertDoesNotThrow(() -> validate(DefinitionType.SEQUENCE, """
                {"implementation":"NATIVE","start":10,"increment":-1,"cycle":false}
                """));
        assertValidationContains(DefinitionType.SEQUENCE, """
                {"implementation":"NATIVE","start":10,"increment":0,"cycle":false}
                """, "sıfır");
    }

    private String validMapping() {
        return """
                {"datasets":[{"id":"s","role":"SOURCE"},{"id":"t","role":"TARGET"}],
                 "columnMappings":[
                   {"source":{"dataset":"s","column":"ID"},
                    "target":{"dataset":"t","column":"ID"}}
                 ],
                 "writeStrategy":{"kind":"MERGE","key":["ID"],"deleteMissing":false}}
                """;
    }

    private void assertValidationContains(DefinitionType type, String content, String messagePart) {
        ApiException exception = assertThrows(
                ApiException.class, () -> validate(type, content));
        assertEquals("VALIDATION_FAILED", exception.code());
        assertTrue(exception.getMessage().contains(messagePart), exception.getMessage());
    }

    private void validate(DefinitionType type, String content) {
        validator.validate(type, json(content));
    }

    private JsonNode json(String value) {
        return objectMapper.readTree(value);
    }
}

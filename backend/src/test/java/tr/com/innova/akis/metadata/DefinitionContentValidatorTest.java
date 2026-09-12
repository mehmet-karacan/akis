package tr.com.innova.akis.metadata;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

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
    void rejectsCommonProcedureSqlPunctuationErrors() {
        assertValidationContains(DefinitionType.PROCEDURE, """
                {"tasks":[{"id":"insert","type":"SQL","connectionRole":"TARGET",
                            "riskClass":"DML","command":"INSERT INTO T (ID, ) VALUES (:ID)"}]}
                """, "virgül");
        assertValidationContains(DefinitionType.PROCEDURE, """
                {"tasks":[{"id":"read","type":"SQL","connectionRole":"SOURCE",
                            "riskClass":"READ_ONLY","command":"SELECT (ID FROM T"}]}
                """, "parantez");
        assertValidationContains(DefinitionType.PROCEDURE, """
                {"tasks":[{"id":"read","type":"SQL","connectionRole":"SOURCE",
                            "riskClass":"READ_ONLY","command":"SELECT 'broken FROM T"}]}
                """, "tırnak");
    }

    @Test
    void rejectsInvalidProcedureTransactionAndCounterContracts() {
        assertValidationContains(DefinitionType.PROCEDURE, 2, """
                {"tasks":[{"id":"read","type":"SQL","connectionRole":"SOURCE",
                  "riskClass":"READ_ONLY","command":"SELECT ID FROM T",
                  "transactionMode":"TRANSACTION","transactionChannel":0}]}
                """, "yalnız hedef DML");
        assertValidationContains(DefinitionType.PROCEDURE, 2, """
                {"tasks":[{"id":"write","type":"SQL","connectionRole":"TARGET",
                  "riskClass":"DML","command":"INSERT INTO T (ID) VALUES (1)",
                  "transactionMode":"TRANSACTION","transactionChannel":10}]}
                """, "0-9");
        assertValidationContains(DefinitionType.PROCEDURE, 2, """
                {"tasks":[{"id":"write","type":"SQL","connectionRole":"TARGET",
                  "riskClass":"DML","command":"INSERT INTO T (ID) VALUES (1)",
                  "transactionMode":"AUTOCOMMIT","commitMode":"NO_COMMIT"}]}
                """, "COMMIT");
        assertValidationContains(DefinitionType.PROCEDURE, 2, """
                {"tasks":[{"id":"write","type":"SQL","connectionRole":"TARGET",
                  "riskClass":"DML","command":"UPDATE T SET ID = 1","logCounter":"INSERT"}]}
                """, "eşleşmiyor");
    }

    @Test
    void acceptsOrderedProcedureV2WithMaintenanceAndCrossConnectionRowTransfer() {
        assertDoesNotThrow(() -> validate(DefinitionType.PROCEDURE, 2, """
                {"tasks":[
                  {"id":"CLEAR_TARGET","type":"SQL","connectionRole":"TARGET",
                   "riskClass":"DESTRUCTIVE","requiresApproval":true,"onError":"STOP",
                   "command":"TRUNCATE TABLE INNOVA_ODI.STG_HAKEDIS_TIPI"},
                  {"id":"READ_SOURCE","type":"SQL","connectionRole":"SOURCE",
                   "riskClass":"READ_ONLY","onError":"STOP","timeoutSeconds":60,
                   "command":"SELECT ID, ACIKLAMA FROM TTBP.HAKEDIS_TIPI",
                   "output":{"kind":"ROWSET","maxRows":10000}},
                  {"id":"WRITE_TARGET","type":"SQL","connectionRole":"TARGET",
                   "riskClass":"DML","onError":"STOP","logCounter":"INSERT",
                   "transactionMode":"TRANSACTION","transactionChannel":0,
                   "transactionIsolation":"READ_COMMITTED","commitMode":"COMMIT",
                   "command":"INSERT INTO INNOVA_ODI.STG_HAKEDIS_TIPI (ID, ACIKLAMA) VALUES (:ID, :ACIKLAMA)",
                   "input":{"fromTask":"READ_SOURCE","mode":"BATCH","batchSize":250}},
                  {"id":"GATHER_STATS","type":"PLSQL","connectionRole":"TARGET",
                   "riskClass":"DDL","requiresApproval":true,"onError":"STOP",
                   "command":"BEGIN DBMS_STATS.GATHER_TABLE_STATS('INNOVA_ODI','STG_HAKEDIS_TIPI'); END;"}
                ]}
                """));
    }

    @Test
    void rejectsProcedureV2RowInputBeforeItsProducer() {
        assertValidationContains(DefinitionType.PROCEDURE, 2, """
                {"tasks":[
                  {"id":"WRITE_TARGET","type":"SQL","connectionRole":"TARGET",
                   "riskClass":"DML","command":"INSERT INTO T (ID) VALUES (:ID)",
                   "input":{"fromTask":"READ_SOURCE","mode":"BATCH","batchSize":250}},
                  {"id":"READ_SOURCE","type":"SQL","connectionRole":"SOURCE",
                   "riskClass":"READ_ONLY","command":"SELECT ID FROM S",
                   "output":{"kind":"ROWSET","maxRows":1000}}
                ]}
                """, "daha önce");
    }

    @Test
    void rejectsProcedureV2RowInsertWithoutNamedBinds() {
        assertValidationContains(DefinitionType.PROCEDURE, 2, """
                {"tasks":[
                  {"id":"READ_SOURCE","type":"SQL","connectionRole":"SOURCE",
                   "riskClass":"READ_ONLY","command":"SELECT ID FROM S",
                   "output":{"kind":"ROWSET","maxRows":1000}},
                  {"id":"WRITE_TARGET","type":"SQL","connectionRole":"TARGET",
                   "riskClass":"DML","command":"INSERT INTO T (ID) VALUES (1)",
                   "input":{"fromTask":"READ_SOURCE","mode":"BATCH","batchSize":250}}
                ]}
                """, "named bind");
    }

    @Test
    void rejectsProcedureV2BindsThatOnlyAppearInsideCommentsOrLiterals() {
        for (String command : List.of(
                "INSERT INTO T (ID) VALUES (1) /* :ID */",
                "INSERT INTO T (ID) VALUES (':ID')",
                "INSERT INTO T (ID) VALUES (q'[ :ID ]')")) {
            assertValidationContains(DefinitionType.PROCEDURE, 2, """
                    {"tasks":[
                      {"id":"READ_SOURCE","type":"SQL","connectionRole":"SOURCE",
                       "riskClass":"READ_ONLY","command":"SELECT ID FROM S",
                       "output":{"kind":"ROWSET","maxRows":1000}},
                      {"id":"WRITE_TARGET","type":"SQL","connectionRole":"TARGET",
                       "riskClass":"DML","command":"%s",
                       "input":{"fromTask":"READ_SOURCE","mode":"BATCH","batchSize":250}}
                    ]}
                    """.formatted(command), "named bind");
        }
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
    void acceptsExplicitAtomicDeleteInsertStrategy() {
        assertDoesNotThrow(() -> validate(DefinitionType.MAPPING, 2,
                validMapping().replace(
                        "\"MERGE\",\"key\":[\"ID\"],\"deleteMissing\":false",
                        "\"ATOMIC_DELETE_INSERT\"")));
        assertValidationContains(
                DefinitionType.MAPPING, 1,
                validMapping().replace(
                        "\"MERGE\",\"key\":[\"ID\"],\"deleteMissing\":false",
                        "\"ATOMIC_DELETE_INSERT\""),
                "desteklenmeyen");
    }

    @Test
    void rejectsSchemaVersionTwoForNonMappingDefinitions() {
        assertValidationContains(
                DefinitionType.SEQUENCE,
                2,
                "{\"implementation\":\"REPOSITORY\",\"start\":1,\"increment\":1,\"cycle\":false}",
                "yalnız MAPPING");
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
        assertValidationContains(type, 1, content, messagePart);
    }

    private void assertValidationContains(
            DefinitionType type, int schemaVersion, String content, String messagePart) {
        ApiException exception = assertThrows(
                ApiException.class, () -> validate(type, schemaVersion, content));
        assertEquals("VALIDATION_FAILED", exception.code());
        assertTrue(exception.getMessage().contains(messagePart), exception.getMessage());
    }

    private void validate(DefinitionType type, String content) {
        validate(type, 1, content);
    }

    private void validate(DefinitionType type, int schemaVersion, String content) {
        validator.validate(type, schemaVersion, json(content));
    }

    private JsonNode json(String value) {
        return objectMapper.readTree(value);
    }
}

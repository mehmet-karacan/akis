package tr.com.innova.akis.execution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import tr.com.innova.akis.execution.ProcedureRuntimePlan.BatchInput;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.ConnectionRole;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.ErrorPolicy;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.LogCounter;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.TransactionMode;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.TransactionIsolation;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.CommitMode;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.RiskClass;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.RowsetOutput;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.Task;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.TaskBinding;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.TaskType;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.ParameterType;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.ParameterValue;
import tr.com.innova.akis.execution.ProcedureRuntimePlan.ParameterSource;
import tr.com.innova.akis.metadata.DefinitionContentValidator;
import tr.com.innova.akis.metadata.DefinitionType;
import tr.com.innova.akis.metadata.NamedBindParser;
import tr.com.innova.akis.projectbundle.SecretValueSanitizer;

/** Resolves the first bounded, single-source/single-target Oracle Procedure runtime. */
@Component
public final class ProcedureRuntimePlanResolver {

    public static final String CAPABILITY = "ORACLE_PROCEDURE_V1";

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern ORACLE_IDENTIFIER = Pattern.compile("[A-Z][A-Z0-9_$#]{0,127}");

    private final ObjectMapper objectMapper;
    private final SecretValueSanitizer secretSanitizer;
    private final DefinitionContentValidator contentValidator;

    public ProcedureRuntimePlanResolver(
            ObjectMapper objectMapper,
            SecretValueSanitizer secretSanitizer,
            DefinitionContentValidator contentValidator) {
        this.objectMapper = objectMapper;
        this.secretSanitizer = secretSanitizer;
        this.contentValidator = contentValidator;
    }

    public boolean isProcedureCandidate(JsonNode scenarioPlan) {
        try {
            ScenarioSource source = scenarioSource(requireObject(
                    scenarioPlan, ProcedurePlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED,
                    "Scenario plan"));
            rejectSecrets(scenarioPlan);
            parseTasks(source.definition());
            return true;
        }
        catch (ProcedureRuntimePlanException exception) {
            return false;
        }
    }

    /** Returns true when the Scenario explicitly opts into the Procedure V2 contract. */
    public boolean isProcedureV2(JsonNode scenarioPlan) {
        return scenarioPlan != null
                && scenarioPlan.isObject()
                && "PROCEDURE".equals(scenarioPlan.path("executable").path("kind").asString())
                && "PROCEDURE".equals(scenarioPlan.path("source").path("definitionType").asString())
                && scenarioPlan.path("source").path("schemaVersion").isIntegralNumber()
                && scenarioPlan.path("source").path("schemaVersion").intValue() == 2;
    }

    public boolean requiresApprovalForPublication(JsonNode scenarioPlan) {
        ScenarioSource source = scenarioSource(requireObject(
                scenarioPlan, ProcedurePlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED,
                "Scenario plan"));
        rejectSecrets(scenarioPlan);
        return parseTasks(source.definition()).stream().anyMatch(Task::requiresApproval);
    }

    public String compileHashForPublication(
            String expectedScenarioPlanHash,
            JsonNode scenarioPlan,
            JsonNode unsignedManifestCore) {
        requireHash(expectedScenarioPlanHash, "Expected scenario plan hash");
        ObjectNode verifiedPlan = verifiedScenarioPlan(expectedScenarioPlanHash, scenarioPlan);
        ScenarioSource source = scenarioSource(verifiedPlan);
        ObjectNode manifest = requireObject(
                unsignedManifestCore, ProcedurePlanFailure.INVALID_PUBLICATION_MANIFEST,
                "Unsigned physical manifest core");
        rejectSecrets(manifest);
        requireManifestCore(manifest, expectedScenarioPlanHash, false);
        return compileVerified(source, expectedScenarioPlanHash, manifest, null).runtimePlanHash();
    }

    public ProcedureRuntimePlan resolve(
            String expectedReleaseHash,
            String expectedScenarioPlanHash,
            JsonNode scenarioPlan,
            JsonNode physicalManifest) {
        requireHash(expectedReleaseHash, "Expected release hash");
        requireHash(expectedScenarioPlanHash, "Expected scenario plan hash");
        ScenarioSource source = scenarioSource(
                verifiedScenarioPlan(expectedScenarioPlanHash, scenarioPlan));
        ObjectNode manifest = requireObject(
                physicalManifest, ProcedurePlanFailure.INVALID_PUBLICATION_MANIFEST,
                "Physical manifest");
        rejectSecrets(manifest);
        requireManifestIntegrity(manifest, expectedReleaseHash, expectedScenarioPlanHash);
        ProcedureRuntimePlan compiled = compileVerified(
                source, expectedScenarioPlanHash, manifest, expectedReleaseHash);
        String publishedHash = requireText(manifest, "runtimePlanHash");
        requireHash(publishedHash, "Runtime plan hash");
        if (!publishedHash.equals(compiled.runtimePlanHash())) {
            throw failure(
                    ProcedurePlanFailure.RUNTIME_PLAN_INTEGRITY_FAILED,
                    "The Procedure runtime plan does not match the published hash.");
        }
        return compiled;
    }

    private ObjectNode verifiedScenarioPlan(String expectedHash, JsonNode scenarioPlan) {
        ObjectNode plan = requireObject(
                scenarioPlan, ProcedurePlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED,
                "Scenario plan");
        rejectSecrets(plan);
        if (!expectedHash.equals(sha256(canonicalize(plan).toString()))) {
            throw failure(
                    ProcedurePlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED,
                    "The Scenario plan content does not match its pinned hash.");
        }
        return plan;
    }

    private ProcedureRuntimePlan compileVerified(
            ScenarioSource source,
            String scenarioPlanHash,
            ObjectNode manifest,
            String releaseHash) {
        validateManifestDefinition(source, manifest);
        List<Task> tasks = parseTasks(source.definition());
        if (manifest.has("variableBindings") && !manifest.get("variableBindings").isObject()) {
            throw shape("Variable bindings must be an object.");
        }
        for (Task task : tasks) for (ParameterValue parameter : task.parameters().values()) {
            if (parameter.logicalSchemaUuid() == null) continue; // Legacy plans are rejected at execution, not rewritten.
            JsonNode binding = manifest.path("variableBindings").path(String.valueOf(parameter.definitionUuid()));
            if (!binding.isObject()
                    || !parameter.logicalSchemaUuid().toString().equals(binding.path("logicalSchemaUuid").asText())
                    || !java.util.Objects.equals(parameter.refreshQuery(), binding.path("query").asText())
                    || !parameter.type().name().equals(binding.path("type").asText())
                    || !parameter.historyMode().equals(binding.path("historyMode").asText())
                    || !manifest.path("environment").path("environmentUuid").asText().equals(binding.path("environmentUuid").asText())) {
                throw shape("Variable binding does not match its published definition and environment.");
            }
            for (String field : List.of("projectUuid", "connectionVersionUuid", "physicalSchemaUuid")) {
                try { UUID.fromString(binding.path(field).asText()); }
                catch (IllegalArgumentException invalid) { throw shape("Variable binding identity is invalid."); }
            }
        }
        boolean approvalRequired = tr.com.innova.akis.publication.ApprovalPolicyEvaluator.requiresApproval(
                manifest.path("environment").path("risk").asText(),
                tasks.stream().anyMatch(Task::requiresApproval));
        if (!manifest.path("approvalRequired").isBoolean()
                || manifest.path("approvalRequired").booleanValue() != approvalRequired) {
            throw failure(
                    ProcedurePlanFailure.INVALID_PUBLICATION_MANIFEST,
                    "Procedure approval evidence does not match its tasks.");
        }
        Map<String, TaskBinding> bindings = manifestBindings(manifest, tasks);
        validateTopologyShape(tasks, bindings);
        validateBoundCommands(tasks, bindings);

        ObjectNode plan = objectMapper.createObjectNode();
        plan.set("bindings", bindingNodes(tasks, bindings));
        plan.set("definition", definitionNode(source));
        plan.set("limits", limitsNode());
        plan.put("planVersion", ProcedureRuntimePlan.CURRENT_VERSION);
        // Preserve hashes of historical unversioned manifests; never rewrite stored releases.
        if (manifest.has("policyVersions")) plan.set("policyVersions", manifest.get("policyVersions").deepCopy());
        plan.set("release", releaseNode(manifest, scenarioPlanHash));
        plan.set("tasks", taskNodes(tasks));
        if (manifest.has("variableBindings")) plan.set("variableBindings", manifest.get("variableBindings").deepCopy());
        JsonNode canonicalPlan = canonicalize(plan);
        String runtimePlanHash = sha256(canonicalPlan.toString());
        return new ProcedureRuntimePlan(
                ProcedureRuntimePlan.CURRENT_VERSION,
                runtimePlanHash,
                releaseHash,
                scenarioPlanHash,
                source.definitionUuid(),
                source.definitionVersionUuid(),
                tasks,
                bindings,
                canonicalPlan);
    }

    private ScenarioSource scenarioSource(ObjectNode plan) {
        requireOnlyFields(
                plan, Set.of("compiler", "compilerVersion", "executable", "source"),
                "Scenario plan", ProcedurePlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED);
        if (!"AKIS".equals(requireText(
                plan, "compiler", ProcedurePlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED))
                || requireInteger(
                        plan, "compilerVersion",
                        ProcedurePlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED) != 2) {
            throw failure(
                    ProcedurePlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED,
                    "Only AKIS Scenario compiler version 2 is supported.");
        }
        ObjectNode executable = requireObject(
                plan.get("executable"), ProcedurePlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED,
                "scenarioPlan.executable");
        requireOnlyFields(
                executable, Set.of("kind", "definition"), "Scenario executable",
                ProcedurePlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED);
        if (!"PROCEDURE".equals(requireText(
                executable, "kind", ProcedurePlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED))) {
            throw failure(
                    ProcedurePlanFailure.UNSUPPORTED_DEFINITION_TYPE,
                    "The Oracle Procedure runtime executes only PROCEDURE definitions.");
        }
        ObjectNode source = requireObject(
                plan.get("source"), ProcedurePlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED,
                "scenarioPlan.source");
        requireOnlyFields(
                source,
                Set.of("contentHash", "definitionType", "definitionUuid",
                        "definitionVersion", "definitionVersionUuid", "schemaVersion"),
                "Scenario source", ProcedurePlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED);
        if (!"PROCEDURE".equals(requireText(
                source, "definitionType", ProcedurePlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED))) {
            throw failure(
                    ProcedurePlanFailure.UNSUPPORTED_DEFINITION_TYPE,
                    "The Scenario source must be a PROCEDURE definition.");
        }
        int schemaVersion = requireInteger(
                source, "schemaVersion", ProcedurePlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED);
        if (schemaVersion != 2) {
            throw shape("The Oracle Procedure runtime requires schema version 2.");
        }
        int definitionVersion = requireInteger(
                source, "definitionVersion", ProcedurePlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED);
        if (definitionVersion <= 0) {
            throw failure(
                    ProcedurePlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED,
                    "Definition version must be positive.");
        }
        String contentHash = requireText(
                source, "contentHash", ProcedurePlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED);
        requireHash(contentHash, "Definition content hash");
        ObjectNode definition = requireObject(
                executable.get("definition"),
                ProcedurePlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED,
                "scenarioPlan.executable.definition");
        if (!contentHash.equals(sha256(canonicalize(definition).toString()))) {
            throw failure(
                    ProcedurePlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED,
                    "The executable Procedure does not match source.contentHash.");
        }
        try {
            contentValidator.validate(DefinitionType.PROCEDURE, schemaVersion, definition);
        }
        catch (RuntimeException exception) {
            throw shape("The Procedure definition failed schema validation.");
        }
        return new ScenarioSource(
                requireUuid(source, "definitionUuid", ProcedurePlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED),
                requireUuid(source, "definitionVersionUuid", ProcedurePlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED),
                definitionVersion,
                schemaVersion,
                contentHash,
                definition);
    }

    private List<Task> parseTasks(ObjectNode definition) {
        requireOnlyFields(
                definition, Set.of("tasks", "parameterSchema", "ui", "layout", "editor", "technology"),
                "Procedure definition", ProcedurePlanFailure.UNSUPPORTED_PROCEDURE_SHAPE);
        JsonNode nodes = definition.get("tasks");
        if (nodes == null || !nodes.isArray() || nodes.isEmpty()
                || nodes.size() > ProcedureRuntimePlan.MAXIMUM_TASKS) {
            throw shape("Procedure task count is outside the runtime limit.");
        }
        List<Task> tasks = new ArrayList<>();
        for (int index = 0; index < nodes.size(); index++) {
            ObjectNode node = requireObject(
                    nodes.get(index), ProcedurePlanFailure.UNSUPPORTED_PROCEDURE_SHAPE,
                    "Procedure task");
            requireOnlyFields(
                    node,
                    Set.of("id", "name", "type", "connectionRole", "riskClass",
                            "command", "requiresApproval", "onError", "timeoutSeconds",
                            "output", "input", "logCounter", "transactionMode",
                            "transactionChannel", "transactionIsolation", "commitMode",
                            "logicalSchemaUuid", "environmentUuid", "parameters", "enabled"),
                    "Procedure task", ProcedurePlanFailure.UNSUPPORTED_PROCEDURE_SHAPE);
            // ODI "Execute" flag: a disabled task is authored but never part of the runtime plan.
            if (node.has("enabled") && !node.get("enabled").isNull() && !node.get("enabled").asBoolean(true)) {
                continue;
            }
            String id = requireText(node, "id");
            String command = requireText(node, "command");
            if (command.getBytes(StandardCharsets.UTF_8).length
                    > ProcedureRuntimePlan.MAXIMUM_COMMAND_BYTES) {
                throw shape("Procedure command exceeds the runtime byte limit.");
            }
            ConnectionRole role = parseEnum(
                    ConnectionRole.class, requireText(node, "connectionRole"));
            RiskClass risk = parseEnum(RiskClass.class, requireText(node, "riskClass"));
            TaskType type = parseEnum(TaskType.class, requireText(node, "type"));
            boolean requiresApproval = optionalBoolean(node, "requiresApproval");
            validateCommandContract(type, role, risk, requiresApproval, command);
            int timeout = node.has("timeoutSeconds")
                    ? requireInteger(node, "timeoutSeconds")
                    : ProcedureRuntimePlan.MAXIMUM_TIMEOUT_SECONDS;
            if (timeout < 1 || timeout > ProcedureRuntimePlan.MAXIMUM_TIMEOUT_SECONDS) {
                throw shape("Procedure task timeout exceeds the runtime limit.");
            }
            RowsetOutput output = parseOutput(node.get("output"));
            BatchInput input = parseInput(node.get("input"));
            ErrorPolicy errorPolicy = node.has("onError")
                    ? parseEnum(ErrorPolicy.class, requireText(node, "onError"))
                    : ErrorPolicy.STOP;
            LogCounter logCounter = parseLogCounter(optionalText(node, "logCounter", "NONE"));
            validateLogCounter(type, role, command, logCounter);
            TransactionMode transactionMode = node.has("transactionMode")
                    ? parseEnum(TransactionMode.class, requireText(node, "transactionMode"))
                    : TransactionMode.AUTOCOMMIT;
            TransactionIsolation transactionIsolation = node.has("transactionIsolation")
                    ? parseEnum(TransactionIsolation.class, requireText(node, "transactionIsolation"))
                    : TransactionIsolation.DRIVER_DEFAULT;
            CommitMode commitMode = node.has("commitMode")
                    ? parseEnum(CommitMode.class, requireText(node, "commitMode"))
                    : CommitMode.COMMIT;
            Integer transactionChannel = node.has("transactionChannel")
                    ? requireInteger(node, "transactionChannel") : null;
            validateTransactionContract(role, risk, transactionMode, transactionChannel, commitMode);
            if ((output != null || input != null) && errorPolicy != ErrorPolicy.STOP) {
                throw shape("Row transfer tasks must use STOP in Procedure V1.");
            }
            List<String> namedBinds;
            try {
                namedBinds = NamedBindParser.parse(command);
            }
            catch (IllegalArgumentException exception) {
                throw shape("Procedure command contains malformed quoting or comments.");
            }
            Map<String, ParameterValue> parameters = parseParameters(node.get("parameters"));
            if (input == null && !parameters.keySet().containsAll(namedBinds)) {
                throw shape("Every named bind must have a typed parameter value.");
            }
            tasks.add(new Task(
                    id,
                    optionalText(node, "name", id),
                    type,
                    role,
                    risk,
                    command,
                    sha256(command),
                    requiresApproval,
                    errorPolicy,
                    timeout,
                    output,
                    input,
                    namedBinds,
                    parameters,
                    logCounter,
                    transactionMode,
                    transactionChannel,
                    transactionIsolation,
                    commitMode));
        }
        if (tasks.isEmpty()) {
            throw shape("Every procedure task is disabled; enable at least one task.");
        }
        for (int index = 0; index < tasks.size(); index++) {
            Task task = tasks.get(index);
            if (task.output() != null) {
                if (index + 1 >= tasks.size()
                        || tasks.get(index + 1).input() == null
                        || !task.id().equals(tasks.get(index + 1).input().fromTask())) {
                    throw shape("A rowset producer must be followed immediately by its consumer.");
                }
            }
            if (task.input() != null
                    && (index == 0 || tasks.get(index - 1).output() == null
                    || !task.input().fromTask().equals(tasks.get(index - 1).id()))) {
                throw shape("A rowset consumer must immediately follow its producer.");
            }
        }
        return List.copyOf(tasks);
    }

    private Map<String, ParameterValue> parseParameters(JsonNode node) {
        if (node == null || node.isNull()) return Map.of();
        ObjectNode object = requireObject(node, ProcedurePlanFailure.UNSUPPORTED_PROCEDURE_SHAPE,
                "Procedure task parameters");
        Map<String, ParameterValue> result = new LinkedHashMap<>();
        object.properties().forEach(entry -> {
            if (!entry.getKey().matches("[A-Za-z][A-Za-z0-9_]{0,127}")) {
                throw shape("Procedure parameter name is invalid.");
            }
            ObjectNode value = requireObject(entry.getValue(),
                    ProcedurePlanFailure.UNSUPPORTED_PROCEDURE_SHAPE, "Procedure parameter");
            ParameterType type = parseEnum(ParameterType.class, requireText(value, "type"));
            ParameterSource source = value.has("valueSource")
                    ? parseEnum(ParameterSource.class, requireText(value, "valueSource"))
                    : ParameterSource.VALUE;
            String text = source == ParameterSource.VALUE ? requireText(value, "value") : null;
            String refreshQuery = source == ParameterSource.REFRESH_QUERY
                    ? requireText(value, "query") : null;
            UUID definitionUuid = value.has("definitionUuid")
                    ? UUID.fromString(requireText(value, "definitionUuid")) : null;
            if (source == ParameterSource.REFRESH_QUERY) {
                try { tr.com.innova.akis.metadata.VariableQueryPolicy.validate(refreshQuery, type.name()); }
                catch (tr.com.innova.akis.metadata.ApiException invalid) { throw shape(invalid.getMessage()); }
            }
            try {
                if (source == ParameterSource.VALUE) switch (type) {
                    case INTEGER -> Long.parseLong(text);
                    case DECIMAL -> new java.math.BigDecimal(text);
                    case BOOLEAN -> { if (!"true".equalsIgnoreCase(text) && !"false".equalsIgnoreCase(text)) throw new IllegalArgumentException(); }
                    case DATE -> java.time.LocalDate.parse(text);
                    case TIMESTAMP -> java.time.LocalDateTime.parse(text);
                    case STRING -> { }
                }
            } catch (RuntimeException invalid) {
                throw shape("Procedure parameter value does not match its type.");
            }
            String normalizedName = entry.getKey().toUpperCase(java.util.Locale.ROOT);
            if (result.putIfAbsent(normalizedName, new ParameterValue(
                    type, text, source, refreshQuery, definitionUuid,
                    value.hasNonNull("logicalSchemaUuid") && !value.path("logicalSchemaUuid").asText().isBlank()
                        ? UUID.fromString(value.path("logicalSchemaUuid").asText()) : null,
                    value.path("historyMode").asText("NONE"))) != null) {
                throw shape("Procedure parameter names must be unique ignoring case.");
            }
        });
        return Map.copyOf(result);
    }

    private void validateCommandContract(
            TaskType type,
            ConnectionRole role,
            RiskClass risk,
            boolean requiresApproval,
            String command) {
        try {
            var policy = SqlStatementPolicy.inspect(command, role.name());
            if (!policy.type().equals(type.name()) || !policy.riskClass().equals(risk.name())
                    || policy.requiresApproval() && !requiresApproval) {
                throw shape("Procedure SQL metadata does not match the server policy.");
            }
        } catch (IllegalArgumentException invalid) {
            throw shape("Procedure SQL does not satisfy the server policy.");
        }
    }

    private LogCounter parseLogCounter(String value) {
        if ("ANALYSIS".equals(value) || "STATISTICS".equals(value)) {
            return LogCounter.NONE;
        }
        return parseEnum(LogCounter.class, value);
    }

    private void validateLogCounter(
            TaskType type,
            ConnectionRole role,
            String command,
            LogCounter counter) {
        if (counter == LogCounter.NONE) return;
        if (type != TaskType.SQL || role != ConnectionRole.TARGET) {
            throw shape("Log counters apply only to target SQL commands.");
        }
        String keyword = firstKeyword(executableSql(command));
        boolean valid = switch (counter) {
            case INSERT -> "INSERT".equals(keyword);
            case UPDATE -> "UPDATE".equals(keyword);
            case DELETE -> "DELETE".equals(keyword);
            case ERRORS -> "INSERT".equals(keyword) || "UPDATE".equals(keyword);
            case NONE -> true;
        };
        if (!valid) throw shape("The log counter does not match the target SQL command.");
    }

    private void validateTransactionContract(
            ConnectionRole role,
            RiskClass risk,
            TransactionMode mode,
            Integer channel,
            CommitMode commitMode) {
        if (mode == TransactionMode.AUTOCOMMIT) {
            if (channel != null || commitMode != CommitMode.COMMIT) {
                throw shape("Autocommit tasks cannot select a transaction channel or deferred commit.");
            }
            return;
        }
        if (role != ConnectionRole.TARGET || risk != RiskClass.DML
                || channel == null || channel < 0 || channel > 9) {
            throw shape("Managed transactions require a target DML command and channel 0-9.");
        }
    }

    /** Removes comments and literals while retaining executable punctuation and keywords. */
    private String executableSql(String sql) {
        try {
            return NamedBindParser.compile(sql).executableSql();
        } catch (IllegalArgumentException invalid) {
            throw shape("Procedure command contains malformed quoting or comments.");
        }
    }

    private String firstKeyword(String executable) {
        int index = 0;
        while (index < executable.length() && Character.isWhitespace(executable.charAt(index))) index++;
        int start = index;
        while (index < executable.length() && Character.isLetter(executable.charAt(index))) index++;
        if (start == index) throw shape("Procedure SQL command must begin with a supported keyword.");
        return executable.substring(start, index);
    }

    private RowsetOutput parseOutput(JsonNode value) {
        if (value == null || value.isNull()) return null;
        ObjectNode output = requireObject(
                value, ProcedurePlanFailure.UNSUPPORTED_PROCEDURE_SHAPE,
                "Procedure rowset output");
        requireOnlyFields(
                output, Set.of("kind", "maxRows"), "Procedure rowset output",
                ProcedurePlanFailure.UNSUPPORTED_PROCEDURE_SHAPE);
        if (!"ROWSET".equals(requireText(output, "kind"))) {
            throw shape("Unsupported Procedure output kind.");
        }
        int maximumRows = requireInteger(output, "maxRows");
        if (maximumRows < 1 || maximumRows > ProcedureRuntimePlan.MAXIMUM_ROWSET_ROWS) {
            throw shape("Procedure rowset exceeds the runtime row limit.");
        }
        return new RowsetOutput(maximumRows);
    }

    private BatchInput parseInput(JsonNode value) {
        if (value == null || value.isNull()) return null;
        ObjectNode input = requireObject(
                value, ProcedurePlanFailure.UNSUPPORTED_PROCEDURE_SHAPE,
                "Procedure batch input");
        requireOnlyFields(
                input, Set.of("fromTask", "mode", "batchSize"), "Procedure batch input",
                ProcedurePlanFailure.UNSUPPORTED_PROCEDURE_SHAPE);
        if (!"BATCH".equals(requireText(input, "mode"))) {
            throw shape("Unsupported Procedure input mode.");
        }
        int batchSize = requireInteger(input, "batchSize");
        if (batchSize < 1 || batchSize > 1_000) {
            throw shape("Procedure batch size is outside the runtime limit.");
        }
        return new BatchInput(requireText(input, "fromTask"), batchSize);
    }

    private void validateManifestDefinition(ScenarioSource source, ObjectNode manifest) {
        ObjectNode definition = requireObject(
                manifest.get("definition"), ProcedurePlanFailure.INVALID_PUBLICATION_MANIFEST,
                "manifest.definition");
        requireOnlyFields(
                definition,
                Set.of("contentHash", "definitionUuid", "definitionVersionUuid", "schemaVersion"),
                "Manifest definition", ProcedurePlanFailure.INVALID_PUBLICATION_MANIFEST);
        if (!source.definitionUuid().equals(requireUuid(definition, "definitionUuid"))
                || !source.definitionVersionUuid().equals(requireUuid(definition, "definitionVersionUuid"))
                || source.schemaVersion() != requireInteger(definition, "schemaVersion")
                || !source.contentHash().equals(requireText(definition, "contentHash"))) {
            throw failure(
                    ProcedurePlanFailure.DEFINITION_BINDING_MISMATCH,
                    "Scenario Procedure does not match the manifest definition.");
        }
    }

    private Map<String, TaskBinding> manifestBindings(ObjectNode manifest, List<Task> tasks) {
        JsonNode nodes = manifest.get("bindings");
        if (nodes == null || !nodes.isArray() || nodes.size() != tasks.size()) {
            throw bindingFailure("Manifest must contain exactly one binding per Procedure task.");
        }
        Map<String, Task> tasksById = new HashMap<>();
        tasks.forEach(task -> tasksById.put(task.id(), task));
        Map<String, TaskBinding> bindings = new LinkedHashMap<>();
        for (JsonNode value : nodes) {
            ObjectNode node = requireObject(
                    value, ProcedurePlanFailure.INVALID_PUBLICATION_MANIFEST,
                    "Manifest binding");
            requireOnlyFields(
                    node,
                    Set.of("bindingVersion", "connectionVersionUuid", "databaseType",
                            "dataObjectReference", "dataObjectType", "dataObjectUuid",
                            "definitionDataObjectUuid", "environmentSchemaBindingUuid",
                            "nodeCode", "physicalIdentity", "physicalSchemaReference",
                            "physicalSchemaUuid", "role", "schemaSnapshotFingerprint",
                            "schemaSnapshotUuid"),
                    "Manifest binding", ProcedurePlanFailure.INVALID_PUBLICATION_MANIFEST);
            String taskId = requireText(node, "nodeCode");
            Task task = tasksById.get(taskId);
            if (task == null || bindings.containsKey(taskId)) {
                throw bindingFailure("Manifest contains an unknown or duplicate Procedure task binding.");
            }
            String expectedRole = task.connectionRole() == ConnectionRole.SOURCE ? "KAYNAK" : "HEDEF";
            if (!expectedRole.equals(requireText(node, "role"))) {
                throw bindingFailure("Manifest binding role does not match Procedure task " + taskId + ".");
            }
            String databaseType = requireText(node, "databaseType");
            if (!Set.of("ORACLE", "POSTGRESQL").contains(databaseType)) {
                throw shape("Procedure V1 supports only Oracle and PostgreSQL bindings.");
            }
            String objectType = requireText(node, "dataObjectType");
            if (!("TABLO".equals(objectType) || "VIEW".equals(objectType))
                    || task.connectionRole() == ConnectionRole.TARGET && !"TABLO".equals(objectType)) {
                throw shape("Procedure binding uses an unsupported data object type.");
            }
            String physicalIdentity = requireText(node, "physicalIdentity");
            String[] identity = physicalIdentity.split("\\.", -1);
            if (identity.length != 2
                    || !ORACLE_IDENTIFIER.matcher(identity[0]).matches()
                    || !ORACLE_IDENTIFIER.matcher(identity[1]).matches()
                    || !identity[0].equals(requireText(node, "physicalSchemaReference"))
                    || !identity[1].equals(requireText(node, "dataObjectReference"))) {
                throw shape("Procedure bindings require canonical OWNER.OBJECT identities.");
            }
            String fingerprint = requireText(node, "schemaSnapshotFingerprint");
            requireHash(fingerprint, "Schema snapshot fingerprint");
            long bindingVersion = requireLong(node, "bindingVersion");
            if (bindingVersion <= 0) throw bindingFailure("Binding version must be positive.");
            bindings.put(taskId, new TaskBinding(
                    taskId,
                    task.connectionRole(),
                    requireUuid(node, "definitionDataObjectUuid"),
                    requireUuid(node, "dataObjectUuid"),
                    requireUuid(node, "environmentSchemaBindingUuid"),
                    requireUuid(node, "physicalSchemaUuid"),
                    requireUuid(node, "connectionVersionUuid"),
                    requireUuid(node, "schemaSnapshotUuid"),
                    bindingVersion,
                    fingerprint,
                    physicalIdentity,
                    identity[0],
                    identity[1],
                    objectType));
        }
        return Map.copyOf(bindings);
    }

    private void validateTopologyShape(List<Task> tasks, Map<String, TaskBinding> bindings) {
        Set<UUID> sourceConnections = new HashSet<>();
        Set<TargetIdentity> targetIdentities = new HashSet<>();
        for (Task task : tasks) {
            TaskBinding binding = bindings.get(task.id());
            if (task.connectionRole() == ConnectionRole.SOURCE) {
                sourceConnections.add(binding.connectionVersionUuid());
            }
            else {
                targetIdentities.add(new TargetIdentity(
                        binding.dataObjectUuid(),
                        binding.environmentSchemaBindingUuid(),
                        binding.physicalSchemaUuid(),
                        binding.connectionVersionUuid(),
                        binding.schemaSnapshotUuid(),
                        binding.schemaSnapshotFingerprint(),
                        binding.physicalIdentity()));
            }
        }
        if (sourceConnections.size() != 1 || targetIdentities.size() != 1) {
            throw shape("Procedure V1 requires one SOURCE connection and one TARGET object/connection.");
        }
    }

    private void validateBoundCommands(List<Task> tasks, Map<String, TaskBinding> bindings) {
        for (Task task : tasks) {
            TaskBinding binding = bindings.get(task.id());
            if (task.connectionRole() == ConnectionRole.SOURCE) {
                validateBoundSourceSelect(task, binding);
            }
            else {
                validateBoundTargetCommand(task, binding);
            }
        }
    }

    private void validateBoundSourceSelect(Task task, TaskBinding binding) {
        try {
            ProcedureOracleSourceSqlContract.validateShape(task, binding);
        } catch (IllegalArgumentException invalid) {
            throw shape("Procedure V1 SELECT must match its pinned source and typed bind contract.");
        }
    }

    private void validateBoundTargetCommand(Task task, TaskBinding binding) {
        if (task.output() != null) {
            throw shape("Procedure V1 target tasks cannot publish rowset output.");
        }
        String identifier = Pattern.quote(binding.physicalIdentity());
        if (task.type() == TaskType.SQL && task.riskClass() == RiskClass.DML) {
            if (task.input() == null || task.namedBinds().isEmpty()) {
                throw shape("Procedure V1 INSERT requires an adjacent rowset input and named binds.");
            }
            Pattern insert = Pattern.compile(
                    "^INSERT\\s+INTO\\s+" + identifier
                            + "\\s*\\(\\s*(?<columns>[A-Z][A-Z0-9_$#]*(?:\\s*,\\s*[A-Z][A-Z0-9_$#]*)*)\\s*\\)"
                            + "\\s*VALUES\\s*\\(\\s*(?<values>:[A-Z][A-Z0-9_$#]*(?:\\s*,\\s*:[A-Z][A-Z0-9_$#]*)*)\\s*\\)$",
                    Pattern.CASE_INSENSITIVE);
            var match = insert.matcher(executableSql(task.command()));
            if (!match.matches()) {
                throw shape("Procedure V1 INSERT must write only to its bound target object.");
            }
            List<String> columns = commaSeparated(match.group("columns"));
            List<String> values = commaSeparated(match.group("values")).stream()
                    .map(value -> value.substring(1))
                    .toList();
            if (!columns.equals(values) || !values.equals(task.namedBinds())) {
                throw shape("Procedure V1 INSERT columns and named binds must match in order.");
            }
            return;
        }
        if (task.type() == TaskType.SQL && task.riskClass() == RiskClass.DESTRUCTIVE) {
            if (task.input() != null || !task.namedBinds().isEmpty()
                    || !Pattern.compile(
                            "^TRUNCATE\\s+TABLE\\s+" + identifier + "$",
                            Pattern.CASE_INSENSITIVE)
                    .matcher(executableSql(task.command())).matches()) {
                throw shape("Procedure V1 destructive SQL is limited to its bound target TRUNCATE.");
            }
            return;
        }
        if (task.type() == TaskType.PLSQL && task.riskClass() == RiskClass.DESTRUCTIVE) {
            String owner = Pattern.quote(binding.owner());
            String objectName = Pattern.quote(binding.objectName());
            Pattern gatherStats = Pattern.compile(
                    "^\\s*BEGIN\\s+DBMS_STATS\\.GATHER_TABLE_STATS\\s*\\(\\s*"
                            + "(?:OWNNAME\\s*=>\\s*)?'" + owner + "'\\s*,\\s*"
                            + "(?:TABNAME\\s*=>\\s*)?'" + objectName + "'\\s*\\)\\s*;\\s*END\\s*;\\s*$",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            if (task.input() != null || !task.namedBinds().isEmpty()
                    || !gatherStats.matcher(task.command()).matches()) {
                throw shape("Procedure V1 PL/SQL is limited to approved statistics gathering for its bound target.");
            }
            return;
        }
        throw shape("Procedure V1 target task type is not executable.");
    }

    private List<String> commaSeparated(String value) {
        return Pattern.compile("\\s*,\\s*").splitAsStream(value.trim())
                .map(part -> part.toUpperCase(java.util.Locale.ROOT))
                .toList();
    }

    private void requireManifestIntegrity(
            ObjectNode manifest, String releaseHash, String scenarioPlanHash) {
        if (!releaseHash.equals(requireText(manifest, "releaseHash"))) {
            throw failure(
                    ProcedurePlanFailure.RELEASE_INTEGRITY_FAILED,
                    "Requested release hash does not match the Procedure manifest.");
        }
        ObjectNode unsigned = manifest.deepCopy();
        unsigned.remove("releaseHash");
        if (!releaseHash.equals(sha256(canonicalize(unsigned).toString()))) {
            throw failure(
                    ProcedurePlanFailure.RELEASE_INTEGRITY_FAILED,
                    "Procedure manifest content does not match its release hash.");
        }
        requireManifestCore(manifest, scenarioPlanHash, true);
    }

    private void requireManifestCore(
            ObjectNode manifest, String scenarioPlanHash, boolean signed) {
        if (requireInteger(manifest, "manifestVersion") != 2) {
            throw failure(
                    ProcedurePlanFailure.INVALID_PUBLICATION_MANIFEST,
                    "Procedure runtime requires manifest version 2.");
        }
        Set<String> fields = new HashSet<>(Set.of(
                "approvalRequired", "bindings", "definition", "environment", "manifestVersion",
                "runtimeCapability", "scenario", "policyVersions", "variableBindings"));
        if (signed) {
            fields.add("releaseHash");
            fields.add("runtimePlanHash");
        }
        requireOnlyFields(
                manifest, fields, "Physical manifest",
                ProcedurePlanFailure.INVALID_PUBLICATION_MANIFEST);
        if (manifest.has("policyVersions")) {
            try {
                ProcedurePolicyVersions.requireSupported(manifest.get("policyVersions"));
            }
            catch (IllegalArgumentException exception) {
                throw failure(ProcedurePlanFailure.INVALID_PUBLICATION_MANIFEST, exception.getMessage());
            }
        }
        if (!CAPABILITY.equals(requireText(manifest, "runtimeCapability"))) {
            throw failure(
                    ProcedurePlanFailure.INVALID_PUBLICATION_MANIFEST,
                    "Release is not an Oracle Procedure V1 plan.");
        }
        ObjectNode scenario = requireObject(
                manifest.get("scenario"), ProcedurePlanFailure.INVALID_PUBLICATION_MANIFEST,
                "manifest.scenario");
        requireOnlyFields(
                scenario, Set.of("planHash", "scenarioUuid"), "Manifest scenario",
                ProcedurePlanFailure.INVALID_PUBLICATION_MANIFEST);
        if (!scenarioPlanHash.equals(requireText(scenario, "planHash"))) {
            throw failure(
                    ProcedurePlanFailure.RELEASE_PLAN_MISMATCH,
                    "Manifest is not bound to the requested Scenario plan.");
        }
    }

    private ObjectNode definitionNode(ScenarioSource source) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("contentHash", source.contentHash());
        node.put("definitionType", "PROCEDURE");
        node.put("definitionUuid", source.definitionUuid().toString());
        node.put("definitionVersion", source.definitionVersion());
        node.put("definitionVersionUuid", source.definitionVersionUuid().toString());
        node.put("schemaVersion", source.schemaVersion());
        return node;
    }

    private ObjectNode limitsNode() {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("maximumCommandBytes", ProcedureRuntimePlan.MAXIMUM_COMMAND_BYTES);
        node.put("maximumRowsetRows", ProcedureRuntimePlan.MAXIMUM_ROWSET_ROWS);
        node.put("maximumTasks", ProcedureRuntimePlan.MAXIMUM_TASKS);
        node.put("maximumTimeoutSeconds", ProcedureRuntimePlan.MAXIMUM_TIMEOUT_SECONDS);
        return node;
    }

    private ObjectNode releaseNode(ObjectNode manifest, String scenarioPlanHash) {
        ObjectNode environment = requireObject(
                manifest.get("environment"), ProcedurePlanFailure.INVALID_PUBLICATION_MANIFEST,
                "manifest.environment");
        requireOnlyFields(
                environment, Set.of("code", "environmentUuid", "policy", "policyVersion", "risk"),
                "Manifest environment", ProcedurePlanFailure.INVALID_PUBLICATION_MANIFEST);
        ObjectNode node = objectMapper.createObjectNode();
        node.put("environmentUuid", requireUuid(environment, "environmentUuid").toString());
        node.put("scenarioPlanHash", scenarioPlanHash);
        return node;
    }

    private ArrayNode taskNodes(List<Task> tasks) {
        ArrayNode result = objectMapper.createArrayNode();
        for (Task task : tasks) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("commandHash", task.commandHash());
            node.put("connectionRole", task.connectionRole().name());
            node.put("id", task.id());
            if (task.logCounter() != LogCounter.NONE) {
                node.put("logCounter", task.logCounter().name());
            }
            if (task.transactionMode() != TransactionMode.AUTOCOMMIT) {
                node.put("transactionMode", task.transactionMode().name());
                node.put("transactionChannel", task.transactionChannel());
                node.put("commitMode", task.commitMode().name());
            }
            if (task.transactionIsolation() != TransactionIsolation.DRIVER_DEFAULT) {
                node.put("transactionIsolation", task.transactionIsolation().name());
            }
            if (task.input() != null) {
                ObjectNode input = objectMapper.createObjectNode();
                input.put("batchSize", task.input().batchSize());
                input.put("fromTask", task.input().fromTask());
                node.set("input", input);
            }
            ArrayNode binds = objectMapper.createArrayNode();
            task.namedBinds().forEach(binds::add);
            node.set("namedBinds", binds);
            ObjectNode parameters = objectMapper.createObjectNode();
            task.parameters().forEach((name, parameter) -> {
                ObjectNode value = parameters.putObject(name)
                        .put("type", parameter.type().name())
                        .put("valueSource", parameter.source().name());
                if (parameter.value() != null) value.put("value", parameter.value());
                if (parameter.refreshQuery() != null) value.put("query", parameter.refreshQuery());
                if (parameter.definitionUuid() != null) value.put("definitionUuid", parameter.definitionUuid().toString());
                if (parameter.logicalSchemaUuid() != null) {
                    value.put("logicalSchemaUuid", parameter.logicalSchemaUuid().toString());
                    value.put("historyMode", parameter.historyMode());
                }
            });
            node.set("parameters", parameters);
            node.put("onError", task.onError().name());
            if (task.output() != null) {
                ObjectNode output = objectMapper.createObjectNode();
                output.put("maximumRows", task.output().maximumRows());
                node.set("output", output);
            }
            node.put("requiresApproval", task.requiresApproval());
            node.put("riskClass", task.riskClass().name());
            node.put("timeoutSeconds", task.timeoutSeconds());
            node.put("type", task.type().name());
            result.add(node);
        }
        return result;
    }

    private ArrayNode bindingNodes(List<Task> tasks, Map<String, TaskBinding> bindings) {
        ArrayNode result = objectMapper.createArrayNode();
        for (Task task : tasks) {
            TaskBinding binding = bindings.get(task.id());
            ObjectNode node = objectMapper.createObjectNode();
            node.put("bindingVersion", binding.bindingVersion());
            node.put("connectionVersionUuid", binding.connectionVersionUuid().toString());
            node.put("dataObjectType", binding.dataObjectType());
            node.put("dataObjectUuid", binding.dataObjectUuid().toString());
            node.put("definitionDataObjectUuid", binding.definitionDataObjectUuid().toString());
            node.put("environmentSchemaBindingUuid", binding.environmentSchemaBindingUuid().toString());
            node.put("physicalIdentity", binding.physicalIdentity());
            node.put("physicalSchemaUuid", binding.physicalSchemaUuid().toString());
            node.put("role", binding.role().name());
            node.put("schemaSnapshotFingerprint", binding.schemaSnapshotFingerprint());
            node.put("schemaSnapshotUuid", binding.schemaSnapshotUuid().toString());
            node.put("taskId", binding.taskId());
            result.add(node);
        }
        return result;
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode canonical = objectMapper.createObjectNode();
            List<String> names = new ArrayList<>();
            names.addAll(node.propertyNames());
            names.sort(Comparator.naturalOrder());
            names.forEach(name -> canonical.set(name, canonicalize(node.get(name))));
            return canonical;
        }
        if (node.isArray()) {
            ArrayNode canonical = objectMapper.createArrayNode();
            node.forEach(value -> canonical.add(canonicalize(value)));
            return canonical;
        }
        return node.deepCopy();
    }

    private void rejectSecrets(JsonNode node) {
        if (!secretSanitizer.sensitivePaths(node).isEmpty()) {
            throw failure(
                    ProcedurePlanFailure.INVALID_PUBLICATION_MANIFEST,
                    "Procedure runtime documents must not contain secret values.");
        }
    }

    private <T extends Enum<T>> T parseEnum(Class<T> type, String value) {
        try {
            return Enum.valueOf(type, value);
        }
        catch (IllegalArgumentException exception) {
            throw shape("Procedure contains an unsupported enum value.");
        }
    }

    private ObjectNode requireObject(JsonNode node, ProcedurePlanFailure type, String field) {
        if (node == null || !node.isObject()) throw failure(type, field + " must be an object.");
        return (ObjectNode) node;
    }

    private void requireOnlyFields(
            ObjectNode node, Set<String> allowed, String field, ProcedurePlanFailure type) {
        for (String name : node.propertyNames()) {
            if (!allowed.contains(name)) throw failure(type, field + " contains an unsupported field.");
        }
    }

    private String requireText(ObjectNode node, String field) {
        return requireText(node, field, ProcedurePlanFailure.INVALID_PUBLICATION_MANIFEST);
    }

    private String requireText(ObjectNode node, String field, ProcedurePlanFailure type) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.stringValue().isBlank()) {
            throw failure(type, field + " must be a non-empty string.");
        }
        return value.stringValue();
    }

    private String optionalText(ObjectNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return fallback;
        if (!value.isString() || value.stringValue().isBlank()) {
            throw shape(field + " must be a non-empty string when supplied.");
        }
        return value.stringValue();
    }

    private boolean optionalBoolean(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return false;
        if (!value.isBoolean()) throw shape(field + " must be boolean when supplied.");
        return value.booleanValue();
    }

    private int requireInteger(ObjectNode node, String field) {
        return requireInteger(node, field, ProcedurePlanFailure.INVALID_PUBLICATION_MANIFEST);
    }

    private int requireInteger(ObjectNode node, String field, ProcedurePlanFailure type) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw failure(type, field + " must be an integer.");
        }
        return value.intValue();
    }

    private long requireLong(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw failure(ProcedurePlanFailure.INVALID_PUBLICATION_MANIFEST,
                    field + " must be an integer.");
        }
        return value.longValue();
    }

    private UUID requireUuid(ObjectNode node, String field) {
        return requireUuid(node, field, ProcedurePlanFailure.INVALID_PUBLICATION_MANIFEST);
    }

    private UUID requireUuid(ObjectNode node, String field, ProcedurePlanFailure type) {
        try {
            return UUID.fromString(requireText(node, field, type));
        }
        catch (IllegalArgumentException exception) {
            throw failure(type, field + " must be a UUID.");
        }
    }

    private void requireHash(String value, String field) {
        if (value == null || !SHA_256.matcher(value).matches()) {
            throw failure(ProcedurePlanFailure.SCENARIO_PLAN_INTEGRITY_FAILED,
                    field + " must be a lowercase SHA-256 value.");
        }
    }

    private String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private ProcedureRuntimePlanException bindingFailure(String message) {
        return failure(ProcedurePlanFailure.DEFINITION_BINDING_MISMATCH, message);
    }

    private ProcedureRuntimePlanException shape(String message) {
        return failure(ProcedurePlanFailure.UNSUPPORTED_PROCEDURE_SHAPE, message);
    }

    private ProcedureRuntimePlanException failure(ProcedurePlanFailure type, String message) {
        return new ProcedureRuntimePlanException(type, message);
    }

    private record ScenarioSource(
            UUID definitionUuid,
            UUID definitionVersionUuid,
            int definitionVersion,
            int schemaVersion,
            String contentHash,
            ObjectNode definition) {
    }

    private record TargetIdentity(
            UUID dataObjectUuid,
            UUID environmentSchemaBindingUuid,
            UUID physicalSchemaUuid,
            UUID connectionVersionUuid,
            UUID schemaSnapshotUuid,
            String schemaSnapshotFingerprint,
            String physicalIdentity) {
    }
}

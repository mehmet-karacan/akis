package tr.com.innova.akis.metadata;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

/**
 * Validates definition content at the immutable-version boundary. Drafts are
 * deliberately allowed to be incomplete so editors can persist work in progress.
 */
@Component
public final class DefinitionContentValidator {

    private static final int MAX_GRAPH_NODES = 10_000;
    private static final int MAX_LOAD_PLAN_DEPTH = 100;
    private static final int MAX_PROCEDURE_TASKS = 10_000;

    private static final Set<String> PACKAGE_STEP_TYPES = Set.of(
            "MAPPING", "PROCEDURE", "VARIABLE_DECLARE", "VARIABLE_REFRESH",
            "VARIABLE_SET", "VARIABLE_INCREMENT", "VARIABLE_EVALUATE", "PACKAGE");
    private static final Set<String> TRANSITION_OUTCOMES = Set.of(
            "SUCCESS", "FAILURE", "TRUE", "FALSE", "ALWAYS");
    private static final Set<String> PROCEDURE_TASK_TYPES = Set.of(
            "SQL", "PLSQL", "STORED_PROCEDURE");
    private static final Set<String> PROCEDURE_CONNECTION_ROLES = Set.of("SOURCE", "TARGET");
    private static final Set<String> PROCEDURE_RISK_CLASSES = Set.of(
            "READ_ONLY", "DML", "DDL", "DESTRUCTIVE");
    private static final Set<String> PROCEDURE_ERROR_POLICIES = Set.of("STOP", "CONTINUE");
    private static final Set<String> PROCEDURE_LOG_COUNTERS = Set.of(
            "NONE", "INSERT", "UPDATE", "DELETE", "ERRORS", "STATISTICS", "ANALYSIS");
    private static final Set<String> PROCEDURE_TRANSACTION_MODES = Set.of(
            "AUTOCOMMIT", "TRANSACTION");
    private static final Set<String> PROCEDURE_TRANSACTION_ISOLATIONS = Set.of(
            "DRIVER_DEFAULT", "READ_COMMITTED", "SERIALIZABLE");
    private static final Set<String> PROCEDURE_COMMIT_MODES = Set.of("NO_COMMIT", "COMMIT");
    private static final Set<String> DATASET_ROLES = Set.of("SOURCE", "TARGET");
    private static final Set<String> V1_WRITE_STRATEGIES = Set.of(
            "APPEND", "STAGED_REPLACE", "MERGE", "TRUNCATE_LOAD");
    private static final Set<String> V2_WRITE_STRATEGIES = Set.of(
            "APPEND", "STAGED_REPLACE", "MERGE", "TRUNCATE_LOAD",
            "ATOMIC_DELETE_INSERT");
    private static final Set<String> LOAD_PLAN_STEP_TYPES = Set.of(
            "SCENARIO", "SERIAL", "PARALLEL", "CASE");
    private static final Set<String> RESTART_POLICIES = Set.of(
            "FAILED_STEP", "FROM_BEGINNING", "MANUAL");

    private static final Pattern DROP_OR_TRUNCATE = Pattern.compile(
            "(?is)^\\s*(DROP|TRUNCATE)\\b");
    private static final Pattern UNBOUNDED_DELETE = Pattern.compile(
            "(?is)^\\s*DELETE\\s+FROM\\b(?!.*\\bWHERE\\b).*$");
    private static final Pattern DDL = Pattern.compile(
            "(?is)^\\s*(CREATE|ALTER|COMMENT|GRANT|REVOKE)\\b");
    private static final Pattern DML = Pattern.compile(
            "(?is)^\\s*(INSERT|UPDATE|DELETE|MERGE)\\b");
    public DefinitionContentValidator() {
    }

    public void validate(DefinitionType type, JsonNode content) {
        validate(type, 1, content);
    }

    public void validate(DefinitionType type, int schemaVersion, JsonNode content) {
        if (schemaVersion != 1 && schemaVersion != 2 && !((schemaVersion == 3 || schemaVersion == 4) && type == DefinitionType.MAPPING)) {
            fail("Desteklenmeyen tanım şema sürümü: " + schemaVersion);
        }
        if (schemaVersion == 2
                && type != DefinitionType.MAPPING
                && type != DefinitionType.KNOWLEDGE_MODULE
                && type != DefinitionType.PROCEDURE) {
            fail("Tanım şema sürümü 2 yalnız MAPPING, PROCEDURE ve KNOWLEDGE_MODULE için desteklenir.");
        }
        requireObject(content, "Tanım içeriği");
        List<String> requiredFields = type == DefinitionType.MAPPING && schemaVersion < 4
                ? List.of("datasets", "columnMappings", "writeStrategy")
                : type.requiredContentFields();
        List<String> missing = requiredFields.stream()
                .filter(field -> content.get(field) == null || content.get(field).isNull())
                .toList();
        if (!missing.isEmpty()) {
            fail(type.label() + " içeriğinde zorunlu alanlar eksik: "
                    + String.join(", ", missing));
        }

        switch (type) {
            case PACKAGE -> validatePackage(content);
            case PROCEDURE -> validateProcedure(content, schemaVersion);
            case VARIABLE -> validateVariable(content);
            case SEQUENCE -> validateSequence(content);
            case MAPPING -> validateMapping(content, schemaVersion);
            case REUSABLE_MAPPING -> {
                requireArray(content, "inputs");
                requireArray(content, "outputs");
                requireArray(content, "nodes");
            }
            case KNOWLEDGE_MODULE -> {
                if (schemaVersion == 2 && !Set.of("AKIS_KM/1", "AKIS_KM/2", "AKIS_KM/3").contains(content.path("language").asText())) fail("KM sürüm 2 için desteklenen AKIS_KM dili zorunludur.");
                requireAllowed(content, "kmType", Set.of(
                        "RKM", "CKM", "LKM", "IKM", "XKM", "JKM", "SKM"));
                requireArray(content, "tasks");
                requireArray(content, "options");
                if (content.has("technology")) {
                    JsonNode technology = content.path("technology");
                    requireObject(technology, "technology");
                    Set<String> providers = Set.of("ORACLE", "POSTGRESQL", "MYSQL", "SQLSERVER");
                    requireAllowed(technology, "source", providers, "technology");
                    requireAllowed(technology, "target", providers, "technology");
                }
                if (content.has("language")) {
                    if (!Set.of("AKIS_KM/1", "AKIS_KM/2", "AKIS_KM/3").contains(content.path("language").asText())) fail("Desteklenmeyen KM dili.");
                    try {
                        var program = tr.com.innova.akis.knowledge.AkisKmLanguage.parse(requireText(content, "source"));
                        if (!program.kind().name().equals(content.path("kmType").asText())) fail("KM türü ile MODUL bildirimi uyuşmuyor.");
                        if (!content.path("tasks").isEmpty() || !content.path("options").isEmpty()) fail("AKIS_KM görevleri ve seçenekleri dil kaynağından alınır.");
                        if (Set.of("AKIS_KM/2", "AKIS_KM/3").contains(program.language()) && content.has("optionSchema") && !content.path("optionSchema").isEmpty()) fail("AKIS_KM seçeneklerinin tek kaynağı SECENEK bildirimleridir.");
                        if ("AKIS_KM/1".equals(program.language())) validateKnowledgeOptionSchema(content.path("optionSchema"));
                    } catch (IllegalArgumentException invalid) { fail(invalid.getMessage()); }
                }
            }
            case LOAD_PLAN -> validateLoadPlan(content);
        }
    }

    private void validateKnowledgeOptionSchema(JsonNode schema) {
        if (schema.isMissingNode()) return;
        if (!schema.isArray() || schema.size() > 32) fail("KM optionSchema en fazla 32 seçenek içeren bir dizi olmalıdır.");
        Set<String> keys = new HashSet<>();
        Set<String> types = Set.of("BOOLEAN", "INTEGER", "STRING", "ENUM", "SQL_HINT", "IDENTIFIER", "COLUMN_LIST");
        for (int index = 0; index < schema.size(); index++) {
            JsonNode option = schema.get(index);
            requireObject(option, "optionSchema[" + index + "]");
            String key = requireText(option, "key", "optionSchema[" + index + "]");
            if (!key.matches("[A-Z][A-Z0-9_]{0,63}") || !keys.add(key)) fail("KM seçenek anahtarları benzersiz standart kimlik olmalıdır.");
            requireText(option, "label", "optionSchema[" + index + "]");
            String type = requireAllowed(option, "type", types, "optionSchema[" + index + "]");
            if (option.has("required") && !option.path("required").isBoolean()) fail("KM required alanı boolean olmalıdır.");
            JsonNode defaultValue = option.get("defaultValue");
            if (defaultValue != null && !defaultValue.isNull()) {
                boolean valid = switch (type) {
                    case "BOOLEAN" -> defaultValue.isBoolean();
                    case "INTEGER" -> (defaultValue.isIntegralNumber() && defaultValue.canConvertToLong())
                            || (defaultValue.isTextual() && tr.com.innova.akis.knowledge.KmIntegerValue.valid(defaultValue.asText()));
                    default -> defaultValue.isTextual() && defaultValue.asText().length() <= 1024;
                };
                if (!valid) fail("KM varsayılan değeri seçenek tipiyle uyuşmuyor: " + key);
            }
            if ("ENUM".equals(type)) {
                JsonNode values = requireArray(option, "values", "optionSchema[" + index + "]");
                if (values.isEmpty() || values.size() > 64) fail("ENUM en az bir, en fazla 64 değer içermelidir.");
                for (JsonNode value : values) if (!value.isTextual() || value.asText().isBlank()) fail("ENUM değerleri boş olmayan metin olmalıdır.");
            }
        }
    }

    private void validatePackage(JsonNode content) {
        JsonNode steps = requireArray(content, "steps");
        JsonNode transitions = requireArray(content, "transitions");
        String firstStepId = requireText(content, "firstStepId");
        if (steps.isEmpty()) {
            fail("Paket en az bir adım içermelidir.");
        }
        if (steps.size() > MAX_GRAPH_NODES) {
            fail("Paket adım sayısı güvenli sınırı aşıyor.");
        }

        Set<String> stepIds = new HashSet<>();
        for (int index = 0; index < steps.size(); index++) {
            JsonNode step = steps.get(index);
            requireObject(step, "steps[" + index + "]");
            String id = requireText(step, "id", "steps[" + index + "]");
            if (!stepIds.add(id)) {
                fail("Paket adım kimliği benzersiz olmalıdır: " + id);
            }
            requireAllowed(step, "type", PACKAGE_STEP_TYPES, "steps[" + index + "]");
        }
        if (!stepIds.contains(firstStepId)) {
            fail("Paket firstStepId var olan bir adıma referans vermelidir.");
        }

        Map<String, Set<String>> adjacency = new HashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        stepIds.forEach(id -> {
            adjacency.put(id, new HashSet<>());
            indegree.put(id, 0);
        });
        Set<String> outcomeSlots = new HashSet<>();
        for (int index = 0; index < transitions.size(); index++) {
            JsonNode transition = transitions.get(index);
            String path = "transitions[" + index + "]";
            requireObject(transition, path);
            String from = requireText(transition, "fromStepId", path);
            String to = requireText(transition, "toStepId", path);
            if (!stepIds.contains(from) || !stepIds.contains(to)) {
                fail(path + " var olmayan paket adımına referans veriyor.");
            }
            if (!adjacency.get(from).add(to)) {
                fail("Aynı paket geçişi birden fazla tanımlanamaz: " + from + " -> " + to);
            }
            indegree.put(to, indegree.get(to) + 1);

            JsonNode outcomeNode = transition.get("outcome");
            if (outcomeNode != null && !outcomeNode.isNull()) {
                String outcome = requireAllowed(transition, "outcome", TRANSITION_OUTCOMES, path);
                if (!outcomeSlots.add(from + "\u0000" + outcome)) {
                    fail("Bir paket adımında aynı sonuç için birden fazla geçiş olamaz: "
                            + from + "/" + outcome);
                }
            }
        }

        ensureAllStepsReachable(firstStepId, adjacency, stepIds.size());
        ensureAcyclic(adjacency, indegree, stepIds.size(), "Paket kontrol grafiği cycle içeremez.");
    }

    private void ensureAllStepsReachable(
            String firstStepId, Map<String, Set<String>> adjacency, int stepCount) {
        Set<String> visited = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(firstStepId);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (visited.add(current)) {
                adjacency.get(current).forEach(queue::addLast);
            }
        }
        if (visited.size() != stepCount) {
            fail("Paket ilk adımdan erişilemeyen adımlar içeriyor.");
        }
    }

    private void ensureAcyclic(
            Map<String, Set<String>> adjacency,
            Map<String, Integer> indegree,
            int stepCount,
            String message) {
        ArrayDeque<String> queue = new ArrayDeque<>();
        indegree.forEach((id, degree) -> {
            if (degree == 0) {
                queue.addLast(id);
            }
        });
        int processed = 0;
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            processed++;
            for (String target : adjacency.get(current)) {
                int degree = indegree.compute(target, (ignored, old) -> old - 1);
                if (degree == 0) {
                    queue.addLast(target);
                }
            }
        }
        if (processed != stepCount) {
            fail(message);
        }
    }

    private void validateProcedure(JsonNode content, int schemaVersion) {
        JsonNode tasks = requireArray(content, "tasks");
        if (tasks.isEmpty()) {
            fail("Prosedür en az bir görev içermelidir.");
        }
        if (tasks.size() > MAX_PROCEDURE_TASKS) {
            fail("Prosedür en fazla " + MAX_PROCEDURE_TASKS + " görev içerebilir.");
        }
        Set<String> taskIds = new HashSet<>();
        Set<String> rowsetTaskIds = new HashSet<>();
        for (int index = 0; index < tasks.size(); index++) {
            JsonNode task = tasks.get(index);
            String path = "tasks[" + index + "]";
            requireObject(task, path);
            String id = requireText(task, "id", path);
            if (!taskIds.add(id)) {
                fail("Prosedür görev kimliği benzersiz olmalıdır: " + id);
            }
            String type = requireAllowed(task, "type", PROCEDURE_TASK_TYPES, path);
            String connectionRole = requireAllowed(
                    task, "connectionRole", PROCEDURE_CONNECTION_ROLES, path);
            String risk = requireAllowed(task, "riskClass", PROCEDURE_RISK_CLASSES, path);
            String command = requireText(task, "command", path);
            validateCommonSqlSyntax(path, command);
            JsonNode logCounter = task.get("logCounter");
            if (logCounter != null && !logCounter.isNull()) {
                String counter = requireAllowed(task, "logCounter", PROCEDURE_LOG_COUNTERS, path);
                validateLogCounter(path, type, connectionRole, command, counter);
            }
            String transactionMode = optionalAllowed(
                    task, "transactionMode", PROCEDURE_TRANSACTION_MODES, path, "AUTOCOMMIT");
            optionalAllowed(task, "transactionIsolation",
                    PROCEDURE_TRANSACTION_ISOLATIONS, path, "DRIVER_DEFAULT");
            String commitMode = optionalAllowed(
                    task, "commitMode", PROCEDURE_COMMIT_MODES, path, "COMMIT");
            JsonNode transactionChannel = task.get("transactionChannel");
            if ("TRANSACTION".equals(transactionMode)) {
                if (!"TARGET".equals(connectionRole) || !"DML".equals(risk)) {
                    fail(path + " yalnız hedef DML komutunda yönetilen transaction kullanabilir.");
                }
                validateRequiredInteger(task, "transactionChannel", path, 0, 9);
            }
            else if (transactionChannel != null && !transactionChannel.isNull()) {
                fail(path + ".transactionChannel Autocommit için tanımlanamaz.");
            }
            if ("AUTOCOMMIT".equals(transactionMode) && !"COMMIT".equals(commitMode)) {
                fail(path + ".commitMode Autocommit için COMMIT olmalıdır.");
            }
            validateCommandRisk(path, command, risk);
            if (("DDL".equals(risk) || "DESTRUCTIVE".equals(risk))
                    && !task.path("requiresApproval").isBoolean()) {
                fail(path + ".requiresApproval yüksek riskli görevlerde boolean olmalıdır.");
            }
            if (("DDL".equals(risk) || "DESTRUCTIVE".equals(risk))
                    && !task.path("requiresApproval").booleanValue()) {
                fail(path + " yüksek riskli komut için açık onay gerektirmelidir.");
            }
            if (schemaVersion == 2) {
                validateProcedureV2Task(
                        task, path, id, type, connectionRole, risk, command,
                        rowsetTaskIds);
            }
        }
    }

    private void validateProcedureV2Task(
            JsonNode task,
            String path,
            String taskId,
            String type,
            String connectionRole,
            String risk,
            String command,
            Set<String> rowsetTaskIds) {
        JsonNode onError = task.get("onError");
        if (onError != null && !onError.isNull()) {
            requireAllowed(task, "onError", PROCEDURE_ERROR_POLICIES, path);
        }
        JsonNode enabled = task.get("enabled");
        if (enabled != null && !enabled.isNull() && !enabled.isBoolean()) {
            fail(path + ".enabled boolean olmalıdır.");
        }
        validateOptionalInteger(task, "timeoutSeconds", path, 1, 3_600);

        JsonNode output = task.get("output");
        JsonNode input = task.get("input");
        boolean hasOutput = output != null && !output.isNull();
        boolean hasInput = input != null && !input.isNull();
        if (hasOutput && hasInput) {
            fail(path + " aynı anda hem satır çıktısı üretip hem satır girdisi tüketemez.");
        }
        if (hasOutput) {
            requireObject(output, path + ".output");
            requireAllowed(output, "kind", Set.of("ROWSET"), path + ".output");
            validateRequiredInteger(output, "maxRows", path + ".output", 1, 100_000);
            String sql = stripLeadingSqlComments(command).stripLeading()
                    .toUpperCase(Locale.ROOT);
            if (!"SQL".equals(type) || !"SOURCE".equals(connectionRole)
                    || !"READ_ONLY".equals(risk)
                    || !sql.matches("(?s)^(SELECT|WITH)\\s+.*")
                    || sql.contains(";")) {
                fail(path + ".output yalnız tek bir SOURCE READ_ONLY SELECT/WITH SQL görevinde kullanılabilir.");
            }
            rowsetTaskIds.add(taskId);
        }
        if (hasInput) {
            requireObject(input, path + ".input");
            String fromTask = requireText(input, "fromTask", path + ".input");
            requireAllowed(input, "mode", Set.of("BATCH"), path + ".input");
            validateRequiredInteger(input, "batchSize", path + ".input", 1, 1_000);
            String sql = stripLeadingSqlComments(command).stripLeading()
                    .toUpperCase(Locale.ROOT);
            if (!rowsetTaskIds.contains(fromTask)) {
                fail(path + ".input.fromTask daha önce tanımlanmış bir ROWSET görevine referans vermelidir.");
            }
            if (!"SQL".equals(type) || !"TARGET".equals(connectionRole)
                    || !"DML".equals(risk) || !sql.matches("(?s)^INSERT\\s+.*")
                    || sql.contains(";") || !hasNamedBind(command)) {
                fail(path + ".input yalnız named bind kullanan tek bir TARGET DML INSERT SQL görevinde kullanılabilir.");
            }
        }
    }

    private boolean hasNamedBind(String command) {
        try {
            return !NamedBindParser.parse(command).isEmpty();
        }
        catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private void validateOptionalInteger(
            JsonNode node, String field, String path, int minimum, int maximum) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return;
        }
        validateInteger(value, field, path, minimum, maximum);
    }

    private void validateRequiredInteger(
            JsonNode node, String field, String path, int minimum, int maximum) {
        validateInteger(node.get(field), field, path, minimum, maximum);
    }

    private void validateInteger(
            JsonNode value, String field, String path, int minimum, int maximum) {
        if (value == null || !value.isIntegralNumber()
                || value.intValue() < minimum || value.intValue() > maximum) {
            fail(path(path, field) + " " + minimum + "-" + maximum
                    + " aralığında tam sayı olmalıdır.");
        }
    }

    private void validateCommandRisk(String path, String command, String risk) {
        String trimmed = stripLeadingSqlComments(command);
        if (DROP_OR_TRUNCATE.matcher(trimmed).find() || UNBOUNDED_DELETE.matcher(trimmed).matches()) {
            if (!"DESTRUCTIVE".equals(risk)) {
                fail(path + " destructive komutu DESTRUCTIVE risk sınıfında işaretlemelidir.");
            }
            return;
        }
        if (DDL.matcher(trimmed).find() && !Set.of("DDL", "DESTRUCTIVE").contains(risk)) {
            fail(path + " DDL komutu DDL veya DESTRUCTIVE risk sınıfında olmalıdır.");
        }
        if (DML.matcher(trimmed).find() && "READ_ONLY".equals(risk)) {
            fail(path + " veri değiştiren komutu READ_ONLY olarak işaretleyemez.");
        }
    }

    private void validateLogCounter(
            String path, String type, String connectionRole, String command, String counter) {
        if (Set.of("NONE", "ANALYSIS", "STATISTICS").contains(counter)) return;
        if (!"SQL".equals(type) || !"TARGET".equals(connectionRole)) {
            fail(path + ".logCounter yalnız hedef SQL komutlarında kullanılabilir.");
        }
        String keyword = stripLeadingSqlComments(command).stripLeading()
                .split("\\s+", 2)[0].toUpperCase(Locale.ROOT);
        boolean matches = switch (counter) {
            case "INSERT" -> "INSERT".equals(keyword);
            case "UPDATE" -> "UPDATE".equals(keyword);
            case "DELETE" -> "DELETE".equals(keyword);
            case "ERRORS" -> Set.of("INSERT", "UPDATE").contains(keyword);
            default -> true;
        };
        if (!matches) fail(path + ".logCounter SQL komut türüyle eşleşmiyor.");
    }

    private void validateCommonSqlSyntax(String path, String command) {
        StringBuilder executable = new StringBuilder(command.length());
        int parentheses = 0;
        boolean singleQuote = false;
        boolean doubleQuote = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int index = 0; index < command.length(); index++) {
            char current = command.charAt(index);
            char next = index + 1 < command.length() ? command.charAt(index + 1) : '\0';
            if (lineComment) {
                if (current == '\n') { lineComment = false; executable.append('\n'); }
                else executable.append(' ');
                continue;
            }
            if (blockComment) {
                executable.append(current == '\n' ? '\n' : ' ');
                if (current == '*' && next == '/') { executable.append(' '); blockComment = false; index++; }
                continue;
            }
            if (singleQuote || doubleQuote) {
                executable.append(current == '\n' ? '\n' : ' ');
                char quote = singleQuote ? '\'' : '"';
                if (current == quote && next == quote) { executable.append(' '); index++; }
                else if (current == quote) { singleQuote = false; doubleQuote = false; }
                continue;
            }
            if (current == '-' && next == '-') { executable.append("  "); lineComment = true; index++; continue; }
            if (current == '/' && next == '*') { executable.append("  "); blockComment = true; index++; continue; }
            if (current == '\'' || current == '"') {
                executable.append('X');
                singleQuote = current == '\'';
                doubleQuote = current == '"';
                continue;
            }
            executable.append(current);
            if (current == '(') parentheses++;
            else if (current == ')' && --parentheses < 0) fail(path + ".command kapanış parantezi eşleşmiyor.");
        }
        if (singleQuote || doubleQuote) fail(path + ".command kapatılmamış tırnak içeriyor.");
        if (blockComment) fail(path + ".command kapatılmamış blok yorumu içeriyor.");
        if (parentheses != 0) fail(path + ".command parantezleri dengeli olmalıdır.");
        String normalized = executable.toString();
        if (normalized.matches("(?s).*(,\\s*[,)]|\\(\\s*,).*$")) {
            fail(path + ".command geçersiz virgül kullanımı içeriyor.");
        }
    }

    private String stripLeadingSqlComments(String command) {
        String result = command;
        while (true) {
            String stripped = result.stripLeading();
            if (stripped.startsWith("--")) {
                int lineEnd = stripped.indexOf('\n');
                result = lineEnd < 0 ? "" : stripped.substring(lineEnd + 1);
            }
            else if (stripped.startsWith("/*")) {
                int commentEnd = stripped.indexOf("*/", 2);
                result = commentEnd < 0 ? stripped : stripped.substring(commentEnd + 2);
            }
            else {
                return stripped;
            }
        }
    }

    private void validateMapping(JsonNode content, int schemaVersion) {
        if (schemaVersion == 4) {
            validateDirectMapping(content);
            return;
        }
        if (schemaVersion == 3) {
            try { tr.com.innova.akis.knowledge.StagedMappingDefinition.parse(content); }
            catch (IllegalArgumentException invalid) { fail(invalid.getMessage()); }
        }
        JsonNode datasets = requireArray(content, "datasets");
        JsonNode columnMappings = requireArray(content, "columnMappings");
        JsonNode writeStrategy = content.path("writeStrategy");
        requireObject(writeStrategy, "writeStrategy");
        if (datasets.isEmpty()) {
            fail("Mapping en az bir SOURCE ve bir TARGET dataset içermelidir.");
        }

        Map<String, String> rolesByDataset = new HashMap<>();
        for (int index = 0; index < datasets.size(); index++) {
            JsonNode dataset = datasets.get(index);
            String path = "datasets[" + index + "]";
            requireObject(dataset, path);
            String id = requireText(dataset, "id", path);
            String role = requireAllowed(dataset, "role", DATASET_ROLES, path);
            if (rolesByDataset.putIfAbsent(id, role) != null) {
                fail("Mapping dataset kimliği benzersiz olmalıdır: " + id);
            }
        }
        if (!rolesByDataset.containsValue("SOURCE") || !rolesByDataset.containsValue("TARGET")) {
            fail("Mapping en az bir SOURCE ve bir TARGET dataset içermelidir.");
        }
        if (columnMappings.isEmpty()) {
            fail("Mapping en az bir kolon eşlemesi içermelidir.");
        }

        Set<String> mappedTargets = new HashSet<>();
        for (int index = 0; index < columnMappings.size(); index++) {
            JsonNode columnMapping = columnMappings.get(index);
            String path = "columnMappings[" + index + "]";
            requireObject(columnMapping, path);
            JsonNode target = columnMapping.path("target");
            requireObject(target, path + ".target");
            String targetDataset = requireText(target, "dataset", path + ".target");
            String targetColumn = requireText(target, "column", path + ".target");
            if (!"TARGET".equals(rolesByDataset.get(targetDataset))) {
                fail(path + ".target TARGET rolündeki bir datasete referans vermelidir.");
            }
            String targetKey = targetDataset + "\u0000" + targetColumn.toUpperCase(Locale.ROOT);
            if (!mappedTargets.add(targetKey)) {
                fail("Aynı hedef kolon birden fazla kez yazılamaz: "
                        + targetDataset + "." + targetColumn);
            }

            JsonNode source = columnMapping.get("source");
            JsonNode expression = columnMapping.get("expression");
            boolean hasSource = source != null && !source.isNull();
            boolean hasExpression = expression != null && !expression.isNull();
            if (hasSource == hasExpression) {
                fail(path + " tam olarak bir source veya expression içermelidir.");
            }
            if (hasSource) {
                requireObject(source, path + ".source");
                String sourceDataset = requireText(source, "dataset", path + ".source");
                requireText(source, "column", path + ".source");
                if (!"SOURCE".equals(rolesByDataset.get(sourceDataset))) {
                    fail(path + ".source SOURCE rolündeki bir datasete referans vermelidir.");
                }
            }
            else {
                MappingExpressionValidator.validate(expression, rolesByDataset, path + ".expression");
            }
        }

        Set<String> writeStrategies = schemaVersion >= 2
                ? V2_WRITE_STRATEGIES : V1_WRITE_STRATEGIES;
        String strategy = requireAllowed(
                writeStrategy, "kind", writeStrategies, "writeStrategy");
        if ("MERGE".equals(strategy)) {
            JsonNode key = requireArray(writeStrategy, "key", "writeStrategy");
            if (key.isEmpty()) {
                fail("MERGE writeStrategy en az bir key kolonu içermelidir.");
            }
            Set<String> keys = new HashSet<>();
            for (int index = 0; index < key.size(); index++) {
                JsonNode keyColumn = key.get(index);
                if (!keyColumn.isString() || keyColumn.stringValue().isBlank()) {
                    fail("writeStrategy.key yalnız boş olmayan kolon adları içermelidir.");
                }
                if (!keys.add(keyColumn.stringValue().toUpperCase(Locale.ROOT))) {
                    fail("writeStrategy.key aynı kolonu birden fazla içeremez.");
                }
            }
        }
    }

    private void validateDirectMapping(JsonNode content) {
        JsonNode sources = requireArray(content, "sources");
        JsonNode target = content.path("target");
        requireObject(target, "target");
        JsonNode joins = requireArray(content, "joins");
        JsonNode filters = requireArray(content, "filters");
        JsonNode mappings = requireArray(content, "columnMappings");
        if (sources.isEmpty()) fail("Arayüz en az bir kaynak içermelidir.");
        Map<String, String> roles = new HashMap<>();
        for (int index = 0; index < sources.size(); index++) {
            validateObjectReference(sources.get(index), "sources[" + index + "]", "SOURCE", roles);
        }
        validateObjectReference(target, "target", "TARGET", roles);
        if (mappings.isEmpty()) fail("Arayüz en az bir kolon eşlemesi içermelidir.");
        Set<String> mappedTargets = new HashSet<>();
        for (int index = 0; index < mappings.size(); index++) {
            JsonNode mapping = mappings.get(index);
            String path = "columnMappings[" + index + "]";
            requireObject(mapping, path);
            JsonNode targetRef = mapping.path("target");
            String targetObject = directColumnReference(targetRef, path + ".target", roles, "TARGET");
            String targetColumn = requireText(targetRef, "column", path + ".target");
            if (!mappedTargets.add(targetObject + "\u0000" + targetColumn.toUpperCase(Locale.ROOT))) fail("Aynı hedef kolon birden fazla kez yazılamaz.");
            JsonNode source = mapping.get("source");
            JsonNode expression = mapping.get("expression");
            boolean hasSource = source != null && !source.isNull();
            boolean hasExpression = expression != null && !expression.isNull();
            if (hasSource == hasExpression) fail(path + " tam olarak bir kaynak veya ifade içermelidir.");
            if (hasSource) directColumnReference(source, path + ".source", roles, "SOURCE");
            // Schema-4 expressions share the bounded SQL contract used by the
            // staged planner/runtime; StagedMappingDefinition below validates it.
        }
        Set<String> joinIds = new HashSet<>();
        for (int index = 0; index < joins.size(); index++) {
            JsonNode join = joins.get(index); String path = "joins[" + index + "]"; requireObject(join, path);
            String id = requireText(join, "id", path); if (!joinIds.add(id)) fail("Join kimliği benzersiz olmalıdır.");
            requireAllowed(join, "type", Set.of("INNER", "LEFT", "RIGHT", "FULL"), path);
            String left = directColumnReference(join.path("left"), path + ".left", roles, "SOURCE");
            String right = directColumnReference(join.path("right"), path + ".right", roles, "SOURCE");
            if (left.equals(right)) fail("Join iki farklı kaynak arasında olmalıdır.");
        }
        Set<String> filterIds = new HashSet<>();
        for (int index = 0; index < filters.size(); index++) {
            JsonNode filter = filters.get(index); String path = "filters[" + index + "]"; requireObject(filter, path);
            String id = requireText(filter, "id", path); if (!filterIds.add(id)) fail("Filtre kimliği benzersiz olmalıdır.");
            String scope = requireAllowed(filter, "scope", Set.of("SOURCE", "GLOBAL"), path);
            if ("SOURCE".equals(scope)) {
                String object = requireText(filter, "object", path);
                if (!"SOURCE".equals(roles.get(object))) fail(path + " etkin bir kaynağa referans vermelidir.");
            }
            String object = requireText(filter, "object", path);
            if (!"SOURCE".equals(roles.get(object))) fail(path + " etkin bir kaynağa referans vermelidir.");
            // Free predicates are validated against their scope by the shared
            // staged SQL contract below, including rejection of mixed fields.
            if (filter.has("predicate")) continue;
            requireText(filter, "column", path);
            String operator = requireAllowed(filter, "operator", Set.of("EQUALS", "NOT_EQUALS", "GREATER_THAN", "LESS_THAN", "LIKE", "IS_NULL", "IS_NOT_NULL"), path);
            if (!Set.of("IS_NULL", "IS_NOT_NULL").contains(operator)) requireText(filter, "value", path);
        }
        try { tr.com.innova.akis.knowledge.StagedMappingDefinition.parse(content); }
        catch (IllegalArgumentException invalid) { fail(invalid.getMessage()); }
    }

    private void validateObjectReference(JsonNode node, String path, String role, Map<String, String> roles) {
        requireObject(node, path);
        String id = requireText(node, "id", path);
        requireText(node, "alias", path);
        requireText(node, "dataObjectUuid", path);
        requireText(node, "schemaSnapshotUuid", path);
        if (roles.putIfAbsent(id, role) != null) fail("Kaynak ve hedef kimlikleri benzersiz olmalıdır: " + id);
    }

    private String directColumnReference(JsonNode node, String path, Map<String, String> roles, String expectedRole) {
        requireObject(node, path);
        String object = requireText(node, "object", path);
        requireText(node, "column", path);
        if (!expectedRole.equals(roles.get(object))) fail(path + " " + expectedRole + " rolündeki bir nesneye referans vermelidir.");
        return object;
    }

    private void validateLoadPlan(JsonNode content) {
        JsonNode steps = requireArray(content, "steps");
        requireAllowed(content, "restartPolicy", RESTART_POLICIES);
        if (steps.isEmpty()) {
            fail("Load Plan en az bir adım içermelidir.");
        }

        Set<String> stepIds = new HashSet<>();
        ArrayDeque<LoadPlanFrame> stack = new ArrayDeque<>();
        for (int index = steps.size() - 1; index >= 0; index--) {
            stack.push(new LoadPlanFrame(steps.get(index), "steps[" + index + "]", 1));
        }
        int count = 0;
        while (!stack.isEmpty()) {
            LoadPlanFrame frame = stack.pop();
            if (++count > MAX_GRAPH_NODES) {
                fail("Load Plan adım sayısı güvenli sınırı aşıyor.");
            }
            if (frame.depth() > MAX_LOAD_PLAN_DEPTH) {
                fail("Load Plan iç içe adım derinliği güvenli sınırı aşıyor.");
            }
            JsonNode step = frame.step();
            requireObject(step, frame.path());
            String id = requireText(step, "id", frame.path());
            if (!stepIds.add(id)) {
                fail("Load Plan adım kimliği benzersiz olmalıdır: " + id);
            }
            String type = requireAllowed(step, "type", LOAD_PLAN_STEP_TYPES, frame.path());
            if ("SCENARIO".equals(type)) {
                requireText(step, "scenarioVersionUuid", frame.path());
                if (step.has("steps")) {
                    fail(frame.path() + " SCENARIO adımı alt adım içeremez.");
                }
                continue;
            }

            if ("CASE".equals(type)) {
                requireText(step, "condition", frame.path());
            }
            JsonNode children = requireArray(step, "steps", frame.path());
            if (children.isEmpty()) {
                fail(frame.path() + " container adımı en az bir alt adım içermelidir.");
            }
            for (int index = children.size() - 1; index >= 0; index--) {
                stack.push(new LoadPlanFrame(
                        children.get(index),
                        frame.path() + ".steps[" + index + "]",
                        frame.depth() + 1));
            }
        }
    }

    private void validateVariable(JsonNode content) {
        requireAllowed(content, "dataType", Set.of(
                "STRING", "INTEGER", "DECIMAL", "BOOLEAN", "DATE", "TIMESTAMP", "JSON"));
        requireAllowed(content, "scope", Set.of("GLOBAL", "PROJECT", "PACKAGE_RUN", "STEP"));
        requireAllowed(content, "historyMode", Set.of("NONE", "LATEST", "ALL"));
        String valueSource = requireAllowed(content, "valueSource", Set.of(
                "INPUT", "DEFAULT", "REFRESH_QUERY", "EXPRESSION", "STEP_OUTPUT"));
        if ("REFRESH_QUERY".equals(valueSource)) {
            try { java.util.UUID.fromString(content.path("logicalSchemaUuid").asText()); }
            catch (IllegalArgumentException invalid) { fail("Sorguyla yenilenen değişken için Mantıksal Şema seçilmelidir."); }
            if ("JSON".equals(content.path("dataType").asText())) fail("JSON query variables are not supported.");
            VariableQueryPolicy.validate(requireText(content, "query"), content.path("dataType").asText());
        }
    }

    private void validateSequence(JsonNode content) {
        requireAllowed(content, "implementation", Set.of("NATIVE", "REPOSITORY", "TABLE"));
        if (!content.path("start").isNumber() || !content.path("increment").isNumber()) {
            fail("Sequence start ve increment sayısal olmalıdır.");
        }
        if (content.path("increment").decimalValue().signum() == 0) {
            fail("Sequence increment sıfır olamaz.");
        }
        if (!content.path("cycle").isBoolean()) {
            fail("Sequence cycle boolean olmalıdır.");
        }
    }

    private JsonNode requireArray(JsonNode node, String field) {
        return requireArray(node, field, null);
    }

    private JsonNode requireArray(JsonNode node, String field, String parentPath) {
        JsonNode value = node.path(field);
        if (!value.isArray()) {
            fail(path(parentPath, field) + " alanı dizi olmalıdır.");
        }
        return value;
    }

    private void requireObject(JsonNode node, String path) {
        if (node == null || !node.isObject()) {
            fail(path + " JSON nesnesi olmalıdır.");
        }
    }

    private String requireText(JsonNode node, String field) {
        return requireText(node, field, null);
    }

    private String requireText(JsonNode node, String field, String parentPath) {
        JsonNode value = node.path(field);
        if (!value.isString() || value.stringValue().isBlank()) {
            fail(path(parentPath, field) + " alanı boş olmayan metin olmalıdır.");
        }
        return value.stringValue();
    }

    private String requireAllowed(JsonNode node, String field, Set<String> allowed) {
        return requireAllowed(node, field, allowed, null);
    }

    private String requireAllowed(
            JsonNode node, String field, Set<String> allowed, String parentPath) {
        String value = requireText(node, field, parentPath);
        if (!allowed.contains(value)) {
            fail(path(parentPath, field) + " alanı desteklenmeyen değer içeriyor.");
        }
        return value;
    }

    private String optionalAllowed(
            JsonNode node,
            String field,
            Set<String> allowed,
            String parentPath,
            String defaultValue) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        return requireAllowed(node, field, allowed, parentPath);
    }

    private String path(String parent, String field) {
        return parent == null ? field : parent + "." + field;
    }

    private void fail(String message) {
        throw new ApiException(
                HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED", message);
    }

    private record LoadPlanFrame(JsonNode step, String path, int depth) {
    }
}

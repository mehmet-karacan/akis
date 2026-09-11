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
        if (schemaVersion != 1 && schemaVersion != 2) {
            fail("Desteklenmeyen tanım şema sürümü: " + schemaVersion);
        }
        if (schemaVersion == 2 && type != DefinitionType.MAPPING) {
            fail("Tanım şema sürümü 2 şu anda yalnız MAPPING için desteklenir.");
        }
        requireObject(content, "Tanım içeriği");
        List<String> missing = type.requiredContentFields().stream()
                .filter(field -> content.get(field) == null || content.get(field).isNull())
                .toList();
        if (!missing.isEmpty()) {
            fail(type.label() + " içeriğinde zorunlu alanlar eksik: "
                    + String.join(", ", missing));
        }

        switch (type) {
            case PACKAGE -> validatePackage(content);
            case PROCEDURE -> validateProcedure(content);
            case VARIABLE -> validateVariable(content);
            case SEQUENCE -> validateSequence(content);
            case MAPPING -> validateMapping(content, schemaVersion);
            case REUSABLE_MAPPING -> {
                requireArray(content, "inputs");
                requireArray(content, "outputs");
                requireArray(content, "nodes");
            }
            case USER_FUNCTION -> {
                requireText(content, "returnType");
                requireArray(content, "parameters");
                requireArray(content, "implementations");
            }
            case KNOWLEDGE_MODULE -> {
                requireAllowed(content, "kmType", Set.of(
                        "RKM", "CKM", "LKM", "IKM", "XKM", "JKM", "SKM"));
                requireArray(content, "tasks");
                requireArray(content, "options");
            }
            case LOAD_PLAN -> validateLoadPlan(content);
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

    private void validateProcedure(JsonNode content) {
        JsonNode tasks = requireArray(content, "tasks");
        if (tasks.isEmpty()) {
            fail("Prosedür en az bir görev içermelidir.");
        }
        Set<String> taskIds = new HashSet<>();
        for (int index = 0; index < tasks.size(); index++) {
            JsonNode task = tasks.get(index);
            String path = "tasks[" + index + "]";
            requireObject(task, path);
            String id = requireText(task, "id", path);
            if (!taskIds.add(id)) {
                fail("Prosedür görev kimliği benzersiz olmalıdır: " + id);
            }
            requireAllowed(task, "type", PROCEDURE_TASK_TYPES, path);
            requireAllowed(task, "connectionRole", PROCEDURE_CONNECTION_ROLES, path);
            String risk = requireAllowed(task, "riskClass", PROCEDURE_RISK_CLASSES, path);
            String command = requireText(task, "command", path);
            validateCommandRisk(path, command, risk);
            if (("DDL".equals(risk) || "DESTRUCTIVE".equals(risk))
                    && !task.path("requiresApproval").isBoolean()) {
                fail(path + ".requiresApproval yüksek riskli görevlerde boolean olmalıdır.");
            }
            if (("DDL".equals(risk) || "DESTRUCTIVE".equals(risk))
                    && !task.path("requiresApproval").booleanValue()) {
                fail(path + " yüksek riskli komut için açık onay gerektirmelidir.");
            }
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
                requireObject(expression, path + ".expression");
            }
        }

        Set<String> writeStrategies = schemaVersion == 2
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
        requireAllowed(content, "valueSource", Set.of(
                "INPUT", "DEFAULT", "REFRESH_QUERY", "EXPRESSION", "STEP_OUTPUT"));
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

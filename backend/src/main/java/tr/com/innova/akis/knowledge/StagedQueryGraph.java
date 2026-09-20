package tr.com.innova.akis.knowledge;

import java.util.*;
import static tr.com.innova.akis.knowledge.StagedMappingDefinition.Join;

/** Metadata-only join planning shared by definition validation and JDBC rendering. */
public final class StagedQueryGraph {
    private StagedQueryGraph() { }

    public record Step(String source, String type, List<Join> conditions) {
        public Step { conditions = List.copyOf(conditions); }
    }
    public record Plan(String firstSource, List<Step> steps, List<Join> remainingConditions) {
        public Plan { steps = List.copyOf(steps); remainingConditions = List.copyOf(remainingConditions); }
    }

    public static Plan compile(List<String> sourceIds, List<Join> joins) {
        if (sourceIds.isEmpty() || sourceIds.size() > 16 || joins.size() > 256)
            throw invalid("Sorgu 1-16 kaynak ve en fazla 256 join koşulu içerebilir.");
        Set<String> sources = new LinkedHashSet<>();
        for (String id : sourceIds) {
            StagedMappingDefinition.identifier(id);
            if (!sources.add(id)) throw invalid("Kaynak kimliği tekrar edemez.");
        }
        // Validate every edge, including edges that would otherwise remain after
        // all sources have been visited. No malformed condition may be ignored.
        for (Join join : joins) {
            if (join == null || join.left() == null || join.right() == null || join.type() == null
                    || !Set.of("INNER", "LEFT", "RIGHT", "FULL").contains(join.type())
                    || !sources.contains(join.left().object()) || !sources.contains(join.right().object())
                    || join.left().object().equals(join.right().object()))
                throw invalid("Join iki farklı, tanımlı kaynağı bağlamalıdır.");
            StagedMappingDefinition.identifier(join.left().column());
            StagedMappingDefinition.identifier(join.right().column());
        }
        List<Join> pending = new ArrayList<>(joins);
        Set<String> visited = new LinkedHashSet<>();
        visited.add(sourceIds.getFirst());
        List<Step> steps = new ArrayList<>();
        while (visited.size() < sources.size()) {
            Join edge = pending.stream().filter(join -> visited.contains(join.left().object())
                    ^ visited.contains(join.right().object())).findFirst()
                    .orElseThrow(() -> invalid("Tüm kaynaklar join ile bağlanmalıdır; kopuk kaynak var."));
            pending.remove(edge);
            boolean leftVisited = visited.contains(edge.left().object());
            String next = leftVisited ? edge.right().object() : edge.left().object();
            List<Join> conditions = new ArrayList<>();
            conditions.add(edge);
            for (Iterator<Join> iterator = pending.iterator(); iterator.hasNext();) {
                Join extra = iterator.next();
                boolean same = samePair(edge, extra);
                boolean reversed = reversedPair(edge, extra);
                if (!same && !reversed) continue;
                if (!extra.type().equals(reversed ? reverse(edge.type()) : edge.type()))
                    throw invalid("Aynı kaynak çifti için join türleri çelişiyor.");
                conditions.add(extra);
                iterator.remove();
            }
            steps.add(new Step(next, leftVisited ? edge.type() : reverse(edge.type()), conditions));
            visited.add(next);
        }
        // An INNER cycle is an additional conjunction. Moving an OUTER edge to
        // WHERE changes its null-preserving semantics, so that graph is rejected.
        if (!pending.isEmpty() && joins.stream().anyMatch(join -> !"INNER".equals(join.type())))
            throw invalid("Döngüsel dış join desteklenmiyor; kaynak ilişkisini açık bir ağaç olarak tanımlayın.");
        return new Plan(sourceIds.getFirst(), steps, pending);
    }

    private static boolean samePair(Join a, Join b) {
        return a.left().object().equals(b.left().object()) && a.right().object().equals(b.right().object());
    }
    private static boolean reversedPair(Join a, Join b) {
        return a.left().object().equals(b.right().object()) && a.right().object().equals(b.left().object());
    }
    private static String reverse(String type) {
        return switch (type) { case "LEFT" -> "RIGHT"; case "RIGHT" -> "LEFT"; default -> type; };
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}

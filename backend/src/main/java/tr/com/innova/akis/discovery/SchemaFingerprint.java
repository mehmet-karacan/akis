package tr.com.innova.akis.discovery;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import tr.com.innova.akis.discovery.SchemaSnapshotModels.ColumnInput;
import tr.com.innova.akis.discovery.SchemaSnapshotModels.ConstraintInput;

final class SchemaFingerprint {

    private final ObjectMapper objectMapper;

    SchemaFingerprint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String calculate(
            String engineVersion,
            int propertyVersion,
            JsonNode properties,
            List<ColumnInput> columns,
            List<ConstraintInput> constraints) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("engineVersion", engineVersion);
        root.put("propertyVersion", propertyVersion);
        root.set("properties", canonicalize(properties));

        ArrayNode columnArray = root.putArray("columns");
        columns.stream()
                .sorted(Comparator.comparingInt(ColumnInput::ordinal)
                        .thenComparing(ColumnInput::reference))
                .map(this::columnNode)
                .forEach(columnArray::add);

        ArrayNode constraintArray = root.putArray("constraints");
        constraints.stream()
                .sorted(Comparator.comparing(ConstraintInput::externalReference))
                .map(this::constraintNode)
                .forEach(constraintArray::add);
        return sha256(root.toString());
    }

    JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode canonical = objectMapper.createObjectNode();
            List<String> names = new ArrayList<>(node.propertyNames());
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

    private ObjectNode columnNode(ColumnInput column) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("reference", column.reference());
        node.put("producerType", column.producerType());
        node.put("canonicalType", column.canonicalType());
        node.put("ordinal", column.ordinal());
        putNullable(node, "precision", column.precision());
        putNullable(node, "scale", column.scale());
        putNullable(node, "length", column.length());
        putNullable(node, "timePrecision", column.timePrecision());
        node.put("nullable", column.nullable());
        putNullable(node, "defaultExpression", column.defaultExpression());
        node.put("name", column.name());
        return node;
    }

    private ObjectNode constraintNode(ConstraintInput constraint) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("externalReference", constraint.externalReference());
        node.put("type", constraint.type());
        node.put("enabled", constraint.enabled());
        node.put("detailVersion", constraint.detailVersion());
        node.set("details", canonicalize(constraint.details()));
        node.put("name", constraint.name());
        ArrayNode references = node.putArray("columnReferences");
        constraint.columnReferences().forEach(references::add);
        return node;
    }

    private void putNullable(ObjectNode node, String field, Integer value) {
        if (value == null) {
            node.putNull(field);
        }
        else {
            node.put(field, value);
        }
    }

    private void putNullable(ObjectNode node, String field, Long value) {
        if (value == null) {
            node.putNull(field);
        }
        else {
            node.put(field, value);
        }
    }

    private void putNullable(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        }
        else {
            node.put(field, value);
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}

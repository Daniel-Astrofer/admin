package io.kerosene.jctl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class OutputFormatter {
    private OutputFormatter() {}
    static void requireSupportedMode(String mode) {
        if (!java.util.List.of("text", "json", "json-pretty").contains(mode)) throw new IllegalArgumentException("Expected text, json or json-pretty");
    }
    static String format(JsonNode body, String mode) throws Exception {
        requireSupportedMode(mode);
        if ("json".equals(mode)) return new ObjectMapper().writeValueAsString(body);
        if ("json-pretty".equals(mode)) return new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(body);
        StringBuilder out = new StringBuilder(); text(body, out, 0); return out.toString().stripTrailing();
    }
    private static void text(JsonNode node, StringBuilder out, int depth) {
        String indent = "  ".repeat(depth);
        if (node.isObject()) {
            for (var field : node.properties()) {
                out.append(indent).append(field.getKey()).append("=");
                if (field.getValue().isObject()) { out.append("{\n"); text(field.getValue(), out, depth + 1); out.append(indent).append("}\n"); }
                else if (field.getValue().isArray()) {
                    out.append("[").append(field.getValue().size()).append(" items]\n");
                    for (JsonNode item : field.getValue()) text(item, out, depth + 1);
                } else out.append(field.getValue().isNull() ? "null" : field.getValue().asText()).append('\n');
            }
        } else if (node.isArray()) { for (JsonNode item : node) text(item, out, depth); }
        else out.append(indent).append(node.isNull() ? "null" : node.asText()).append('\n');
    }
}

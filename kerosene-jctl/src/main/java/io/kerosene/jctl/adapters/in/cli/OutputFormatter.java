package io.kerosene.jctl.adapters.in.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.Iterator;
import java.util.Map;

/** Converts API responses to stable CLI representations. */
public final class OutputFormatter {
    private OutputFormatter() {}

    /** Serializes one API response as compact JSON, pretty JSON, or flattened text.
     * @param body parsed JSON response tree
     * @param outputMode one of {@code text}, {@code json}, or {@code json-pretty}
     * @return rendered response without an added trailing newline
     * @throws Exception if Jackson cannot serialize the response
     */
    public static String format(JsonNode body, String outputMode) throws Exception {
        requireSupportedMode(outputMode);
        ObjectMapper mapper = new ObjectMapper();
        if ("json".equals(outputMode)) {
            return mapper.writeValueAsString(body);
        }
        if ("text".equals(outputMode)) {
            StringBuilder result = new StringBuilder();
            text(body, result, 0);
            return result.toString().stripTrailing();
        }
        if ("json-pretty".equals(outputMode)) {
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
            return mapper.writeValueAsString(body);
        }
        throw new AssertionError("validated output mode was not handled: " + outputMode);
    }

    /** Rejects unsupported modes before network I/O or response rendering. */
    static void requireSupportedMode(String outputMode) {
        if (!"text".equals(outputMode)
                && !"json".equals(outputMode)
                && !"json-pretty".equals(outputMode)) {
            throw new IllegalArgumentException(
                    "Unsupported output mode: " + outputMode + "; expected text, json or json-pretty");
        }
    }

    /** Appends a deterministic, indented key/value view of a JSON subtree. */
    private static void text(JsonNode node, StringBuilder out, int depth) {
        String indent = "  ".repeat(depth);
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode value = field.getValue();
                out.append(indent).append(field.getKey()).append("=");
                if (value.isObject()) {
                    out.append("{\n");
                    text(value, out, depth + 1);
                    out.append(indent).append("}");
                } else if (value.isArray()) {
                    out.append("[").append(value.size()).append(" items]");
                } else if (value.isNull()) {
                    out.append("null");
                } else {
                    out.append(value.asText());
                }
                if (fields.hasNext()) {
                    out.append("\n");
                }
            }
        } else if (node.isArray()) {
            out.append("[").append(node.size()).append(" items]");
        } else if (node.isNull()) {
            out.append("null");
        } else {
            out.append(node.asText());
        }
    }
}

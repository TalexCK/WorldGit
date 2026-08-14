package com.worldgit.ai;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AiToolRegistry {

    private final Map<String, AiToolDefinition> definitions = new LinkedHashMap<>();

    public void register(AiToolDefinition definition) {
        definitions.put(definition.name(), definition);
    }

    public List<AiToolDefinition> list() {
        return List.copyOf(definitions.values());
    }

    public JsonObject execute(String toolName, JsonObject arguments, AiExecutionContext context) {
        AiToolDefinition definition = definitions.get(toolName);
        if (definition == null) {
            return createErrorResult(toolName, "unknown_tool", "未知工具: " + toolName);
        }
        try {
            JsonObject normalizedArguments = arguments == null ? new JsonObject() : arguments;
            JsonObject result = definition.handler().handle(normalizedArguments, context);
            return result == null
                    ? createErrorResult(toolName, "empty_result", "工具没有返回结果")
                    : result;
        } catch (IllegalStateException exception) {
            return createErrorResult(toolName, "tool_rejected", exception.getMessage());
        } catch (Exception exception) {
            return createErrorResult(toolName, "tool_failed", exception.getMessage());
        }
    }

    public static JsonObject createErrorResult(String toolName, String errorCode, String message) {
        JsonObject result = new JsonObject();
        result.addProperty("ok", false);
        result.addProperty("tool", toolName);
        result.addProperty("errorCode", errorCode);
        result.addProperty("message", message);
        return result;
    }
}

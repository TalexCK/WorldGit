package com.worldgit.ai;

import com.google.gson.JsonObject;
import java.util.Objects;

public final class AiToolDefinition {

    @FunctionalInterface
    public interface AiToolHandler {
        JsonObject handle(JsonObject arguments, AiExecutionContext context);
    }

    private final String name;
    private final String description;
    private final JsonObject parametersSchema;
    private final AiToolHandler handler;

    public AiToolDefinition(
            String name,
            String description,
            JsonObject parametersSchema,
            AiToolHandler handler
    ) {
        this.name = Objects.requireNonNull(name, "工具名称不能为空");
        this.description = Objects.requireNonNull(description, "工具描述不能为空");
        this.parametersSchema = Objects.requireNonNull(parametersSchema, "工具参数 Schema 不能为空");
        this.handler = Objects.requireNonNull(handler, "工具处理器不能为空");
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public JsonObject parametersSchema() {
        return parametersSchema.deepCopy();
    }

    public AiToolHandler handler() {
        return handler;
    }
}

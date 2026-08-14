package com.worldgit.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.worldgit.config.PluginConfig;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class AnthropicAiProvider implements AiProvider {

    private final HttpClient httpClient;
    private final Gson gson;
    private final PluginConfig pluginConfig;
    private final AiToolRegistry toolRegistry;

    public AnthropicAiProvider(
            HttpClient httpClient,
            Gson gson,
            PluginConfig pluginConfig,
            AiToolRegistry toolRegistry
    ) {
        this.httpClient = Objects.requireNonNull(httpClient, "HTTP 客户端不能为空");
        this.gson = Objects.requireNonNull(gson, "Gson 不能为空");
        this.pluginConfig = Objects.requireNonNull(pluginConfig, "插件配置不能为空");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "工具注册表不能为空");
    }

    @Override
    public AiConversationResult runConversation(
            AiExecutionContext context,
            String systemPrompt,
            String userPrompt,
            AiImageInput imageInput,
            AiRunLogger logger
    ) throws Exception {
        JsonArray messages = new JsonArray();
        messages.add(initialUserMessage(userPrompt, imageInput));

        int requestRound = 0;
        while (true) {
            requestRound++;
            JsonObject body = new JsonObject();
            body.addProperty("model", pluginConfig.aiModel());
            body.addProperty("system", systemPrompt);
            body.addProperty("max_tokens", 2048);
            body.add("messages", messages);
            body.add("tools", anthropicTools());

            logger.info("provider_request", "请求 Anthropic Messages", requestMeta(requestRound));
            JsonObject response = AiProviderSupport.postJson(
                    httpClient,
                    gson,
                    AiProviderSupport.endpoint(pluginConfig.aiBaseUrl(), "messages"),
                    Duration.ofSeconds(pluginConfig.aiRequestTimeoutSeconds()),
                    body,
                    anthropicHeaders()
            );
            logger.info("provider_response", "收到 Anthropic Messages 响应", responseMeta(response));

            JsonArray content = response.has("content") && response.get("content").isJsonArray()
                    ? response.getAsJsonArray("content")
                    : new JsonArray();
            JsonArray toolUses = new JsonArray();
            for (int index = 0; index < content.size(); index++) {
                JsonObject block = content.get(index).getAsJsonObject();
                String type = block.has("type") ? block.get("type").getAsString() : "";
                if ("tool_use".equals(type)) {
                    toolUses.add(block.deepCopy());
                }
            }

            if (!toolUses.isEmpty()) {
                context.beginToolRound(pluginConfig.aiMaxToolRounds());

                JsonObject assistantMessage = new JsonObject();
                assistantMessage.addProperty("role", "assistant");
                assistantMessage.add("content", content.deepCopy());
                messages.add(assistantMessage);

                JsonArray toolResults = new JsonArray();
                for (int index = 0; index < toolUses.size(); index++) {
                    JsonObject toolUse = toolUses.get(index).getAsJsonObject();
                    toolResults.add(executeToolUse(toolUse, context, logger));
                }

                JsonObject userMessage = new JsonObject();
                userMessage.addProperty("role", "user");
                userMessage.add("content", toolResults);
                messages.add(userMessage);
                continue;
            }

            String finalText = AiProviderSupport.extractAnthropicText(response);
            logger.info("assistant_final", "AI 返回最终回复", stringPayload(finalText));
            return new AiConversationResult(
                    "anthropic",
                    pluginConfig.aiModel(),
                    context.sessionId(),
                    context.branch().id(),
                    context.branch().worldName(),
                    finalText,
                    context.toolRounds(),
                    context.totalBlockChanges(),
                    logger.snapshot()
            );
        }
    }

    private JsonObject executeToolUse(
            JsonObject toolUse,
            AiExecutionContext context,
            AiRunLogger logger
    ) {
        String toolName = toolUse.get("name").getAsString();
        String toolUseId = toolUse.get("id").getAsString();
        JsonObject arguments = toolUse.has("input") && toolUse.get("input").isJsonObject()
                ? toolUse.getAsJsonObject("input")
                : new JsonObject();

        logger.info("tool_call", "模型请求工具: " + toolName, toolCallMeta(toolName, arguments));
        JsonObject result = toolRegistry.execute(toolName, arguments, context);
        logger.info("tool_result", "工具执行完成: " + toolName, result.deepCopy());
        JsonObject compactResult = AiProviderSupport.compactToolResultForModel(toolName, result);

        JsonObject toolResult = new JsonObject();
        toolResult.addProperty("type", "tool_result");
        toolResult.addProperty("tool_use_id", toolUseId);
        toolResult.addProperty("content", gson.toJson(compactResult));
        if (result.has("ok") && !result.get("ok").getAsBoolean()) {
            toolResult.addProperty("is_error", true);
        }
        return toolResult;
    }

    private JsonObject initialUserMessage(String userPrompt, AiImageInput imageInput) {
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        JsonArray content = new JsonArray();

        if (imageInput != null && imageInput.isPresent()) {
            JsonObject imageBlock = new JsonObject();
            imageBlock.addProperty("type", "image");
            JsonObject source = new JsonObject();
            source.addProperty("type", "base64");
            source.addProperty("media_type", imageInput.mimeType());
            source.addProperty("data", imageInput.base64Data());
            imageBlock.add("source", source);
            content.add(imageBlock);
        }

        JsonObject textBlock = new JsonObject();
        textBlock.addProperty("type", "text");
        textBlock.addProperty("text", userPrompt);
        content.add(textBlock);

        message.add("content", content);
        return message;
    }

    private JsonArray anthropicTools() {
        JsonArray tools = new JsonArray();
        for (AiToolDefinition definition : toolRegistry.list()) {
            JsonObject tool = new JsonObject();
            tool.addProperty("name", definition.name());
            tool.addProperty("description", definition.description());
            tool.add("input_schema", definition.parametersSchema());
            tools.add(tool);
        }
        return tools;
    }

    private Map<String, String> anthropicHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-api-key", pluginConfig.aiApiKey());
        headers.put("anthropic-version", "2023-06-01");
        return headers;
    }

    private JsonObject requestMeta(int round) {
        JsonObject payload = new JsonObject();
        payload.addProperty("round", round);
        payload.addProperty("model", pluginConfig.aiModel());
        return payload;
    }

    private JsonObject responseMeta(JsonObject response) {
        JsonObject payload = new JsonObject();
        payload.addProperty("id", response.has("id") && !response.get("id").isJsonNull()
                ? response.get("id").getAsString()
                : "");
        payload.addProperty("stopReason", response.has("stop_reason") && !response.get("stop_reason").isJsonNull()
                ? response.get("stop_reason").getAsString()
                : "");
        return payload;
    }

    private JsonObject toolCallMeta(String toolName, JsonObject arguments) {
        JsonObject payload = new JsonObject();
        payload.addProperty("tool", toolName);
        payload.add("arguments", arguments.deepCopy());
        return payload;
    }

    private JsonObject stringPayload(String value) {
        JsonObject payload = new JsonObject();
        payload.addProperty("text", value == null ? "" : value);
        return payload;
    }
}

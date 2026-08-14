package com.worldgit.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.worldgit.config.PluginConfig;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class OpenAiProvider implements AiProvider {

    private final HttpClient httpClient;
    private final Gson gson;
    private final PluginConfig pluginConfig;
    private final AiToolRegistry toolRegistry;

    public OpenAiProvider(
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
        if ("completion".equalsIgnoreCase(pluginConfig.aiOpenAiMode())) {
            return runChatCompletions(context, systemPrompt, userPrompt, imageInput, logger);
        }
        return runResponses(context, systemPrompt, userPrompt, imageInput, logger);
    }

    private AiConversationResult runChatCompletions(
            AiExecutionContext context,
            String systemPrompt,
            String userPrompt,
            AiImageInput imageInput,
            AiRunLogger logger
    ) throws Exception {
        JsonArray messages = new JsonArray();
        messages.add(simpleMessage("system", systemPrompt));
        messages.add(chatUserMessage(userPrompt, imageInput));

        int requestRound = 0;
        while (true) {
            requestRound++;
            JsonObject body = new JsonObject();
            body.addProperty("model", pluginConfig.aiModel());
            body.add("messages", messages);
            body.add("tools", chatTools());
            body.addProperty("tool_choice", "auto");
            body.addProperty("max_completion_tokens", 2048);

            logger.info("provider_request", "请求 OpenAI Chat Completions", requestMeta("completion", requestRound));
            JsonObject response = AiProviderSupport.postJson(
                    httpClient,
                    gson,
                    AiProviderSupport.endpoint(pluginConfig.aiBaseUrl(), "chat/completions"),
                    Duration.ofSeconds(pluginConfig.aiRequestTimeoutSeconds()),
                    body,
                    bearerHeaders()
            );
            logger.info("provider_response", "收到 OpenAI Chat Completions 响应", responseMeta(response));

            JsonObject choice = response.getAsJsonArray("choices").get(0).getAsJsonObject();
            JsonObject message = choice.getAsJsonObject("message");
            JsonArray toolCalls = message.has("tool_calls") && message.get("tool_calls").isJsonArray()
                    ? message.getAsJsonArray("tool_calls")
                    : null;

            if (toolCalls != null && !toolCalls.isEmpty()) {
                context.beginToolRound(pluginConfig.aiMaxToolRounds());
                JsonObject assistantMessage = new JsonObject();
                assistantMessage.addProperty("role", "assistant");
                assistantMessage.add("content", message.has("content") ? message.get("content").deepCopy() : JsonNull.INSTANCE);
                assistantMessage.add("tool_calls", toolCalls.deepCopy());
                messages.add(assistantMessage);

                for (int index = 0; index < toolCalls.size(); index++) {
                    JsonObject toolCall = toolCalls.get(index).getAsJsonObject();
                    messages.add(executeChatToolCall(toolCall, context, logger));
                }
                continue;
            }

            String finalText = AiProviderSupport.extractChatMessageText(message);
            logger.info("assistant_final", "AI 返回最终回复", stringPayload(finalText));
            return new AiConversationResult(
                    "openai",
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

    private AiConversationResult runResponses(
            AiExecutionContext context,
            String systemPrompt,
            String userPrompt,
            AiImageInput imageInput,
            AiRunLogger logger
    ) throws Exception {
        JsonObject response = requestResponses(
                null,
                systemPrompt,
                initialResponsesInput(userPrompt, imageInput),
                1,
                logger
        );

        while (true) {
            JsonArray output = response.has("output") && response.get("output").isJsonArray()
                    ? response.getAsJsonArray("output")
                    : new JsonArray();
            JsonArray functionCalls = new JsonArray();
            for (int index = 0; index < output.size(); index++) {
                JsonObject item = output.get(index).getAsJsonObject();
                String type = item.has("type") ? item.get("type").getAsString() : "";
                if ("function_call".equals(type)) {
                    functionCalls.add(item.deepCopy());
                }
            }

            if (!functionCalls.isEmpty()) {
                context.beginToolRound(pluginConfig.aiMaxToolRounds());
                String previousResponseId = response.has("id") && !response.get("id").isJsonNull()
                        ? response.get("id").getAsString()
                        : null;
                if (previousResponseId == null || previousResponseId.isBlank()) {
                    throw new IllegalStateException("Responses API 未返回 response id，无法继续工具循环");
                }

                JsonArray toolOutputs = new JsonArray();
                for (int index = 0; index < functionCalls.size(); index++) {
                    JsonObject toolCall = functionCalls.get(index).getAsJsonObject();
                    toolOutputs.add(executeResponsesToolCall(toolCall, context, logger));
                }
                response = requestResponses(previousResponseId, systemPrompt, toolOutputs, context.toolRounds() + 1, logger);
                continue;
            }

            String finalText = AiProviderSupport.extractResponsesText(response);
            logger.info("assistant_final", "AI 返回最终回复", stringPayload(finalText));
            return new AiConversationResult(
                    "openai",
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

    private JsonObject requestResponses(
            String previousResponseId,
            String systemPrompt,
            JsonArray input,
            int round,
            AiRunLogger logger
    ) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", pluginConfig.aiModel());
        body.addProperty("store", true);
        body.addProperty("max_output_tokens", 2048);
        body.add("tools", responsesTools());
        body.addProperty("tool_choice", "auto");
        if (previousResponseId == null) {
            body.addProperty("instructions", systemPrompt);
        } else {
            body.addProperty("previous_response_id", previousResponseId);
            // 续轮时也带上 instructions，避免某些兼容实现丢失上下文。
            body.addProperty("instructions", systemPrompt);
        }
        body.add("input", input);

        logger.info("provider_request", "请求 OpenAI Responses", requestMeta("responses", round));
        JsonObject response;
        try {
            response = AiProviderSupport.postJson(
                    httpClient,
                    gson,
                    AiProviderSupport.endpoint(pluginConfig.aiBaseUrl(), "responses"),
                    Duration.ofSeconds(pluginConfig.aiRequestTimeoutSeconds()),
                    body,
                    bearerHeaders()
            );
        } catch (IllegalStateException exception) {
            String message = exception.getMessage() == null ? "" : exception.getMessage();
            // previous_response_id 找不到 — 通常是第三方服务不完整支持 Responses API
            if (previousResponseId != null
                    && (message.contains("Previous response") || message.contains("previous_response_id"))
                    && (message.contains("not found") || message.contains("not_found") || message.contains("invalid"))) {
                throw new IllegalStateException(
                        "当前 OpenAI 风格 provider 的 /responses 接口无法继续 previous_response_id 续轮。"
                                + "这通常是因为该服务没有完整实现 Responses API 状态续写。"
                                + "建议改用 ai.openai-mode: completion，或切换到支持完整 Responses API 的服务。"
                );
            }
            // 404 / 不支持 /responses 端点
            if (message.contains("404") || message.contains("not_found") || message.contains("Not Found")) {
                throw new IllegalStateException(
                        "当前 AI 服务不支持 /responses 端点。"
                                + "请在配置中设置 ai.openai-mode: completion 以使用 Chat Completions API。"
                );
            }
            throw exception;
        }
        logger.info("provider_response", "收到 OpenAI Responses 响应", responseMeta(response));
        return response;
    }

    private JsonObject executeChatToolCall(
            JsonObject toolCall,
            AiExecutionContext context,
            AiRunLogger logger
    ) {
        String toolId = toolCall.has("id") ? toolCall.get("id").getAsString() : "";
        JsonObject function = toolCall.getAsJsonObject("function");
        String toolName = function.get("name").getAsString();
        JsonObject arguments = parseToolArguments(toolName, function.get("arguments").getAsString(), logger);

        logger.info("tool_call", "模型请求工具: " + toolName, toolCallMeta(toolName, arguments));
        JsonObject result = toolRegistry.execute(toolName, arguments, context);
        logger.info("tool_result", "工具执行完成: " + toolName, result.deepCopy());
        JsonObject compactResult = AiProviderSupport.compactToolResultForModel(toolName, result);

        JsonObject toolMessage = new JsonObject();
        toolMessage.addProperty("role", "tool");
        toolMessage.addProperty("tool_call_id", toolId);
        toolMessage.addProperty("content", gson.toJson(compactResult));
        return toolMessage;
    }

    private JsonObject executeResponsesToolCall(
            JsonObject toolCall,
            AiExecutionContext context,
            AiRunLogger logger
    ) {
        String toolName = toolCall.get("name").getAsString();
        String callId = toolCall.get("call_id").getAsString();
        JsonObject arguments = parseToolArguments(toolName, toolCall.get("arguments").getAsString(), logger);

        logger.info("tool_call", "模型请求工具: " + toolName, toolCallMeta(toolName, arguments));
        JsonObject result = toolRegistry.execute(toolName, arguments, context);
        logger.info("tool_result", "工具执行完成: " + toolName, result.deepCopy());
        JsonObject compactResult = AiProviderSupport.compactToolResultForModel(toolName, result);

        JsonObject toolOutput = new JsonObject();
        toolOutput.addProperty("type", "function_call_output");
        toolOutput.addProperty("call_id", callId);
        toolOutput.addProperty("output", gson.toJson(compactResult));
        return toolOutput;
    }

    private JsonObject parseToolArguments(String toolName, String rawArguments, AiRunLogger logger) {
        try {
            return AiProviderSupport.parseArgumentsObject(rawArguments);
        } catch (IllegalStateException exception) {
            logger.warn("tool_parse_error", "工具参数解析失败: " + toolName, stringPayload(rawArguments));
            return new JsonObject();
        }
    }

    private JsonObject simpleMessage(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    private JsonObject chatUserMessage(String userPrompt, AiImageInput imageInput) {
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        if (imageInput == null || !imageInput.isPresent()) {
            message.addProperty("content", userPrompt);
            return message;
        }

        JsonArray content = new JsonArray();
        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", userPrompt);
        content.add(textPart);

        JsonObject imagePart = new JsonObject();
        imagePart.addProperty("type", "image_url");
        JsonObject imageUrl = new JsonObject();
        imageUrl.addProperty("url", imageInput.dataUrl());
        imagePart.add("image_url", imageUrl);
        content.add(imagePart);

        message.add("content", content);
        return message;
    }

    private JsonArray initialResponsesInput(String userPrompt, AiImageInput imageInput) {
        JsonArray input = new JsonArray();
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        JsonArray content = new JsonArray();

        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "input_text");
        textPart.addProperty("text", userPrompt);
        content.add(textPart);

        if (imageInput != null && imageInput.isPresent()) {
            JsonObject imagePart = new JsonObject();
            imagePart.addProperty("type", "input_image");
            imagePart.addProperty("image_url", imageInput.dataUrl());
            content.add(imagePart);
        }

        message.add("content", content);
        input.add(message);
        return input;
    }

    private JsonArray chatTools() {
        JsonArray tools = new JsonArray();
        for (AiToolDefinition definition : toolRegistry.list()) {
            JsonObject tool = new JsonObject();
            tool.addProperty("type", "function");
            JsonObject function = new JsonObject();
            function.addProperty("name", definition.name());
            function.addProperty("description", definition.description());
            function.add("parameters", definition.parametersSchema());
            function.addProperty("strict", true);
            tool.add("function", function);
            tools.add(tool);
        }
        return tools;
    }

    private JsonArray responsesTools() {
        JsonArray tools = new JsonArray();
        for (AiToolDefinition definition : toolRegistry.list()) {
            JsonObject tool = new JsonObject();
            tool.addProperty("type", "function");
            tool.addProperty("name", definition.name());
            tool.addProperty("description", definition.description());
            tool.add("parameters", definition.parametersSchema());
            tool.addProperty("strict", true);
            tools.add(tool);
        }
        return tools;
    }

    private Map<String, String> bearerHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + pluginConfig.aiApiKey());
        return headers;
    }

    private JsonObject requestMeta(String mode, int round) {
        JsonObject payload = new JsonObject();
        payload.addProperty("mode", mode);
        payload.addProperty("round", round);
        payload.addProperty("model", pluginConfig.aiModel());
        return payload;
    }

    private JsonObject responseMeta(JsonObject response) {
        JsonObject payload = new JsonObject();
        payload.addProperty("id", response.has("id") && !response.get("id").isJsonNull()
                ? response.get("id").getAsString()
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

package com.worldgit.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

final class AiProviderSupport {

    private AiProviderSupport() {
    }

    static URI endpoint(String baseUrl, String path) {
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String normalizedPath = path.startsWith("/") ? path.substring(1) : path;
        return URI.create(normalizedBase + "/" + normalizedPath);
    }

    static JsonObject postJson(
            HttpClient httpClient,
            Gson gson,
            URI uri,
            Duration timeout,
            JsonObject body,
            Map<String, String> headers
    ) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8));
        for (Map.Entry<String, String> header : headers.entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }
        HttpResponse<String> response = httpClient.send(
                builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        JsonObject responseBody = parseObject(response.body());
        if (response.statusCode() >= 400) {
            String message = extractErrorMessage(responseBody);
            throw new IllegalStateException("AI Provider 请求失败(" + response.statusCode() + "): " + message);
        }
        return responseBody;
    }

    static JsonObject parseObject(String raw) {
        try {
            JsonElement parsed = JsonParser.parseString(raw);
            if (!parsed.isJsonObject()) {
                throw new IllegalStateException("AI Provider 返回了非对象 JSON");
            }
            return parsed.getAsJsonObject();
        } catch (JsonSyntaxException exception) {
            throw new IllegalStateException("AI Provider 返回了非法 JSON", exception);
        }
    }

    static JsonObject parseArgumentsObject(String raw) {
        if (raw == null || raw.isBlank()) {
            return new JsonObject();
        }
        JsonObject parsed = parseObject(raw);
        return parsed == null ? new JsonObject() : parsed;
    }

    static String extractChatMessageText(JsonObject message) {
        if (message == null || !message.has("content") || message.get("content").isJsonNull()) {
            return "";
        }
        JsonElement content = message.get("content");
        if (content.isJsonPrimitive()) {
            return content.getAsString();
        }
        if (content.isJsonArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonElement element : content.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject object = element.getAsJsonObject();
                if (object.has("text") && !object.get("text").isJsonNull()) {
                    if (builder.length() > 0) {
                        builder.append('\n');
                    }
                    builder.append(object.get("text").getAsString());
                }
            }
            return builder.toString();
        }
        return "";
    }

    static String extractResponsesText(JsonObject response) {
        if (response.has("output_text") && !response.get("output_text").isJsonNull()) {
            return response.get("output_text").getAsString();
        }
        if (!response.has("output") || !response.get("output").isJsonArray()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        JsonArray output = response.getAsJsonArray("output");
        for (JsonElement itemElement : output) {
            if (!itemElement.isJsonObject()) {
                continue;
            }
            JsonObject item = itemElement.getAsJsonObject();
            if (!item.has("type") || !"message".equals(item.get("type").getAsString())) {
                continue;
            }
            if (!item.has("content") || !item.get("content").isJsonArray()) {
                continue;
            }
            for (JsonElement contentElement : item.getAsJsonArray("content")) {
                if (!contentElement.isJsonObject()) {
                    continue;
                }
                JsonObject content = contentElement.getAsJsonObject();
                String type = content.has("type") ? content.get("type").getAsString() : "";
                if (!"output_text".equals(type) && !"text".equals(type)) {
                    continue;
                }
                String text = content.has("text") && !content.get("text").isJsonNull()
                        ? content.get("text").getAsString()
                        : "";
                if (!text.isBlank()) {
                    if (builder.length() > 0) {
                        builder.append('\n');
                    }
                    builder.append(text);
                }
            }
        }
        return builder.toString();
    }

    static String extractAnthropicText(JsonObject response) {
        if (!response.has("content") || !response.get("content").isJsonArray()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (JsonElement element : response.getAsJsonArray("content")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject block = element.getAsJsonObject();
            String type = block.has("type") ? block.get("type").getAsString() : "";
            if (!"text".equals(type)) {
                continue;
            }
            String text = block.has("text") && !block.get("text").isJsonNull()
                    ? block.get("text").getAsString()
                    : "";
            if (!text.isBlank()) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(text);
            }
        }
        return builder.toString();
    }

    static String extractErrorMessage(JsonObject responseBody) {
        if (responseBody == null) {
            return "未知错误";
        }
        if (responseBody.has("error")) {
            JsonElement error = responseBody.get("error");
            if (error.isJsonPrimitive()) {
                return error.getAsString();
            }
            if (error.isJsonObject()) {
                JsonObject errorObject = error.getAsJsonObject();
                if (errorObject.has("message") && !errorObject.get("message").isJsonNull()) {
                    return errorObject.get("message").getAsString();
                }
                if (errorObject.has("type") && !errorObject.get("type").isJsonNull()) {
                    return errorObject.get("type").getAsString();
                }
            }
        }
        if (responseBody.has("message") && !responseBody.get("message").isJsonNull()) {
            return responseBody.get("message").getAsString();
        }
        return responseBody.toString();
    }

    static JsonElement nonNull(JsonElement element) {
        return element == null ? JsonNull.INSTANCE : element;
    }

    static JsonObject compactToolResultForModel(String toolName, JsonObject result) {
        JsonObject source = result == null ? new JsonObject() : result;
        JsonObject compact = new JsonObject();
        compact.addProperty("ok", source.has("ok") && !source.get("ok").isJsonNull() && source.get("ok").getAsBoolean());
        compact.addProperty("tool", readString(source, "tool", toolName));
        copyScalar(source, compact, "branchId");
        copyScalar(source, compact, "worldName");
        copyScalar(source, compact, "status");
        copyScalar(source, compact, "changedCount");
        copyScalar(source, compact, "lineCount");
        copyScalar(source, compact, "blockCount");
        copyScalar(source, compact, "nonAirCount");
        copyScalar(source, compact, "airCount");
        copyScalar(source, compact, "uniqueMaterialCount");
        copyScalar(source, compact, "material");
        copyScalar(source, compact, "blockData");
        copyScalar(source, compact, "message");
        copyScalar(source, compact, "errorCode");

        if (source.has("box") && source.get("box").isJsonObject()) {
            compact.add("box", compactBox(source.getAsJsonObject("box")));
        }
        if (source.has("block") && source.get("block").isJsonObject()) {
            compact.add("block", compactBlock(source.getAsJsonObject("block")));
        }
        if (source.has("materials") && source.get("materials").isJsonArray()) {
            compact.add("materials", compactMaterials(source.getAsJsonArray("materials"), 12));
        }
        if (source.has("sampleBlocks") && source.get("sampleBlocks").isJsonArray()) {
            compact.add("sampleBlocks", compactBlocks(source.getAsJsonArray("sampleBlocks"), 12));
        }
        if (source.has("blocks") && source.get("blocks").isJsonArray()) {
            JsonArray blocks = source.getAsJsonArray("blocks");
            if (blocks.size() <= 24) {
                compact.add("blocks", compactBlocks(blocks, 24));
            } else {
                compact.addProperty("returnedBlockEntries", blocks.size());
                if (!compact.has("sampleBlocks")) {
                    compact.add("sampleBlocks", compactBlocks(blocks, 8));
                }
                if (!compact.has("materials")) {
                    compact.add("materials", summarizeMaterialsFromBlocks(blocks, 10));
                }
            }
        }
        if (source.has("placements") && source.get("placements").isJsonArray()) {
            JsonArray placements = source.getAsJsonArray("placements");
            compact.addProperty("returnedPlacementEntries", placements.size());
            compact.add("samplePlacements", compactPlacements(placements, 6));
        }
        return compact;
    }

    private static void copyScalar(JsonObject source, JsonObject target, String fieldName) {
        if (!source.has(fieldName) || source.get(fieldName).isJsonNull()) {
            return;
        }
        target.add(fieldName, source.get(fieldName).deepCopy());
    }

    private static String readString(JsonObject source, String fieldName, String fallback) {
        if (!source.has(fieldName) || source.get(fieldName).isJsonNull()) {
            return fallback;
        }
        return source.get(fieldName).getAsString();
    }

    private static JsonObject compactBox(JsonObject box) {
        JsonObject compact = new JsonObject();
        copyScalar(box, compact, "minX");
        copyScalar(box, compact, "minY");
        copyScalar(box, compact, "minZ");
        copyScalar(box, compact, "maxX");
        copyScalar(box, compact, "maxY");
        copyScalar(box, compact, "maxZ");
        copyScalar(box, compact, "volume");
        return compact;
    }

    private static JsonObject compactBlock(JsonObject block) {
        JsonObject compact = new JsonObject();
        copyScalar(block, compact, "x");
        copyScalar(block, compact, "y");
        copyScalar(block, compact, "z");
        copyScalar(block, compact, "material");
        copyScalar(block, compact, "blockData");
        return compact;
    }

    private static JsonArray compactBlocks(JsonArray blocks, int limit) {
        JsonArray compact = new JsonArray();
        int size = Math.min(limit, blocks.size());
        for (int index = 0; index < size; index++) {
            JsonElement element = blocks.get(index);
            if (element.isJsonObject()) {
                compact.add(compactBlock(element.getAsJsonObject()));
            }
        }
        return compact;
    }

    private static JsonArray compactMaterials(JsonArray materials, int limit) {
        JsonArray compact = new JsonArray();
        int size = Math.min(limit, materials.size());
        for (int index = 0; index < size; index++) {
            JsonElement element = materials.get(index);
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject source = element.getAsJsonObject();
            JsonObject item = new JsonObject();
            copyScalar(source, item, "material");
            copyScalar(source, item, "count");
            copyScalar(source, item, "ratio");
            compact.add(item);
        }
        return compact;
    }

    private static JsonArray summarizeMaterialsFromBlocks(JsonArray blocks, int limit) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (JsonElement element : blocks) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject block = element.getAsJsonObject();
            String material = readString(block, "material", "UNKNOWN");
            counts.merge(material, 1, Integer::sum);
        }
        JsonArray materials = new JsonArray();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(limit)
                .forEach(entry -> {
                    JsonObject item = new JsonObject();
                    item.addProperty("material", entry.getKey());
                    item.addProperty("count", entry.getValue());
                    materials.add(item);
                });
        return materials;
    }

    private static JsonArray compactPlacements(JsonArray placements, int limit) {
        JsonArray compact = new JsonArray();
        int size = Math.min(limit, placements.size());
        for (int index = 0; index < size; index++) {
            JsonElement element = placements.get(index);
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject source = element.getAsJsonObject();
            JsonObject item = new JsonObject();
            copyScalar(source, item, "lineNumber");
            copyScalar(source, item, "material");
            copyScalar(source, item, "changedCount");
            if (source.has("box") && source.get("box").isJsonObject()) {
                item.add("box", compactBox(source.getAsJsonObject("box")));
            }
            compact.add(item);
        }
        return compact;
    }
}

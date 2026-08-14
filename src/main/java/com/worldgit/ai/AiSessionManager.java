package com.worldgit.ai;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class AiSessionManager {

    private static final String JWT_ALGORITHM = "HS256";
    private static final String JWT_TYPE = "JWT";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final Duration ttl;
    private final byte[] signingKey;
    private final Gson gson;

    public AiSessionManager(Duration ttl, byte[] signingKey, Gson gson) {
        this.ttl = Objects.requireNonNull(ttl, "会话 TTL 不能为空");
        this.signingKey = Objects.requireNonNull(signingKey, "JWT 签名密钥不能为空").clone();
        if (this.signingKey.length < 32) {
            throw new IllegalStateException("JWT 签名密钥至少需要 32 字节");
        }
        this.gson = Objects.requireNonNull(gson, "JSON 工具不能为空");
    }

    public AiSession createSession(UUID playerUuid, String playerName) {
        return createSession(playerUuid, playerName, ttl);
    }

    public AiSession createSession(UUID playerUuid, String playerName, Duration sessionTtl) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(sessionTtl);
        JsonObject header = new JsonObject();
        header.addProperty("alg", JWT_ALGORITHM);
        header.addProperty("typ", JWT_TYPE);

        JsonObject payload = new JsonObject();
        payload.addProperty("sub", playerUuid.toString());
        payload.addProperty("name", playerName);
        payload.addProperty("iat", now.getEpochSecond());
        payload.addProperty("exp", expiresAt.getEpochSecond());

        String token = encode(header) + "." + encode(payload);
        token = token + "." + sign(token);
        return new AiSession(token, playerUuid, playerName, now, expiresAt);
    }

    public AiSession requireSession(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("缺少 AI 会话令牌");
        }
        String normalized = token.trim();
        String[] parts = normalized.split("\\.", -1);
        if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
            throw new IllegalStateException("AI 会话令牌格式无效");
        }

        String signingInput = parts[0] + "." + parts[1];
        if (!MessageDigest.isEqual(sign(signingInput).getBytes(StandardCharsets.UTF_8), parts[2].getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalStateException("AI 会话签名无效，请重新登录");
        }

        JsonObject header = decodeObject(parts[0]);
        if (!JWT_ALGORITHM.equals(getString(header, "alg")) || !JWT_TYPE.equals(getString(header, "typ"))) {
            throw new IllegalStateException("AI 会话令牌头无效");
        }

        JsonObject payload = decodeObject(parts[1]);
        UUID playerUuid = UUID.fromString(getRequiredString(payload, "sub"));
        String playerName = getRequiredString(payload, "name");
        Instant issuedAt = Instant.ofEpochSecond(getRequiredLong(payload, "iat"));
        Instant expiresAt = Instant.ofEpochSecond(getRequiredLong(payload, "exp"));
        if (expiresAt.isBefore(Instant.now())) {
            throw new IllegalStateException("AI 会话已过期，请重新登录");
        }
        return new AiSession(normalized, playerUuid, playerName, issuedAt, expiresAt);
    }

    public void invalidatePlayerSessions(UUID playerUuid) {
        // JWT 是无状态令牌；如需立即撤销，后续可追加 tokenVersion 或黑名单表。
    }

    public void clear() {
        // JWT 会话无需清理内存状态。
    }

    private String encode(JsonObject object) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(gson.toJson(object).getBytes(StandardCharsets.UTF_8));
    }

    private JsonObject decodeObject(String encoded) {
        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("AI 会话令牌不是合法 Base64URL", exception);
        }
        var parsed = JsonParser.parseString(new String(decoded, StandardCharsets.UTF_8));
        if (!parsed.isJsonObject()) {
            throw new IllegalStateException("AI 会话令牌载荷无效");
        }
        return parsed.getAsJsonObject();
    }

    private String sign(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(signingKey, HMAC_ALGORITHM));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("生成 JWT 签名失败", exception);
        }
    }

    private String getString(JsonObject object, String fieldName) {
        if (!object.has(fieldName) || object.get(fieldName).isJsonNull()) {
            return null;
        }
        return object.get(fieldName).getAsString();
    }

    private String getRequiredString(JsonObject object, String fieldName) {
        String value = getString(object, fieldName);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("AI 会话令牌缺少字段: " + fieldName);
        }
        return value;
    }

    private long getRequiredLong(JsonObject object, String fieldName) {
        if (!object.has(fieldName) || object.get(fieldName).isJsonNull()) {
            throw new IllegalStateException("AI 会话令牌缺少字段: " + fieldName);
        }
        return object.get(fieldName).getAsLong();
    }
}

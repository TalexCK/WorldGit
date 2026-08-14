package com.worldgit.web;

import com.google.gson.JsonObject;
import com.worldgit.ai.WorldGitAiService;
import com.worldgit.util.MessageUtil;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class WebLoginRequestService {

    private static final Duration REQUEST_TTL = Duration.ofMinutes(1);
    private static final Duration SESSION_TTL = Duration.ofDays(7);
    private static final int REQUEST_ID_BYTES = 18;

    private final WorldGitAiService aiService;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, PendingLoginRequest> requests = new ConcurrentHashMap<>();

    public WebLoginRequestService(WorldGitAiService aiService) {
        this.aiService = Objects.requireNonNull(aiService, "AI 服务不能为空");
    }

    public JsonObject createRequest(String playerId) {
        purgeExpired();
        Player player = resolveOnlinePlayer(playerId);
        String requestId = randomRequestId();
        Instant now = Instant.now();
        PendingLoginRequest request = new PendingLoginRequest(
                requestId,
                player.getUniqueId(),
                player.getName(),
                now,
                now.plus(REQUEST_TTL),
                LoginRequestStatus.PENDING,
                null
        );
        requests.put(requestId, request);
        sendLoginPrompt(player, request);
        return requestJson(request, false);
    }

    public JsonObject status(String requestId) {
        purgeExpired();
        PendingLoginRequest request = requireRequest(requestId);
        if (request.isExpired()) {
            request = request.withStatus(LoginRequestStatus.EXPIRED);
            requests.put(request.id(), request);
        }
        return requestJson(request, true);
    }

    public boolean accept(Player player, String requestId) {
        PendingLoginRequest request = requireRequest(requestId);
        ensureRequestPlayer(player, request);
        if (request.isExpired()) {
            requests.put(request.id(), request.withStatus(LoginRequestStatus.EXPIRED));
            throw new IllegalStateException("登录请求已过期，请在网页重新发起。");
        }
        if (request.status() != LoginRequestStatus.PENDING) {
            throw new IllegalStateException("登录请求已处理。");
        }
        JsonObject sessionPayload = aiService.createWebSession(player, SESSION_TTL);
        requests.put(request.id(), request.withAccepted(sessionPayload));
        return true;
    }

    public boolean deny(Player player, String requestId) {
        PendingLoginRequest request = requireRequest(requestId);
        ensureRequestPlayer(player, request);
        if (request.status() != LoginRequestStatus.PENDING) {
            throw new IllegalStateException("登录请求已处理。");
        }
        requests.put(request.id(), request.withStatus(LoginRequestStatus.DENIED));
        return true;
    }

    private void sendLoginPrompt(Player player, PendingLoginRequest request) {
        Component accept = Component.text(" [Accept]", NamedTextColor.GREEN)
                .clickEvent(ClickEvent.runCommand("/secret accept " + request.id()))
                .hoverEvent(HoverEvent.showText(Component.text("允许本次 Web 登录", NamedTextColor.GREEN)));
        Component deny = Component.text(" [Deny]", NamedTextColor.RED)
                .clickEvent(ClickEvent.runCommand("/secret deny " + request.id()))
                .hoverEvent(HoverEvent.showText(Component.text("拒绝本次 Web 登录", NamedTextColor.RED)));
        player.sendMessage(MessageUtil.prefix()
                .append(Component.text("收到 WorldGit Web 登录请求，1 分钟内有效。", NamedTextColor.YELLOW))
                .append(accept)
                .append(deny));
    }

    private JsonObject requestJson(PendingLoginRequest request, boolean includeSession) {
        JsonObject payload = new JsonObject();
        payload.addProperty("ok", true);
        payload.addProperty("requestId", request.id());
        payload.addProperty("playerUuid", request.playerUuid().toString());
        payload.addProperty("playerName", request.playerName());
        payload.addProperty("status", request.status().name().toLowerCase(Locale.ROOT));
        payload.addProperty("expiresAt", request.expiresAt().toString());
        if (includeSession && request.sessionPayload() != null) {
            payload.add("sessionPayload", request.sessionPayload());
        }
        return payload;
    }

    private PendingLoginRequest requireRequest(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalStateException("缺少登录请求ID");
        }
        PendingLoginRequest request = requests.get(requestId.trim());
        if (request == null) {
            throw new IllegalStateException("登录请求不存在或已过期");
        }
        return request;
    }

    private void ensureRequestPlayer(Player player, PendingLoginRequest request) {
        if (player == null || !player.getUniqueId().equals(request.playerUuid())) {
            throw new IllegalStateException("你不能处理其他玩家的登录请求。");
        }
    }

    private Player resolveOnlinePlayer(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalStateException("请输入玩家ID");
        }
        String normalized = playerId.trim();
        Player exact = Bukkit.getPlayerExact(normalized);
        if (exact != null && exact.isOnline()) {
            return exact;
        }
        try {
            Player byUuid = Bukkit.getPlayer(UUID.fromString(normalized));
            if (byUuid != null && byUuid.isOnline()) {
                return byUuid;
            }
        } catch (IllegalArgumentException ignored) {
            // 输入不是 UUID 时按玩家名继续查找。
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().equalsIgnoreCase(normalized)) {
                return player;
            }
        }
        throw new IllegalStateException("玩家不在线或不存在: " + normalized);
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        requests.entrySet().removeIf(entry -> {
            PendingLoginRequest request = entry.getValue();
            if (request.status() == LoginRequestStatus.PENDING) {
                return request.expiresAt().plus(REQUEST_TTL).isBefore(now);
            }
            return request.expiresAt().plus(Duration.ofMinutes(2)).isBefore(now);
        });
    }

    private String randomRequestId() {
        byte[] bytes = new byte[REQUEST_ID_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private enum LoginRequestStatus {
        PENDING,
        ACCEPTED,
        DENIED,
        EXPIRED
    }

    private record PendingLoginRequest(
            String id,
            UUID playerUuid,
            String playerName,
            Instant createdAt,
            Instant expiresAt,
            LoginRequestStatus status,
            JsonObject sessionPayload
    ) {

        boolean isExpired() {
            return status == LoginRequestStatus.PENDING && expiresAt.isBefore(Instant.now());
        }

        PendingLoginRequest withStatus(LoginRequestStatus newStatus) {
            return new PendingLoginRequest(id, playerUuid, playerName, createdAt, expiresAt, newStatus, sessionPayload);
        }

        PendingLoginRequest withAccepted(JsonObject newSessionPayload) {
            return new PendingLoginRequest(id, playerUuid, playerName, createdAt, expiresAt, LoginRequestStatus.ACCEPTED, newSessionPayload);
        }
    }
}

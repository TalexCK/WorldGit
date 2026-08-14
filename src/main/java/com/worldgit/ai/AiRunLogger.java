package com.worldgit.ai;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.worldgit.database.AiAuditRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class AiRunLogger {

    private final Gson gson;
    private final AiAuditRepository auditRepository;
    private final String sessionId;
    private final UUID playerUuid;
    private final String branchId;
    private final String provider;
    private final String model;
    private final int maxPayloadLength;
    private final List<AiLogEntry> entries = new ArrayList<>();
    private volatile Listener listener;

    @FunctionalInterface
    public interface Listener {
        void onEntry(AiLogEntry entry);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public AiRunLogger(
            Gson gson,
            AiAuditRepository auditRepository,
            String sessionId,
            UUID playerUuid,
            String branchId,
            String provider,
            String model,
            int maxPayloadLength
    ) {
        this.gson = Objects.requireNonNull(gson, "Gson 不能为空");
        this.auditRepository = Objects.requireNonNull(auditRepository, "审计仓储不能为空");
        this.sessionId = Objects.requireNonNull(sessionId, "会话 ID 不能为空");
        this.playerUuid = Objects.requireNonNull(playerUuid, "玩家 UUID 不能为空");
        this.branchId = branchId;
        this.provider = Objects.requireNonNull(provider, "provider 不能为空");
        this.model = Objects.requireNonNull(model, "model 不能为空");
        this.maxPayloadLength = Math.max(256, maxPayloadLength);
    }

    public void info(String type, String message) {
        log("info", type, message, JsonNull.INSTANCE);
    }

    public void info(String type, String message, JsonElement data) {
        log("info", type, message, data);
    }

    public void warn(String type, String message, JsonElement data) {
        log("warn", type, message, data);
    }

    public void error(String type, String message, JsonElement data) {
        log("error", type, message, data);
    }

    public List<AiLogEntry> snapshot() {
        synchronized (entries) {
            return List.copyOf(entries);
        }
    }

    private void log(String level, String type, String message, JsonElement data) {
        Instant now = Instant.now();
        JsonElement safeData = data == null ? JsonNull.INSTANCE : data.deepCopy();
        AiLogEntry entry = new AiLogEntry(level, type, message, now, safeData);
        synchronized (entries) {
            entries.add(entry);
        }
        Listener currentListener = listener;
        if (currentListener != null) {
            try {
                currentListener.onEntry(entry);
            } catch (RuntimeException ignored) {
                // 监听器异常不应阻断日志写入。
            }
        }
        try {
            auditRepository.append(
                    sessionId,
                    playerUuid,
                    branchId,
                    provider,
                    model,
                    type,
                    truncate(gson.toJson(safeData)),
                    now
            );
        } catch (RuntimeException ignored) {
            // 审计失败不应阻断主流程。
        }
    }

    private String truncate(String value) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxPayloadLength) {
            return value;
        }
        return value.substring(0, maxPayloadLength) + "...";
    }
}

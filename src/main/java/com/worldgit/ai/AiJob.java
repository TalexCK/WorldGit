package com.worldgit.ai;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class AiJob {

    public enum Status {
        RUNNING,
        DONE,
        ERROR
    }

    private final String id;
    private final UUID playerUuid;
    private final String sessionToken;
    private final String sourceBranchId;
    private final String previewBranchId;
    private final Instant createdAt;
    private final List<AiLogEntry> logs = new ArrayList<>();

    private volatile Status status = Status.RUNNING;
    private volatile AiConversationResult result;
    private volatile String errorMessage;
    private volatile Instant finishedAt;

    public AiJob(
            String id,
            UUID playerUuid,
            String sessionToken,
            String sourceBranchId,
            String previewBranchId,
            Instant createdAt
    ) {
        this.id = id;
        this.playerUuid = playerUuid;
        this.sessionToken = sessionToken;
        this.sourceBranchId = sourceBranchId;
        this.previewBranchId = previewBranchId;
        this.createdAt = createdAt;
    }

    public String id() {
        return id;
    }

    public UUID playerUuid() {
        return playerUuid;
    }

    public String sessionToken() {
        return sessionToken;
    }

    public String sourceBranchId() {
        return sourceBranchId;
    }

    public String previewBranchId() {
        return previewBranchId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Status status() {
        return status;
    }

    public AiConversationResult result() {
        return result;
    }

    public String errorMessage() {
        return errorMessage;
    }

    public Instant finishedAt() {
        return finishedAt;
    }

    public void appendLog(AiLogEntry entry) {
        synchronized (logs) {
            logs.add(entry);
        }
    }

    public List<AiLogEntry> logsSince(int fromIndex) {
        synchronized (logs) {
            if (fromIndex <= 0) {
                return List.copyOf(logs);
            }
            if (fromIndex >= logs.size()) {
                return List.of();
            }
            return List.copyOf(logs.subList(fromIndex, logs.size()));
        }
    }

    public int logSize() {
        synchronized (logs) {
            return logs.size();
        }
    }

    public void markDone(AiConversationResult result) {
        this.result = result;
        this.status = Status.DONE;
        this.finishedAt = Instant.now();
    }

    public void markError(String message) {
        this.errorMessage = message;
        this.status = Status.ERROR;
        this.finishedAt = Instant.now();
    }
}

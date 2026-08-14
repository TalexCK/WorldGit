package com.worldgit.ai;

import com.worldgit.model.Branch;
import java.util.Objects;
import java.util.UUID;

public final class AiExecutionContext {

    private final String sessionId;
    private final UUID playerUuid;
    private final String playerName;
    private final Branch branch;
    private final int maxBoxBlocks;
    private final int maxTotalBlockChanges;
    private final AiRunLogger logger;
    private boolean observed;
    private int toolRounds;
    private int totalBlockChanges;

    public AiExecutionContext(
            String sessionId,
            UUID playerUuid,
            String playerName,
            Branch branch,
            int maxBoxBlocks,
            int maxTotalBlockChanges,
            AiRunLogger logger
    ) {
        this.sessionId = Objects.requireNonNull(sessionId, "会话 ID 不能为空");
        this.playerUuid = Objects.requireNonNull(playerUuid, "玩家 UUID 不能为空");
        this.playerName = Objects.requireNonNull(playerName, "玩家名称不能为空");
        this.branch = Objects.requireNonNull(branch, "分支不能为空");
        this.maxBoxBlocks = Math.max(1, maxBoxBlocks);
        this.maxTotalBlockChanges = Math.max(1, maxTotalBlockChanges);
        this.logger = Objects.requireNonNull(logger, "日志器不能为空");
    }

    public void beginToolRound(int maxRounds) {
        toolRounds++;
        if (toolRounds > Math.max(1, maxRounds)) {
            throw new IllegalStateException("AI 工具调用轮次超过限制");
        }
    }

    public void markObserved() {
        observed = true;
    }

    public void ensureObservedBeforeMutation() {
        if (!observed) {
            throw new IllegalStateException("必须先观察分支区域，再执行改块");
        }
    }

    public void ensureBoxLimit(long volume) {
        if (volume <= 0L) {
            throw new IllegalStateException("区域体积必须大于 0");
        }
        if (volume > maxBoxBlocks) {
            throw new IllegalStateException("区域体积超过单次工具限制: " + volume + " > " + maxBoxBlocks);
        }
    }

    public void ensureBlockChangeBudget(int changeCount) {
        if (changeCount < 0) {
            throw new IllegalStateException("改块数量不能为负数");
        }
        if (totalBlockChanges + changeCount > maxTotalBlockChanges) {
            throw new IllegalStateException("本次 AI 会话累计改块数超过上限");
        }
    }

    public void recordBlockChanges(int changeCount) {
        ensureBlockChangeBudget(changeCount);
        totalBlockChanges += changeCount;
    }

    public void recordBlockChangesUnchecked(int changeCount) {
        if (changeCount < 0) {
            throw new IllegalStateException("改块数量不能为负数");
        }
        totalBlockChanges += changeCount;
    }

    public String sessionId() {
        return sessionId;
    }

    public UUID playerUuid() {
        return playerUuid;
    }

    public String playerName() {
        return playerName;
    }

    public Branch branch() {
        return branch;
    }

    public int maxBoxBlocks() {
        return maxBoxBlocks;
    }

    public int maxTotalBlockChanges() {
        return maxTotalBlockChanges;
    }

    public int toolRounds() {
        return toolRounds;
    }

    public int totalBlockChanges() {
        return totalBlockChanges;
    }

    public AiRunLogger logger() {
        return logger;
    }
}

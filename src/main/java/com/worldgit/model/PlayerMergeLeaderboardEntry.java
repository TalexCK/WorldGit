package com.worldgit.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 玩家维度的合并改块排行榜条目。
 */
public record PlayerMergeLeaderboardEntry(
        UUID playerUuid,
        String playerName,
        long totalChangedBlocks,
        int mergedBranchCount,
        Instant lastMergedAt
) {

    public PlayerMergeLeaderboardEntry {
        Objects.requireNonNull(playerUuid, "玩家 UUID 不能为空");
        Objects.requireNonNull(playerName, "玩家名称不能为空");
    }
}

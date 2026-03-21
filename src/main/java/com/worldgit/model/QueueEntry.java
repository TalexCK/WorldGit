package com.worldgit.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 锁定区域排队记录，对应 queue_entries 表。
 */
public record QueueEntry(
        Long id,
        UUID playerUuid,
        String playerName,
        String mainWorld,
        Integer minX,
        Integer minY,
        Integer minZ,
        Integer maxX,
        Integer maxY,
        Integer maxZ,
        Instant queuedAt
) {

    public QueueEntry {
        Objects.requireNonNull(playerUuid, "玩家UUID不能为空");
        Objects.requireNonNull(playerName, "玩家名称不能为空");
        Objects.requireNonNull(mainWorld, "主世界名不能为空");
        Objects.requireNonNull(queuedAt, "排队时间不能为空");
    }

    public boolean overlaps(int otherMinX, int otherMinY, int otherMinZ, int otherMaxX, int otherMaxY, int otherMaxZ) {
        if (minX == null || minY == null || minZ == null || maxX == null || maxY == null || maxZ == null) {
            return false;
        }
        return otherMinX <= maxX
                && otherMaxX >= minX
                && otherMinY <= maxY
                && otherMaxY >= minY
                && otherMinZ <= maxZ
                && otherMaxZ >= minZ;
    }
}

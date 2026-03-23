package com.worldgit.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 主世界提交记录。
 */
public record WorldCommit(
        long id,
        String mainWorld,
        long revision,
        String branchId,
        String commitKind,
        int minX,
        int minY,
        int minZ,
        int maxX,
        int maxY,
        int maxZ,
        UUID authorUuid,
        String authorName,
        String message,
        Instant createdAt
) {

    public WorldCommit {
        Objects.requireNonNull(mainWorld, "主世界名不能为空");
        Objects.requireNonNull(commitKind, "提交类型不能为空");
        Objects.requireNonNull(createdAt, "提交时间不能为空");
        if (revision < 0L) {
            throw new IllegalArgumentException("提交版本号不能为负数");
        }
    }

    public boolean overlaps(int otherMinX, int otherMinY, int otherMinZ, int otherMaxX, int otherMaxY, int otherMaxZ) {
        return !(maxX < otherMinX || minX > otherMaxX
                || maxY < otherMinY || minY > otherMaxY
                || maxZ < otherMinZ || minZ > otherMaxZ);
    }
}

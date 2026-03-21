package com.worldgit.model;

import java.time.Instant;
import java.util.Objects;

/**
 * 区域锁定记录，对应 region_locks 表。
 */
public record RegionLock(
        Long id,
        String branchId,
        String mainWorld,
        Integer minX,
        Integer minY,
        Integer minZ,
        Integer maxX,
        Integer maxY,
        Integer maxZ,
        Instant lockedAt
) {

    public RegionLock {
        Objects.requireNonNull(branchId, "分支ID不能为空");
        Objects.requireNonNull(mainWorld, "主世界名不能为空");
        Objects.requireNonNull(lockedAt, "锁定时间不能为空");
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

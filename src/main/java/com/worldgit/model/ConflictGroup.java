package com.worldgit.model;

import java.time.Instant;
import java.util.Objects;

/**
 * 冲突分组摘要。
 */
public record ConflictGroup(
        long id,
        String branchId,
        int groupIndex,
        int minX,
        int minY,
        int minZ,
        int maxX,
        int maxY,
        int maxZ,
        int blockCount,
        String status,
        String resolution,
        String detailPath,
        Instant createdAt,
        Instant resolvedAt
) {

    public ConflictGroup {
        Objects.requireNonNull(branchId, "分支ID不能为空");
        Objects.requireNonNull(status, "冲突状态不能为空");
        Objects.requireNonNull(detailPath, "冲突详情路径不能为空");
        Objects.requireNonNull(createdAt, "创建时间不能为空");
        if (groupIndex < 0) {
            throw new IllegalArgumentException("冲突组编号不能为负数");
        }
        if (blockCount < 1) {
            throw new IllegalArgumentException("冲突块数量必须大于 0");
        }
    }

    public boolean resolved() {
        return "RESOLVED".equalsIgnoreCase(status);
    }
}

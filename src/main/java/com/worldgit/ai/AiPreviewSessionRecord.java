package com.worldgit.ai;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AiPreviewSessionRecord(
        String previewBranchId,
        String sourceBranchId,
        UUID playerUuid,
        String sourceWorldName,
        Double sourceX,
        Double sourceY,
        Double sourceZ,
        Float sourceYaw,
        Float sourcePitch,
        Instant createdAt
) {

    public AiPreviewSessionRecord {
        Objects.requireNonNull(previewBranchId, "预览分支 ID 不能为空");
        Objects.requireNonNull(playerUuid, "玩家 UUID 不能为空");
        Objects.requireNonNull(createdAt, "创建时间不能为空");
    }
}

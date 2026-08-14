package com.worldgit.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RealshotRequest(
        String id,
        String branchId,
        UUID requesterUuid,
        String requesterName,
        String question,
        Instant createdAt
) {

    public RealshotRequest {
        Objects.requireNonNull(id, "实景图片请求ID不能为空");
        Objects.requireNonNull(branchId, "分支ID不能为空");
        Objects.requireNonNull(requesterUuid, "请求者UUID不能为空");
        Objects.requireNonNull(requesterName, "请求者名称不能为空");
        Objects.requireNonNull(question, "问题不能为空");
        Objects.requireNonNull(createdAt, "创建时间不能为空");
    }
}

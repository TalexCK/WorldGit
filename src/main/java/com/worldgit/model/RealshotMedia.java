package com.worldgit.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RealshotMedia(
        String id,
        String requestId,
        String branchId,
        UUID uploaderUuid,
        String uploaderName,
        String fileName,
        String mimeType,
        String filePath,
        long fileSize,
        Instant createdAt
) {

    public RealshotMedia {
        Objects.requireNonNull(id, "实景图片素材ID不能为空");
        Objects.requireNonNull(requestId, "请求ID不能为空");
        Objects.requireNonNull(branchId, "分支ID不能为空");
        Objects.requireNonNull(uploaderUuid, "上传者UUID不能为空");
        Objects.requireNonNull(uploaderName, "上传者名称不能为空");
        Objects.requireNonNull(fileName, "文件名不能为空");
        Objects.requireNonNull(mimeType, "MIME 类型不能为空");
        Objects.requireNonNull(filePath, "文件路径不能为空");
        Objects.requireNonNull(createdAt, "创建时间不能为空");
    }
}

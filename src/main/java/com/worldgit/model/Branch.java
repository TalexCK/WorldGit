package com.worldgit.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 分支实体，对应 branches 表。
 */
public record Branch(
        String id,
        UUID ownerUuid,
        String ownerName,
        String worldName,
        String mainWorld,
        Integer minX,
        Integer minY,
        Integer minZ,
        Integer maxX,
        Integer maxY,
        Integer maxZ,
        BranchStatus status,
        Instant createdAt,
        Instant submittedAt,
        UUID reviewedBy,
        Instant reviewedAt,
        String reviewNote,
        UUID mergedBy,
        String mergeMessage,
        Instant mergedAt,
        Instant closedAt
) {

    public Branch {
        Objects.requireNonNull(id, "分支ID不能为空");
        Objects.requireNonNull(ownerUuid, "所有者UUID不能为空");
        Objects.requireNonNull(ownerName, "所有者名称不能为空");
        Objects.requireNonNull(worldName, "分支世界名不能为空");
        Objects.requireNonNull(mainWorld, "主世界名不能为空");
        Objects.requireNonNull(status, "分支状态不能为空");
        Objects.requireNonNull(createdAt, "创建时间不能为空");
    }

    public Branch withStatus(BranchStatus newStatus) {
        return new Branch(
                id,
                ownerUuid,
                ownerName,
                worldName,
                mainWorld,
                minX,
                minY,
                minZ,
                maxX,
                maxY,
                maxZ,
                newStatus,
                createdAt,
                submittedAt,
                reviewedBy,
                reviewedAt,
                reviewNote,
                mergedBy,
                mergeMessage,
                mergedAt,
                closedAt
        );
    }

    public Branch withSubmittedAt(Instant newSubmittedAt) {
        return new Branch(
                id,
                ownerUuid,
                ownerName,
                worldName,
                mainWorld,
                minX,
                minY,
                minZ,
                maxX,
                maxY,
                maxZ,
                status,
                createdAt,
                newSubmittedAt,
                reviewedBy,
                reviewedAt,
                reviewNote,
                mergedBy,
                mergeMessage,
                mergedAt,
                closedAt
        );
    }

    public Branch withReview(BranchStatus newStatus, UUID newReviewedBy, Instant newReviewedAt, String newReviewNote) {
        return new Branch(
                id,
                ownerUuid,
                ownerName,
                worldName,
                mainWorld,
                minX,
                minY,
                minZ,
                maxX,
                maxY,
                maxZ,
                newStatus,
                createdAt,
                submittedAt,
                newReviewedBy,
                newReviewedAt,
                newReviewNote,
                mergedBy,
                mergeMessage,
                mergedAt,
                closedAt
        );
    }

    public Branch withMerge(UUID newMergedBy, String newMergeMessage, Instant newMergedAt) {
        return new Branch(
                id,
                ownerUuid,
                ownerName,
                worldName,
                mainWorld,
                minX,
                minY,
                minZ,
                maxX,
                maxY,
                maxZ,
                status,
                createdAt,
                submittedAt,
                reviewedBy,
                reviewedAt,
                reviewNote,
                newMergedBy,
                newMergeMessage,
                newMergedAt,
                closedAt
        );
    }

    public Branch withClosedAt(BranchStatus newStatus, Instant newClosedAt) {
        return new Branch(
                id,
                ownerUuid,
                ownerName,
                worldName,
                mainWorld,
                minX,
                minY,
                minZ,
                maxX,
                maxY,
                maxZ,
                newStatus,
                createdAt,
                submittedAt,
                reviewedBy,
                reviewedAt,
                reviewNote,
                mergedBy,
                mergeMessage,
                mergedAt,
                newClosedAt
        );
    }

    public boolean hasRegion() {
        return minX != null && minY != null && minZ != null && maxX != null && maxY != null && maxZ != null;
    }
}

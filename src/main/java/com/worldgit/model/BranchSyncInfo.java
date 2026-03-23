package com.worldgit.model;

import java.util.Objects;

/**
 * 分支同步元数据。
 */
public record BranchSyncInfo(
        String branchId,
        long baseRevision,
        Long lastRebasedRevision,
        Long lastReviewedRevision,
        BranchSyncState syncState,
        String snapshotPath,
        Long pendingRebaseRevision,
        String pendingSnapshotPath,
        String workingSnapshotPath,
        int unresolvedGroupCount,
        String staleReason
) {

    public BranchSyncInfo {
        Objects.requireNonNull(branchId, "分支ID不能为空");
        Objects.requireNonNull(syncState, "同步状态不能为空");
        Objects.requireNonNull(snapshotPath, "基线快照路径不能为空");
        if (baseRevision < 0L) {
            throw new IllegalArgumentException("基线版本号不能为负数");
        }
        if (unresolvedGroupCount < 0) {
            throw new IllegalArgumentException("未解决冲突数不能为负数");
        }
    }

    public BranchSyncInfo withState(BranchSyncState newState, String newStaleReason) {
        return new BranchSyncInfo(
                branchId,
                baseRevision,
                lastRebasedRevision,
                lastReviewedRevision,
                newState,
                snapshotPath,
                pendingRebaseRevision,
                pendingSnapshotPath,
                workingSnapshotPath,
                unresolvedGroupCount,
                newStaleReason
        );
    }

    public BranchSyncInfo withReviewRevision(Long newLastReviewedRevision) {
        return new BranchSyncInfo(
                branchId,
                baseRevision,
                lastRebasedRevision,
                newLastReviewedRevision,
                syncState,
                snapshotPath,
                pendingRebaseRevision,
                pendingSnapshotPath,
                workingSnapshotPath,
                unresolvedGroupCount,
                staleReason
        );
    }
}

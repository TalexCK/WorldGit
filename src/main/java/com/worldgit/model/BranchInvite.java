package com.worldgit.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 分支邀请记录，对应 branch_invites 表。
 */
public record BranchInvite(
        String branchId,
        UUID playerUuid,
        UUID invitedBy,
        Instant invitedAt
) {
}

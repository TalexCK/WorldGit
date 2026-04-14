package com.worldgit.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 待接受的分支邀请记录，对应 branch_invite_requests 表。
 */
public record BranchInviteRequest(
        String branchId,
        UUID playerUuid,
        UUID invitedBy,
        Instant invitedAt
) {
}

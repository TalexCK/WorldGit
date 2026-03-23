package com.worldgit.model;

import java.util.Locale;

/**
 * 分支同步状态。
 */
public enum BranchSyncState {

    CLEAN,
    NEEDS_REBASE,
    REBASING,
    HAS_CONFLICTS;

    public String dbValue() {
        return name();
    }

    public static BranchSyncState fromDbValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("分支同步状态不能为空");
        }
        return BranchSyncState.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}

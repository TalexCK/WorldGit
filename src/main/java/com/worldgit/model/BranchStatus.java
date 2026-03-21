package com.worldgit.model;

import java.util.Locale;

/**
 * 分支状态。
 */
public enum BranchStatus {

    ACTIVE,
    SUBMITTED,
    APPROVED,
    REJECTED,
    MERGED,
    ABANDONED;

    public String dbValue() {
        return name();
    }

    public static BranchStatus fromDbValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("分支状态不能为空");
        }
        return BranchStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}

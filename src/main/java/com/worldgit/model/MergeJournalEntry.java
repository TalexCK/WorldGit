package com.worldgit.model;

import java.time.Instant;

/**
 * 合并日志记录，对应 merge_journal 表。
 */
public record MergeJournalEntry(
        Long id,
        String branchId,
        String phase,
        Instant updatedAt
) {
}

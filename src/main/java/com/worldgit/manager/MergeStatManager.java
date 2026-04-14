package com.worldgit.manager;

import com.worldgit.WorldGitPlugin;
import com.worldgit.database.BranchRepository;
import com.worldgit.database.BranchSyncRepository;
import com.worldgit.model.Branch;
import com.worldgit.model.BranchSyncInfo;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;

/**
 * 负责在 merge 时记录改块数量，并补齐历史统计。
 */
public final class MergeStatManager {

    private final WorldGitPlugin plugin;
    private final BranchRepository branchRepository;
    private final BranchSyncRepository branchSyncRepository;
    private final BranchChangeManager branchChangeManager;

    public MergeStatManager(
            WorldGitPlugin plugin,
            BranchRepository branchRepository,
            BranchSyncRepository branchSyncRepository,
            BranchChangeManager branchChangeManager
    ) {
        this.plugin = Objects.requireNonNull(plugin, "插件实例不能为空");
        this.branchRepository = Objects.requireNonNull(branchRepository, "分支仓储不能为空");
        this.branchSyncRepository = Objects.requireNonNull(branchSyncRepository, "分支同步仓储不能为空");
        this.branchChangeManager = Objects.requireNonNull(branchChangeManager, "分支改动管理器不能为空");
    }

    public void recordMergedBranchStat(Branch branch) {
        Objects.requireNonNull(branch, "分支不能为空");
        if (!branch.hasRegion()) {
            return;
        }

        BranchSyncInfo syncInfo = branchSyncRepository.findOptionalUnchecked(branch.id()).orElse(null);
        if (syncInfo == null || syncInfo.snapshotPath() == null || syncInfo.snapshotPath().isBlank()) {
            return;
        }

        long changedBlockCount = branchChangeManager.describeBranchChanges(branch, syncInfo).changedBlockCount();
        branchRepository.saveMergeBlockStatsUnchecked(branch.id(), changedBlockCount);
    }

    public void backfillMissingMergedBranchStats() {
        List<Branch> missingStatsBranches = branchRepository.listMergedWithoutBlockStatsUnchecked();
        if (missingStatsBranches.isEmpty()) {
            return;
        }

        plugin.getLogger().info("开始回填合并改块统计，共 " + missingStatsBranches.size() + " 个分支。");
        int successCount = 0;
        for (Branch branch : missingStatsBranches) {
            try {
                recordMergedBranchStat(branch);
                successCount++;
            } catch (Exception exception) {
                plugin.getLogger().log(
                        Level.WARNING,
                        "回填合并改块统计失败: " + branch.id() + " -> " + exception.getMessage(),
                        exception
                );
            }
        }
        plugin.getLogger().info("合并改块统计回填完成: " + successCount + "/" + missingStatsBranches.size());
    }
}

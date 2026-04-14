package com.worldgit.manager;

import com.worldgit.WorldGitPlugin;
import com.worldgit.config.PluginConfig;
import com.worldgit.database.BranchRepository;
import com.worldgit.database.DatabaseManager;
import com.worldgit.database.RevisionRepository;
import com.worldgit.model.Branch;
import com.worldgit.model.BranchStatus;
import com.worldgit.model.RegionLock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

public final class MergeManager {

    private static final String PHASE_STARTED = "STARTED";
    private static final String PHASE_BLOCKS_COPIED = "BLOCKS_COPIED";
    private static final String PHASE_COMMIT_RECORDED = "COMMIT_RECORDED";
    private static final String PHASE_UNLOCKED = "UNLOCKED";
    private static final String PHASE_WORLD_UNLOADED = "WORLD_UNLOADED";
    private static final String LEGACY_PHASE_WORLD_DELETED = "WORLD_DELETED";
    private static final String PHASE_COMPLETE = "COMPLETE";

    private final WorldGitPlugin plugin;
    private final PluginConfig pluginConfig;
    private final DatabaseManager databaseManager;
    private final BranchRepository branchRepository;
    private final RevisionRepository revisionRepository;
    private final LockManager lockManager;
    private final QueueManager queueManager;
    private final WorldManager worldManager;
    private final RegionCopyManager regionCopyManager;
    private final MergeStatManager mergeStatManager;

    public MergeManager(
            WorldGitPlugin plugin,
            PluginConfig pluginConfig,
            DatabaseManager databaseManager,
            BranchRepository branchRepository,
            RevisionRepository revisionRepository,
            LockManager lockManager,
            QueueManager queueManager,
            WorldManager worldManager,
            RegionCopyManager regionCopyManager,
            MergeStatManager mergeStatManager
    ) {
        this.plugin = plugin;
        this.pluginConfig = pluginConfig;
        this.databaseManager = databaseManager;
        this.branchRepository = branchRepository;
        this.revisionRepository = revisionRepository;
        this.lockManager = lockManager;
        this.queueManager = queueManager;
        this.worldManager = worldManager;
        this.regionCopyManager = regionCopyManager;
        this.mergeStatManager = mergeStatManager;
    }

    public void confirmMerge(Player player, Branch branch, String mergeMessage) {
        if (branch.status() != BranchStatus.APPROVED) {
            throw new IllegalStateException("该分支尚未通过审核");
        }
        if (!branch.ownerUuid().equals(player.getUniqueId())) {
            throw new IllegalStateException("只能确认自己的分支");
        }
        beginMerge(branch, player.getUniqueId(), mergeMessage);
    }

    public void forceMerge(Branch branch, UUID mergedBy, String mergeMessage) {
        beginMerge(branch, mergedBy, mergeMessage);
    }

    public void recoverIncompleteMerges() {
        List<String> branchIds = databaseManager.listIncompleteMergeBranchIds();
        for (String branchId : branchIds) {
            try {
                resumeMerge(branchId);
            } catch (Exception ex) {
                plugin.getLogger().warning("恢复合并失败: " + branchId + " -> " + ex.getMessage());
            }
        }
    }

    public void resumeMerge(String branchId) {
        Branch branch = branchRepository.findByIdUnchecked(branchId)
                .orElseThrow(() -> new IllegalStateException("分支不存在: " + branchId));
        String phase = databaseManager.getMergePhase(branchId).orElse(null);

        if (phase == null) {
            ensureMergeLock(branch);
            databaseManager.upsertMergePhase(branchId, PHASE_STARTED);
            phase = PHASE_STARTED;
        }

        if (!PHASE_UNLOCKED.equals(phase)
                && !PHASE_WORLD_UNLOADED.equals(phase)
                && !PHASE_COMPLETE.equals(phase)
                && !LEGACY_PHASE_WORLD_DELETED.equals(phase)) {
            ensureMergeLock(branch);
        }

        if (PHASE_STARTED.equals(phase)) {
            mergeStatManager.recordMergedBranchStat(branch);
            copyBlocks(branch);
            databaseManager.upsertMergePhase(branch.id(), PHASE_BLOCKS_COPIED);
            phase = PHASE_BLOCKS_COPIED;
        }

        if (PHASE_BLOCKS_COPIED.equals(phase)) {
            recordCommit(branch);
            databaseManager.upsertMergePhase(branch.id(), PHASE_COMMIT_RECORDED);
            phase = PHASE_COMMIT_RECORDED;
        }

        if (PHASE_COMMIT_RECORDED.equals(phase)) {
            lockManager.unlockBranch(branch.id());
            databaseManager.upsertMergePhase(branch.id(), PHASE_UNLOCKED);
            phase = PHASE_UNLOCKED;
        }

        if (PHASE_UNLOCKED.equals(phase)) {
            Location fallback = worldManager.createReturnLocation(branch.minX(), branch.maxX(), branch.minZ(), branch.maxZ());
            if (!worldManager.unloadWorld(branch.worldName(), fallback)) {
                throw new IllegalStateException("无法卸载分支世界: " + branch.worldName());
            }
            databaseManager.upsertMergePhase(branch.id(), PHASE_WORLD_UNLOADED);
            phase = PHASE_WORLD_UNLOADED;
        }

        if (PHASE_WORLD_UNLOADED.equals(phase) || LEGACY_PHASE_WORLD_DELETED.equals(phase)) {
            branchRepository.markMerged(branch.id(), Instant.now().getEpochSecond());
            databaseManager.upsertMergePhase(branch.id(), PHASE_COMPLETE);
            phase = PHASE_COMPLETE;
        }

        if (PHASE_COMPLETE.equals(phase)) {
            databaseManager.deleteMergeJournalQuietly(branch.id());
        }
    }

    private void ensureMergeLock(Branch branch) {
        List<RegionLock> conflicts = lockManager.findConflicts(
                branch.mainWorld(),
                branch.minX(),
                branch.minY(),
                branch.minZ(),
                branch.maxX(),
                branch.maxY(),
                branch.maxZ()
        ).stream()
                .filter(lock -> !branch.id().equals(lock.branchId()))
                .toList();
        if (!conflicts.isEmpty()) {
            throw new IllegalStateException("当前有其他重叠区域正在合并，请稍后重试。");
        }
        if (lockManager.findByBranchId(branch.id()).isPresent()) {
            return;
        }
        lockManager.createLock(
                branch.id(),
                branch.mainWorld(),
                branch.minX(),
                branch.minY(),
                branch.minZ(),
                branch.maxX(),
                branch.maxY(),
                branch.maxZ()
        );
    }

    private void copyBlocks(Branch branch) {
        World source = worldManager.createBranchWorld(branch.worldName());
        World target = Bukkit.getWorld(branch.mainWorld());
        if (target == null) {
            throw new IllegalStateException("主世界不存在: " + branch.mainWorld());
        }

        Location fallback = worldManager.createReturnLocation(branch.minX(), branch.maxX(), branch.minZ(), branch.maxZ());
        for (Player onlinePlayer : source.getPlayers()) {
            onlinePlayer.teleportAsync(fallback);
        }
        regionCopyManager.copyRegion(
                source,
                target,
                branch.minX(),
                branch.minY(),
                branch.minZ(),
                branch.maxX(),
                branch.maxY(),
                branch.maxZ()
        );
        target.save();
    }

    private void recordCommit(Branch branch) {
        revisionRepository.appendCommitUnchecked(
                branch.mainWorld(),
                branch.id(),
                "MERGE",
                branch.minX(),
                branch.minY(),
                branch.minZ(),
                branch.maxX(),
                branch.maxY(),
                branch.maxZ(),
                branch.mergedBy(),
                branch.ownerName(),
                normalizeMergeMessage(branch.mergeMessage()),
                Instant.now()
        );
    }

    private String normalizeMergeMessage(String mergeMessage) {
        if (mergeMessage == null || mergeMessage.isBlank()) {
            return "未填写合并说明";
        }
        return mergeMessage.trim();
    }

    private void beginMerge(Branch branch, UUID mergedBy, String mergeMessage) {
        branchRepository.saveMergeMetadata(branch.id(), mergedBy, normalizeMergeMessage(mergeMessage));
        resumeMerge(branch.id());
    }
}

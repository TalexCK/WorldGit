package com.worldgit.manager;

import com.worldgit.WorldGitPlugin;
import com.worldgit.config.PluginConfig;
import com.worldgit.database.BranchRepository;
import com.worldgit.database.DatabaseManager;
import com.worldgit.model.Branch;
import com.worldgit.model.BranchStatus;
import java.time.Instant;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

public final class MergeManager {

    private static final String PHASE_STARTED = "STARTED";
    private static final String PHASE_BLOCKS_COPIED = "BLOCKS_COPIED";
    private static final String PHASE_UNLOCKED = "UNLOCKED";
    private static final String PHASE_WORLD_DELETED = "WORLD_DELETED";
    private static final String PHASE_COMPLETE = "COMPLETE";

    private final WorldGitPlugin plugin;
    private final PluginConfig pluginConfig;
    private final DatabaseManager databaseManager;
    private final BranchRepository branchRepository;
    private final LockManager lockManager;
    private final QueueManager queueManager;
    private final WorldManager worldManager;
    private final RegionCopyManager regionCopyManager;

    public MergeManager(
            WorldGitPlugin plugin,
            PluginConfig pluginConfig,
            DatabaseManager databaseManager,
            BranchRepository branchRepository,
            LockManager lockManager,
            QueueManager queueManager,
            WorldManager worldManager,
            RegionCopyManager regionCopyManager
    ) {
        this.plugin = plugin;
        this.pluginConfig = pluginConfig;
        this.databaseManager = databaseManager;
        this.branchRepository = branchRepository;
        this.lockManager = lockManager;
        this.queueManager = queueManager;
        this.worldManager = worldManager;
        this.regionCopyManager = regionCopyManager;
    }

    public void confirmMerge(Player player, Branch branch) {
        if (branch.status() != BranchStatus.APPROVED) {
            throw new IllegalStateException("该分支尚未通过审核");
        }
        if (!branch.ownerUuid().equals(player.getUniqueId())) {
            throw new IllegalStateException("只能确认自己的分支");
        }
        resumeMerge(branch.id());
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
            databaseManager.upsertMergePhase(branchId, PHASE_STARTED);
            phase = PHASE_STARTED;
        }

        if (PHASE_STARTED.equals(phase)) {
            copyBlocks(branch);
            databaseManager.upsertMergePhase(branch.id(), PHASE_BLOCKS_COPIED);
            phase = PHASE_BLOCKS_COPIED;
        }

        if (PHASE_BLOCKS_COPIED.equals(phase)) {
            lockManager.unlockBranch(branch.id());
            queueManager.notifyRegionUnlocked(
                    branch.mainWorld(),
                    branch.minX(),
                    branch.minY(),
                    branch.minZ(),
                    branch.maxX(),
                    branch.maxY(),
                    branch.maxZ()
            );
            databaseManager.upsertMergePhase(branch.id(), PHASE_UNLOCKED);
            phase = PHASE_UNLOCKED;
        }

        if (PHASE_UNLOCKED.equals(phase)) {
            Location fallback = worldManager.createReturnLocation(branch.minX(), branch.maxX(), branch.minZ(), branch.maxZ());
            worldManager.deleteWorld(branch.worldName(), fallback);
            databaseManager.upsertMergePhase(branch.id(), PHASE_WORLD_DELETED);
            phase = PHASE_WORLD_DELETED;
        }

        if (PHASE_WORLD_DELETED.equals(phase)) {
            branchRepository.markMerged(branch.id(), Instant.now().getEpochSecond());
            databaseManager.upsertMergePhase(branch.id(), PHASE_COMPLETE);
            phase = PHASE_COMPLETE;
        }

        if (PHASE_COMPLETE.equals(phase)) {
            databaseManager.deleteMergeJournalQuietly(branch.id());
        }
    }

    private void copyBlocks(Branch branch) {
        World source = Bukkit.getWorld(branch.worldName());
        if (source == null) {
            throw new IllegalStateException("分支世界不存在: " + branch.worldName());
        }
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
}

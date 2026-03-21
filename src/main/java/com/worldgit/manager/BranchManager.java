package com.worldgit.manager;

import com.worldgit.WorldGitPlugin;
import com.worldgit.config.PluginConfig;
import com.worldgit.database.BranchRepository;
import com.worldgit.model.Branch;
import com.worldgit.model.BranchStatus;
import com.worldgit.model.RegionLock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

public final class BranchManager {

    private final WorldGitPlugin plugin;
    private final PluginConfig pluginConfig;
    private final BranchRepository branchRepository;
    private final LockManager lockManager;
    private final QueueManager queueManager;
    private final MergeManager mergeManager;
    private final WorldManager worldManager;
    private final RegionCopyManager regionCopyManager;
    private final ProtectionManager protectionManager;

    public BranchManager(
            WorldGitPlugin plugin,
            PluginConfig pluginConfig,
            BranchRepository branchRepository,
            LockManager lockManager,
            QueueManager queueManager,
            MergeManager mergeManager,
            WorldManager worldManager,
            RegionCopyManager regionCopyManager,
            ProtectionManager protectionManager
    ) {
        this.plugin = plugin;
        this.pluginConfig = pluginConfig;
        this.branchRepository = branchRepository;
        this.lockManager = lockManager;
        this.queueManager = queueManager;
        this.mergeManager = mergeManager;
        this.worldManager = worldManager;
        this.regionCopyManager = regionCopyManager;
        this.protectionManager = protectionManager;
    }

    public Branch createBranch(Player player) {
        return createBranchForOwner(player, player.getUniqueId(), player.getName(), player);
    }

    public Branch createAssignedBranch(Player selector, Player owner) {
        return createBranchForOwner(selector, owner.getUniqueId(), owner.getName(), owner);
    }

    public void queueSelection(Player player) {
        if (!protectionManager.isMainWorld(player.getWorld())) {
            throw new IllegalStateException("只能在主世界操作区域");
        }

        RegionCopyManager.SelectionBounds selection = regionCopyManager.readSelection(player);
        List<RegionLock> conflicts = lockManager.findConflicts(
                player.getWorld().getName(),
                selection.minX(),
                selection.minY(),
                selection.minZ(),
                selection.maxX(),
                selection.maxY(),
                selection.maxZ()
        );
        if (conflicts.isEmpty()) {
            throw new IllegalStateException("所选区域当前未被锁定，无需排队");
        }
        queueManager.enqueue(player, selection);
    }

    private Branch createBranchForOwner(Player selector, UUID ownerUuid, String ownerName, Player teleportTarget) {
        if (!protectionManager.isMainWorld(selector.getWorld())) {
            throw new IllegalStateException("只能在主世界创建分支");
        }

        long activeCount = branchRepository.countActiveBranches(ownerUuid);
        if (activeCount >= pluginConfig.maxActiveBranches()) {
            throw new IllegalStateException("目标玩家的活跃分支已达上限");
        }

        RegionCopyManager.SelectionBounds selection = regionCopyManager.readSelection(selector);
        List<RegionLock> conflicts = lockManager.findConflicts(
                selector.getWorld().getName(),
                selection.minX(),
                selection.minY(),
                selection.minZ(),
                selection.maxX(),
                selection.maxY(),
                selection.maxZ()
        );
        if (!conflicts.isEmpty()) {
            throw new IllegalStateException("所选区域已被锁定，可使用 /wg queue 排队");
        }

        String branchId = UUID.randomUUID().toString().replace("-", "");
        String worldName = worldManager.createBranchWorldName(branchId);
        Branch branch = new Branch(
                branchId,
                ownerUuid,
                ownerName,
                worldName,
                selector.getWorld().getName(),
                selection.minX(),
                selection.minY(),
                selection.minZ(),
                selection.maxX(),
                selection.maxY(),
                selection.maxZ(),
                BranchStatus.ACTIVE,
                Instant.now(),
                null,
                null,
                null,
                null,
                null,
                null
        );

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

        World branchWorld = worldManager.createBranchWorld(branch.worldName());
        World sourceWorld = Bukkit.getWorld(branch.mainWorld());
        if (sourceWorld == null) {
            throw new IllegalStateException("主世界不存在: " + branch.mainWorld());
        }
        regionCopyManager.copyRegion(sourceWorld, branchWorld, selection);
        branchRepository.insert(branch);
        teleportTarget.teleportAsync(worldManager.createBranchSpawn(
                branchWorld,
                branch.minX(),
                branch.maxX(),
                branch.minY(),
                branch.maxY(),
                branch.minZ(),
                branch.maxZ()
        ));
        return branch;
    }

    public List<Branch> listOwnBranches(Player player) {
        return branchRepository.findByOwnerUnchecked(player.getUniqueId());
    }

    public List<Branch> listAllBranches() {
        return branchRepository.findAll();
    }

    public List<Branch> listPendingReviews() {
        return branchRepository.findSubmitted();
    }

    public Branch requireBranch(String branchId) {
        return branchRepository.findByIdUnchecked(branchId)
                .orElseThrow(() -> new IllegalStateException("分支不存在: " + branchId));
    }

    public Branch resolveOwnedBranch(Player player, String branchId) {
        if (branchId == null || branchId.isBlank()) {
            return branchRepository.findLatestOwnedActiveBranch(player.getUniqueId())
                    .orElseThrow(() -> new IllegalStateException("你当前没有可操作的分支"));
        }
        Branch branch = requireBranch(branchId);
        ensureOwner(branch, player);
        return branch;
    }

    public void submitBranch(Player player, String branchId) {
        Branch branch = resolveOwnedBranch(player, branchId);
        if (branch.status() != BranchStatus.ACTIVE && branch.status() != BranchStatus.REJECTED) {
            throw new IllegalStateException("当前状态不允许提交审核");
        }
        branchRepository.markSubmitted(branch.id(), Instant.now().getEpochSecond());
        notifyAdmins("<yellow>分支已提交审核: <white>" + branch.id() + "</white> by " + player.getName());
    }

    public void approveBranch(Player admin, String branchId, String note) {
        Branch branch = requireBranch(branchId);
        if (branch.status() != BranchStatus.SUBMITTED) {
            throw new IllegalStateException("只有待审核分支可以批准");
        }
        branchRepository.markReviewed(branch.id(), BranchStatus.APPROVED, admin.getUniqueId(), Instant.now().getEpochSecond(), note);
        Player owner = Bukkit.getPlayer(branch.ownerUuid());
        if (owner != null && owner.isOnline()) {
            owner.sendRichMessage("<green>你的分支已通过审核，可执行 /wg confirm</green>");
        }
    }

    public void rejectBranch(Player admin, String branchId, String note) {
        if (note == null || note.isBlank()) {
            throw new IllegalStateException("拒绝分支时必须提供原因");
        }
        Branch branch = requireBranch(branchId);
        if (branch.status() != BranchStatus.SUBMITTED) {
            throw new IllegalStateException("只有待审核分支可以拒绝");
        }
        branchRepository.markReviewed(branch.id(), BranchStatus.REJECTED, admin.getUniqueId(), Instant.now().getEpochSecond(), note);
        Player owner = Bukkit.getPlayer(branch.ownerUuid());
        if (owner != null && owner.isOnline()) {
            owner.sendRichMessage("<red>你的分支被驳回: " + note + "</red>");
        }
    }

    public void confirmBranch(Player player, String branchId) {
        Branch branch = resolveOwnedBranch(player, branchId);
        mergeManager.confirmMerge(player, branch);
    }

    public void abandonBranch(Player player, String branchId) {
        Branch branch = resolveOwnedBranch(player, branchId);
        closeBranch(branch, BranchStatus.ABANDONED, Instant.now().getEpochSecond(), "用户主动放弃");
    }

    public void forceCloseBranch(String branchId, String note) {
        Branch branch = requireBranch(branchId);
        closeBranch(branch, BranchStatus.ABANDONED, Instant.now().getEpochSecond(), note == null ? "管理员关闭" : note);
    }

    public void teleportToBranch(Player player, String branchId) {
        Branch branch = resolveAccessibleBranch(player, branchId);
        World world = Bukkit.getWorld(branch.worldName());
        if (world == null) {
            throw new IllegalStateException("分支世界不存在: " + branch.worldName());
        }
        player.teleportAsync(worldManager.createBranchSpawn(
                world,
                branch.minX(),
                branch.maxX(),
                branch.minY(),
                branch.maxY(),
                branch.minZ(),
                branch.maxZ()
        ));
    }

    public void returnToMainWorld(Player player) {
        Location location = worldManager.getMainWorld().getSpawnLocation();
        player.teleportAsync(location);
    }

    public void invitePlayer(Player inviter, Player invited, String branchId) {
        Branch branch = resolveOwnedBranch(inviter, branchId);
        branchRepository.addInvite(branch.id(), invited.getUniqueId(), inviter.getUniqueId(), Instant.now().getEpochSecond());
        invited.sendRichMessage("<green>你被邀请进入分支: <white>" + branch.id() + "</white></green>");
    }

    public void uninvitePlayer(Player inviter, UUID invitedUuid, String branchId) {
        Branch branch = resolveOwnedBranch(inviter, branchId);
        branchRepository.removeInvite(branch.id(), invitedUuid);
    }

    public Optional<Branch> findByWorld(String worldName) {
        return branchRepository.findByWorldNameUnchecked(worldName);
    }

    public boolean canAccessBranch(Player player, Branch branch) {
        return player.hasPermission("worldgit.admin.bypass")
                || branch.ownerUuid().equals(player.getUniqueId())
                || branchRepository.isInvited(branch.id(), player.getUniqueId());
    }

    private Branch resolveAccessibleBranch(Player player, String branchId) {
        Branch branch = requireBranch(branchId);
        if (!canAccessBranch(player, branch)) {
            throw new IllegalStateException("你无权访问该分支");
        }
        return branch;
    }

    private void ensureOwner(Branch branch, Player player) {
        if (!branch.ownerUuid().equals(player.getUniqueId())) {
            throw new IllegalStateException("只能操作自己的分支");
        }
    }

    private void closeBranch(Branch branch, BranchStatus finalStatus, long closedAt, String note) {
        Location fallback = worldManager.createReturnLocation(branch.minX(), branch.maxX(), branch.minZ(), branch.maxZ());
        worldManager.deleteWorld(branch.worldName(), fallback);
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
        branchRepository.markClosed(branch.id(), finalStatus, closedAt, note);
    }

    private void notifyAdmins(String message) {
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            if (onlinePlayer.hasPermission("worldgit.admin.review")) {
                onlinePlayer.sendRichMessage(message);
            }
        }
    }
}

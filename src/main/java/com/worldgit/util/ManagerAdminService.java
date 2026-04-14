package com.worldgit.util;

import com.worldgit.WorldGitPlugin;
import com.worldgit.manager.BackupManager;
import com.worldgit.manager.BranchManager;
import com.worldgit.manager.GitHubSyncManager;
import com.worldgit.manager.LockManager;
import com.worldgit.model.Branch;
import com.worldgit.model.RegionLock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class ManagerAdminService implements AdminService {

    private static final Duration FORCE_MERGE_CONFIRM_TTL = Duration.ofSeconds(30);

    private final WorldGitPlugin plugin;
    private final BranchManager branchManager;
    private final LockManager lockManager;
    private final BackupManager backupManager;
    private final GitHubSyncManager gitHubSyncManager;
    private final Map<String, PendingForceMerge> pendingForceMerges = new ConcurrentHashMap<>();

    public ManagerAdminService(
            WorldGitPlugin plugin,
            BranchManager branchManager,
            LockManager lockManager,
            BackupManager backupManager,
            GitHubSyncManager gitHubSyncManager
    ) {
        this.plugin = plugin;
        this.branchManager = branchManager;
        this.lockManager = lockManager;
        this.backupManager = backupManager;
        this.gitHubSyncManager = gitHubSyncManager;
    }

    @Override
    public boolean close(CommandSender sender, String branchId) {
        requirePermission(sender, "worldgit.admin.close");
        branchManager.forceCloseBranch(branchId, "管理员关闭");
        MessageUtil.sendSuccess(sender, "已关闭分支: " + branchId);
        return true;
    }

    @Override
    public boolean assign(CommandSender sender, String playerName) {
        Player admin = requirePlayer(sender);
        requirePermission(admin, "worldgit.admin.assign");
        Player target = Bukkit.getPlayerExact(playerName);
        if (target == null) {
            throw new IllegalStateException("目标玩家不在线: " + playerName);
        }
        Branch branch = branchManager.createAssignedBranch(admin, target);
        MessageUtil.sendSuccess(sender, "已为 " + target.getName() + " 创建分支: " + branch.id());
        if (!admin.getUniqueId().equals(target.getUniqueId())) {
            MessageUtil.sendInfo(target, "管理员已为你分配分支: " + branch.id());
        }
        return true;
    }

    @Override
    public boolean backup(CommandSender sender) {
        requirePermission(sender, "worldgit.admin.backup");
        backupManager.createBackupSafe();
        MessageUtil.sendSuccess(sender, "已触发手动备份");
        return true;
    }

    @Override
    public boolean sync(CommandSender sender) {
        requirePermission(sender, "worldgit.admin.sync");
        String invalidReason = gitHubSyncManager.getInvalidReason();
        if (invalidReason != null) {
            throw new IllegalStateException("GitHub 同步不可用: " + invalidReason);
        }
        if (!gitHubSyncManager.syncNowSafe()) {
            MessageUtil.sendInfo(sender, "GitHub 同步任务已在运行，已跳过本次触发。");
            return true;
        }
        MessageUtil.sendSuccess(sender, "已触发手动 GitHub 同步");
        return true;
    }

    @Override
    public boolean locks(CommandSender sender) {
        requirePermission(sender, "worldgit.admin.locks");
        List<RegionLock> locks = lockManager.findAll();
        if (locks.isEmpty()) {
            MessageUtil.sendInfo(sender, "当前没有活动锁。");
            return true;
        }
        MessageUtil.sendInfo(sender, "活动锁列表:");
        for (RegionLock lock : locks) {
            sender.sendMessage(MessageUtil.info(lock.branchId() + " | " + lock.mainWorld()
                    + " | (" + lock.minX() + ", " + lock.minY() + ", " + lock.minZ() + ")"
                    + " -> (" + lock.maxX() + ", " + lock.maxY() + ", " + lock.maxZ() + ")"));
        }
        return true;
    }

    @Override
    public boolean forceMerge(CommandSender sender, String branchId, boolean confirmed) {
        requirePermission(sender, "worldgit.admin.forcemerge");
        Branch branch = branchManager.requireBranch(branchId);
        validateForceMergeTarget(branch);

        String confirmationKey = confirmationKey(sender);
        if (!confirmed) {
            pendingForceMerges.put(confirmationKey, new PendingForceMerge(branch.id(), Instant.now()));
            MessageUtil.sendWarning(sender, "⚠ 强制合并会用分支世界当前内容直接覆盖主世界对应区域。");
            MessageUtil.sendInfo(sender, "目标分支: " + branch.id() + " | " + branch.ownerName() + " | " + branch.status());
            MessageUtil.sendInfo(sender,
                    "确认命令: /wg admin forcemerge " + branch.id() + " confirm"
                            + " （30 秒内有效）");
            return true;
        }

        PendingForceMerge pending = pendingForceMerges.remove(confirmationKey);
        if (pending == null || !pending.branchId().equals(branch.id()) || pending.isExpired()) {
            throw new IllegalStateException("二次确认已失效，请先重新执行 /wg admin forcemerge " + branch.id());
        }

        String mergeMessage = buildForceMergeMessage(sender);
        branchManager.forceMergeBranch(sender, branch.id(), mergeMessage);
        MessageUtil.sendSuccess(sender, "已开始强制合并分支: " + branch.id());
        return true;
    }

    @Override
    public boolean list(CommandSender sender, String playerName) {
        requirePermission(sender, "worldgit.admin.list");
        List<Branch> branches = branchManager.listAllBranches().stream()
                .filter(branch -> playerName == null || playerName.isBlank()
                        || branch.ownerName().equalsIgnoreCase(playerName))
                .toList();
        if (branches.isEmpty()) {
            MessageUtil.sendInfo(sender, "没有符合条件的分支。");
            return true;
        }
        MessageUtil.sendInfo(sender, "分支总览:");
        for (Branch branch : branches) {
            sender.sendMessage(MessageUtil.info(branch.id() + " | " + branch.ownerName() + " | " + branch.status()));
        }
        return true;
    }

    @Override
    public boolean reload(CommandSender sender) {
        requirePermission(sender, "worldgit.admin.reload");
        try {
            plugin.reloadRuntime();
        } catch (Exception exception) {
            throw new IllegalStateException("重载失败: " + exception.getMessage(), exception);
        }
        MessageUtil.sendSuccess(sender, "配置已重载");
        return true;
    }

    @Override
    public List<String> suggestPlayerNames(CommandSender sender, String prefix) {
        String normalized = prefix == null ? "" : prefix.toLowerCase();
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase().startsWith(normalized))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    @Override
    public List<String> suggestBranchIds(CommandSender sender, String prefix) {
        String normalized = prefix == null ? "" : prefix.toLowerCase();
        return branchManager.listAllBranches().stream()
                .map(Branch::id)
                .filter(id -> id.toLowerCase().startsWith(normalized))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        throw new IllegalStateException("该命令只能由玩家执行");
    }

    private void requirePermission(CommandSender sender, String permission) {
        if (!sender.hasPermission(permission)) {
            throw new IllegalStateException("你没有权限执行该命令");
        }
    }

    private void validateForceMergeTarget(Branch branch) {
        if (branch.status() == com.worldgit.model.BranchStatus.ABANDONED) {
            throw new IllegalStateException("已废弃分支不能再强制合并。");
        }
    }

    private String buildForceMergeMessage(CommandSender sender) {
        return "管理员强制合并 by " + sender.getName();
    }

    private String confirmationKey(CommandSender sender) {
        if (sender instanceof Player player) {
            UUID uuid = player.getUniqueId();
            return "player:" + uuid;
        }
        return "sender:" + sender.getName().toLowerCase();
    }

    private record PendingForceMerge(String branchId, Instant createdAt) {

        private boolean isExpired() {
            return createdAt.plus(FORCE_MERGE_CONFIRM_TTL).isBefore(Instant.now());
        }
    }
}

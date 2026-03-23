package com.worldgit.util;

import com.worldgit.WorldGitPlugin;
import com.worldgit.manager.BackupManager;
import com.worldgit.manager.BranchManager;
import com.worldgit.manager.GitHubSyncManager;
import com.worldgit.manager.LockManager;
import com.worldgit.model.Branch;
import com.worldgit.model.RegionLock;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class ManagerAdminService implements AdminService {

    private final WorldGitPlugin plugin;
    private final BranchManager branchManager;
    private final LockManager lockManager;
    private final BackupManager backupManager;
    private final GitHubSyncManager gitHubSyncManager;

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
}

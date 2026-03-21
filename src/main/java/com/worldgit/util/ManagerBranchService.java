package com.worldgit.util;

import com.worldgit.manager.BranchManager;
import com.worldgit.model.Branch;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class ManagerBranchService implements BranchService {

    private final BranchManager branchManager;

    public ManagerBranchService(BranchManager branchManager) {
        this.branchManager = branchManager;
    }

    @Override
    public boolean create(Player player) {
        requirePermission(player, "worldgit.branch.create");
        Branch branch = branchManager.createBranch(player);
        MessageUtil.sendSuccess(player, "分支创建成功: " + branch.id());
        return true;
    }

    @Override
    public boolean abandon(CommandSender sender, String branchId) {
        Player player = requirePlayer(sender);
        requirePermission(player, "worldgit.branch.abandon");
        branchManager.abandonBranch(player, branchId);
        MessageUtil.sendSuccess(sender, "分支已放弃: " + branchId);
        return true;
    }

    @Override
    public boolean list(CommandSender sender) {
        Player player = requirePlayer(sender);
        requirePermission(player, "worldgit.branch.list");
        List<Branch> branches = branchManager.listOwnBranches(player);
        if (branches.isEmpty()) {
            MessageUtil.sendInfo(sender, "你当前没有分支。");
            return true;
        }
        MessageUtil.sendInfo(sender, "你的分支列表:");
        for (Branch branch : branches) {
            sender.sendMessage(MessageUtil.info(branch.id() + " | " + branch.status() + " | " + branch.worldName()));
        }
        return true;
    }

    @Override
    public boolean info(CommandSender sender, String branchId) {
        Player player = requirePlayer(sender);
        requirePermission(player, "worldgit.branch.info");
        Branch branch = branchManager.requireBranch(branchId);
        if (!branchManager.canAccessBranch(player, branch) && !branch.ownerUuid().equals(player.getUniqueId())) {
            throw new IllegalStateException("你无权查看该分支");
        }
        MessageUtil.sendInfo(sender, "分支 " + branch.id() + " 状态: " + branch.status());
        sender.sendMessage(MessageUtil.info("世界: " + branch.worldName() + " | 主世界: " + branch.mainWorld()));
        sender.sendMessage(MessageUtil.info("区域: (" + branch.minX() + ", " + branch.minY() + ", " + branch.minZ()
                + ") -> (" + branch.maxX() + ", " + branch.maxY() + ", " + branch.maxZ() + ")"));
        return true;
    }

    @Override
    public boolean teleport(Player player, String branchId) {
        requirePermission(player, "worldgit.branch.tp");
        branchManager.teleportToBranch(player, branchId);
        MessageUtil.sendSuccess(player, "正在传送到分支: " + branchId);
        return true;
    }

    @Override
    public boolean returnToMain(Player player) {
        requirePermission(player, "worldgit.branch.return");
        branchManager.returnToMainWorld(player);
        MessageUtil.sendSuccess(player, "正在返回主世界");
        return true;
    }

    @Override
    public boolean queue(Player player) {
        requirePermission(player, "worldgit.branch.queue");
        branchManager.queueSelection(player);
        MessageUtil.sendSuccess(player, "已加入该区域的排队列表");
        return true;
    }

    @Override
    public List<String> suggestBranchIds(CommandSender sender, String prefix) {
        if (!(sender instanceof Player player)) {
            return List.of();
        }
        String normalized = prefix == null ? "" : prefix.toLowerCase();
        return branchManager.listOwnBranches(player).stream()
                .map(Branch::id)
                .filter(id -> id.toLowerCase().startsWith(normalized))
                .toList();
    }

    private Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        throw new IllegalStateException("只有玩家可以执行该命令");
    }

    private void requirePermission(CommandSender sender, String permission) {
        if (!sender.hasPermission(permission)) {
            throw new IllegalStateException("你没有权限执行该命令");
        }
    }
}

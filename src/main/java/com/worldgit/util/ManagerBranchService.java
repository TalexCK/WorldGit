package com.worldgit.util;

import com.worldgit.manager.BranchManager;
import com.worldgit.manager.PlayerSelectionManager;
import com.worldgit.manager.RegionCopyManager;
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
    public boolean setPos1(Player player, Integer x, Integer y, Integer z) {
        requirePermission(player, "worldgit.branch.create");
        PlayerSelectionManager.SelectionSnapshot snapshot = branchManager.setSelectionPos1(player, x, y, z);
        MessageUtil.sendSuccess(player, "已设置 pos1: " + formatPoint(snapshot.pos1()));
        sendSelectionSummary(player, snapshot);
        return true;
    }

    @Override
    public boolean setPos2(Player player, Integer x, Integer y, Integer z) {
        requirePermission(player, "worldgit.branch.create");
        PlayerSelectionManager.SelectionSnapshot snapshot = branchManager.setSelectionPos2(player, x, y, z);
        MessageUtil.sendSuccess(player, "已设置 pos2: " + formatPoint(snapshot.pos2()));
        sendSelectionSummary(player, snapshot);
        return true;
    }

    @Override
    public boolean selection(Player player) {
        requirePermission(player, "worldgit.branch.create");
        PlayerSelectionManager.SelectionSnapshot snapshot = branchManager.getSelection(player)
                .orElseThrow(() -> new IllegalStateException("你当前还没有设置选区"));
        sendSelectionSummary(player, snapshot);
        return true;
    }

    @Override
    public boolean clearSelection(Player player) {
        requirePermission(player, "worldgit.branch.create");
        branchManager.clearSelection(player);
        MessageUtil.sendSuccess(player, "已清除当前选区");
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

    private void sendSelectionSummary(Player player, PlayerSelectionManager.SelectionSnapshot snapshot) {
        if (!snapshot.complete()) {
            MessageUtil.sendInfo(player, "当前选区点位: " + snapshot.selectedPoints() + "/2");
            return;
        }
        RegionCopyManager.SelectionBounds bounds = snapshot.toBounds(player.getWorld(), false);
        MessageUtil.sendInfo(player, "选区范围: ("
                + bounds.minX() + ", " + bounds.minY() + ", " + bounds.minZ()
                + ") -> ("
                + bounds.maxX() + ", " + bounds.maxY() + ", " + bounds.maxZ() + ")");
    }

    private String formatPoint(PlayerSelectionManager.SelectionPoint point) {
        return "(" + point.x() + ", " + point.y() + ", " + point.z() + ")";
    }
}

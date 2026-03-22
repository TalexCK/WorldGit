package com.worldgit.util;

import com.worldgit.manager.BranchManager;
import com.worldgit.model.Branch;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class ManagerReviewService implements ReviewService {

    private final BranchManager branchManager;
    private final ReviewMenuManager reviewMenuManager;

    public ManagerReviewService(BranchManager branchManager, ReviewMenuManager reviewMenuManager) {
        this.branchManager = branchManager;
        this.reviewMenuManager = reviewMenuManager;
    }

    @Override
    public boolean submit(Player player, String branchId) {
        requirePermission(player, "worldgit.branch.submit");
        branchManager.submitBranch(player, branchId);
        MessageUtil.sendSuccess(player, "分支已提交审核");
        return true;
    }

    @Override
    public boolean confirm(Player player, String branchId) {
        requirePermission(player, "worldgit.branch.confirm");
        branchManager.confirmBranch(player, branchId);
        MessageUtil.sendSuccess(player, "合并流程已开始");
        return true;
    }

    @Override
    public boolean forceEdit(Player player, String branchId) {
        requirePermission(player, "worldgit.branch.forceedit");
        Branch branch = branchManager.forceEditBranch(player, branchId);
        MessageUtil.sendSuccess(player, "分支已切回编辑状态: " + branch.id() + "，请重新提交审核。");
        return true;
    }

    @Override
    public boolean approve(CommandSender sender, String branchId, String note) {
        Player player = requirePlayer(sender);
        requirePermission(player, "worldgit.admin.review");
        branchManager.approveBranch(player, branchId, note);
        MessageUtil.sendSuccess(sender, "已批准分支: " + branchId);
        return true;
    }

    @Override
    public boolean reject(CommandSender sender, String branchId, String note) {
        Player player = requirePlayer(sender);
        requirePermission(player, "worldgit.admin.review");
        branchManager.rejectBranch(player, branchId, note);
        MessageUtil.sendSuccess(sender, "已拒绝分支: " + branchId);
        return true;
    }

    @Override
    public boolean list(CommandSender sender) {
        requirePermission(sender, "worldgit.admin.review");
        if (sender instanceof Player player) {
            reviewMenuManager.openPendingReviewMenu(player);
            return true;
        }
        List<Branch> branches = branchManager.listPendingReviews();
        if (branches.isEmpty()) {
            MessageUtil.sendInfo(sender, "当前没有待审核分支。");
            return true;
        }
        MessageUtil.sendInfo(sender, "待审核分支:");
        for (Branch branch : branches) {
            sender.sendMessage(MessageUtil.info(branch.id() + " | " + branch.ownerName() + " | " + branch.worldName()));
        }
        return true;
    }

    @Override
    public List<String> suggestReviewIds(CommandSender sender, String prefix) {
        String normalized = prefix == null ? "" : prefix.toLowerCase();
        return branchManager.listPendingReviews().stream()
                .map(Branch::id)
                .filter(id -> id.toLowerCase().startsWith(normalized))
                .toList();
    }

    @Override
    public List<String> suggestConfirmIds(CommandSender sender, String prefix) {
        if (!(sender instanceof Player player)) {
            return List.of();
        }
        String normalized = prefix == null ? "" : prefix.toLowerCase();
        return branchManager.listOwnApprovedBranches(player).stream()
                .map(Branch::id)
                .filter(id -> id.toLowerCase().startsWith(normalized))
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

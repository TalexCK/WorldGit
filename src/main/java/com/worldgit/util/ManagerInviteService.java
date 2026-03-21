package com.worldgit.util;

import com.worldgit.manager.BranchManager;
import com.worldgit.model.Branch;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class ManagerInviteService implements InviteService {

    private final BranchManager branchManager;

    public ManagerInviteService(BranchManager branchManager) {
        this.branchManager = branchManager;
    }

    @Override
    public boolean invite(CommandSender sender, String target, String branchId) {
        Player inviter = requirePlayer(sender);
        requirePermission(inviter, "worldgit.branch.invite");
        Player invited = Bukkit.getPlayerExact(target);
        if (invited == null) {
            throw new IllegalStateException("目标玩家不在线: " + target);
        }
        branchManager.invitePlayer(inviter, invited, branchId);
        MessageUtil.sendSuccess(sender, "已邀请玩家: " + invited.getName());
        return true;
    }

    @Override
    public boolean uninvite(CommandSender sender, String target, String branchId) {
        Player inviter = requirePlayer(sender);
        requirePermission(inviter, "worldgit.branch.invite");
        Player invited = Bukkit.getPlayerExact(target);
        if (invited == null) {
            throw new IllegalStateException("目标玩家不在线: " + target);
        }
        branchManager.uninvitePlayer(inviter, invited.getUniqueId(), branchId);
        MessageUtil.sendSuccess(sender, "已取消邀请: " + invited.getName());
        return true;
    }

    @Override
    public List<String> suggestTargets(CommandSender sender, String prefix) {
        String normalized = prefix == null ? "" : prefix.toLowerCase();
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase().startsWith(normalized))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
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
        throw new IllegalStateException("该命令只能由玩家执行");
    }

    private void requirePermission(CommandSender sender, String permission) {
        if (!sender.hasPermission(permission)) {
            throw new IllegalStateException("你没有权限执行该命令");
        }
    }
}

package com.worldgit.util;

import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * 邀请成员相关操作契约。
 */
public interface InviteService {

    boolean invite(CommandSender sender, String target, String branchId);

    boolean uninvite(CommandSender sender, String target, String branchId);

    List<String> suggestTargets(CommandSender sender, String prefix);

    List<String> suggestBranchIds(CommandSender sender, String prefix);

    static InviteService noop() {
        return new InviteService() {
            @Override
            public boolean invite(CommandSender sender, String target, String branchId) {
                return false;
            }

            @Override
            public boolean uninvite(CommandSender sender, String target, String branchId) {
                return false;
            }

            @Override
            public List<String> suggestTargets(CommandSender sender, String prefix) {
                return List.of();
            }

            @Override
            public List<String> suggestBranchIds(CommandSender sender, String prefix) {
                return List.of();
            }
        };
    }
}

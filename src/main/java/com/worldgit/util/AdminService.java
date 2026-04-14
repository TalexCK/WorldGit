package com.worldgit.util;

import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * 管理员操作契约。
 */
public interface AdminService {

    boolean close(CommandSender sender, String branchId);

    boolean assign(CommandSender sender, String playerName);

    boolean backup(CommandSender sender);

    boolean sync(CommandSender sender);

    boolean locks(CommandSender sender);

    boolean forceMerge(CommandSender sender, String branchId, boolean confirmed);

    boolean list(CommandSender sender, String playerName);

    boolean reload(CommandSender sender);

    List<String> suggestPlayerNames(CommandSender sender, String prefix);

    List<String> suggestBranchIds(CommandSender sender, String prefix);

    static AdminService noop() {
        return new AdminService() {
            @Override
            public boolean close(CommandSender sender, String branchId) {
                return false;
            }

            @Override
            public boolean assign(CommandSender sender, String playerName) {
                return false;
            }

            @Override
            public boolean backup(CommandSender sender) {
                return false;
            }

            @Override
            public boolean sync(CommandSender sender) {
                return false;
            }

            @Override
            public boolean locks(CommandSender sender) {
                return false;
            }

            @Override
            public boolean forceMerge(CommandSender sender, String branchId, boolean confirmed) {
                return false;
            }

            @Override
            public boolean list(CommandSender sender, String playerName) {
                return false;
            }

            @Override
            public boolean reload(CommandSender sender) {
                return false;
            }

            @Override
            public List<String> suggestPlayerNames(CommandSender sender, String prefix) {
                return List.of();
            }

            @Override
            public List<String> suggestBranchIds(CommandSender sender, String prefix) {
                return List.of();
            }
        };
    }
}

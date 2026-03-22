package com.worldgit.util;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * 审核与合并相关操作契约。
 */
public interface ReviewService {

    boolean submit(Player player, String branchId);

    boolean confirm(Player player, String branchId);

    boolean forceEdit(Player player, String branchId);

    boolean approve(CommandSender sender, String branchId, String note);

    boolean reject(CommandSender sender, String branchId, String note);

    boolean list(CommandSender sender);

    List<String> suggestReviewIds(CommandSender sender, String prefix);

    List<String> suggestConfirmIds(CommandSender sender, String prefix);

    static ReviewService noop() {
        return new ReviewService() {
            @Override
            public boolean submit(Player player, String branchId) {
                return false;
            }

            @Override
            public boolean confirm(Player player, String branchId) {
                return false;
            }

            @Override
            public boolean forceEdit(Player player, String branchId) {
                return false;
            }

            @Override
            public boolean approve(CommandSender sender, String branchId, String note) {
                return false;
            }

            @Override
            public boolean reject(CommandSender sender, String branchId, String note) {
                return false;
            }

            @Override
            public boolean list(CommandSender sender) {
                return false;
            }

            @Override
            public List<String> suggestReviewIds(CommandSender sender, String prefix) {
                return List.of();
            }

            @Override
            public List<String> suggestConfirmIds(CommandSender sender, String prefix) {
                return List.of();
            }
        };
    }
}

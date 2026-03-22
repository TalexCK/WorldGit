package com.worldgit.util;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * 分支相关操作契约。
 *
 * 这里先只定义命令层需要的最小接口，后续由真实管理器实现。
 */
public interface BranchService {

    boolean create(Player player);

    boolean abandon(CommandSender sender, String branchId);

    boolean list(CommandSender sender);

    boolean info(CommandSender sender, String branchId);

    boolean teleport(Player player, String branchId);

    boolean returnToMain(Player player);

    boolean queue(Player player);

    boolean setPos1(Player player, Integer x, Integer y, Integer z);

    boolean setPos2(Player player, Integer x, Integer y, Integer z);

    boolean selection(Player player);

    boolean clearSelection(Player player);

    List<String> suggestBranchIds(CommandSender sender, String prefix);

    static BranchService noop() {
        return new BranchService() {
            @Override
            public boolean create(Player player) {
                return false;
            }

            @Override
            public boolean abandon(CommandSender sender, String branchId) {
                return false;
            }

            @Override
            public boolean list(CommandSender sender) {
                return false;
            }

            @Override
            public boolean info(CommandSender sender, String branchId) {
                return false;
            }

            @Override
            public boolean teleport(Player player, String branchId) {
                return false;
            }

            @Override
            public boolean returnToMain(Player player) {
                return false;
            }

            @Override
            public boolean queue(Player player) {
                return false;
            }

            @Override
            public boolean setPos1(Player player, Integer x, Integer y, Integer z) {
                return false;
            }

            @Override
            public boolean setPos2(Player player, Integer x, Integer y, Integer z) {
                return false;
            }

            @Override
            public boolean selection(Player player) {
                return false;
            }

            @Override
            public boolean clearSelection(Player player) {
                return false;
            }

            @Override
            public List<String> suggestBranchIds(CommandSender sender, String prefix) {
                return List.of();
            }
        };
    }
}

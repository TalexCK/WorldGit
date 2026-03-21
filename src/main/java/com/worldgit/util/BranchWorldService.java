package com.worldgit.util;

import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * 分支世界访问控制契约。
 */
public interface BranchWorldService {

    boolean isBranchWorld(World world);

    boolean canAccess(Player player, World world);

    void handleUnauthorizedTeleport(Player player, World world);

    static BranchWorldService noop() {
        return new BranchWorldService() {
            @Override
            public boolean isBranchWorld(World world) {
                return false;
            }

            @Override
            public boolean canAccess(Player player, World world) {
                return true;
            }

            @Override
            public void handleUnauthorizedTeleport(Player player, World world) {
                // 空实现，后续由真实分支管理器接管
            }
        };
    }
}

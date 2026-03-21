package com.worldgit.util;

import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * 主世界保护契约。
 */
public interface ProtectionService {

    boolean isMainWorld(World world);

    boolean canBypass(Player player);

    static ProtectionService noop() {
        return new ProtectionService() {
            @Override
            public boolean isMainWorld(World world) {
                return false;
            }

            @Override
            public boolean canBypass(Player player) {
                return false;
            }
        };
    }
}

package com.worldgit.util;

import org.bukkit.entity.Player;

/**
 * 玩家连接生命周期契约。
 */
public interface ConnectionService {

    void handleJoin(Player player);

    void handleQuit(Player player);

    static ConnectionService noop() {
        return new ConnectionService() {
            @Override
            public void handleJoin(Player player) {
                // 空实现，后续由真实管理器接管
            }

            @Override
            public void handleQuit(Player player) {
                // 空实现，后续由真实管理器接管
            }
        };
    }
}

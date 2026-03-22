package com.worldgit.util;

import org.bukkit.entity.Player;

/**
 * 玩家主菜单入口。
 */
public interface PlayerMenuService {

    boolean openMainMenu(Player player);

    static PlayerMenuService noop() {
        return new PlayerMenuService() {
            @Override
            public boolean openMainMenu(Player player) {
                return false;
            }
        };
    }
}

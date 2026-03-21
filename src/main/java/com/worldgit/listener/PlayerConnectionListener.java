package com.worldgit.listener;

import com.worldgit.util.ConnectionService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 玩家连接生命周期监听器。
 */
public final class PlayerConnectionListener implements Listener {

    private final ConnectionService connectionService;

    public PlayerConnectionListener() {
        this(ConnectionService.noop());
    }

    public PlayerConnectionListener(ConnectionService connectionService) {
        this.connectionService = connectionService;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        connectionService.handleJoin(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        connectionService.handleQuit(event.getPlayer());
    }
}

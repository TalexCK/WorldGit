package com.worldgit.listener;

import com.worldgit.manager.PlayerStateManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * 玩家世界状态和 action bar 监听器。
 */
public final class PlayerStateListener implements Listener {

    private final PlayerStateManager playerStateManager;

    public PlayerStateListener(PlayerStateManager playerStateManager) {
        this.playerStateManager = playerStateManager;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        playerStateManager.handleJoin(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        playerStateManager.handleQuit(event.getPlayer());
    }

    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        playerStateManager.handleWorldChange(event.getPlayer(), event.getFrom());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        playerStateManager.handleRespawn(event.getPlayer());
    }
}

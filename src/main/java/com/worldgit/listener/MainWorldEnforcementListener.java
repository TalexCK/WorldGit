package com.worldgit.listener;

import com.worldgit.WorldGitPlugin;
import com.worldgit.config.PluginConfig;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * 强制主世界玩家保持创造模式。
 */
public final class MainWorldEnforcementListener implements Listener {

    private final WorldGitPlugin plugin;
    private final PluginConfig config;

    public MainWorldEnforcementListener(WorldGitPlugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        enforceLater(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        enforceLater(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        enforceLater(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        if (!isMainWorld(event.getPlayer().getWorld().getName())) {
            return;
        }
        if (event.getNewGameMode() == GameMode.CREATIVE) {
            return;
        }
        event.setCancelled(true);
        enforceLater(event.getPlayer().getUniqueId());
    }

    private void enforceLater(UUID playerId) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            var player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                return;
            }
            if (!isMainWorld(player.getWorld().getName())) {
                return;
            }
            if (player.getGameMode() != GameMode.CREATIVE) {
                player.setGameMode(GameMode.CREATIVE);
            }
            if (!player.getAllowFlight()) {
                player.setAllowFlight(true);
            }
            player.setFallDistance(0.0f);
        });
    }

    private boolean isMainWorld(String worldName) {
        return config.mainWorld().equalsIgnoreCase(worldName);
    }
}

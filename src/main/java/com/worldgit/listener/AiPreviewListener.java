package com.worldgit.listener;

import com.worldgit.ai.WorldGitAiService;
import java.util.Objects;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;

public final class AiPreviewListener implements Listener {

    private final WorldGitAiService aiService;

    public AiPreviewListener(WorldGitAiService aiService) {
        this.aiService = Objects.requireNonNull(aiService, "AI 服务不能为空");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        aiService.handlePlayerJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        aiService.handlePlayerChangedWorld(event.getPlayer());
    }
}

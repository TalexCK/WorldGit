package com.worldgit.manager;

import com.worldgit.WorldGitPlugin;
import com.worldgit.config.PluginConfig;
import com.worldgit.database.QueueRepository;
import com.worldgit.model.QueueEntry;
import com.worldgit.util.MessageUtil;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class QueueManager {

    private final WorldGitPlugin plugin;
    private final PluginConfig pluginConfig;
    private final QueueRepository queueRepository;

    public QueueManager(WorldGitPlugin plugin, PluginConfig pluginConfig, QueueRepository queueRepository) {
        this.plugin = plugin;
        this.pluginConfig = pluginConfig;
        this.queueRepository = queueRepository;
    }

    public void enqueue(Player player, RegionCopyManager.SelectionBounds bounds) {
        long currentCount = queueRepository.countByPlayerQuietly(player.getUniqueId());
        if (currentCount >= pluginConfig.maxQueueEntries()) {
            throw new IllegalStateException("你的排队条目已达上限");
        }

        QueueEntry entry = new QueueEntry(
                0L,
                player.getUniqueId(),
                player.getName(),
                player.getWorld().getName(),
                bounds.minX(),
                bounds.minY(),
                bounds.minZ(),
                bounds.maxX(),
                bounds.maxY(),
                bounds.maxZ(),
                Instant.now()
        );
        queueRepository.insert(entry);
    }

    public void clearPlayer(UUID playerUuid) {
        queueRepository.deleteByPlayerQuietly(playerUuid);
    }

    public java.util.Optional<QueueEntry> findByPlayer(UUID playerUuid) {
        return queueRepository.findByPlayerQuietly(playerUuid);
    }

    public void notifyRegionUnlocked(String mainWorld, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        List<QueueEntry> entries = queueRepository.findOverlapping(mainWorld, minX, minY, minZ, maxX, maxY, maxZ);
        for (QueueEntry entry : entries) {
            Player player = Bukkit.getPlayer(entry.playerUuid());
            if (player != null && player.isOnline()) {
                MessageUtil.sendSuccess(player, "你排队的区域已解锁，可重新执行 /wg create");
            }
        }
    }

    public QueueRepository queueRepository() {
        return queueRepository;
    }

    public void clearAll() {
        queueRepository.deleteAllQuietly();
    }
}

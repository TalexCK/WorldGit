package com.worldgit.manager;

import com.worldgit.WorldGitPlugin;
import com.worldgit.config.PluginConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;

public final class BackupManager {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final WorldGitPlugin plugin;
    private final PluginConfig pluginConfig;
    private final AtomicBoolean backupRunning = new AtomicBoolean(false);
    private BukkitTask task;

    public BackupManager(WorldGitPlugin plugin, PluginConfig pluginConfig) {
        this.plugin = plugin;
        this.pluginConfig = pluginConfig;
    }

    public void start() {
        if (!pluginConfig.backupEnabled()) {
            return;
        }
        stop();
        long intervalTicks = pluginConfig.backupIntervalMinutes() * 60L * 20L;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::createBackupSafe, intervalTicks, intervalTicks);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public void createBackupSafe() {
        if (!backupRunning.compareAndSet(false, true)) {
            plugin.getLogger().info("已有备份任务在执行，跳过本次备份触发");
            return;
        }
        try {
            createBackup();
        } catch (Exception ex) {
            backupRunning.set(false);
            plugin.getLogger().warning("创建备份失败: " + ex.getMessage());
        }
    }

    public void createBackup() {
        World world = Bukkit.getWorld(pluginConfig.mainWorld());
        if (world == null) {
            throw new IllegalStateException("主世界不存在，无法备份");
        }

        Path backupRoot = pluginConfig.backupDirectoryPath(plugin);
        try {
            Files.createDirectories(backupRoot);
        } catch (IOException ex) {
            throw new IllegalStateException("无法创建备份目录", ex);
        }

        // 开启自动保存时，服务端会自行周期性落盘；此时强制 world.save()
        // 会触发 Paper 的 plugin-induced save 警告，因此仅在关闭自动保存时手动保存。
        if (!world.isAutoSave()) {
            world.save();
        }

        Path source = world.getWorldFolder().toPath();
        String backupName = world.getName() + "-" + FORMATTER.format(LocalDateTime.now());
        Path target = backupRoot.resolve(backupName);
        Path tempTarget = backupRoot.resolve(backupName + ".tmp");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (Files.exists(tempTarget)) {
                    WorldSnapshotUtil.deleteDirectory(tempTarget);
                }
                WorldSnapshotUtil.copyWorldSnapshot(source, tempTarget);
                Files.move(tempTarget, target, StandardCopyOption.REPLACE_EXISTING);
                pruneBackups(backupRoot);
                plugin.getLogger().info("世界备份完成: " + target.getFileName());
            } catch (Exception ex) {
                try {
                    if (Files.exists(tempTarget)) {
                        WorldSnapshotUtil.deleteDirectory(tempTarget);
                    }
                } catch (IOException cleanupEx) {
                    plugin.getLogger().warning("清理失败备份目录时出错: " + cleanupEx.getMessage());
                }
                plugin.getLogger().warning("异步备份失败: " + ex.getMessage());
            } finally {
                backupRunning.set(false);
            }
        });
    }

    private void pruneBackups(Path backupRoot) throws IOException {
        try (var stream = Files.list(backupRoot)) {
            List<Path> backups = stream.filter(Files::isDirectory)
                    .filter(path -> !path.getFileName().toString().endsWith(".tmp"))
                    .sorted(Comparator.comparing(Path::getFileName).reversed())
                    .toList();
            for (int index = pluginConfig.backupMaxBackups(); index < backups.size(); index++) {
                WorldSnapshotUtil.deleteDirectory(backups.get(index));
            }
        }
    }
}

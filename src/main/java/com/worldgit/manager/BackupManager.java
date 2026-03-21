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
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;

public final class BackupManager {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final WorldGitPlugin plugin;
    private final PluginConfig pluginConfig;
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
        try {
            createBackup();
        } catch (Exception ex) {
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

        world.save();

        Path source = world.getWorldFolder().toPath();
        Path target = backupRoot.resolve(world.getName() + "-" + FORMATTER.format(LocalDateTime.now()));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                copyDirectory(source, target);
                pruneBackups(backupRoot);
                plugin.getLogger().info("世界备份完成: " + target.getFileName());
            } catch (Exception ex) {
                plugin.getLogger().warning("异步备份失败: " + ex.getMessage());
            }
        });
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        try (var stream = Files.walk(source)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                Path relative = source.relativize(path);
                if ("session.lock".equals(relative.toString())) {
                    continue;
                }
                Path destination = target.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private void pruneBackups(Path backupRoot) throws IOException {
        try (var stream = Files.list(backupRoot)) {
            List<Path> backups = stream.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(Path::getFileName).reversed())
                    .toList();
            for (int index = pluginConfig.backupMaxBackups(); index < backups.size(); index++) {
                deleteDirectory(backups.get(index));
            }
        }
    }

    private void deleteDirectory(Path directory) throws IOException {
        try (var stream = Files.walk(directory)) {
            for (Path path : (Iterable<Path>) stream.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(path);
            }
        }
    }
}

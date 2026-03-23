package com.worldgit.manager;

import com.worldgit.WorldGitPlugin;
import com.worldgit.config.PluginConfig;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
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
                    deleteDirectory(tempTarget);
                }
                copyDirectory(source, tempTarget);
                Files.move(tempTarget, target, StandardCopyOption.REPLACE_EXISTING);
                pruneBackups(backupRoot);
                plugin.getLogger().info("世界备份完成: " + target.getFileName());
            } catch (Exception ex) {
                try {
                    if (Files.exists(tempTarget)) {
                        deleteDirectory(tempTarget);
                    }
                } catch (IOException cleanupEx) {
                    plugin.getLogger().warning("清理失败备份目录时出错: " + cleanupEx.getMessage());
                }
                plugin.getLogger().warning("异步备份失败: " + ex.getMessage());
            }
        });
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(dir);
                if (shouldSkip(relative)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(target.resolve(relative));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(file);
                if (shouldSkip(relative)) {
                    return FileVisitResult.CONTINUE;
                }

                Path destination = target.resolve(relative);
                Files.createDirectories(destination.getParent());
                try {
                    Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                } catch (NoSuchFileException ex) {
                    if (!shouldIgnoreMissing(file)) {
                        throw ex;
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                if (exc instanceof NoSuchFileException && shouldIgnoreMissing(file)) {
                    return FileVisitResult.CONTINUE;
                }
                throw exc;
            }
        });
    }

    private boolean shouldSkip(Path relativePath) {
        if (relativePath == null || relativePath.toString().isBlank()) {
            return false;
        }
        return "session.lock".equals(relativePath.toString());
    }

    private boolean shouldIgnoreMissing(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }

        String fileName = path.getFileName().toString();
        if ("session.lock".equals(fileName)) {
            return true;
        }

        // Minecraft 在写入 level.dat 时会先生成临时 level*.dat，再原子替换。
        // 这些临时文件在遍历期间消失是正常现象，不应导致整个备份失败。
        return fileName.startsWith("level")
                && fileName.endsWith(".dat")
                && !"level.dat".equals(fileName)
                && !"level.dat_old".equals(fileName);
    }

    private void pruneBackups(Path backupRoot) throws IOException {
        try (var stream = Files.list(backupRoot)) {
            List<Path> backups = stream.filter(Files::isDirectory)
                    .filter(path -> !path.getFileName().toString().endsWith(".tmp"))
                    .sorted(Comparator.comparing(Path::getFileName).reversed())
                    .toList();
            for (int index = pluginConfig.backupMaxBackups(); index < backups.size(); index++) {
                deleteDirectory(backups.get(index));
            }
        }
    }

    private void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var stream = Files.walk(directory)) {
            for (Path path : (Iterable<Path>) stream.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(path);
            }
        }
    }
}

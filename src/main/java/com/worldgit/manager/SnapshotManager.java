package com.worldgit.manager;

import com.worldgit.WorldGitPlugin;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

/**
 * 区域快照管理器。
 */
public final class SnapshotManager {

    private static final int SNAPSHOT_MAGIC = 0x57475331;

    private final WorldGitPlugin plugin;

    public SnapshotManager(WorldGitPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "插件实例不能为空");
    }

    public Path createBaseSnapshotPath(String branchId) {
        return branchDirectory(branchId).resolve("base.wgsnap");
    }

    public Path createPendingSnapshotPath(String branchId, long revision) {
        return branchDirectory(branchId).resolve("pending-" + revision + ".wgsnap");
    }

    public Path createWorkingSnapshotPath(String branchId) {
        return branchDirectory(branchId).resolve("working.wgsnap");
    }

    public Path createConflictDetailPath(String branchId, int groupIndex) {
        return branchDirectory(branchId).resolve("conflict-" + groupIndex + ".wgconf");
    }

    public RegionSnapshot capture(World world, RegionCopyManager.SelectionBounds bounds, Path targetPath) {
        Objects.requireNonNull(world, "世界不能为空");
        Objects.requireNonNull(bounds, "区域不能为空");
        Objects.requireNonNull(targetPath, "目标路径不能为空");
        RegionSnapshot snapshot = RegionSnapshot.capture(world, bounds);
        saveSnapshot(snapshot, targetPath);
        return snapshot;
    }

    public void saveSnapshot(RegionSnapshot snapshot, Path targetPath) {
        Objects.requireNonNull(snapshot, "快照不能为空");
        Objects.requireNonNull(targetPath, "目标路径不能为空");
        try {
            Files.createDirectories(targetPath.toAbsolutePath().getParent());
            try (DataOutputStream outputStream = new DataOutputStream(
                    new BufferedOutputStream(
                            new GZIPOutputStream(Files.newOutputStream(targetPath))
                    )
            )) {
                outputStream.writeInt(SNAPSHOT_MAGIC);
                outputStream.writeInt(snapshot.bounds().minX());
                outputStream.writeInt(snapshot.bounds().minY());
                outputStream.writeInt(snapshot.bounds().minZ());
                outputStream.writeInt(snapshot.bounds().maxX());
                outputStream.writeInt(snapshot.bounds().maxY());
                outputStream.writeInt(snapshot.bounds().maxZ());
                outputStream.writeInt(snapshot.palette().length);
                for (String blockState : snapshot.palette()) {
                    outputStream.writeUTF(blockState);
                }
                outputStream.writeInt(snapshot.paletteIndexes().length);
                for (int paletteIndex : snapshot.paletteIndexes()) {
                    outputStream.writeInt(paletteIndex);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("保存区域快照失败: " + targetPath, exception);
        }
    }

    public RegionSnapshot loadSnapshot(String path) {
        return loadSnapshot(Path.of(path));
    }

    public RegionSnapshot loadSnapshot(Path path) {
        Objects.requireNonNull(path, "快照路径不能为空");
        try (DataInputStream inputStream = new DataInputStream(
                new BufferedInputStream(
                        new GZIPInputStream(Files.newInputStream(path))
                )
        )) {
            int magic = inputStream.readInt();
            if (magic != SNAPSHOT_MAGIC) {
                throw new IllegalStateException("无法识别的快照格式: " + path);
            }
            RegionCopyManager.SelectionBounds bounds = new RegionCopyManager.SelectionBounds(
                    inputStream.readInt(),
                    inputStream.readInt(),
                    inputStream.readInt(),
                    inputStream.readInt(),
                    inputStream.readInt(),
                    inputStream.readInt()
            );
            int paletteSize = inputStream.readInt();
            String[] palette = new String[paletteSize];
            for (int index = 0; index < paletteSize; index++) {
                palette[index] = inputStream.readUTF();
            }
            int dataSize = inputStream.readInt();
            int[] paletteIndexes = new int[dataSize];
            for (int index = 0; index < dataSize; index++) {
                paletteIndexes[index] = inputStream.readInt();
            }
            return new RegionSnapshot(bounds, palette, paletteIndexes);
        } catch (IOException exception) {
            throw new IllegalStateException("读取区域快照失败: " + path, exception);
        }
    }

    public void applySnapshot(World world, RegionSnapshot snapshot) {
        Objects.requireNonNull(world, "世界不能为空");
        Objects.requireNonNull(snapshot, "快照不能为空");
        Map<String, BlockData> cache = new HashMap<>();
        RegionCopyManager.SelectionBounds bounds = snapshot.bounds();
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                    String blockState = snapshot.blockStateAt(x, y, z);
                    BlockData blockData = cache.computeIfAbsent(blockState, Bukkit::createBlockData);
                    world.getBlockAt(x, y, z).setBlockData(blockData, false);
                }
            }
        }
    }

    public void moveSnapshot(Path source, Path target) {
        try {
            Files.createDirectories(target.toAbsolutePath().getParent());
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("移动快照文件失败: " + source + " -> " + target, exception);
        }
    }

    public void deleteQuietly(String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        deleteQuietly(Path.of(path));
    }

    public void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 清理阶段尽力而为。
        }
    }

    public void deleteBranchDirectoryQuietly(String branchId) {
        Path directory = snapshotRootPath().resolve(branchId);
        if (!Files.exists(directory)) {
            return;
        }
        try (var stream = Files.walk(directory)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(this::deleteQuietly);
        } catch (IOException ignored) {
            // 清理阶段尽力而为。
        }
    }

    private Path branchDirectory(String branchId) {
        Path directory = snapshotRootPath().resolve(branchId);
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建快照目录: " + directory, exception);
        }
        return directory;
    }

    private Path snapshotRootPath() {
        return plugin.getDataFolder().toPath().resolve("snapshots");
    }

    public record RegionSnapshot(
            RegionCopyManager.SelectionBounds bounds,
            String[] palette,
            int[] paletteIndexes
    ) {

        public RegionSnapshot {
            Objects.requireNonNull(bounds, "快照区域不能为空");
            Objects.requireNonNull(palette, "快照调色板不能为空");
            Objects.requireNonNull(paletteIndexes, "快照数据不能为空");
        }

        public static RegionSnapshot capture(World world, RegionCopyManager.SelectionBounds bounds) {
            LinkedHashMap<String, Integer> paletteIndexes = new LinkedHashMap<>();
            int[] data = new int[volume(bounds)];
            int cursor = 0;
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                        String blockState = world.getBlockAt(x, y, z).getBlockData().getAsString();
                        int paletteIndex = paletteIndexes.computeIfAbsent(blockState, ignored -> paletteIndexes.size());
                        data[cursor++] = paletteIndex;
                    }
                }
            }
            String[] palette = new String[paletteIndexes.size()];
            for (Map.Entry<String, Integer> entry : paletteIndexes.entrySet()) {
                palette[entry.getValue()] = entry.getKey();
            }
            return new RegionSnapshot(bounds, palette, data);
        }

        public String blockStateAt(int x, int y, int z) {
            int offset = offsetOf(x, y, z);
            return palette[paletteIndexes[offset]];
        }

        private int offsetOf(int x, int y, int z) {
            int widthY = bounds.maxY() - bounds.minY() + 1;
            int widthZ = bounds.maxZ() - bounds.minZ() + 1;
            int relativeX = x - bounds.minX();
            int relativeY = y - bounds.minY();
            int relativeZ = z - bounds.minZ();
            return ((relativeX * widthZ) + relativeZ) * widthY + relativeY;
        }

        private static int volume(RegionCopyManager.SelectionBounds bounds) {
            int widthX = bounds.maxX() - bounds.minX() + 1;
            int widthY = bounds.maxY() - bounds.minY() + 1;
            int widthZ = bounds.maxZ() - bounds.minZ() + 1;
            return widthX * widthY * widthZ;
        }
    }
}

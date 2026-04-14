package com.worldgit.manager;

import com.worldgit.config.PluginConfig;
import java.util.HashSet;
import java.util.Set;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

public final class RegionCopyManager {

    private static final int COPY_PADDING = 20;

    private final PluginConfig pluginConfig;

    public RegionCopyManager(PluginConfig pluginConfig) {
        this.pluginConfig = pluginConfig;
    }

    public void validate(SelectionBounds bounds) {
        // 1.1.1 起取消 Pos1 / Pos2 选区尺寸上限，仅保留后续复制与世界边界校验。
    }

    public void copyRegion(World source, World target, SelectionBounds bounds) {
        copyRegion(source, target, bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ());
    }

    public int copyRegionOutsideExclusion(
            World source,
            World target,
            SelectionBounds bounds,
            SelectionBounds excludedBounds
    ) {
        return copyRegion(source, target, bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX(), bounds.maxY(), bounds.maxZ(), excludedBounds);
    }

    public int countRegionDifferencesOutsideExclusion(
            World source,
            World target,
            SelectionBounds bounds,
            SelectionBounds excludedBounds
    ) {
        return countRegionDifferences(source, target, bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX(), bounds.maxY(), bounds.maxZ(), excludedBounds);
    }

    public SelectionBounds expandForCopy(World world, SelectionBounds editableBounds) {
        return new SelectionBounds(
                editableBounds.minX() - COPY_PADDING,
                world.getMinHeight(),
                editableBounds.minZ() - COPY_PADDING,
                editableBounds.maxX() + COPY_PADDING,
                world.getMaxHeight() - 1,
                editableBounds.maxZ() + COPY_PADDING
        );
    }

    public void copyRegion(World source, World target, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        copyRegion(source, target, minX, minY, minZ, maxX, maxY, maxZ, null);
    }

    private int copyRegion(
            World source,
            World target,
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ,
            SelectionBounds excludedBounds
    ) {
        preloadChunks(source, minX, minZ, maxX, maxZ);
        preloadChunks(target, minX, minZ, maxX, maxZ);
        int copiedBlocks = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    if (excludedBounds != null
                            && x >= excludedBounds.minX() && x <= excludedBounds.maxX()
                            && y >= excludedBounds.minY() && y <= excludedBounds.maxY()
                            && z >= excludedBounds.minZ() && z <= excludedBounds.maxZ()) {
                        continue;
                    }
                    Block sourceBlock = source.getBlockAt(x, y, z);
                    Block targetBlock = target.getBlockAt(x, y, z);
                    BlockData data = sourceBlock.getBlockData().clone();
                    if (data.equals(targetBlock.getBlockData())) {
                        continue;
                    }
                    targetBlock.setBlockData(data, false);
                    copiedBlocks++;
                }
            }
        }
        return copiedBlocks;
    }

    private int countRegionDifferences(
            World source,
            World target,
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ,
            SelectionBounds excludedBounds
    ) {
        preloadChunks(source, minX, minZ, maxX, maxZ);
        preloadChunks(target, minX, minZ, maxX, maxZ);
        int differentBlocks = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    if (excludedBounds != null
                            && x >= excludedBounds.minX() && x <= excludedBounds.maxX()
                            && y >= excludedBounds.minY() && y <= excludedBounds.maxY()
                            && z >= excludedBounds.minZ() && z <= excludedBounds.maxZ()) {
                        continue;
                    }
                    BlockData sourceData = source.getBlockAt(x, y, z).getBlockData();
                    BlockData targetData = target.getBlockAt(x, y, z).getBlockData();
                    if (!sourceData.equals(targetData)) {
                        differentBlocks++;
                    }
                }
            }
        }
        return differentBlocks;
    }

    private void preloadChunks(World world, int minX, int minZ, int maxX, int maxZ) {
        Set<Long> visited = new HashSet<>();
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                long key = (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
                if (visited.add(key)) {
                    world.getChunkAt(chunkX, chunkZ).load(true);
                }
            }
        }
    }

    public record SelectionBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

        public int widthX() {
            return maxX - minX + 1;
        }

        public int widthZ() {
            return maxZ - minZ + 1;
        }
    }
}

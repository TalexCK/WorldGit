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
        if (bounds.widthX() > pluginConfig.maxRegionSizeX()) {
            throw new IllegalStateException("所选区域 X 长度超过限制: " + pluginConfig.maxRegionSizeX());
        }
        if (bounds.widthZ() > pluginConfig.maxRegionSizeZ()) {
            throw new IllegalStateException("所选区域 Z 长度超过限制: " + pluginConfig.maxRegionSizeZ());
        }
    }

    public void copyRegion(World source, World target, SelectionBounds bounds) {
        copyRegion(source, target, bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ());
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
        preloadChunks(source, minX, minZ, maxX, maxZ);
        preloadChunks(target, minX, minZ, maxX, maxZ);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    Block sourceBlock = source.getBlockAt(x, y, z);
                    Block targetBlock = target.getBlockAt(x, y, z);
                    BlockData data = sourceBlock.getBlockData().clone();
                    targetBlock.setBlockData(data, false);
                }
            }
        }
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

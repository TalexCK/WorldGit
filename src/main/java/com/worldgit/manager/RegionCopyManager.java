package com.worldgit.manager;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.worldgit.WorldGitPlugin;
import com.worldgit.config.PluginConfig;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

public final class RegionCopyManager {

    private final WorldGitPlugin plugin;
    private final PluginConfig pluginConfig;

    public RegionCopyManager(WorldGitPlugin plugin, PluginConfig pluginConfig) {
        this.plugin = plugin;
        this.pluginConfig = pluginConfig;
    }

    public SelectionBounds readSelection(Player player) {
        try {
            LocalSession session = WorldEdit.getInstance()
                    .getSessionManager()
                    .get(BukkitAdapter.adapt(player));
            Region selection = session.getSelection(BukkitAdapter.adapt(player.getWorld()));
            BlockVector3 min = selection.getMinimumPoint();
            BlockVector3 max = selection.getMaximumPoint();

            int minY = pluginConfig.useFullHeight() ? player.getWorld().getMinHeight() : min.y();
            int maxY = pluginConfig.useFullHeight() ? player.getWorld().getMaxHeight() - 1 : max.y();
            SelectionBounds bounds = new SelectionBounds(min.x(), minY, min.z(), max.x(), maxY, max.z());
            validate(bounds);
            return bounds;
        } catch (IncompleteRegionException ex) {
            throw new IllegalStateException("请先使用 WorldEdit 选择一个长方体区域");
        }
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

    public void copyRegion(World source, World target, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                target.getChunkAt(x >> 4, z >> 4).load(true);
                for (int y = minY; y <= maxY; y++) {
                    Block sourceBlock = source.getBlockAt(x, y, z);
                    Block targetBlock = target.getBlockAt(x, y, z);
                    BlockData data = sourceBlock.getBlockData().clone();
                    targetBlock.setType(Material.AIR, false);
                    targetBlock.setBlockData(data, false);
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

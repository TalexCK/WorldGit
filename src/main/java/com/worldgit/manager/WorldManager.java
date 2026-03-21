package com.worldgit.manager;

import com.worldgit.WorldGitPlugin;
import com.worldgit.config.PluginConfig;
import com.worldgit.generator.VoidChunkGenerator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;

public final class WorldManager {

    private final WorldGitPlugin plugin;
    private final PluginConfig pluginConfig;

    public WorldManager(WorldGitPlugin plugin, PluginConfig pluginConfig) {
        this.plugin = plugin;
        this.pluginConfig = pluginConfig;
    }

    public World getMainWorld() {
        World world = Bukkit.getWorld(pluginConfig.mainWorld());
        if (world == null) {
            throw new IllegalStateException("主世界不存在: " + pluginConfig.mainWorld());
        }
        return world;
    }

    public World createBranchWorld(String worldName) {
        World existing = Bukkit.getWorld(worldName);
        if (existing != null) {
            return existing;
        }

        WorldCreator creator = new WorldCreator(worldName)
                .generator(new VoidChunkGenerator())
                .environment(World.Environment.NORMAL);
        World world = Bukkit.createWorld(creator);
        if (world == null) {
            throw new IllegalStateException("分支世界创建失败: " + worldName);
        }
        world.setAutoSave(false);
        return world;
    }

    public boolean deleteWorld(String worldName, Location fallbackLocation) {
        World world = Bukkit.getWorld(worldName);
        if (world != null) {
            Location safeFallback = fallbackLocation != null ? fallbackLocation : getMainWorld().getSpawnLocation();
            for (Player player : world.getPlayers()) {
                player.teleport(safeFallback);
            }
            if (!Bukkit.unloadWorld(world, false)) {
                return false;
            }
        }

        Path worldDir = Bukkit.getWorldContainer().toPath().resolve(worldName);
        if (!Files.exists(worldDir)) {
            return true;
        }

        try (var stream = Files.walk(worldDir)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ex) {
                            throw new IllegalStateException("删除世界目录失败: " + worldName, ex);
                        }
                    });
            return true;
        } catch (IOException ex) {
            throw new IllegalStateException("删除世界目录失败: " + worldName, ex);
        }
    }

    public boolean isBranchWorld(String worldName) {
        return worldName != null && worldName.startsWith(pluginConfig.branchWorldPrefix());
    }

    public boolean isBranchWorld(World world) {
        return world != null && isBranchWorld(world.getName());
    }

    public String createBranchWorldName(String branchId) {
        return pluginConfig.branchWorldPrefix() + branchId.substring(0, Math.min(8, branchId.length())).toLowerCase();
    }

    public Location createReturnLocation(int minX, int maxX, int minZ, int maxZ) {
        World mainWorld = getMainWorld();
        int centerX = (minX + maxX) / 2;
        int centerZ = (minZ + maxZ) / 2;
        int y = Math.max(mainWorld.getHighestBlockYAt(centerX, centerZ) + 1, mainWorld.getSpawnLocation().getBlockY());
        return new Location(mainWorld, centerX + 0.5, y, centerZ + 0.5);
    }

    public Location createBranchSpawn(World world, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        int centerX = (minX + maxX) / 2;
        int centerZ = (minZ + maxZ) / 2;
        int highest = world.getHighestBlockYAt(centerX, centerZ);
        int y = highest > world.getMinHeight() ? highest + 1 : Math.min(maxY + 2, world.getMaxHeight() - 1);
        y = Math.max(y, minY + 1);
        return new Location(world, centerX + 0.5, y, centerZ + 0.5);
    }
}

package com.worldgit.config;

import java.nio.file.Path;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class PluginConfig {

    private final String mainWorld;
    private final int maxRegionSizeX;
    private final int maxRegionSizeZ;
    private final boolean useFullHeight;
    private final int maxActiveBranches;
    private final int maxQueueEntries;
    private final boolean backupEnabled;
    private final int backupIntervalMinutes;
    private final int backupMaxBackups;
    private final String backupDirectory;
    private final String branchWorldPrefix;
    private final String databaseFile;

    private PluginConfig(
            String mainWorld,
            int maxRegionSizeX,
            int maxRegionSizeZ,
            boolean useFullHeight,
            int maxActiveBranches,
            int maxQueueEntries,
            boolean backupEnabled,
            int backupIntervalMinutes,
            int backupMaxBackups,
            String backupDirectory,
            String branchWorldPrefix,
            String databaseFile
    ) {
        this.mainWorld = mainWorld;
        this.maxRegionSizeX = maxRegionSizeX;
        this.maxRegionSizeZ = maxRegionSizeZ;
        this.useFullHeight = useFullHeight;
        this.maxActiveBranches = maxActiveBranches;
        this.maxQueueEntries = maxQueueEntries;
        this.backupEnabled = backupEnabled;
        this.backupIntervalMinutes = backupIntervalMinutes;
        this.backupMaxBackups = backupMaxBackups;
        this.backupDirectory = backupDirectory;
        this.branchWorldPrefix = branchWorldPrefix;
        this.databaseFile = databaseFile;
    }

    public static PluginConfig load(JavaPlugin plugin) {
        FileConfiguration config = plugin.getConfig();
        return new PluginConfig(
                config.getString("main-world", "world"),
                Math.max(1, config.getInt("max-region-size-x", 50)),
                Math.max(1, config.getInt("max-region-size-z", 50)),
                config.getBoolean("use-full-height", false),
                Math.max(1, config.getInt("max-active-branches", 2)),
                Math.max(1, config.getInt("max-queue-entries", 1)),
                config.getBoolean("backup.enabled", true),
                Math.max(1, config.getInt("backup.interval-minutes", 30)),
                Math.max(1, config.getInt("backup.max-backups", 10)),
                config.getString("backup.directory", "backups"),
                config.getString("branch-world.prefix", "wg_"),
                config.getString("database.file", "worldgit.db")
        );
    }

    public String mainWorld() {
        return mainWorld;
    }

    public int maxRegionSizeX() {
        return maxRegionSizeX;
    }

    public int maxRegionSizeZ() {
        return maxRegionSizeZ;
    }

    public boolean useFullHeight() {
        return useFullHeight;
    }

    public int maxActiveBranches() {
        return maxActiveBranches;
    }

    public int maxQueueEntries() {
        return maxQueueEntries;
    }

    public boolean backupEnabled() {
        return backupEnabled;
    }

    public int backupIntervalMinutes() {
        return backupIntervalMinutes;
    }

    public int backupMaxBackups() {
        return backupMaxBackups;
    }

    public String backupDirectory() {
        return backupDirectory;
    }

    public Path backupDirectoryPath(JavaPlugin plugin) {
        return plugin.getDataFolder().toPath().resolve(backupDirectory);
    }

    public String branchWorldPrefix() {
        return branchWorldPrefix;
    }

    public String databaseFile() {
        return databaseFile;
    }

    public Path databasePath(JavaPlugin plugin) {
        return plugin.getDataFolder().toPath().resolve(databaseFile);
    }
}

package com.worldgit.config;

import java.nio.file.Path;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class PluginConfig {

    private final String displayPrefix;
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
    private final boolean githubSyncEnabled;
    private final int githubSyncIntervalMinutes;
    private final String githubSyncRepository;
    private final String githubSyncPrivateKeyPath;
    private final String githubSyncBranch;
    private final String githubSyncDirectory;
    private final String githubSyncAuthorName;
    private final String githubSyncAuthorEmail;
    private final String branchWorldDirectory;
    private final String branchWorldPrefix;
    private final String databaseFile;
    private final boolean webEnabled;
    private final String webHost;
    private final int webPort;
    private final String webStaticDirectory;
    private final int webRecentLimit;
    private final String webBlueMapUrl;

    private PluginConfig(
            String displayPrefix,
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
            boolean githubSyncEnabled,
            int githubSyncIntervalMinutes,
            String githubSyncRepository,
            String githubSyncPrivateKeyPath,
            String githubSyncBranch,
            String githubSyncDirectory,
            String githubSyncAuthorName,
            String githubSyncAuthorEmail,
            String branchWorldDirectory,
            String branchWorldPrefix,
            String databaseFile,
            boolean webEnabled,
            String webHost,
            int webPort,
            String webStaticDirectory,
            int webRecentLimit,
            String webBlueMapUrl
    ) {
        this.displayPrefix = displayPrefix;
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
        this.githubSyncEnabled = githubSyncEnabled;
        this.githubSyncIntervalMinutes = githubSyncIntervalMinutes;
        this.githubSyncRepository = githubSyncRepository;
        this.githubSyncPrivateKeyPath = githubSyncPrivateKeyPath;
        this.githubSyncBranch = githubSyncBranch;
        this.githubSyncDirectory = githubSyncDirectory;
        this.githubSyncAuthorName = githubSyncAuthorName;
        this.githubSyncAuthorEmail = githubSyncAuthorEmail;
        this.branchWorldDirectory = branchWorldDirectory;
        this.branchWorldPrefix = branchWorldPrefix;
        this.databaseFile = databaseFile;
        this.webEnabled = webEnabled;
        this.webHost = webHost;
        this.webPort = webPort;
        this.webStaticDirectory = webStaticDirectory;
        this.webRecentLimit = webRecentLimit;
        this.webBlueMapUrl = webBlueMapUrl;
    }

    public static PluginConfig load(JavaPlugin plugin) {
        FileConfiguration config = plugin.getConfig();
        return new PluginConfig(
                normalizeDisplayPrefix(config.getString("display-prefix", "WorldGit")),
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
                config.getBoolean("github-sync.enabled", false),
                Math.max(1, config.getInt("github-sync.interval-minutes", 30)),
                normalizeOptional(config.getString("github-sync.repository", "")),
                normalizeOptional(config.getString("github-sync.private-key-path", "")),
                normalizeDirectory(config.getString("github-sync.branch", "main"), "main"),
                normalizeDirectory(config.getString("github-sync.directory", "github-sync"), "github-sync"),
                normalizeDirectory(config.getString("github-sync.author-name", "WorldGit"), "WorldGit"),
                normalizeDirectory(config.getString("github-sync.author-email", "worldgit@local"), "worldgit@local"),
                config.getString("branch-world.directory", "branch"),
                config.getString("branch-world.prefix", "wg_"),
                config.getString("database.file", "worldgit.db"),
                config.getBoolean("web.enabled", true),
                normalizeHost(config.getString("web.host", "0.0.0.0")),
                normalizePort(config.getInt("web.port", 80)),
                normalizeDirectory(config.getString("web.static-directory", "web"), "web"),
                Math.max(1, config.getInt("web.recent-limit", 20)),
                normalizeOptional(config.getString("web.bluemap-url", ""))
        );
    }

    private static String normalizeDisplayPrefix(String value) {
        if (value == null || value.isBlank()) {
            return "WorldGit";
        }
        return value.trim();
    }

    private static String normalizeHost(String value) {
        if (value == null || value.isBlank()) {
            return "0.0.0.0";
        }
        return value.trim();
    }

    private static int normalizePort(int value) {
        if (value < 1 || value > 65535) {
            return 80;
        }
        return value;
    }

    private static String normalizeDirectory(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim();
    }

    public String displayPrefix() {
        return displayPrefix;
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

    public boolean githubSyncEnabled() {
        return githubSyncEnabled;
    }

    public int githubSyncIntervalMinutes() {
        return githubSyncIntervalMinutes;
    }

    public String githubSyncRepository() {
        return githubSyncRepository;
    }

    public String githubSyncPrivateKeyPath() {
        return githubSyncPrivateKeyPath;
    }

    public Path githubSyncPrivateKeyPath(JavaPlugin plugin) {
        Path configuredPath = Path.of(githubSyncPrivateKeyPath);
        if (configuredPath.isAbsolute()) {
            return configuredPath;
        }
        return plugin.getDataFolder().toPath().resolve(configuredPath);
    }

    public String githubSyncBranch() {
        return githubSyncBranch;
    }

    public String githubSyncDirectory() {
        return githubSyncDirectory;
    }

    public Path githubSyncDirectoryPath(JavaPlugin plugin) {
        Path dataFolder = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        Path resolved = dataFolder.resolve(githubSyncDirectory).normalize();
        if (!resolved.startsWith(dataFolder)) {
            throw new IllegalStateException("github-sync.directory 必须位于插件数据目录内");
        }
        return resolved;
    }

    public String githubSyncAuthorName() {
        return githubSyncAuthorName;
    }

    public String githubSyncAuthorEmail() {
        return githubSyncAuthorEmail;
    }

    public String branchWorldPrefix() {
        return branchWorldPrefix;
    }

    public String branchWorldDirectory() {
        return branchWorldDirectory;
    }

    public String databaseFile() {
        return databaseFile;
    }

    public Path databasePath(JavaPlugin plugin) {
        return plugin.getDataFolder().toPath().resolve(databaseFile);
    }

    public boolean webEnabled() {
        return webEnabled;
    }

    public String webHost() {
        return webHost;
    }

    public int webPort() {
        return webPort;
    }

    public String webStaticDirectory() {
        return webStaticDirectory;
    }

    public Path webStaticDirectoryPath(JavaPlugin plugin) {
        return plugin.getDataFolder().toPath().resolve(webStaticDirectory);
    }

    public int webRecentLimit() {
        return webRecentLimit;
    }

    public String webBlueMapUrl() {
        return webBlueMapUrl;
    }
}

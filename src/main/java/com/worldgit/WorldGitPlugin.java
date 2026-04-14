package com.worldgit;

import com.worldgit.command.AdminCommands;
import com.worldgit.command.BranchCommands;
import com.worldgit.command.InviteCommands;
import com.worldgit.command.ReviewCommands;
import com.worldgit.command.WorldGitCommand;
import com.worldgit.config.PluginConfig;
import com.worldgit.database.BranchRepository;
import com.worldgit.database.BranchSyncRepository;
import com.worldgit.database.DatabaseManager;
import com.worldgit.database.LockRepository;
import com.worldgit.database.QueueRepository;
import com.worldgit.database.RevisionRepository;
import com.worldgit.generator.VoidChunkGenerator;
import com.worldgit.listener.BranchWorldListener;
import com.worldgit.listener.BranchEditProtectionListener;
import com.worldgit.listener.MainWorldEnforcementListener;
import com.worldgit.listener.MainWorldProtectionListener;
import com.worldgit.listener.PlayerConnectionListener;
import com.worldgit.listener.PlayerStateListener;
import com.worldgit.manager.AxiomProtectionHook;
import com.worldgit.manager.BackupManager;
import com.worldgit.manager.BranchManager;
import com.worldgit.manager.BranchChangeManager;
import com.worldgit.manager.BlueMapEditRegionManager;
import com.worldgit.manager.ConflictToolManager;
import com.worldgit.manager.GitHubSyncManager;
import com.worldgit.manager.LockManager;
import com.worldgit.manager.MergeManager;
import com.worldgit.manager.MergeStatManager;
import com.worldgit.manager.PlayerSelectionManager;
import com.worldgit.manager.PlayerStateManager;
import com.worldgit.manager.ProtectionManager;
import com.worldgit.manager.QueueManager;
import com.worldgit.manager.RebaseManager;
import com.worldgit.manager.RegionCopyManager;
import com.worldgit.manager.SnapshotManager;
import com.worldgit.manager.WorldManager;
import com.worldgit.util.ManagerAdminService;
import com.worldgit.util.ManagerBranchService;
import com.worldgit.util.ManagerBranchWorldService;
import com.worldgit.util.ManagerConnectionService;
import com.worldgit.util.ManagerInviteService;
import com.worldgit.util.ManagerReviewService;
import com.worldgit.util.MessageUtil;
import com.worldgit.util.PlayerMenuManager;
import com.worldgit.util.ReviewMenuManager;
import com.worldgit.web.PluginWebServer;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Objects;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.World;
import org.bukkit.entity.Ambient;
import org.bukkit.entity.Animals;
import org.bukkit.entity.WaterMob;
import org.bukkit.command.PluginCommand;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class WorldGitPlugin extends JavaPlugin {

    private PluginConfig pluginConfig;
    private DatabaseManager databaseManager;
    private BranchRepository branchRepository;
    private BranchSyncRepository branchSyncRepository;
    private LockRepository lockRepository;
    private QueueRepository queueRepository;
    private RevisionRepository revisionRepository;
    private ProtectionManager protectionManager;
    private WorldManager worldManager;
    private RegionCopyManager regionCopyManager;
    private QueueManager queueManager;
    private LockManager lockManager;
    private SnapshotManager snapshotManager;
    private RebaseManager rebaseManager;
    private MergeManager mergeManager;
    private BackupManager backupManager;
    private GitHubSyncManager gitHubSyncManager;
    private BranchManager branchManager;
    private BlueMapEditRegionManager blueMapEditRegionManager;
    private ConflictToolManager conflictToolManager;
    private BranchChangeManager branchChangeManager;
    private MergeStatManager mergeStatManager;
    private AxiomProtectionHook axiomProtectionHook;
    private PlayerSelectionManager selectionManager;
    private PlayerStateManager playerStateManager;
    private ReviewMenuManager reviewMenuManager;
    private PlayerMenuManager playerMenuManager;
    private PluginWebServer webServer;

    @Override
    public void onEnable() {
        try {
            saveDefaultConfig();
            if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
                throw new IllegalStateException("无法创建插件数据目录");
            }
            reloadRuntime();
        } catch (SQLException exception) {
            throw new IllegalStateException("初始化数据库失败", exception);
        }
    }

    @Override
    public void onDisable() {
        stopAxiomProtectionHook();
        if (backupManager != null) {
            backupManager.stop();
        }
        if (gitHubSyncManager != null) {
            gitHubSyncManager.stop();
        }
        if (playerMenuManager != null) {
            playerMenuManager.stop();
        }
        if (blueMapEditRegionManager != null) {
            blueMapEditRegionManager.stop();
        }
        if (conflictToolManager != null) {
            conflictToolManager.stopAllSessions();
        }
        if (playerStateManager != null) {
            playerStateManager.stop();
        }
        if (webServer != null) {
            webServer.stop();
        }
        if (databaseManager != null) {
            try {
                databaseManager.close();
            } catch (IOException exception) {
                getLogger().warning("关闭数据库时发生异常: " + exception.getMessage());
            }
        }
    }

    public void reloadPluginConfig() {
        reloadConfig();
        pluginConfig = PluginConfig.load(this);
        MessageUtil.setDisplayPrefix(pluginConfig.displayPrefix());
    }

    public void reloadRuntime() throws SQLException {
        if (backupManager != null) {
            backupManager.stop();
        }
        if (gitHubSyncManager != null) {
            gitHubSyncManager.stop();
        }
        if (playerMenuManager != null) {
            playerMenuManager.stop();
        }
        if (blueMapEditRegionManager != null) {
            blueMapEditRegionManager.stop();
        }
        if (conflictToolManager != null) {
            conflictToolManager.stopAllSessions();
        }
        if (playerStateManager != null) {
            playerStateManager.stop();
        }
        if (webServer != null) {
            webServer.stop();
        }
        stopAxiomProtectionHook();
        HandlerList.unregisterAll(this);
        if (databaseManager != null) {
            try {
                databaseManager.close();
            } catch (IOException exception) {
                getLogger().warning("关闭旧数据库连接时发生异常: " + exception.getMessage());
            }
        }

        reloadPluginConfig();

        databaseManager = new DatabaseManager(pluginConfig.databasePath(this));
        branchRepository = new BranchRepository(databaseManager);
        branchSyncRepository = new BranchSyncRepository(databaseManager);
        lockRepository = new LockRepository(databaseManager);
        queueRepository = new QueueRepository(databaseManager);
        revisionRepository = new RevisionRepository(databaseManager);

        protectionManager = new ProtectionManager(pluginConfig);
        axiomProtectionHook = createAxiomProtectionHook();
        worldManager = new WorldManager(this, pluginConfig);
        regionCopyManager = new RegionCopyManager(pluginConfig);
        snapshotManager = new SnapshotManager(this);
        selectionManager = new PlayerSelectionManager();
        branchChangeManager = new BranchChangeManager(snapshotManager, worldManager);
        mergeStatManager = new MergeStatManager(this, branchRepository, branchSyncRepository, branchChangeManager);
        queueManager = new QueueManager(this, pluginConfig, queueRepository);
        lockManager = new LockManager(lockRepository);
        rebaseManager = new RebaseManager(
                branchRepository,
                branchSyncRepository,
                revisionRepository,
                worldManager,
                regionCopyManager,
                snapshotManager
        );
        mergeManager = new MergeManager(
                this,
                pluginConfig,
                databaseManager,
                branchRepository,
                revisionRepository,
                lockManager,
                queueManager,
                worldManager,
                regionCopyManager,
                mergeStatManager
        );
        backupManager = new BackupManager(this, pluginConfig);
        gitHubSyncManager = new GitHubSyncManager(this, pluginConfig);
        branchManager = new BranchManager(
                this,
                pluginConfig,
                branchRepository,
                lockManager,
                queueManager,
                mergeManager,
                rebaseManager,
                worldManager,
                regionCopyManager,
                selectionManager,
                protectionManager
        );
        reviewMenuManager = new ReviewMenuManager(this, branchManager, branchChangeManager);
        blueMapEditRegionManager = new BlueMapEditRegionManager(this, branchManager);
        conflictToolManager = new ConflictToolManager(this, branchManager);
        playerMenuManager = new PlayerMenuManager(this, branchManager, conflictToolManager, reviewMenuManager);
        reviewMenuManager.setPlayerMenuService(playerMenuManager);
        playerStateManager = new PlayerStateManager(this, pluginConfig, worldManager, branchManager, selectionManager);

        registerCommands();
        registerListeners();
        startAxiomProtectionHook();
        applyMainWorldSettings();
        backupManager.start();
        gitHubSyncManager.start();
        lockManager.unlockAll();
        queueManager.clearAll();
        branchManager.bootstrapLegacyBranches();
        playerMenuManager.start();
        playerStateManager.start();
        blueMapEditRegionManager.start();
        rebaseManager.recoverIncompleteRebases();
        mergeManager.recoverIncompleteMerges();
        mergeStatManager.backfillMissingMergedBranchStats();
        webServer = new PluginWebServer(this, pluginConfig, branchRepository);
        webServer.start();
    }

    private void registerCommands() {
        PluginCommand command = Objects.requireNonNull(getCommand("wg"), "未注册 wg 命令");
        WorldGitCommand executor = new WorldGitCommand(
                new BranchCommands(new ManagerBranchService(branchManager)),
                new ReviewCommands(new ManagerReviewService(branchManager, reviewMenuManager)),
                new AdminCommands(new ManagerAdminService(this, branchManager, lockManager, backupManager, gitHubSyncManager)),
                new InviteCommands(new ManagerInviteService(branchManager)),
                playerMenuManager
        );
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    private void applyMainWorldSettings() {
        World mainWorld = getServer().getWorld(pluginConfig.mainWorld());
        if (mainWorld == null) {
            getLogger().warning("主世界 '" + pluginConfig.mainWorld() + "' 未加载，跳过世界规则设置");
            return;
        }

        mainWorld.setDifficulty(Difficulty.PEACEFUL);
        mainWorld.setGameRule(GameRules.SPAWN_MOBS,       false);
        mainWorld.setGameRule(GameRules.ADVANCE_TIME,     false);
        mainWorld.setGameRule(GameRules.ADVANCE_WEATHER,  false);
        mainWorld.setGameRule(GameRules.MOB_GRIEFING,     false);
        mainWorld.setSpawnFlags(false, false);
        mainWorld.setTime(6000); // 正午
        mainWorld.setStorm(false);
        mainWorld.setThundering(false);
        mainWorld.setWeatherDuration(Integer.MAX_VALUE);

        int removedPassiveEntities = 0;
        for (var entity : mainWorld.getEntities()) {
            if (entity instanceof Animals || entity instanceof Ambient || entity instanceof WaterMob) {
                entity.remove();
                removedPassiveEntities++;
            }
        }

        // 对已在线的玩家立即生效
        mainWorld.getPlayers().forEach(p -> {
            p.setGameMode(GameMode.CREATIVE);
            p.setAllowFlight(true);
            p.setFallDistance(0.0f);
        });

        getLogger().info(
                "主世界规则已设置：和平 / 创造 / 正午 / 晴天 / 无生物生成"
                        + (removedPassiveEntities > 0 ? " / 已清理被动生物 " + removedPassiveEntities + " 个" : "")
        );
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(
                new MainWorldProtectionListener(protectionManager),
                this
        );
        getServer().getPluginManager().registerEvents(
                new MainWorldEnforcementListener(this, pluginConfig),
                this
        );
        getServer().getPluginManager().registerEvents(
                new BranchWorldListener(new ManagerBranchWorldService(branchManager, worldManager)),
                this
        );
        getServer().getPluginManager().registerEvents(
                new BranchEditProtectionListener(branchManager, worldManager),
                this
        );
        getServer().getPluginManager().registerEvents(
                new PlayerConnectionListener(new ManagerConnectionService(branchManager, worldManager)),
                this
        );
        getServer().getPluginManager().registerEvents(
                new PlayerStateListener(playerStateManager),
                this
        );
        getServer().getPluginManager().registerEvents(
                reviewMenuManager,
                this
        );
        getServer().getPluginManager().registerEvents(
                conflictToolManager,
                this
        );
        getServer().getPluginManager().registerEvents(
                playerMenuManager,
                this
        );
    }

    private AxiomProtectionHook createAxiomProtectionHook() {
        Plugin protocolLib = getServer().getPluginManager().getPlugin("ProtocolLib");
        if (protocolLib == null || !protocolLib.isEnabled()) {
            Plugin axiomPaper = getServer().getPluginManager().getPlugin("AxiomPaper");
            if (axiomPaper != null && axiomPaper.isEnabled()) {
                getLogger().warning("检测到 AxiomPaper，但未安装或未启用 ProtocolLib，无法拦截 Axiom 自定义改块包。");
            }
            return null;
        }

        try {
            Class<?> hookClass = Class.forName("com.worldgit.manager.ProtocolLibAxiomProtectionHook");
            return (AxiomProtectionHook) hookClass
                    .getConstructor(WorldGitPlugin.class, com.worldgit.util.ProtectionService.class)
                    .newInstance(this, protectionManager);
        } catch (ReflectiveOperationException | LinkageError exception) {
            getLogger().warning("初始化 Axiom 包拦截器失败: " + exception.getMessage());
            return null;
        }
    }

    private void startAxiomProtectionHook() {
        if (axiomProtectionHook == null) {
            return;
        }
        axiomProtectionHook.start();
    }

    private void stopAxiomProtectionHook() {
        if (axiomProtectionHook == null) {
            return;
        }
        axiomProtectionHook.stop();
        axiomProtectionHook = null;
    }

    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        if (worldManager != null && worldManager.isBranchWorld(worldName)) {
            return new VoidChunkGenerator();
        }
        return super.getDefaultWorldGenerator(worldName, id);
    }

    public PluginConfig pluginConfig() {
        return pluginConfig;
    }

    public BranchManager branchManager() {
        return branchManager;
    }

    public QueueManager queueManager() {
        return queueManager;
    }

    public MergeManager mergeManager() {
        return mergeManager;
    }

    public BackupManager backupManager() {
        return backupManager;
    }
}

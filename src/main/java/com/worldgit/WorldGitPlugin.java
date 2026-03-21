package com.worldgit;

import com.worldgit.command.AdminCommands;
import com.worldgit.command.BranchCommands;
import com.worldgit.command.InviteCommands;
import com.worldgit.command.ReviewCommands;
import com.worldgit.command.WorldGitCommand;
import com.worldgit.config.PluginConfig;
import com.worldgit.database.BranchRepository;
import com.worldgit.database.DatabaseManager;
import com.worldgit.database.LockRepository;
import com.worldgit.database.QueueRepository;
import com.worldgit.generator.VoidChunkGenerator;
import com.worldgit.listener.BranchWorldListener;
import com.worldgit.listener.MainWorldProtectionListener;
import com.worldgit.listener.PlayerConnectionListener;
import com.worldgit.manager.BackupManager;
import com.worldgit.manager.BranchManager;
import com.worldgit.manager.LockManager;
import com.worldgit.manager.MergeManager;
import com.worldgit.manager.ProtectionManager;
import com.worldgit.manager.QueueManager;
import com.worldgit.manager.RegionCopyManager;
import com.worldgit.manager.WorldManager;
import com.worldgit.util.ManagerAdminService;
import com.worldgit.util.ManagerBranchService;
import com.worldgit.util.ManagerBranchWorldService;
import com.worldgit.util.ManagerConnectionService;
import com.worldgit.util.ManagerInviteService;
import com.worldgit.util.ManagerReviewService;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Objects;
import org.bukkit.command.PluginCommand;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

public final class WorldGitPlugin extends JavaPlugin {

    private PluginConfig pluginConfig;
    private DatabaseManager databaseManager;
    private BranchRepository branchRepository;
    private LockRepository lockRepository;
    private QueueRepository queueRepository;
    private ProtectionManager protectionManager;
    private WorldManager worldManager;
    private RegionCopyManager regionCopyManager;
    private QueueManager queueManager;
    private LockManager lockManager;
    private MergeManager mergeManager;
    private BackupManager backupManager;
    private BranchManager branchManager;

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
        if (backupManager != null) {
            backupManager.stop();
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
    }

    public void reloadRuntime() throws SQLException {
        if (backupManager != null) {
            backupManager.stop();
        }
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
        lockRepository = new LockRepository(databaseManager);
        queueRepository = new QueueRepository(databaseManager);

        protectionManager = new ProtectionManager(pluginConfig);
        worldManager = new WorldManager(this, pluginConfig);
        regionCopyManager = new RegionCopyManager(this, pluginConfig);
        queueManager = new QueueManager(this, pluginConfig, queueRepository);
        lockManager = new LockManager(lockRepository);
        mergeManager = new MergeManager(
                this,
                pluginConfig,
                databaseManager,
                branchRepository,
                lockManager,
                queueManager,
                worldManager,
                regionCopyManager
        );
        backupManager = new BackupManager(this, pluginConfig);
        branchManager = new BranchManager(
                this,
                pluginConfig,
                branchRepository,
                lockManager,
                queueManager,
                mergeManager,
                worldManager,
                regionCopyManager,
                protectionManager
        );

        registerCommands();
        registerListeners();
        backupManager.start();
        mergeManager.recoverIncompleteMerges();
    }

    private void registerCommands() {
        PluginCommand command = Objects.requireNonNull(getCommand("wg"), "未注册 wg 命令");
        WorldGitCommand executor = new WorldGitCommand(
                new BranchCommands(new ManagerBranchService(branchManager)),
                new ReviewCommands(new ManagerReviewService(branchManager)),
                new AdminCommands(new ManagerAdminService(this, branchManager, lockManager, backupManager)),
                new InviteCommands(new ManagerInviteService(branchManager))
        );
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(
                new MainWorldProtectionListener(protectionManager),
                this
        );
        getServer().getPluginManager().registerEvents(
                new BranchWorldListener(new ManagerBranchWorldService(branchManager, worldManager)),
                this
        );
        getServer().getPluginManager().registerEvents(
                new PlayerConnectionListener(new ManagerConnectionService(branchManager, worldManager)),
                this
        );
    }

    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        if (worldName != null && worldName.startsWith(pluginConfig.branchWorldPrefix())) {
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

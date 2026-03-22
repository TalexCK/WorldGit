package com.worldgit.manager;

import com.worldgit.WorldGitPlugin;
import com.worldgit.config.PluginConfig;
import com.worldgit.model.Branch;
import com.worldgit.util.MessageUtil;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * 维护玩家在主世界和分支世界中的临时状态。
 */
public final class PlayerStateManager {

    private static final int BOUNDARY_STEP = 4;
    private static final int BOUNDARY_VIEW_RADIUS = 40;

    private final WorldGitPlugin plugin;
    private final PluginConfig pluginConfig;
    private final WorldManager worldManager;
    private final BranchManager branchManager;
    private final PlayerSelectionManager selectionManager;
    private final Map<UUID, PlayerStateSnapshot> managedWorldSnapshots = new ConcurrentHashMap<>();
    private BukkitTask actionBarTask;

    public PlayerStateManager(
            WorldGitPlugin plugin,
            PluginConfig pluginConfig,
            WorldManager worldManager,
            BranchManager branchManager,
            PlayerSelectionManager selectionManager
    ) {
        this.plugin = plugin;
        this.pluginConfig = pluginConfig;
        this.worldManager = worldManager;
        this.branchManager = branchManager;
        this.selectionManager = selectionManager;
    }

    public void start() {
        stop();
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            handleJoin(onlinePlayer);
        }
        actionBarTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshActionBars, 1L, 10L);
    }

    public void stop() {
        if (actionBarTask != null) {
            actionBarTask.cancel();
            actionBarTask = null;
        }
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            restoreState(onlinePlayer);
        }
        managedWorldSnapshots.clear();
    }

    public void handleJoin(Player player) {
        applyWorldState(player);
        refreshActionBar(player);
    }

    public void handleQuit(Player player) {
        restoreState(player);
        selectionManager.clear(player);
    }

    public void handleWorldChange(Player player, World fromWorld) {
        applyWorldState(player);
        refreshActionBar(player);
    }

    public void handleRespawn(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            applyWorldState(player);
            refreshActionBar(player);
        });
    }

    private void refreshActionBars() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            refreshActionBar(player);
            renderBranchBoundary(player);
        }
    }

    private void refreshActionBar(Player player) {
        Location location = player.getLocation();
        String branchLabel = resolveBranchLabel(player.getWorld());
        String selectionStatus = pluginConfig.mainWorld().equals(player.getWorld().getName())
                ? " | 选区 " + selectionManager.countSelectedPoints(player) + "/2"
                : "";
        player.sendActionBar(MessageUtil.mini(
                "<gray>坐标</gray> <white>" + location.getBlockX() + ", "
                        + location.getBlockY() + ", " + location.getBlockZ()
                        + "</white> <dark_gray>|</dark_gray> <gray>分支</gray> <gold>"
                        + MessageUtil.escape(branchLabel)
                        + "</gold><gray>" + MessageUtil.escape(selectionStatus) + "</gray>"
        ));
    }

    private String resolveBranchLabel(World world) {
        if (world == null) {
            return "-";
        }
        if (pluginConfig.mainWorld().equals(world.getName())) {
            return "主世界";
        }
        if (worldManager.isBranchWorld(world)) {
            return worldManager.branchLabel(world.getName());
        }
        return world.getName();
    }

    private void applyWorldState(Player player) {
        if (player == null) {
            return;
        }
        World world = player.getWorld();
        if (world == null) {
            return;
        }
        if (isManagedWorld(world)) {
            managedWorldSnapshots.computeIfAbsent(
                    player.getUniqueId(),
                    ignored -> new PlayerStateSnapshot(player.getGameMode(), player.getAllowFlight(), player.isFlying())
            );
            if (pluginConfig.mainWorld().equals(world.getName())) {
                applyMainWorldState(player);
                return;
            }
            if (worldManager.isBranchWorld(world)) {
                applyBranchWorldState(player);
                return;
            }
        }
        restoreState(player);
    }

    private void restoreState(Player player) {
        PlayerStateSnapshot snapshot = managedWorldSnapshots.remove(player.getUniqueId());
        if (snapshot == null) {
            return;
        }
        if (player.getGameMode() != snapshot.gameMode()) {
            player.setGameMode(snapshot.gameMode());
        }
        player.setAllowFlight(snapshot.allowFlight());
        if (snapshot.allowFlight()) {
            player.setFlying(snapshot.flying());
        } else if (player.isFlying()) {
            player.setFlying(false);
        }
    }

    private void restoreMainWorldVitals(Player player) {
        if (player.getAttribute(Attribute.MAX_HEALTH) != null) {
            double maxHealth = player.getAttribute(Attribute.MAX_HEALTH).getValue();
            if (player.getHealth() < maxHealth) {
                player.setHealth(maxHealth);
            }
        }
        if (player.getFoodLevel() < 20) {
            player.setFoodLevel(20);
        }
        if (player.getSaturation() < 20.0f) {
            player.setSaturation(20.0f);
        }
        if (player.getExhaustion() > 0.0f) {
            player.setExhaustion(0.0f);
        }
        if (player.getFireTicks() > 0) {
            player.setFireTicks(0);
        }
    }

    private void applyMainWorldState(Player player) {
        if (player.getGameMode() != GameMode.CREATIVE) {
            player.setGameMode(GameMode.CREATIVE);
        }
        enableImmediateFlight(player);
        restoreMainWorldVitals(player);
    }

    private void applyBranchWorldState(Player player) {
        if (player.getGameMode() != GameMode.CREATIVE) {
            player.setGameMode(GameMode.CREATIVE);
        }
        enableImmediateFlight(player);
    }

    private void enableImmediateFlight(Player player) {
        if (!player.getAllowFlight()) {
            player.setAllowFlight(true);
        }
        player.setFallDistance(0.0f);
    }

    private boolean isManagedWorld(World world) {
        return pluginConfig.mainWorld().equals(world.getName()) || worldManager.isBranchWorld(world);
    }

    private void renderBranchBoundary(Player player) {
        World world = player.getWorld();
        if (world == null || !worldManager.isBranchWorld(world)) {
            return;
        }
        Branch branch = branchManager.findByWorld(world.getName()).orElse(null);
        if (branch == null) {
            return;
        }

        Location location = player.getLocation();
        int y = Math.max(branch.minY(), Math.min(branch.maxY(), location.getBlockY()));
        int minVisibleX = Math.max(branch.minX(), location.getBlockX() - BOUNDARY_VIEW_RADIUS);
        int maxVisibleX = Math.min(branch.maxX(), location.getBlockX() + BOUNDARY_VIEW_RADIUS);
        int minVisibleZ = Math.max(branch.minZ(), location.getBlockZ() - BOUNDARY_VIEW_RADIUS);
        int maxVisibleZ = Math.min(branch.maxZ(), location.getBlockZ() + BOUNDARY_VIEW_RADIUS);

        for (int x = minVisibleX; x <= maxVisibleX; x += BOUNDARY_STEP) {
            spawnBoundaryParticle(player, x, y, branch.minZ());
            spawnBoundaryParticle(player, x, y, branch.maxZ());
        }
        for (int z = minVisibleZ; z <= maxVisibleZ; z += BOUNDARY_STEP) {
            spawnBoundaryParticle(player, branch.minX(), y, z);
            spawnBoundaryParticle(player, branch.maxX(), y, z);
        }
        for (int markerY = Math.max(branch.minY(), y - 4); markerY <= Math.min(branch.maxY(), y + 4); markerY += 2) {
            if (isNearVisibleRange(location, branch.minX(), branch.minZ())) {
                spawnBoundaryParticle(player, branch.minX(), markerY, branch.minZ());
            }
            if (isNearVisibleRange(location, branch.minX(), branch.maxZ())) {
                spawnBoundaryParticle(player, branch.minX(), markerY, branch.maxZ());
            }
            if (isNearVisibleRange(location, branch.maxX(), branch.minZ())) {
                spawnBoundaryParticle(player, branch.maxX(), markerY, branch.minZ());
            }
            if (isNearVisibleRange(location, branch.maxX(), branch.maxZ())) {
                spawnBoundaryParticle(player, branch.maxX(), markerY, branch.maxZ());
            }
        }
    }

    private boolean isNearVisibleRange(Location location, int x, int z) {
        return Math.abs(location.getBlockX() - x) <= BOUNDARY_VIEW_RADIUS
                && Math.abs(location.getBlockZ() - z) <= BOUNDARY_VIEW_RADIUS;
    }

    private void spawnBoundaryParticle(Player player, int x, int y, int z) {
        player.spawnParticle(Particle.END_ROD, x + 0.5, y + 0.1, z + 0.5, 1, 0.0, 0.0, 0.0, 0.0);
    }

    private record PlayerStateSnapshot(GameMode gameMode, boolean allowFlight, boolean flying) {
    }
}

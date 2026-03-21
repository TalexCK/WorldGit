package com.worldgit.util;

import com.worldgit.manager.BranchManager;
import com.worldgit.manager.WorldManager;
import org.bukkit.World;
import org.bukkit.entity.Player;

public final class ManagerConnectionService implements ConnectionService {

    private final BranchManager branchManager;
    private final WorldManager worldManager;

    public ManagerConnectionService(BranchManager branchManager, WorldManager worldManager) {
        this.branchManager = branchManager;
        this.worldManager = worldManager;
    }

    @Override
    public void handleJoin(Player player) {
        World world = player.getWorld();
        if (!worldManager.isBranchWorld(world)) {
            return;
        }
        boolean allowed = branchManager.findByWorld(world.getName())
                .map(branch -> branchManager.canAccessBranch(player, branch))
                .orElse(false);
        if (!allowed) {
            player.teleportAsync(worldManager.getMainWorld().getSpawnLocation());
            MessageUtil.sendWarning(player, "你已被送回主世界，原因是该分支不可访问。");
        }
    }

    @Override
    public void handleQuit(Player player) {
        // 当前版本无需在退出时额外处理。
    }
}

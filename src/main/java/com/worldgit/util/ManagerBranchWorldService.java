package com.worldgit.util;

import com.worldgit.manager.BranchManager;
import com.worldgit.manager.WorldManager;
import org.bukkit.World;
import org.bukkit.entity.Player;

public final class ManagerBranchWorldService implements BranchWorldService {

    private final BranchManager branchManager;
    private final WorldManager worldManager;

    public ManagerBranchWorldService(BranchManager branchManager, WorldManager worldManager) {
        this.branchManager = branchManager;
        this.worldManager = worldManager;
    }

    @Override
    public boolean isBranchWorld(World world) {
        return worldManager.isBranchWorld(world);
    }

    @Override
    public boolean canAccess(Player player, World world) {
        return branchManager.findByWorld(world.getName())
                .map(branch -> branchManager.canAccessBranch(player, branch))
                .orElse(false);
    }

    @Override
    public void handleUnauthorizedTeleport(Player player, World world) {
        player.teleportAsync(worldManager.getMainWorld().getSpawnLocation());
    }
}

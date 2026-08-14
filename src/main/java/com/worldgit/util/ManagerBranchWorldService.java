package com.worldgit.util;

import com.worldgit.manager.BranchManager;
import com.worldgit.manager.WorldManager;
import com.worldgit.model.Branch;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.World;
import org.bukkit.entity.Player;

public final class ManagerBranchWorldService implements BranchWorldService {

    private static final Logger LOGGER = Logger.getLogger("WorldGit");

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
        Optional<Branch> branchOpt = branchManager.findByWorld(world.getName());
        if (branchOpt.isEmpty()) {
            LOGGER.log(Level.WARNING,
                    "拒绝进入分支世界 {0}：在 branches 表中找不到匹配记录（玩家 {1}/{2}）",
                    new Object[]{world.getName(), player.getName(), player.getUniqueId()});
            return false;
        }
        Branch branch = branchOpt.get();
        if (branchManager.canPreviewBranch(player, branch)) {
            return true;
        }
        LOGGER.log(Level.WARNING,
                "拒绝进入分支世界 {0}（分支 {1}，owner={2}/{3}）：在线玩家 {4}/{5} 非 owner 且未被邀请",
                new Object[]{
                        world.getName(),
                        branch.id(),
                        branch.ownerName(),
                        branch.ownerUuid(),
                        player.getName(),
                        player.getUniqueId()
                });
        return false;
    }

    @Override
    public void handleUnauthorizedTeleport(Player player, World world) {
        player.teleportAsync(worldManager.getMainWorld().getSpawnLocation());
    }
}

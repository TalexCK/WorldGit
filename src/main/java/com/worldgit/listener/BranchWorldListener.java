package com.worldgit.listener;

import com.worldgit.util.BranchWorldService;
import com.worldgit.util.MessageUtil;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * 分支世界访问控制监听器。
 */
public final class BranchWorldListener implements Listener {

    private final BranchWorldService branchWorldService;

    public BranchWorldListener() {
        this(BranchWorldService.noop());
    }

    public BranchWorldListener(BranchWorldService branchWorldService) {
        this.branchWorldService = branchWorldService;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        handleTeleport(event.getPlayer(), event.getTo() == null ? null : event.getTo().getWorld(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        handleTeleport(event.getPlayer(), event.getTo() == null ? null : event.getTo().getWorld(), event);
    }

    private void handleTeleport(Player player, World destinationWorld, org.bukkit.event.Cancellable event) {
        if (destinationWorld == null || !branchWorldService.isBranchWorld(destinationWorld)) {
            return;
        }
        if (branchWorldService.canAccess(player, destinationWorld)) {
            return;
        }
        event.setCancelled(true);
        branchWorldService.handleUnauthorizedTeleport(player, destinationWorld);
        MessageUtil.sendWarning(player, "你没有权限进入该分支世界。");
    }
}

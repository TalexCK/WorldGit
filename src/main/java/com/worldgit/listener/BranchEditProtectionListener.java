package com.worldgit.listener;

import com.worldgit.manager.BranchManager;
import com.worldgit.manager.WorldManager;
import com.worldgit.model.Branch;
import com.worldgit.model.BranchStatus;
import com.worldgit.util.MessageUtil;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;

/**
 * 分支世界编辑范围保护监听器。
 */
public final class BranchEditProtectionListener implements Listener {

    private final BranchManager branchManager;
    private final WorldManager worldManager;

    public BranchEditProtectionListener(BranchManager branchManager, WorldManager worldManager) {
        this.branchManager = branchManager;
        this.worldManager = worldManager;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        denyIfReadonly(event, event.getPlayer(), event.getBlockPlaced().getLocation());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockMultiPlace(BlockMultiPlaceEvent event) {
        denyIfReadonly(event, event.getPlayer(), collectPlacedLocations(event));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        denyIfReadonly(event, event.getPlayer(), event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerBucketEmpty(PlayerBucketEmptyEvent event) {
        denyIfReadonly(event, event.getPlayer(), event.getBlockClicked().getRelative(event.getBlockFace()).getLocation());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerBucketFill(PlayerBucketFillEvent event) {
        denyIfReadonly(event, event.getPlayer(), event.getBlockClicked().getLocation());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.PHYSICAL) {
            return;
        }
        if (event.getClickedBlock().getType().isInteractable() || event.getAction() == Action.PHYSICAL) {
            denyIfReadonly(event, event.getPlayer(), event.getClickedBlock().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        Player player = event.getPlayer();
        if (player != null) {
            denyIfReadonly(event, player, event.getEntity().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        if (event.getRemover() instanceof Player player) {
            denyIfReadonly(event, player, event.getEntity().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Hanging) && !(event.getEntity() instanceof ArmorStand)) {
            return;
        }
        if (event.getDamager() instanceof Player player) {
            denyIfReadonly(event, player, event.getEntity().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof Hanging || event.getRightClicked() instanceof ArmorStand) {
            denyIfReadonly(event, event.getPlayer(), event.getRightClicked().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerInteractAtEntity(PlayerInteractAtEntityEvent event) {
        if (event.getRightClicked() instanceof Hanging || event.getRightClicked() instanceof ArmorStand) {
            denyIfReadonly(event, event.getPlayer(), event.getRightClicked().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        denyIfReadonly(event, event.getPlayer(), event.getRightClicked().getLocation());
    }

    private void denyIfReadonly(org.bukkit.event.Cancellable event, Player player, Location location) {
        if (location == null) {
            return;
        }
        denyIfReadonly(event, player, List.of(location));
    }

    private void denyIfReadonly(org.bukkit.event.Cancellable event, Player player, List<Location> locations) {
        if (locations == null || locations.isEmpty()) {
            return;
        }
        if (player == null) {
            return;
        }
        World world = player.getWorld();
        if (!worldManager.isBranchWorld(world)) {
            return;
        }
        Branch branch = branchManager.findByWorld(world.getName()).orElse(null);
        if (branch == null) {
            return;
        }
        if (!branchManager.canModifyBranch(player, branch)) {
            event.setCancelled(true);
            MessageUtil.sendWarning(player, "该分支当前为只读预览模式，无法修改。");
            return;
        }
        if (branch.status() == BranchStatus.APPROVED) {
            event.setCancelled(true);
            MessageUtil.sendWarning(player, "该分支已通过审核，若要继续修改，请先输入 /wg forceedit 重新进入编辑状态。");
            return;
        }
        if (!areAllLocationsInsideEditableRegion(branch, locations)) {
            event.setCancelled(true);
            MessageUtil.sendWarning(player, "多格方块的所有占用位置都必须在你的框选区域内，外围 20 格缓冲区仅供参考。");
        }
    }

    private List<Location> collectPlacedLocations(BlockMultiPlaceEvent event) {
        List<Location> locations = new ArrayList<>();
        for (BlockState state : event.getReplacedBlockStates()) {
            locations.add(state.getLocation());
        }
        if (locations.isEmpty()) {
            locations.add(event.getBlockPlaced().getLocation());
        }
        return locations;
    }

    private boolean areAllLocationsInsideEditableRegion(Branch branch, List<Location> locations) {
        for (Location location : locations) {
            if (!isInsideEditableRegion(branch, location)) {
                return false;
            }
        }
        return true;
    }

    private boolean isInsideEditableRegion(Branch branch, Location location) {
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        return x >= branch.minX() && x <= branch.maxX()
                && y >= branch.minY() && y <= branch.maxY()
                && z >= branch.minZ() && z <= branch.maxZ();
    }
}

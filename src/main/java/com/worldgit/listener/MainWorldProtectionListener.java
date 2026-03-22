package com.worldgit.listener;

import com.worldgit.util.MessageUtil;
import com.worldgit.util.ProtectionService;
import java.util.Set;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.FluidLevelChangeEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerEggThrowEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.world.StructureGrowEvent;

/**
 * 主世界保护监听器。
 */
public final class MainWorldProtectionListener implements Listener {

    private static final Set<String> BLOCKED_SPAWN_REASONS = Set.of(
            "NATURAL",
            "CHUNK_GEN",
            "SPAWNER",
            "TRIAL_SPAWNER",
            "SPAWNER_EGG",
            "DISPENSE_EGG",
            "EGG",
            "PATROL",
            "RAID",
            "VILLAGE_DEFENSE",
            "VILLAGE_INVASION",
            "REINFORCEMENTS"
    );

    private final ProtectionService protectionService;

    public MainWorldProtectionListener() {
        this(ProtectionService.noop());
    }

    public MainWorldProtectionListener(ProtectionService protectionService) {
        this.protectionService = protectionService;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        denyIfProtected(event, event.getPlayer().getWorld(), event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockMultiPlace(BlockMultiPlaceEvent event) {
        denyIfProtected(event, event.getPlayer().getWorld(), event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        denyIfProtected(event, event.getPlayer().getWorld(), event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerBucketEmpty(PlayerBucketEmptyEvent event) {
        denyIfProtected(event, event.getPlayer().getWorld(), event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerBucketFill(PlayerBucketFillEvent event) {
        denyIfProtected(event, event.getPlayer().getWorld(), event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.useItemInHand() == Event.Result.DENY) {
            return;
        }
        if (event.getItem() == null) {
            return;
        }
        if (!isSpawnEgg(event.getItem().getType().name())) {
            return;
        }
        denyIfProtected(event, event.getPlayer().getWorld(), event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockDispense(BlockDispenseEvent event) {
        if (!isSpawnEgg(event.getItem().getType().name())) {
            return;
        }
        denyIfProtected(event, event.getBlock().getWorld());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockFertilize(BlockFertilizeEvent event) {
        denyIfProtected(event, event.getBlock().getWorld());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent event) {
        denyIfProtected(event, event.getBlock().getWorld());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockFade(BlockFadeEvent event) {
        denyIfProtected(event, event.getBlock().getWorld());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent event) {
        denyIfProtected(event, event.getBlock().getWorld());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockFromTo(BlockFromToEvent event) {
        denyIfProtected(event, event.getBlock().getWorld());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFluidLevelChange(FluidLevelChangeEvent event) {
        denyIfProtected(event, event.getBlock().getWorld());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        denyIfProtected(event, event.getLocation().getWorld());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        denyIfProtected(event, event.getBlock().getWorld());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        denyIfProtected(event, event.getBlock().getWorld());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockIgnite(BlockIgniteEvent event) {
        denyIfProtected(event, event.getBlock().getWorld());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        denyIfProtected(event, event.getBlock().getWorld());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockSpread(BlockSpreadEvent event) {
        denyIfProtected(event, event.getSource().getWorld());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        denyIfProtected(event, event.getBlock().getWorld());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        denyIfProtected(event, event.getBlock().getWorld());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        denyIfProtected(event, event.getWorld());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onExplosionPrime(ExplosionPrimeEvent event) {
        if (event.getEntity().getWorld() != null) {
            denyIfProtected(event, event.getEntity().getWorld());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        World world = event.getLocation().getWorld();
        if (world == null || !protectionService.isMainWorld(world)) {
            return;
        }
        if (BLOCKED_SPAWN_REASONS.contains(event.getSpawnReason().name())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerEggThrow(PlayerEggThrowEvent event) {
        if (event.getPlayer().getWorld() == null || !protectionService.isMainWorld(event.getPlayer().getWorld())) {
            return;
        }
        event.setHatching(false);
        event.setNumHatches((byte) 0);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        Player player = event.getPlayer();
        if (player != null) {
            denyIfProtected(event, event.getEntity().getWorld(), player);
            return;
        }
        denyIfProtected(event, event.getEntity().getWorld());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        if (event.getRemover() instanceof Player player) {
            denyIfProtected(event, event.getEntity().getWorld(), player);
            return;
        }
        denyIfProtected(event, event.getEntity().getWorld());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Hanging) && !(event.getEntity() instanceof ArmorStand)) {
            return;
        }
        if (event.getDamager() instanceof Player player) {
            denyIfProtected(event, event.getEntity().getWorld(), player);
            return;
        }
        denyIfProtected(event, event.getEntity().getWorld());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof Hanging || event.getRightClicked() instanceof ArmorStand) {
            denyIfProtected(event, event.getRightClicked().getWorld(), event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerInteractAtEntity(PlayerInteractAtEntityEvent event) {
        if (event.getRightClicked() instanceof Hanging || event.getRightClicked() instanceof ArmorStand) {
            denyIfProtected(event, event.getRightClicked().getWorld(), event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        denyIfProtected(event, event.getRightClicked().getWorld(), event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            denyIfProtected(event, player.getWorld(), player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player) {
            if (player.getWorld() == null || !protectionService.isMainWorld(player.getWorld())) {
                return;
            }
            event.setCancelled(true);
            player.setFoodLevel(20);
            player.setSaturation(20.0f);
            player.setExhaustion(0.0f);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        denyIfEnteringBlockedDimension(event.getPlayer(), event.getFrom().getWorld(), event.getTo() == null ? null : event.getTo().getWorld(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        denyIfEnteringBlockedDimension(event.getPlayer(), event.getFrom().getWorld(), event.getTo() == null ? null : event.getTo().getWorld(), event);
    }

    private void denyIfProtected(org.bukkit.event.Cancellable event, World world) {
        if (world == null || !protectionService.isMainWorld(world)) {
            return;
        }
        event.setCancelled(true);
    }

    private void denyIfProtected(org.bukkit.event.Cancellable event, World world, Player player) {
        if (world == null || !protectionService.isMainWorld(world)) {
            return;
        }
        if (player != null && protectionService.canBypass(player)) {
            return;
        }
        event.setCancelled(true);
    }

    private void denyIfEnteringBlockedDimension(Player player, World fromWorld, World destinationWorld, org.bukkit.event.Cancellable event) {
        if (player == null || fromWorld == null || !protectionService.isMainWorld(fromWorld)) {
            return;
        }
        if (protectionService.canBypass(player)) {
            return;
        }
        if (destinationWorld == null || !isBlockedEnvironment(destinationWorld)) {
            return;
        }
        event.setCancelled(true);
        MessageUtil.sendWarning(player, "主世界已禁用进入地狱和末地。");
    }

    private boolean isBlockedEnvironment(World world) {
        return world.getEnvironment() == World.Environment.NETHER
                || world.getEnvironment() == World.Environment.THE_END;
    }

    private boolean isSpawnEgg(String materialName) {
        return materialName != null && materialName.endsWith("_SPAWN_EGG");
    }
}

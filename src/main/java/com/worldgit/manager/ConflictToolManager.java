package com.worldgit.manager;

import com.worldgit.WorldGitPlugin;
import com.worldgit.model.Branch;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

/**
 * 冲突手动处理木棍、选区与切换预览。
 */
public final class ConflictToolManager implements Listener {

    private static final int TOOL_SLOT = 7;
    private static final long HIGHLIGHT_INTERVAL_TICKS = 8L;
    private static final int MAX_MARKER_PARTICLES = 96;
    private static final Particle.DustOptions OUTLINE_PARTICLE = new Particle.DustOptions(Color.fromRGB(80, 220, 255), 1.0F);
    private static final Particle.DustOptions OURS_PARTICLE = new Particle.DustOptions(Color.fromRGB(120, 255, 140), 0.9F);
    private static final Particle.DustOptions THEIRS_PARTICLE = new Particle.DustOptions(Color.fromRGB(90, 170, 255), 0.9F);
    private static final Particle.DustOptions MANUAL_PARTICLE = new Particle.DustOptions(Color.fromRGB(255, 190, 90), 0.95F);

    private final WorldGitPlugin plugin;
    private final BranchManager branchManager;
    private final NamespacedKey toolKey;
    private final Map<UUID, ConflictSession> sessionsByPlayer = new ConcurrentHashMap<>();
    private final Map<String, UUID> sessionOwnersByGroup = new ConcurrentHashMap<>();
    private BukkitTask highlightTask;

    public ConflictToolManager(WorldGitPlugin plugin, BranchManager branchManager) {
        this.plugin = Objects.requireNonNull(plugin, "插件不能为空");
        this.branchManager = Objects.requireNonNull(branchManager, "分支管理器不能为空");
        this.toolKey = new NamespacedKey(plugin, "conflict-tool-stick");
    }

    public void startSession(Player player, String branchId, int groupIndex) {
        Branch branch = branchManager.requireBranch(branchId);
        if (!branchManager.canModifyBranch(player, branch)) {
            throw new IllegalStateException("你无权处理该分支的冲突。");
        }

        RebaseManager.ConflictGroupDetail detail = branchManager.describeConflictGroup(player, branchId, groupIndex);
        if (detail.group().resolved()) {
            throw new IllegalStateException("该冲突组已经解决。");
        }

        List<RebaseManager.ConflictBlockView> blocks = branchManager.listConflictBlocks(player, branchId, groupIndex);
        if (blocks.isEmpty()) {
            throw new IllegalStateException("该冲突组没有可处理的冲突方块。");
        }

        String sessionKey = sessionKey(branchId, groupIndex);
        UUID existingOwner = sessionOwnersByGroup.get(sessionKey);
        if (existingOwner != null && !existingOwner.equals(player.getUniqueId())) {
            throw new IllegalStateException("该冲突组正在由其他玩家处理。");
        }

        stopSession(player, true);

        World branchWorld = Bukkit.getWorld(branch.worldName());
        if (branchWorld == null) {
            throw new IllegalStateException("分支世界不存在: " + branch.worldName());
        }

        ConflictSession session = new ConflictSession(
                branchId,
                groupIndex,
                branch.worldName(),
                sessionKey,
                player.getInventory().getItem(TOOL_SLOT) == null ? null : player.getInventory().getItem(TOOL_SLOT).clone(),
                player.getInventory().getHeldItemSlot()
        );
        for (RebaseManager.ConflictBlockView block : blocks) {
            BlockPos pos = new BlockPos(block.x(), block.y(), block.z());
            session.blocksByPos.put(pos, new ConflictBlockState(block.ours(), block.theirs(), ConflictChoice.OURS));
        }
        session.resetSelection();
        for (BlockPos position : session.selectedPositions()) {
            ConflictBlockState state = session.blocksByPos.get(position);
            if (state != null) {
                applyChoice(branchWorld, position, state, ConflictChoice.OURS);
            }
        }

        sessionsByPlayer.put(player.getUniqueId(), session);
        sessionOwnersByGroup.put(sessionKey, player.getUniqueId());
        ensureHighlightTask();
        giveTool(player);
        player.teleportAsync(branchManager.getConflictTeleportLocation(player, branchId, groupIndex));
        renderSelectionHighlight(player, session);
        sendSelectionSummary(player, session, "冲突木棍已发放，默认全选当前冲突组");
    }

    public void completeSession(Player player, String branchId, int groupIndex) {
        ConflictSession session = sessionsByPlayer.get(player.getUniqueId());
        if (session != null && session.matches(branchId, groupIndex)) {
            branchManager.markConflictResolvedManually(player, branchId, groupIndex);
            stopSession(player, true);
            player.sendActionBar(Component.text("冲突处理完成，木棍已回收。"));
            return;
        }
        branchManager.markConflictResolvedManually(player, branchId, groupIndex);
    }

    public void stopSessionIfMatches(Player player, String branchId, int groupIndex) {
        ConflictSession session = sessionsByPlayer.get(player.getUniqueId());
        if (session != null && session.matches(branchId, groupIndex)) {
            stopSession(player, true);
        }
    }

    public void stopAllSessions() {
        for (UUID playerId : List.copyOf(sessionsByPlayer.keySet())) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                stopSession(player, true);
            } else {
                ConflictSession removed = sessionsByPlayer.remove(playerId);
                if (removed != null) {
                    sessionOwnersByGroup.remove(removed.sessionKey(), playerId);
                }
            }
        }
        stopHighlightTask();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSelectionBreak(BlockBreakEvent event) {
        ConflictSession session = activeSession(event.getPlayer());
        if (session == null || !isTool(event.getPlayer().getInventory().getItemInMainHand())) {
            return;
        }
        event.setCancelled(true);
        BlockPos clicked = BlockPos.of(event.getBlock().getLocation());
        if (!session.blocksByPos.containsKey(clicked)) {
            event.getPlayer().sendActionBar(Component.text("该方块不在当前冲突组内。"));
            return;
        }
        if (event.getPlayer().isSneaking()) {
            session.resetSelection();
            renderSelectionHighlight(event.getPlayer(), session);
            sendSelectionSummary(event.getPlayer(), session, "已重置为全选");
            return;
        }
        session.updateSelection(clicked);
        renderSelectionHighlight(event.getPlayer(), session);
        sendSelectionSummary(event.getPlayer(), session, "已更新选区");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onToolInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        ConflictSession session = activeSession(event.getPlayer());
        if (session == null || !isTool(event.getItem())) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        event.setCancelled(true);
        toggleSelection(event.getPlayer(), session);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onManualBreak(BlockBreakEvent event) {
        ConflictSession session = activeSession(event.getPlayer());
        if (session == null || isTool(event.getPlayer().getInventory().getItemInMainHand())) {
            return;
        }
        scheduleManualMark(event.getPlayer(), event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onManualPlace(BlockPlaceEvent event) {
        ConflictSession session = activeSession(event.getPlayer());
        if (session == null) {
            return;
        }
        scheduleManualMark(event.getPlayer(), event.getBlockPlaced().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onManualMultiPlace(BlockMultiPlaceEvent event) {
        ConflictSession session = activeSession(event.getPlayer());
        if (session == null) {
            return;
        }
        for (Block block : event.getReplacedBlockStates().stream().map(state -> state.getBlock()).toList()) {
            scheduleManualMark(event.getPlayer(), block.getLocation());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDropTool(PlayerDropItemEvent event) {
        if (activeSession(event.getPlayer()) != null && isTool(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (activeSession(player) == null) {
            return;
        }
        if (isTool(event.getCurrentItem()) || isTool(event.getCursor()) || event.getSlot() == TOOL_SLOT) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        ConflictSession session = sessionsByPlayer.get(event.getPlayer().getUniqueId());
        if (session == null) {
            return;
        }
        if (!event.getPlayer().getWorld().getName().equals(session.worldName())) {
            stopSession(event.getPlayer(), true);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        stopSession(event.getPlayer(), true);
    }

    private void toggleSelection(Player player, ConflictSession session) {
        if (session.selectedPositions().isEmpty()) {
            player.sendActionBar(Component.text("当前选区为空，请先左键选区。"));
            return;
        }

        World world = player.getWorld();
        ConflictChoice targetChoice = resolveToggleTarget(session);
        int theirsCount = 0;
        int oursCount = 0;
        for (BlockPos position : session.selectedPositions()) {
            ConflictBlockState state = session.blocksByPos().get(position);
            if (state == null) {
                continue;
            }
            applyChoice(world, position, state, targetChoice);
            if (targetChoice == ConflictChoice.THEIRS) {
                theirsCount++;
            } else {
                oursCount++;
            }
        }

        String summary = theirsCount > 0 && oursCount == 0 ? "已切到 theirs" : "已切到 mine";
        renderSelectionHighlight(player, session);
        sendSelectionSummary(player, session, summary);
    }

    private ConflictChoice resolveToggleTarget(ConflictSession session) {
        for (BlockPos position : session.selectedPositions()) {
            ConflictBlockState state = session.blocksByPos().get(position);
            if (state == null || state.choice() != ConflictChoice.THEIRS) {
                return ConflictChoice.THEIRS;
            }
        }
        return ConflictChoice.OURS;
    }

    private void applyChoice(World world, BlockPos position, ConflictBlockState state, ConflictChoice choice) {
        String blockState = choice == ConflictChoice.THEIRS ? state.theirs() : state.ours();
        world.getBlockAt(position.x(), position.y(), position.z()).setBlockData(Bukkit.createBlockData(blockState), false);
        state.choice(choice);
    }

    private void scheduleManualMark(Player player, Location location) {
        BlockPos position = BlockPos.of(location);
        Bukkit.getScheduler().runTask(plugin, () -> markManual(player, position));
    }

    private void markManual(Player player, BlockPos position) {
        ConflictSession session = activeSession(player);
        if (session == null) {
            return;
        }
        ConflictBlockState state = session.blocksByPos().get(position);
        if (state == null) {
            return;
        }
        state.choice(ConflictChoice.MANUAL);
        renderSelectionHighlight(player, session);
        sendSelectionSummary(player, session, "已标记手动修改");
    }

    private void giveTool(Player player) {
        ItemStack tool = new ItemStack(Material.STICK);
        ItemMeta meta = tool.getItemMeta();
        meta.displayName(Component.text("冲突处理木棍"));
        meta.lore(List.of(
                Component.text("左键方块：设置选区"),
                Component.text("潜行左键：重置为全选"),
                Component.text("右键：在 mine / theirs 间切换选区预览")
        ));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        meta.getPersistentDataContainer().set(toolKey, PersistentDataType.BYTE, (byte) 1);
        tool.setItemMeta(meta);
        player.getInventory().setItem(TOOL_SLOT, tool);
        player.getInventory().setHeldItemSlot(TOOL_SLOT);
    }

    private void stopSession(Player player, boolean restoreItem) {
        ConflictSession session = sessionsByPlayer.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        sessionOwnersByGroup.remove(session.sessionKey(), player.getUniqueId());
        if (!restoreItem) {
            return;
        }
        player.getInventory().setItem(TOOL_SLOT, session.previousSlotItem());
        int heldSlot = Math.max(0, Math.min(8, session.previousHeldSlot()));
        player.getInventory().setHeldItemSlot(heldSlot);
        if (sessionsByPlayer.isEmpty()) {
            stopHighlightTask();
        }
    }

    private ConflictSession activeSession(Player player) {
        ConflictSession session = sessionsByPlayer.get(player.getUniqueId());
        if (session == null) {
            return null;
        }
        if (!player.getWorld().getName().equals(session.worldName())) {
            return null;
        }
        return session;
    }

    private boolean isTool(ItemStack item) {
        if (item == null || item.getType() != Material.STICK || !item.hasItemMeta()) {
            return false;
        }
        Byte marker = item.getItemMeta().getPersistentDataContainer().get(toolKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    private void sendSelectionSummary(Player player, ConflictSession session, String prefix) {
        int manual = 0;
        int ours = 0;
        int theirs = 0;
        for (ConflictBlockState state : session.blocksByPos().values()) {
            switch (state.choice()) {
                case MANUAL -> manual++;
                case THEIRS -> theirs++;
                case OURS -> ours++;
            }
        }
        player.sendActionBar(Component.text(prefix
                + " | 选区 " + session.selectedPositions().size()
                + " | mine " + ours
                + " | theirs " + theirs
                + " | manual " + manual));
    }

    private void ensureHighlightTask() {
        if (highlightTask != null) {
            return;
        }
        highlightTask = Bukkit.getScheduler().runTaskTimer(plugin, this::renderHighlights, 1L, HIGHLIGHT_INTERVAL_TICKS);
    }

    private void stopHighlightTask() {
        if (highlightTask == null) {
            return;
        }
        highlightTask.cancel();
        highlightTask = null;
    }

    private void renderHighlights() {
        if (sessionsByPlayer.isEmpty()) {
            stopHighlightTask();
            return;
        }
        for (Map.Entry<UUID, ConflictSession> entry : sessionsByPlayer.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                continue;
            }
            ConflictSession session = activeSession(player);
            if (session == null) {
                continue;
            }
            renderSelectionHighlight(player, session);
        }
    }

    private void renderSelectionHighlight(Player player, ConflictSession session) {
        if (session.selectedPositions().isEmpty()) {
            return;
        }

        SelectionBounds bounds = SelectionBounds.from(session.selectedPositions());
        spawnOutline(player, bounds);
        spawnMarkers(player, session);
    }

    private void spawnOutline(Player player, SelectionBounds bounds) {
        int stepX = outlineStep(bounds.maxX() - bounds.minX());
        int stepY = outlineStep(bounds.maxY() - bounds.minY());
        int stepZ = outlineStep(bounds.maxZ() - bounds.minZ());

        for (int x = bounds.minX(); x <= bounds.maxX(); x += stepX) {
            spawnOutlineParticle(player, x, bounds.minY(), bounds.minZ());
            spawnOutlineParticle(player, x, bounds.minY(), bounds.maxZ());
            spawnOutlineParticle(player, x, bounds.maxY(), bounds.minZ());
            spawnOutlineParticle(player, x, bounds.maxY(), bounds.maxZ());
        }
        for (int y = bounds.minY(); y <= bounds.maxY(); y += stepY) {
            spawnOutlineParticle(player, bounds.minX(), y, bounds.minZ());
            spawnOutlineParticle(player, bounds.minX(), y, bounds.maxZ());
            spawnOutlineParticle(player, bounds.maxX(), y, bounds.minZ());
            spawnOutlineParticle(player, bounds.maxX(), y, bounds.maxZ());
        }
        for (int z = bounds.minZ(); z <= bounds.maxZ(); z += stepZ) {
            spawnOutlineParticle(player, bounds.minX(), bounds.minY(), z);
            spawnOutlineParticle(player, bounds.minX(), bounds.maxY(), z);
            spawnOutlineParticle(player, bounds.maxX(), bounds.minY(), z);
            spawnOutlineParticle(player, bounds.maxX(), bounds.maxY(), z);
        }

        spawnOutlineParticle(player, bounds.maxX(), bounds.maxY(), bounds.maxZ());
    }

    private void spawnMarkers(Player player, ConflictSession session) {
        int stride = Math.max(1, (int) Math.ceil((double) session.selectedPositions().size() / MAX_MARKER_PARTICLES));
        int index = 0;
        for (BlockPos position : session.selectedPositions()) {
            if (index % stride != 0) {
                index++;
                continue;
            }
            ConflictBlockState state = session.blocksByPos().get(position);
            if (state != null) {
                spawnMarkerParticle(player, position, state.choice());
            }
            index++;
        }
    }

    private void spawnOutlineParticle(Player player, int x, int y, int z) {
        player.spawnParticle(Particle.DUST, x + 0.5, y + 0.1, z + 0.5, 1, 0.0, 0.0, 0.0, 0.0, OUTLINE_PARTICLE);
    }

    private void spawnMarkerParticle(Player player, BlockPos position, ConflictChoice choice) {
        player.spawnParticle(
                Particle.DUST,
                position.x() + 0.5,
                position.y() + 0.5,
                position.z() + 0.5,
                1,
                0.0,
                0.0,
                0.0,
                0.0,
                markerParticle(choice)
        );
    }

    private Particle.DustOptions markerParticle(ConflictChoice choice) {
        return switch (choice) {
            case OURS -> OURS_PARTICLE;
            case THEIRS -> THEIRS_PARTICLE;
            case MANUAL -> MANUAL_PARTICLE;
        };
    }

    private int outlineStep(int span) {
        return Math.max(1, span / 8);
    }

    private String sessionKey(String branchId, int groupIndex) {
        return branchId + "#" + groupIndex;
    }

    private enum ConflictChoice {
        OURS,
        THEIRS,
        MANUAL
    }

    private record BlockPos(int x, int y, int z) {
        private static BlockPos of(Location location) {
            return new BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }
    }

    private record SelectionBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        private static SelectionBounds from(Set<BlockPos> positions) {
            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxY = Integer.MIN_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (BlockPos position : positions) {
                minX = Math.min(minX, position.x());
                minY = Math.min(minY, position.y());
                minZ = Math.min(minZ, position.z());
                maxX = Math.max(maxX, position.x());
                maxY = Math.max(maxY, position.y());
                maxZ = Math.max(maxZ, position.z());
            }
            return new SelectionBounds(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }

    private static final class ConflictBlockState {
        private final String ours;
        private final String theirs;
        private ConflictChoice choice;

        private ConflictBlockState(String ours, String theirs, ConflictChoice choice) {
            this.ours = ours;
            this.theirs = theirs;
            this.choice = choice;
        }

        private String ours() {
            return ours;
        }

        private String theirs() {
            return theirs;
        }

        private ConflictChoice choice() {
            return choice;
        }

        private void choice(ConflictChoice choice) {
            this.choice = choice;
        }
    }

    private static final class ConflictSession {
        private final String branchId;
        private final int groupIndex;
        private final String worldName;
        private final String sessionKey;
        private final ItemStack previousSlotItem;
        private final int previousHeldSlot;
        private final Map<BlockPos, ConflictBlockState> blocksByPos = new HashMap<>();
        private final Set<BlockPos> selectedPositions = new LinkedHashSet<>();
        private BlockPos pos1;
        private BlockPos pos2;

        private ConflictSession(
                String branchId,
                int groupIndex,
                String worldName,
                String sessionKey,
                ItemStack previousSlotItem,
                int previousHeldSlot
        ) {
            this.branchId = branchId;
            this.groupIndex = groupIndex;
            this.worldName = worldName;
            this.sessionKey = sessionKey;
            this.previousSlotItem = previousSlotItem;
            this.previousHeldSlot = previousHeldSlot;
        }

        private boolean matches(String branchId, int groupIndex) {
            return this.branchId.equals(branchId) && this.groupIndex == groupIndex;
        }

        private void resetSelection() {
            pos1 = null;
            pos2 = null;
            selectedPositions.clear();
            selectedPositions.addAll(blocksByPos.keySet());
        }

        private void updateSelection(BlockPos clicked) {
            if (pos1 == null || pos2 != null) {
                pos1 = clicked;
                pos2 = null;
                selectedPositions.clear();
                selectedPositions.add(clicked);
                return;
            }

            pos2 = clicked;
            int minX = Math.min(pos1.x(), pos2.x());
            int minY = Math.min(pos1.y(), pos2.y());
            int minZ = Math.min(pos1.z(), pos2.z());
            int maxX = Math.max(pos1.x(), pos2.x());
            int maxY = Math.max(pos1.y(), pos2.y());
            int maxZ = Math.max(pos1.z(), pos2.z());

            selectedPositions.clear();
            for (BlockPos position : blocksByPos.keySet()) {
                if (position.x() >= minX && position.x() <= maxX
                        && position.y() >= minY && position.y() <= maxY
                        && position.z() >= minZ && position.z() <= maxZ) {
                    selectedPositions.add(position);
                }
            }
        }

        private String worldName() {
            return worldName;
        }

        private String sessionKey() {
            return sessionKey;
        }

        private ItemStack previousSlotItem() {
            return previousSlotItem;
        }

        private int previousHeldSlot() {
            return previousHeldSlot;
        }

        private Map<BlockPos, ConflictBlockState> blocksByPos() {
            return blocksByPos;
        }

        private Set<BlockPos> selectedPositions() {
            return selectedPositions;
        }
    }
}

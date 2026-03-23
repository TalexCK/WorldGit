package com.worldgit.util;

import com.worldgit.WorldGitPlugin;
import com.worldgit.manager.BranchManager;
import com.worldgit.manager.ConflictToolManager;
import com.worldgit.manager.PlayerSelectionManager;
import com.worldgit.manager.RebaseManager;
import com.worldgit.manager.RegionCopyManager;
import com.worldgit.model.Branch;
import com.worldgit.model.BranchSyncInfo;
import com.worldgit.model.BranchSyncState;
import com.worldgit.model.BranchStatus;
import com.worldgit.model.ConflictGroup;
import com.worldgit.model.QueueEntry;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.block.sign.Side;
import org.bukkit.util.Vector;

/**
 * 玩家 /wg 主菜单与提交确认菜单。
 */
public final class PlayerMenuManager implements Listener, PlayerMenuService {

    private static final int MENU_COMPASS_SLOT = 8;
    private static final String CONFIRM_TITLE_PREFIX = "确认操作 ";
    private static final String MERGE_SELECT_TITLE = "选择合并分支";
    private static final String MERGE_HISTORY_TITLE = "合并记录";
    private static final String MERGE_HISTORY_DETAIL_TITLE = "合并记录详情 ";
    private static final String POS1_SIGN_HINT = "设置 Pos1";
    private static final String POS2_SIGN_HINT = "设置 Pos2";
    private static final String MERGE_MESSAGE_SIGN_HINT = "合并说明";
    private static final int MERGE_HISTORY_PAGE_SIZE = 45;
    private static final int CONFLICT_PAGE_SIZE = 36;

    private static final String ACTION_POS1 = "pos1";
    private static final String ACTION_POS2 = "pos2";
    private static final String ACTION_CREATE = "create";
    private static final String ACTION_CREATE_CONFIRM = "create-confirm";
    private static final String ACTION_QUEUE = "queue";
    private static final String ACTION_CREATE_DISABLED = "create-disabled";
    private static final String ACTION_OPEN_MERGE_SELECT = "open-merge-select";
    private static final String ACTION_OPEN_MERGE_HISTORY = "open-merge-history";
    private static final String ACTION_MAIN_WORLD = "main-world";
    private static final String ACTION_REFRESH = "refresh";
    private static final String ACTION_SUBMIT = "submit";
    private static final String ACTION_MERGE = "merge";
    private static final String ACTION_ABANDON = "abandon";
    private static final String ACTION_CANCEL = "cancel";
    private static final String ACTION_REVIEW = "review";
    private static final String ACTION_QUEUE_ENTRY = "queue-entry";
    private static final String ACTION_OPEN_BRANCH_DETAIL = "open-branch-detail";
    private static final String ACTION_BRANCH_TELEPORT = "branch-teleport";
    private static final String ACTION_BRANCH_REBASE = "branch-rebase";
    private static final String ACTION_BRANCH_FETCH = "branch-fetch";
    private static final String ACTION_BRANCH_CONFLICTS = "branch-conflicts";
    private static final String ACTION_BRANCH_FORCE_EDIT = "branch-forceedit";
    private static final String ACTION_BRANCH_ABANDON = "branch-abandon";
    private static final String ACTION_BRANCH_BACK = "branch-back";
    private static final String ACTION_CONFLICT_OPEN = "conflict-open";
    private static final String ACTION_CONFLICT_ACCEPT_OURS = "conflict-accept-ours";
    private static final String ACTION_CONFLICT_ACCEPT_THEIRS = "conflict-accept-theirs";
    private static final String ACTION_CONFLICT_MANUAL = "conflict-manual";
    private static final String ACTION_CONFLICT_DONE = "conflict-done";
    private static final String ACTION_CONFLICT_BACK = "conflict-back";
    private static final String ACTION_CONFLICT_PREVIOUS = "conflict-previous";
    private static final String ACTION_CONFLICT_NEXT = "conflict-next";
    private static final String ACTION_CONFLICT_SELECT_PAGE = "conflict-select-page";
    private static final String ACTION_CONFLICT_SELECT_ALL = "conflict-select-all";
    private static final String ACTION_CONFLICT_CLEAR_SELECTION = "conflict-clear-selection";
    private static final String ACTION_CONFLICT_BATCH_OURS = "conflict-batch-ours";
    private static final String ACTION_CONFLICT_BATCH_THEIRS = "conflict-batch-theirs";
    private static final String ACTION_CONFLICT_BATCH_DONE = "conflict-batch-done";
    private static final String ACTION_VIEW_MERGE_RECORD = "view-merge-record";
    private static final String ACTION_HISTORY_PREVIOUS = "history-previous";
    private static final String ACTION_HISTORY_NEXT = "history-next";
    private static final String ACTION_TELEPORT_RECORD_BRANCH = "teleport-record-branch";
    private static final String ACTION_BACK_TO_HISTORY = "back-to-history";

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.SIMPLIFIED_CHINESE);

    private final WorldGitPlugin plugin;
    private final BranchManager branchManager;
    private final ConflictToolManager conflictToolManager;
    private final ReviewMenuManager reviewMenuManager;
    private final NamespacedKey actionKey;
    private final NamespacedKey branchKey;
    private final NamespacedKey groupKey;
    private final NamespacedKey compassKey;
    private final Map<UUID, PendingSelectionInput> pendingSelectionInputs = new ConcurrentHashMap<>();
    private final Map<UUID, BranchManager.CreateBranchPreview> pendingCreatePreviews = new ConcurrentHashMap<>();
    private final Map<UUID, PendingMergeInput> pendingMergeInputs = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Set<Integer>>> selectedConflictGroupsByPlayer = new ConcurrentHashMap<>();
    private BukkitTask menuBookTask;

    public PlayerMenuManager(
            WorldGitPlugin plugin,
            BranchManager branchManager,
            ConflictToolManager conflictToolManager,
            ReviewMenuManager reviewMenuManager
    ) {
        this.plugin = plugin;
        this.branchManager = branchManager;
        this.conflictToolManager = conflictToolManager;
        this.reviewMenuManager = reviewMenuManager;
        this.actionKey = new NamespacedKey(plugin, "player-menu-action");
        this.branchKey = new NamespacedKey(plugin, "player-menu-branch-id");
        this.groupKey = new NamespacedKey(plugin, "player-menu-group-index");
        this.compassKey = new NamespacedKey(plugin, "player-menu-compass");
    }

    public void start() {
        stop();
        refreshMenuBooks();
        menuBookTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshMenuBooks, 1L, 1L);
    }

    public void stop() {
        if (menuBookTask != null) {
            menuBookTask.cancel();
            menuBookTask = null;
        }
    }

    @Override
    public boolean openMainMenu(Player player) {
        ensureMenuCompass(player);
        MainMenuHolder holder = new MainMenuHolder();
        Inventory inventory = Bukkit.createInventory(holder, 54, MessageUtil.title("玩家面板"));
        holder.setInventory(inventory);

        List<Branch> ownBranches = sortedOwnBranches(player);
        List<Branch> editingBranches = ownBranches.stream()
                .filter(branch -> branch.status() == BranchStatus.ACTIVE || branch.status() == BranchStatus.REJECTED)
                .toList();
        List<Branch> waitingBranches = ownBranches.stream()
                .filter(branch -> branch.status() == BranchStatus.SUBMITTED || branch.status() == BranchStatus.APPROVED)
                .toList();
        List<Branch> mergedBranches = branchManager.listOwnMergedBranches(player);
        Optional<QueueEntry> queuedSelection = branchManager.getQueuedSelection(player);

        inventory.setItem(0, createActionItem(
                Material.LIME_DYE,
                "设置 Pos1",
                ACTION_POS1,
                null,
                List.of("左键：把你当前坐标设为 Pos1", "右键：告示牌输入 X / Y / Z")
        ));
        inventory.setItem(1, createActionItem(
                Material.ORANGE_DYE,
                "设置 Pos2",
                ACTION_POS2,
                null,
                List.of("左键：把你当前坐标设为 Pos2", "右键：告示牌输入 X / Y / Z")
        ));
        inventory.setItem(2, createSelectionInfoItem(player));
        inventory.setItem(3, createCreateOrQueueItem(player));
        inventory.setItem(4, createConfirmButtonItem(ownBranches));
        inventory.setItem(5, createReturnMainWorldItem(player));
        inventory.setItem(6, createAdminMessageItem(ownBranches));
        inventory.setItem(8, createActionItem(
                Material.CLOCK,
                "刷新菜单",
                ACTION_REFRESH,
                null,
                List.of("左键：刷新当前面板")
        ));

        inventory.setItem(9, createSectionLabel(Material.LIME_STAINED_GLASS_PANE, "正在修改的分支"));
        inventory.setItem(27, createSectionLabel(Material.YELLOW_STAINED_GLASS_PANE, "等待中的分支"));
        inventory.setItem(45, createPlayerGuideItem());
        inventory.setItem(46, createMergeHistoryEntryItem(mergedBranches));
        populateBranchSection(inventory, 10, 26, editingBranches, "你当前没有可编辑的分支。");
        if (player.hasPermission("worldgit.admin.review")) {
            inventory.setItem(53, createActionItem(
                    Material.ENDER_CHEST,
                    "审核入口",
                    ACTION_REVIEW,
                    null,
                    List.of("左键：打开 Review 菜单")
            ));
            populateWaitingSection(inventory, waitingSectionSlots(true), waitingBranches, queuedSelection);
        } else {
            populateWaitingSection(inventory, waitingSectionSlots(false), waitingBranches, queuedSelection);
        }

        player.openInventory(inventory);
        return true;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        ensureMenuCompass(event.getPlayer());
    }

    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        ensureMenuCompass(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> ensureMenuCompass(event.getPlayer()));
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (!isMenuCompass(event.getItem())) {
            return;
        }
        event.setCancelled(true);
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);
        openMainMenu(event.getPlayer());
    }

    @EventHandler
    public void onDropMenuCompass(PlayerDropItemEvent event) {
        if (!isMenuCompass(event.getItemDrop().getItemStack())) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler
    public void onSignChange(SignChangeEvent event) {
        PendingSelectionInput pending = pendingSelectionInputs.remove(event.getPlayer().getUniqueId());
        if (pending != null) {
            handleSelectionSignChange(event, pending);
            return;
        }
        PendingMergeInput mergePending = pendingMergeInputs.remove(event.getPlayer().getUniqueId());
        if (mergePending != null) {
            handleMergeMessageSignChange(event, mergePending);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        PendingSelectionInput pending = pendingSelectionInputs.remove(event.getPlayer().getUniqueId());
        if (pending != null) {
            restoreTemporarySign(pending);
        }
        PendingMergeInput mergePending = pendingMergeInputs.remove(event.getPlayer().getUniqueId());
        if (mergePending != null) {
            restoreTemporarySign(mergePending);
        }
        pendingCreatePreviews.remove(event.getPlayer().getUniqueId());
        selectedConflictGroupsByPlayer.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory topInventory = event.getView().getTopInventory();
        InventoryHolder holder = topInventory.getHolder();
        if (!(holder instanceof MainMenuHolder)
                && !(holder instanceof ConfirmMenuHolder)
                && !(holder instanceof CreateConfirmHolder)
                && !(holder instanceof BranchDetailHolder)
                && !(holder instanceof ConflictListHolder)
                && !(holder instanceof ConflictActionHolder)
                && !(holder instanceof MergeSelectMenuHolder)
                && !(holder instanceof MergeHistoryMenuHolder)
                && !(holder instanceof MergeHistoryDetailHolder)) {
            return;
        }
        event.setCancelled(true);

        ItemStack item = event.getCurrentItem();
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return;
        }

        try {
            if (holder instanceof MainMenuHolder) {
                handleMainMenuClick(player, item, event);
                return;
            }
            if (holder instanceof BranchDetailHolder branchDetailHolder) {
                handleBranchDetailMenuClick(player, item, event, branchDetailHolder);
                return;
            }
            if (holder instanceof CreateConfirmHolder createConfirmHolder) {
                handleCreateConfirmMenuClick(player, item, event, createConfirmHolder);
                return;
            }
            if (holder instanceof ConflictListHolder conflictListHolder) {
                handleConflictListMenuClick(player, item, event, conflictListHolder);
                return;
            }
            if (holder instanceof ConflictActionHolder conflictActionHolder) {
                handleConflictActionMenuClick(player, item, event, conflictActionHolder);
                return;
            }
            if (holder instanceof MergeSelectMenuHolder) {
                handleMergeSelectMenuClick(player, item, event);
                return;
            }
            if (holder instanceof MergeHistoryMenuHolder mergeHistoryHolder) {
                handleMergeHistoryMenuClick(player, item, event, mergeHistoryHolder);
                return;
            }
            if (holder instanceof MergeHistoryDetailHolder mergeHistoryDetailHolder) {
                handleMergeHistoryDetailMenuClick(player, item, event, mergeHistoryDetailHolder);
                return;
            }
            handleConfirmMenuClick(player, item, event);
        } catch (IllegalStateException exception) {
            MessageUtil.sendError(player, exception.getMessage());
        }
    }

    private void handleSelectionSignChange(SignChangeEvent event, PendingSelectionInput pending) {
        if (!sameBlock(pending.location(), event.getBlock().getLocation())) {
            pendingSelectionInputs.put(event.getPlayer().getUniqueId(), pending);
            return;
        }

        restoreTemporarySign(pending);
        Player player = event.getPlayer();
        try {
            String[] coordinateLines = resolveCoordinateLines(event, pending);
            Integer x = parseCoordinateExpression(coordinateLines[0], pending.anchor().getBlockX(), "x");
            Integer y = parseCoordinateExpression(coordinateLines[1], pending.anchor().getBlockY(), "y");
            Integer z = parseCoordinateExpression(coordinateLines[2], pending.anchor().getBlockZ(), "z");
            PlayerSelectionManager.SelectionSnapshot snapshot = pending.firstPoint()
                    ? branchManager.setSelectionPos1(player, x, y, z)
                    : branchManager.setSelectionPos2(player, x, y, z);
            MessageUtil.sendSuccess(player,
                    "已设置 " + (pending.firstPoint() ? "Pos1" : "Pos2") + ": "
                            + formatPoint(pending.firstPoint() ? snapshot.pos1() : snapshot.pos2()));
        } catch (IllegalStateException exception) {
            MessageUtil.sendError(player, exception.getMessage());
            sendSelectionInputGuide(player, pending.firstPoint());
        }
        Bukkit.getScheduler().runTask(plugin, () -> openMainMenu(player));
    }

    private void handleMergeMessageSignChange(SignChangeEvent event, PendingMergeInput pending) {
        if (!sameBlock(pending.location(), event.getBlock().getLocation())) {
            pendingMergeInputs.put(event.getPlayer().getUniqueId(), pending);
            return;
        }

        Player player = event.getPlayer();
        String mergeMessage = resolveMergeMessage(event);
        restoreTemporarySignImmediately(pending.location(), pending.previousBlockData());
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                branchManager.confirmBranch(player, pending.branchId(), mergeMessage);
                MessageUtil.sendSuccess(player, "合并流程已开始: " + pending.branchId());
            } catch (IllegalStateException exception) {
                MessageUtil.sendError(player, exception.getMessage());
                sendMergeMessageGuide(player);
            }
            openMainMenu(player);
        });
    }

    private void handleMainMenuClick(Player player, ItemStack item, InventoryClickEvent event) {
        ItemMeta meta = item.getItemMeta();
        String branchId = meta.getPersistentDataContainer().get(branchKey, PersistentDataType.STRING);
        if (branchId != null && !branchId.isBlank()) {
            handleBranchItemClick(player, branchId, event.isLeftClick(), event.isRightClick());
            return;
        }

        String action = meta.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null || action.isBlank()) {
            return;
        }
        if (ACTION_QUEUE_ENTRY.equals(action)) {
            handleQueueItemClick(player, event.isLeftClick(), event.isRightClick());
            return;
        }

        switch (action) {
            case ACTION_POS1 -> {
                requirePermission(player, "worldgit.branch.create");
                handleSelectionButtonClick(player, true, event.isLeftClick(), event.isRightClick());
            }
            case ACTION_POS2 -> {
                requirePermission(player, "worldgit.branch.create");
                handleSelectionButtonClick(player, false, event.isLeftClick(), event.isRightClick());
            }
            case ACTION_CREATE -> {
                requirePermission(player, "worldgit.branch.create");
                openCreateConfirmMenu(player, branchManager.previewCreateBranch(player));
            }
            case ACTION_QUEUE -> {
                requirePermission(player, "worldgit.branch.queue");
                player.closeInventory();
                branchManager.queueSelection(player);
                MessageUtil.sendSuccess(player, "当前区域已加入排队列表");
            }
            case ACTION_CREATE_DISABLED -> {
                BranchManager.MenuCreateState state = branchManager.resolveMenuCreateState(player);
                MessageUtil.sendWarning(player, state.detail());
                openMainMenu(player);
            }
            case ACTION_OPEN_MERGE_SELECT -> {
                requirePermission(player, "worldgit.branch.confirm");
                openMergeSelectionMenu(player, branchManager.listOwnApprovedBranches(player));
            }
            case ACTION_OPEN_MERGE_HISTORY -> openMergeHistoryMenu(player, 0);
            case ACTION_MAIN_WORLD -> {
                requirePermission(player, "worldgit.branch.return");
                if (isInMainWorld(player)) {
                    MessageUtil.sendWarning(player, "你当前已经在主世界，无需返回。");
                    openMainMenu(player);
                    return;
                }
                player.closeInventory();
                branchManager.returnToMainWorld(player);
                MessageUtil.sendSuccess(player, "正在返回主世界");
            }
            case ACTION_REVIEW -> {
                requirePermission(player, "worldgit.admin.review");
                reviewMenuManager.openPendingReviewMenu(player);
            }
            case ACTION_REFRESH -> openMainMenu(player);
            default -> {
            }
        }
    }

    private void handleSelectionButtonClick(Player player, boolean firstPoint, boolean leftClick, boolean rightClick) {
        if (leftClick) {
            PlayerSelectionManager.SelectionSnapshot snapshot = firstPoint
                    ? branchManager.setSelectionPos1(player, null, null, null)
                    : branchManager.setSelectionPos2(player, null, null, null);
            MessageUtil.sendSuccess(player, "已设置 " + (firstPoint ? "Pos1" : "Pos2") + ": "
                    + formatPoint(firstPoint ? snapshot.pos1() : snapshot.pos2()));
            openMainMenu(player);
            return;
        }
        if (rightClick) {
            player.closeInventory();
            openSelectionCoordinateInput(player, firstPoint);
        }
    }

    private void handleQueueItemClick(Player player, boolean leftClick, boolean rightClick) {
        if (leftClick) {
            requirePermission(player, "worldgit.branch.create");
            player.closeInventory();
            Branch branch = branchManager.createBranchFromQueue(player);
            MessageUtil.sendSuccess(player, "排队区域已创建分支: " + branch.id());
            return;
        }
        if (rightClick) {
            branchManager.removeQueuedSelection(player);
            openMainMenu(player);
        }
    }

    private void handleBranchItemClick(Player player, String branchId, boolean leftClick, boolean rightClick) {
        Branch branch = branchManager.requireBranch(branchId);
        if (!branch.ownerUuid().equals(player.getUniqueId())) {
            throw new IllegalStateException("只能操作你自己的分支");
        }
        if (!leftClick && !rightClick) {
            return;
        }
        openBranchDetailMenu(player, branch);
    }

    private void handleBranchDetailMenuClick(Player player, ItemStack item, InventoryClickEvent event, BranchDetailHolder holder) {
        if (!event.isLeftClick()) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        String action = meta.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null || action.isBlank()) {
            return;
        }
        Branch branch = branchManager.requireBranch(holder.branchId());
        switch (action) {
            case ACTION_BRANCH_BACK -> openMainMenu(player);
            case ACTION_BRANCH_TELEPORT -> {
                requirePermission(player, "worldgit.branch.tp");
                player.closeInventory();
                branchManager.teleportToBranch(player, branch.id());
                MessageUtil.sendSuccess(player, "正在传送到分支: " + branch.id());
            }
            case ACTION_BRANCH_REBASE -> {
                RebaseManager.RebaseResult result = branchManager.rebaseBranch(player, branch.id());
                if (result.hasConflicts()) {
                    MessageUtil.sendWarning(player, "Rebase 完成，但发现 " + result.syncInfo().unresolvedGroupCount() + " 组冲突。");
                    openConflictListMenu(player, branchManager.requireBranch(branch.id()));
                } else {
                    MessageUtil.sendSuccess(player, "Rebase 完成，自动合入 " + result.autoMergedBlocks() + " 个方块更新。");
                    openBranchDetailMenu(player, branchManager.requireBranch(branch.id()));
                }
            }
            case ACTION_BRANCH_FETCH -> {
                BranchManager.FetchResult result = branchManager.fetchOutsideSelection(player, branch.id());
                MessageUtil.sendSuccess(player, "Fetch 完成，已更新分支外围区域的 " + result.updatedBlocks() + " 个方块。");
                openBranchDetailMenu(player, branchManager.requireBranch(branch.id()));
            }
            case ACTION_BRANCH_CONFLICTS -> openConflictListMenu(player, branch);
            case ACTION_BRANCH_FORCE_EDIT -> {
                requirePermission(player, "worldgit.branch.forceedit");
                Branch reopened = branchManager.forceEditBranch(player, branch.id());
                MessageUtil.sendSuccess(player, "分支已切回编辑状态: " + reopened.id());
                openBranchDetailMenu(player, reopened);
            }
            case ACTION_BRANCH_ABANDON -> {
                requirePermission(player, "worldgit.branch.abandon");
                openConfirmMenu(player, branch, ACTION_ABANDON);
            }
            case ACTION_SUBMIT -> {
                requirePermission(player, "worldgit.branch.submit");
                openConfirmMenu(player, branch, ACTION_SUBMIT);
            }
            case ACTION_MERGE -> {
                requirePermission(player, "worldgit.branch.confirm");
                openConfirmMenu(player, branch, ACTION_MERGE);
            }
            default -> {
            }
        }
    }

    private void handleConflictListMenuClick(Player player, ItemStack item, InventoryClickEvent event, ConflictListHolder holder) {
        ItemMeta meta = item.getItemMeta();
        String action = meta.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null || action.isBlank()) {
            return;
        }
        Branch branch = branchManager.requireBranch(holder.branchId());
        List<ConflictGroup> groups = branchManager.listConflictGroups(player, branch.id());
        switch (action) {
            case ACTION_CONFLICT_BACK -> openBranchDetailMenu(player, branch);
            case ACTION_CONFLICT_PREVIOUS -> {
                if (event.isLeftClick()) {
                    openConflictListMenu(player, branch, holder.page() - 1);
                }
            }
            case ACTION_CONFLICT_NEXT -> {
                if (event.isLeftClick()) {
                    openConflictListMenu(player, branch, holder.page() + 1);
                }
            }
            case ACTION_CONFLICT_SELECT_PAGE -> {
                if (event.isLeftClick()) {
                    selectConflictGroups(player, branch.id(), conflictPageGroups(groups, holder.page()).stream()
                            .filter(group -> !group.resolved())
                            .map(ConflictGroup::groupIndex)
                            .toList());
                    openConflictListMenu(player, branch, holder.page());
                }
            }
            case ACTION_CONFLICT_SELECT_ALL -> {
                if (event.isLeftClick()) {
                    selectConflictGroups(player, branch.id(), groups.stream()
                            .filter(group -> !group.resolved())
                            .map(ConflictGroup::groupIndex)
                            .toList());
                    openConflictListMenu(player, branch, holder.page());
                }
            }
            case ACTION_CONFLICT_CLEAR_SELECTION -> {
                if (event.isLeftClick()) {
                    clearConflictSelection(player, branch.id());
                    openConflictListMenu(player, branch, holder.page());
                }
            }
            case ACTION_CONFLICT_BATCH_OURS -> {
                if (event.isLeftClick()) {
                    applyConflictBatch(player, branch, holder.page(), ConflictBatchMode.OURS);
                }
            }
            case ACTION_CONFLICT_BATCH_THEIRS -> {
                if (event.isLeftClick()) {
                    applyConflictBatch(player, branch, holder.page(), ConflictBatchMode.THEIRS);
                }
            }
            case ACTION_CONFLICT_BATCH_DONE -> {
                if (event.isLeftClick()) {
                    applyConflictBatch(player, branch, holder.page(), ConflictBatchMode.MANUAL_DONE);
                }
            }
            case ACTION_CONFLICT_OPEN -> {
                Integer groupIndex = meta.getPersistentDataContainer().get(groupKey, PersistentDataType.INTEGER);
                if (groupIndex == null) {
                    return;
                }
                if (event.isRightClick()) {
                    openConflictActionMenu(player, branch, groupIndex, holder.page());
                } else if (event.isLeftClick()) {
                    toggleConflictSelection(player, branch.id(), groupIndex);
                    openConflictListMenu(player, branch, holder.page());
                }
            }
            default -> {
            }
        }
    }

    private void handleConflictActionMenuClick(Player player, ItemStack item, InventoryClickEvent event, ConflictActionHolder holder) {
        if (!event.isLeftClick()) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        String action = meta.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null || action.isBlank()) {
            return;
        }
        Branch branch = branchManager.requireBranch(holder.branchId());
        switch (action) {
            case ACTION_CONFLICT_BACK -> openConflictListMenu(player, branch, holder.returnPage());
            case ACTION_CONFLICT_ACCEPT_OURS -> {
                conflictToolManager.stopSessionIfMatches(player, branch.id(), holder.groupIndex());
                branchManager.resolveConflictUseOurs(player, branch.id(), holder.groupIndex());
                MessageUtil.sendSuccess(player, "已应用 mine 方案。");
                clearConflictSelection(player, branch.id(), holder.groupIndex());
                openConflictListMenu(player, branchManager.requireBranch(branch.id()), holder.returnPage());
            }
            case ACTION_CONFLICT_ACCEPT_THEIRS -> {
                conflictToolManager.stopSessionIfMatches(player, branch.id(), holder.groupIndex());
                branchManager.resolveConflictUseTheirs(player, branch.id(), holder.groupIndex());
                MessageUtil.sendSuccess(player, "已应用 theirs 方案。");
                clearConflictSelection(player, branch.id(), holder.groupIndex());
                openConflictListMenu(player, branchManager.requireBranch(branch.id()), holder.returnPage());
            }
            case ACTION_CONFLICT_MANUAL -> {
                player.closeInventory();
                conflictToolManager.startSession(player, branch.id(), holder.groupIndex());
                MessageUtil.sendInfo(player, "已进入冲突木棍模式。左键选区，右键切换 mine / theirs，手动改块会自动标记。");
            }
            case ACTION_CONFLICT_DONE -> {
                conflictToolManager.completeSession(player, branch.id(), holder.groupIndex());
                MessageUtil.sendSuccess(player, "当前冲突组已标记为解决。");
                clearConflictSelection(player, branch.id(), holder.groupIndex());
                openConflictListMenu(player, branchManager.requireBranch(branch.id()), holder.returnPage());
            }
            default -> {
            }
        }
    }

    private void handleMergeSelectMenuClick(Player player, ItemStack item, InventoryClickEvent event) {
        if (!event.isLeftClick()) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        String action = meta.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (ACTION_CANCEL.equals(action)) {
            openMainMenu(player);
            return;
        }

        String branchId = meta.getPersistentDataContainer().get(branchKey, PersistentDataType.STRING);
        if (branchId == null || branchId.isBlank()) {
            return;
        }
        requirePermission(player, "worldgit.branch.confirm");
        Branch branch = branchManager.requireBranch(branchId);
        if (!branch.ownerUuid().equals(player.getUniqueId())) {
            throw new IllegalStateException("只能确认你自己的分支");
        }
        if (branch.status() != BranchStatus.APPROVED) {
            throw new IllegalStateException("该分支当前不在待确认合并状态");
        }
        openConfirmMenu(player, branch, ACTION_MERGE);
    }

    private void handleConfirmMenuClick(Player player, ItemStack item, InventoryClickEvent event) {
        if (!event.isLeftClick()) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        String action = meta.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        String branchId = meta.getPersistentDataContainer().get(branchKey, PersistentDataType.STRING);
        if (action == null || action.isBlank()) {
            return;
        }
        if (ACTION_CANCEL.equals(action)) {
            openMainMenu(player);
            return;
        }
        if (branchId == null || branchId.isBlank()) {
            return;
        }
        switch (action) {
            case ACTION_SUBMIT -> {
                requirePermission(player, "worldgit.branch.submit");
                branchManager.submitBranch(player, branchId);
                MessageUtil.sendSuccess(player, "分支已提交审核: " + branchId);
                openMainMenu(player);
            }
            case ACTION_MERGE -> {
                requirePermission(player, "worldgit.branch.confirm");
                player.closeInventory();
                openMergeMessageInput(player, branchManager.requireBranch(branchId));
            }
            case ACTION_ABANDON -> {
                requirePermission(player, "worldgit.branch.abandon");
                branchManager.abandonBranch(player, branchId);
                MessageUtil.sendSuccess(player, "分支已放弃: " + branchId);
                openMainMenu(player);
            }
            default -> {
            }
        }
    }

    private void handleCreateConfirmMenuClick(
            Player player,
            ItemStack item,
            InventoryClickEvent event,
            CreateConfirmHolder holder
    ) {
        if (!event.isLeftClick()) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        String action = meta.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null || action.isBlank()) {
            return;
        }
        if (ACTION_CANCEL.equals(action)) {
            pendingCreatePreviews.remove(player.getUniqueId());
            openMainMenu(player);
            return;
        }
        if (!ACTION_CREATE_CONFIRM.equals(action)) {
            return;
        }

        requirePermission(player, "worldgit.branch.create");
        BranchManager.CreateBranchPreview preview = pendingCreatePreviews.remove(player.getUniqueId());
        if (preview == null) {
            throw new IllegalStateException("创建确认已失效，请重新打开玩家面板。");
        }

        player.closeInventory();
        Branch branch = branchManager.createBranchConfirmed(player, preview.selection());
        MessageUtil.sendSuccess(player, holder.hasOverlap()
                ? "已确认重叠并创建分支: " + branch.id()
                : "分支创建成功: " + branch.id());
    }

    private void openBranchDetailMenu(Player player, Branch branch) {
        BranchSyncInfo syncInfo = branchManager.getSyncInfo(branch);
        Branch refreshed = branchManager.requireBranch(branch.id());
        BranchDetailHolder holder = new BranchDetailHolder(refreshed.id());
        Inventory inventory = Bukkit.createInventory(holder, 54, "分支详情 " + shortId(refreshed.id()));
        holder.setInventory(inventory);

        inventory.setItem(10, createActionItem(
                Material.ENDER_PEARL,
                "进入分支",
                ACTION_BRANCH_TELEPORT,
                refreshed.id(),
                List.of("左键：传送到该分支世界")
        ));
        inventory.setItem(11, createReadonlyItem(
                Material.PAPER,
                "基线信息",
                List.of(
                        "base revision：" + syncInfo.baseRevision(),
                        "sync 状态：" + syncStateLabel(syncInfo.syncState()),
                        "未解决冲突组：" + syncInfo.unresolvedGroupCount()
                )
        ));
        inventory.setItem(12, createReadonlyItem(
                Material.CLOCK,
                "主线状态",
                List.of(
                        "current head：" + branchManager.currentHeadRevision(refreshed.mainWorld()),
                        "待吸收 commit：" + branchManager.listIncomingCommits(refreshed).size(),
                        syncInfo.staleReason() == null || syncInfo.staleReason().isBlank()
                                ? "当前没有额外同步提示"
                                : syncInfo.staleReason()
                )
        ));
        inventory.setItem(13, createReadonlyItem(
                materialFor(refreshed.status()),
                "状态总览",
                List.of(
                        "分支状态：" + statusLabel(refreshed.status()),
                        "世界：" + refreshed.worldName(),
                        "区域：(" + refreshed.minX() + ", " + refreshed.minY() + ", " + refreshed.minZ()
                                + ") -> (" + refreshed.maxX() + ", " + refreshed.maxY() + ", " + refreshed.maxZ() + ")"
                )
        ));
        inventory.setItem(14, createReadonlyItem(
                Material.NAME_TAG,
                "协作者",
                List.of("建造者：" + joinAndTrim(branchManager.listBuilderNames(refreshed)))
        ));
        inventory.setItem(15, createReadonlyItem(
                Material.BOOK,
                "审核记录",
                List.of(
                        "审核人：" + resolvePlayerName(refreshed.reviewedBy(), "暂无"),
                        "审核时间：" + formatInstant(refreshed.reviewedAt()),
                        "审核基线：" + (syncInfo.lastReviewedRevision() == null ? "暂无" : syncInfo.lastReviewedRevision())
                )
        ));

        inventory.setItem(19, createActionItem(
                Material.ANVIL,
                "执行 Rebase",
                ACTION_BRANCH_REBASE,
                refreshed.id(),
                List.of(
                        "左键：把当前分支重放到最新主线上",
                        "如果只想刷新分支外围区域，请改用 Fetch"
                )
        ));
        inventory.setItem(20, createActionItem(
                syncInfo.unresolvedGroupCount() > 0 ? Material.REDSTONE_BLOCK : Material.CHEST,
                "冲突中心",
                ACTION_BRANCH_CONFLICTS,
                refreshed.id(),
                List.of(
                        "冲突组数量：" + syncInfo.unresolvedGroupCount(),
                        "左键：打开冲突中心"
                )
        ));
        inventory.setItem(21, createActionItem(
                refreshed.status() == BranchStatus.ACTIVE || refreshed.status() == BranchStatus.REJECTED ? Material.LIME_CONCRETE : Material.GRAY_DYE,
                "提交审核",
                ACTION_SUBMIT,
                refreshed.id(),
                List.of(
                        refreshed.status() == BranchStatus.ACTIVE || refreshed.status() == BranchStatus.REJECTED
                                ? "左键：提交到审核队列"
                                : "当前状态不可提交审核"
                )
        ));
        inventory.setItem(22, createActionItem(
                refreshed.status() == BranchStatus.APPROVED ? Material.EMERALD_BLOCK : Material.GRAY_DYE,
                "确认合并",
                ACTION_MERGE,
                refreshed.id(),
                List.of(
                        refreshed.status() == BranchStatus.APPROVED
                                ? "左键：进入最终确认"
                                : "当前状态不可确认合并"
                )
        ));
        inventory.setItem(23, createActionItem(
                refreshed.status() == BranchStatus.APPROVED ? Material.ORANGE_CONCRETE : Material.GRAY_DYE,
                "切回编辑",
                ACTION_BRANCH_FORCE_EDIT,
                refreshed.id(),
                List.of(
                        refreshed.status() == BranchStatus.APPROVED
                                ? "左键：撤销已通过状态，继续修改"
                                : "只有已通过审核的分支可用"
                )
        ));
        inventory.setItem(24, createFetchItem(player, refreshed));
        inventory.setItem(31, createActionItem(
                Material.BARRIER,
                "放弃分支",
                ACTION_BRANCH_ABANDON,
                refreshed.id(),
                List.of("左键：进入放弃确认页")
        ));
        inventory.setItem(40, createActionItem(
                Material.ARROW,
                "返回玩家面板",
                ACTION_BRANCH_BACK,
                refreshed.id(),
                List.of("左键：返回主菜单")
        ));
        player.openInventory(inventory);
    }

    private void openConflictListMenu(Player player, Branch branch) {
        openConflictListMenu(player, branch, 0);
    }

    private void openConflictListMenu(Player player, Branch branch, int requestedPage) {
        List<ConflictGroup> groups = branchManager.listConflictGroups(player, branch.id());
        pruneConflictSelection(player, branch.id(), groups);
        int totalPages = Math.max(1, (groups.size() + CONFLICT_PAGE_SIZE - 1) / CONFLICT_PAGE_SIZE);
        int page = Math.max(0, Math.min(requestedPage, totalPages - 1));
        ConflictListHolder holder = new ConflictListHolder(branch.id(), page, totalPages);
        Inventory inventory = Bukkit.createInventory(holder, 54, "冲突中心 " + shortId(branch.id()) + " " + (page + 1) + "/" + totalPages);
        holder.setInventory(inventory);
        Set<Integer> selectedGroups = selectedConflictGroups(player, branch.id());

        inventory.setItem(0, createReadonlyItem(
                Material.BOOK,
                "冲突总览",
                List.of(
                        "总组数：" + groups.size(),
                        "未解决：" + groups.stream().filter(group -> !group.resolved()).count(),
                        "当前页：" + (page + 1) + "/" + totalPages
                )
        ));
        inventory.setItem(1, createReadonlyItem(
                Material.NAME_TAG,
                "已选冲突组",
                List.of(
                        "当前选中：" + selectedGroups.size() + " 组",
                        selectedGroups.isEmpty() ? "左键点组可勾选，右键看详情" : "可在顶部执行批量 mine / theirs / 完成"
                )
        ));
        inventory.setItem(2, createActionItem(
                Material.MAP,
                "全选本页",
                ACTION_CONFLICT_SELECT_PAGE,
                branch.id(),
                List.of("左键：勾选当前页所有未解决冲突")
        ));
        inventory.setItem(3, createActionItem(
                Material.CHEST,
                "全选全部",
                ACTION_CONFLICT_SELECT_ALL,
                branch.id(),
                List.of("左键：勾选该分支所有未解决冲突")
        ));
        inventory.setItem(4, createActionItem(
                Material.BARRIER,
                "清空选择",
                ACTION_CONFLICT_CLEAR_SELECTION,
                branch.id(),
                List.of("左键：清空当前已选冲突组")
        ));
        inventory.setItem(5, createConflictBatchActionItem(
                !selectedGroups.isEmpty(),
                Material.GREEN_CONCRETE,
                "批量 mine",
                ACTION_CONFLICT_BATCH_OURS,
                branch.id(),
                List.of("左键：对已选冲突组统一保留 mine 版本")
        ));
        inventory.setItem(6, createConflictBatchActionItem(
                !selectedGroups.isEmpty(),
                Material.BLUE_CONCRETE,
                "批量 theirs",
                ACTION_CONFLICT_BATCH_THEIRS,
                branch.id(),
                List.of("左键：对已选冲突组统一采用主线版本")
        ));
        inventory.setItem(7, createConflictBatchActionItem(
                !selectedGroups.isEmpty(),
                Material.CRAFTING_TABLE,
                "批量完成",
                ACTION_CONFLICT_BATCH_DONE,
                branch.id(),
                List.of("左键：将已选冲突组统一标记为手动处理完成")
        ));
        inventory.setItem(8, createReadonlyItem(
                Material.COMPASS,
                "操作提示",
                List.of(
                        "左键：勾选 / 取消勾选冲突组",
                        "右键：查看该组详情与方块变化"
                )
        ));

        if (groups.isEmpty()) {
            inventory.setItem(22, createReadonlyItem(
                    Material.LIME_STAINED_GLASS_PANE,
                    "当前没有冲突",
                    List.of("这个分支现在没有待处理的冲突组。")
            ));
        } else {
            int slot = 9;
            for (ConflictGroup group : conflictPageGroups(groups, page)) {
                if (slot > 44) {
                    break;
                }
                RebaseManager.ConflictGroupDetail detail = branchManager.describeConflictGroup(player, branch.id(), group.groupIndex());
                inventory.setItem(slot++, createConflictListItem(group, branch.id(), selectedGroups.contains(group.groupIndex()), detail));
            }
        }
        inventory.setItem(45, createHistoryPageButton(page > 0, Material.ARROW, "上一页", ACTION_CONFLICT_PREVIOUS, List.of("左键：查看上一页冲突组")));
        inventory.setItem(49, createActionItem(
                Material.ARROW,
                "返回分支详情",
                ACTION_CONFLICT_BACK,
                branch.id(),
                List.of("左键：返回分支详情")
        ));
        inventory.setItem(53, createHistoryPageButton(page + 1 < totalPages, Material.ARROW, "下一页", ACTION_CONFLICT_NEXT, List.of("左键：查看下一页冲突组")));
        player.openInventory(inventory);
    }

    private void openConflictActionMenu(Player player, Branch branch, int groupIndex, int returnPage) {
        RebaseManager.ConflictGroupDetail detail = branchManager.describeConflictGroup(player, branch.id(), groupIndex);
        ConflictGroup group = detail.group();
        ConflictActionHolder holder = new ConflictActionHolder(branch.id(), groupIndex, returnPage);
        Inventory inventory = Bukkit.createInventory(holder, 54, "冲突组 #" + groupIndex);
        holder.setInventory(inventory);

        inventory.setItem(10, createGroupedActionItem(
                Material.GREEN_CONCRETE,
                "接受 mine",
                ACTION_CONFLICT_ACCEPT_OURS,
                branch.id(),
                groupIndex,
                List.of("左键：保留分支里的版本")
        ));
        inventory.setItem(12, createGroupedActionItem(
                Material.BLUE_CONCRETE,
                "接受 theirs",
                ACTION_CONFLICT_ACCEPT_THEIRS,
                branch.id(),
                groupIndex,
                List.of("左键：采用主线里的版本")
        ));
        inventory.setItem(14, createGroupedActionItem(
                Material.ENDER_PEARL,
                "手动处理",
                ACTION_CONFLICT_MANUAL,
                branch.id(),
                groupIndex,
                List.of("左键：传送到该冲突区域手动处理")
        ));
        inventory.setItem(16, createGroupedActionItem(
                Material.CRAFTING_TABLE,
                "标记已解决",
                ACTION_CONFLICT_DONE,
                branch.id(),
                groupIndex,
                List.of("左键：将当前组标记为已解决")
        ));
        inventory.setItem(20, createReadonlyItem(
                Material.PAPER,
                "冲突摘要",
                List.of(
                        "组编号：" + group.groupIndex(),
                        "块数：" + group.blockCount(),
                        "范围：(" + group.minX() + ", " + group.minY() + ", " + group.minZ()
                                + ") -> (" + group.maxX() + ", " + group.maxY() + ", " + group.maxZ() + ")",
                        "状态：" + (group.resolved() ? "已解决" : "待处理")
                )
        ));
        inventory.setItem(22, createReadonlyItem(
                Material.GREEN_STAINED_GLASS_PANE,
                "mine 变化",
                buildConflictChangeLore(detail.oursChanges(), "格式：base -> mine x数量")
        ));
        inventory.setItem(24, createReadonlyItem(
                Material.BLUE_STAINED_GLASS_PANE,
                "theirs 变化",
                buildConflictChangeLore(detail.theirsChanges(), "格式：base -> theirs x数量")
        ));
        inventory.setItem(31, createReadonlyItem(
                Material.KNOWLEDGE_BOOK,
                "处理说明",
                List.of(
                        "mine：保留你分支里的结果",
                        "theirs：采用主线里的结果",
                        "手动：自己改完后再点“标记已解决”"
                )
        ));
        inventory.setItem(49, createGroupedActionItem(
                Material.ARROW,
                "返回冲突中心",
                ACTION_CONFLICT_BACK,
                branch.id(),
                groupIndex,
                List.of("左键：回到冲突组列表")
        ));
        player.openInventory(inventory);
    }

    private void handleMergeHistoryMenuClick(Player player, ItemStack item, InventoryClickEvent event, MergeHistoryMenuHolder holder) {
        if (!event.isLeftClick()) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        String action = meta.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        String branchId = meta.getPersistentDataContainer().get(branchKey, PersistentDataType.STRING);
        if (ACTION_CANCEL.equals(action)) {
            openMainMenu(player);
            return;
        }
        if (ACTION_HISTORY_PREVIOUS.equals(action)) {
            openMergeHistoryMenu(player, holder.page() - 1);
            return;
        }
        if (ACTION_HISTORY_NEXT.equals(action)) {
            openMergeHistoryMenu(player, holder.page() + 1);
            return;
        }
        if (!ACTION_VIEW_MERGE_RECORD.equals(action) || branchId == null || branchId.isBlank()) {
            return;
        }

        Branch branch = branchManager.requireBranch(branchId);
        if (!branch.ownerUuid().equals(player.getUniqueId()) || branch.status() != BranchStatus.MERGED) {
            throw new IllegalStateException("只能查看你自己的合并记录");
        }
        openMergeHistoryDetailMenu(player, branch, holder.page());
    }

    private void handleMergeHistoryDetailMenuClick(Player player, ItemStack item, InventoryClickEvent event, MergeHistoryDetailHolder holder) {
        if (!event.isLeftClick()) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        String action = meta.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null || action.isBlank()) {
            return;
        }
        switch (action) {
            case ACTION_TELEPORT_RECORD_BRANCH -> {
                requirePermission(player, "worldgit.branch.tp");
                player.closeInventory();
                branchManager.teleportToBranch(player, holder.branchId());
                MessageUtil.sendSuccess(player, "正在传送到分支: " + holder.branchId());
            }
            case ACTION_BACK_TO_HISTORY -> openMergeHistoryMenu(player, holder.returnPage());
            case ACTION_CANCEL -> openMainMenu(player);
            default -> {
            }
        }
    }

    private void openMergeSelectionMenu(Player player, List<Branch> approvedBranches) {
        if (approvedBranches.isEmpty()) {
            throw new IllegalStateException("当前没有待确认合并的分支");
        }
        MergeSelectMenuHolder holder = new MergeSelectMenuHolder();
        Inventory inventory = Bukkit.createInventory(holder, 54, MERGE_SELECT_TITLE);
        holder.setInventory(inventory);

        int slot = 0;
        for (Branch branch : approvedBranches) {
            if (slot >= 45) {
                break;
            }
            inventory.setItem(slot++, createMergeSelectionItem(branch));
        }

        inventory.setItem(45, createReadonlyItem(
                Material.BOOK,
                "已批准分支",
                List.of("当前可选数量：" + approvedBranches.size(), "左键：选择一个分支进入最终确认")
        ));
        inventory.setItem(49, createActionItem(
                Material.RED_CONCRETE,
                "返回玩家面板",
                ACTION_CANCEL,
                null,
                List.of("左键：取消并返回主菜单")
        ));
        player.openInventory(inventory);
    }

    private void openMergeHistoryMenu(Player player, int requestedPage) {
        List<Branch> mergedBranches = branchManager.listOwnMergedBranches(player);
        int totalPages = Math.max(1, (mergedBranches.size() + MERGE_HISTORY_PAGE_SIZE - 1) / MERGE_HISTORY_PAGE_SIZE);
        int page = Math.max(0, Math.min(requestedPage, totalPages - 1));

        MergeHistoryMenuHolder holder = new MergeHistoryMenuHolder(page, totalPages);
        Inventory inventory = Bukkit.createInventory(
                holder,
                54,
                MERGE_HISTORY_TITLE + " " + (page + 1) + "/" + totalPages
        );
        holder.setInventory(inventory);

        int start = page * MERGE_HISTORY_PAGE_SIZE;
        int end = Math.min(start + MERGE_HISTORY_PAGE_SIZE, mergedBranches.size());
        if (mergedBranches.isEmpty()) {
            inventory.setItem(22, createReadonlyItem(
                    Material.GRAY_STAINED_GLASS_PANE,
                    "暂无合并记录",
                    List.of("你当前还没有完成合并的分支。")
            ));
        } else {
            int slot = 0;
            for (int index = start; index < end; index++) {
                inventory.setItem(slot++, createMergeHistoryItem(mergedBranches.get(index)));
            }
        }

        inventory.setItem(45, createHistoryPageButton(page > 0, Material.ARROW, "上一页", ACTION_HISTORY_PREVIOUS, List.of("左键：查看上一页记录")));
        inventory.setItem(49, createActionItem(
                Material.BOOK,
                "返回玩家面板",
                ACTION_CANCEL,
                null,
                List.of("左键：返回主菜单")
        ));
        inventory.setItem(53, createHistoryPageButton(page + 1 < totalPages, Material.ARROW, "下一页", ACTION_HISTORY_NEXT, List.of("左键：查看下一页记录")));
        player.openInventory(inventory);
    }

    private void openMergeHistoryDetailMenu(Player player, Branch branch, int returnPage) {
        MergeHistoryDetailHolder holder = new MergeHistoryDetailHolder(branch.id(), returnPage);
        Inventory inventory = Bukkit.createInventory(holder, 27, MERGE_HISTORY_DETAIL_TITLE + shortId(branch.id()));
        holder.setInventory(inventory);

        inventory.setItem(11, createActionItem(
                Material.ENDER_PEARL,
                "传送到该分支",
                ACTION_TELEPORT_RECORD_BRANCH,
                branch.id(),
                List.of("左键：进入这个已合并分支的世界")
        ));
        inventory.setItem(13, createMergeHistoryDetailItem(branch));
        inventory.setItem(15, createActionItem(
                Material.ARROW,
                "返回记录列表",
                ACTION_BACK_TO_HISTORY,
                branch.id(),
                List.of("左键：回到合并记录分页列表")
        ));
        inventory.setItem(22, createActionItem(
                Material.BOOK,
                "返回玩家面板",
                ACTION_CANCEL,
                null,
                List.of("左键：回到主菜单")
        ));
        player.openInventory(inventory);
    }

    private void openConfirmMenu(Player player, Branch branch, String action) {
        ConfirmMenuHolder holder = new ConfirmMenuHolder(branch.id(), action);
        Inventory inventory = Bukkit.createInventory(holder, 27, CONFIRM_TITLE_PREFIX + shortId(branch.id()));
        holder.setInventory(inventory);

        String title;
        String detail;
        if (ACTION_SUBMIT.equals(action)) {
            title = "确认提交审核";
            detail = "提交后会进入管理员审核队列";
        } else if (ACTION_ABANDON.equals(action)) {
            title = "确认放弃分支";
            detail = "确认后会关闭该分支并卸载分支世界";
        } else {
            title = "确认合并";
            detail = "确认后将开始把分支内容合并回主世界";
        }

        inventory.setItem(11, createActionItem(
                ACTION_SUBMIT.equals(action) ? Material.LIME_CONCRETE
                        : ACTION_ABANDON.equals(action) ? Material.RED_CONCRETE : Material.PURPLE_CONCRETE,
                title,
                action,
                branch.id(),
                List.of("左键：确认执行", detail)
        ));
        inventory.setItem(13, createBranchDetailItem(branch));
        inventory.setItem(15, createActionItem(
                Material.RED_CONCRETE,
                "取消",
                ACTION_CANCEL,
                branch.id(),
                List.of("左键：取消本次操作")
        ));
        player.openInventory(inventory);
    }

    private void openCreateConfirmMenu(Player player, BranchManager.CreateBranchPreview preview) {
        pendingCreatePreviews.put(player.getUniqueId(), preview);

        CreateConfirmHolder holder = new CreateConfirmHolder(preview.hasOverlap());
        Inventory inventory = Bukkit.createInventory(
                holder,
                54,
                preview.hasOverlap() ? "创建分支重叠确认" : "确认创建分支"
        );
        holder.setInventory(inventory);

        List<String> confirmLore = new ArrayList<>();
        confirmLore.add("左键：确认创建分支");
        if (preview.hasOverlap()) {
            confirmLore.add("你将接受与已有未合并编辑区的重叠");
            confirmLore.add("重叠率：" + formatPercent(preview.overlapRatio()));
        } else {
            confirmLore.add("当前选区未与已有未合并编辑区重叠");
        }
        inventory.setItem(11, createActionItem(
                preview.hasOverlap() ? Material.ORANGE_CONCRETE : Material.LIME_CONCRETE,
                preview.hasOverlap() ? "确认重叠创建" : "确认创建",
                ACTION_CREATE_CONFIRM,
                null,
                confirmLore
        ));
        inventory.setItem(15, createActionItem(
                Material.RED_CONCRETE,
                "取消",
                ACTION_CANCEL,
                null,
                List.of("左键：取消本次创建")
        ));
        inventory.setItem(22, createCreateSelectionSummaryItem(preview));
        inventory.setItem(31, createCreateOverlapSummaryItem(preview));

        if (preview.hasOverlap()) {
            int slot = 36;
            int displayed = 0;
            int maxEntries = 18;
            for (BranchManager.SelectionOverlap overlap : preview.overlaps()) {
                if (displayed >= maxEntries) {
                    break;
                }
                inventory.setItem(slot++, createCreateOverlapItem(preview, overlap));
                displayed++;
            }
            if (preview.overlaps().size() > maxEntries) {
                inventory.setItem(53, createReadonlyItem(
                        Material.BOOK,
                        "其余重叠区域",
                        List.of("还有 " + (preview.overlaps().size() - maxEntries) + " 个重叠区域未展开")
                ));
            }
        } else {
            inventory.setItem(40, createReadonlyItem(
                    Material.LIME_STAINED_GLASS_PANE,
                    "无需重叠确认",
                    List.of("当前选区与已有未合并编辑区没有重叠", "确认后会直接创建分支并传送进去")
            ));
        }

        player.openInventory(inventory);
    }

    private ItemStack createSelectionInfoItem(Player player) {
        Optional<PlayerSelectionManager.SelectionSnapshot> selection = branchManager.getSelection(player);
        List<String> lore = new ArrayList<>();
        lore.add("当前世界：" + player.getWorld().getName());
        if (selection.isEmpty()) {
            lore.add("Pos1：未设置");
            lore.add("Pos2：未设置");
            lore.add("已选点位：0/2");
        } else {
            PlayerSelectionManager.SelectionSnapshot snapshot = selection.get();
            lore.add("Pos1：" + formatPoint(snapshot.pos1()));
            lore.add("Pos2：" + formatPoint(snapshot.pos2()));
            lore.add("已选点位：" + snapshot.selectedPoints() + "/2");
        }
        return createReadonlyItem(Material.MAP, "当前选区", lore);
    }

    private ItemStack createCreateSelectionSummaryItem(BranchManager.CreateBranchPreview preview) {
        List<String> lore = new ArrayList<>();
        lore.add("范围：" + formatBounds(preview.selection()));
        lore.add("总体积：" + preview.totalBlockCount() + " 方块");
        lore.add("重叠方块：" + preview.overlapBlockCount());
        lore.add("重叠率：" + formatPercent(preview.overlapRatio()));
        return createReadonlyItem(Material.MAP, "本次创建选区", lore);
    }

    private ItemStack createCreateOverlapSummaryItem(BranchManager.CreateBranchPreview preview) {
        if (!preview.hasOverlap()) {
            return createReadonlyItem(
                    Material.LIME_DYE,
                    "重叠检查",
                    List.of("未发现与已有未合并编辑区的重叠")
            );
        }
        return createReadonlyItem(
                Material.ORANGE_DYE,
                "重叠检查",
                List.of(
                        "检测到 " + preview.overlaps().size() + " 个重叠区域",
                        "重叠方块：" + preview.overlapBlockCount(),
                        "重叠率：" + formatPercent(preview.overlapRatio())
                )
        );
    }

    private ItemStack createCreateOverlapItem(
            BranchManager.CreateBranchPreview preview,
            BranchManager.SelectionOverlap overlap
    ) {
        List<String> lore = new ArrayList<>();
        lore.add("建造者：" + overlap.ownerName());
        lore.add("状态：" + statusLabel(overlap.status()));
        lore.add("分支：" + shortId(overlap.branchId()));
        lore.add("重叠方块：" + overlap.overlapBlockCount());
        lore.add("占本次选区：" + formatPercent((double) overlap.overlapBlockCount() / (double) preview.totalBlockCount()));
        lore.add("重叠范围：" + formatBounds(overlap.overlapBounds()));
        lore.add("对方编辑区：" + formatBounds(overlap.branchBounds()));
        return createReadonlyItem(Material.ORANGE_STAINED_GLASS_PANE, "重叠区域 " + shortId(overlap.branchId()), lore);
    }

    private ItemStack createReturnMainWorldItem(Player player) {
        if (isInMainWorld(player)) {
            return createActionItem(
                    Material.GRAY_DYE,
                    "返回主世界",
                    ACTION_MAIN_WORLD,
                    null,
                    List.of("你当前已经在主世界", "此按钮在主世界不可点击")
            );
        }
        return createActionItem(
                Material.COMPASS,
                "返回主世界",
                ACTION_MAIN_WORLD,
                null,
                List.of("左键：传送回 main world")
        );
    }

    private ItemStack createPlayerGuideItem() {
        return createReadonlyItem(
                Material.KNOWLEDGE_BOOK,
                "玩家帮助",
                List.of(
                        "Pos1 / Pos2：左键用当前坐标，右键告示牌输入",
                        "告示牌支持两种填法：",
                        "1. 第 2 / 3 / 4 行输入 X / Y / Z",
                        "2. 直接从第 1 / 2 / 3 行输入 X / Y / Z",
                        "坐标支持：整数、~、~+5、~-3",
                        "1.1.0 起同一区域可并发创建分支，不再排队",
                        "分支卡片左键进入详情页，再执行 Fetch / Rebase / 提审 / 合并",
                        "左下第二格纸张：查看你的合并记录",
                        "审核通过后若想继续改，用 /wg forceedit"
                )
        );
    }

    private ItemStack createMergeHistoryEntryItem(List<Branch> mergedBranches) {
        return createActionItem(
                Material.PAPER,
                "合并记录",
                ACTION_OPEN_MERGE_HISTORY,
                null,
                List.of(
                        "已合并分支数量：" + mergedBranches.size(),
                        "左键：打开分页合并记录列表",
                        "可查看建造者、批准者、合并说明和区域坐标"
                )
        );
    }

    private ItemStack createFetchItem(Player player, Branch branch) {
        BranchManager.FetchPreview preview = branchManager.previewFetch(player, branch);
        boolean editable = branch.status() == BranchStatus.ACTIVE || branch.status() == BranchStatus.REJECTED;
        List<String> lore = new ArrayList<>();
        lore.add("作用：更新分支复制范围内、但不在编辑核心区内的主线方块");
        if (preview.protectedBounds() != null) {
            lore.add("保护编辑区：" + formatBounds(preview.protectedBounds()));
        }
        if (preview.branchBounds() != null) {
            lore.add("同步外围：" + formatBounds(preview.branchBounds()));
        }
        if (editable && preview.available()) {
            lore.add("左键：执行 Fetch");
            lore.add("会保留创建分支时的编辑核心区");
            lore.add("并刷新分支世界内那圈外围副本");
            return createActionItem(Material.HOPPER, "执行 Fetch", ACTION_BRANCH_FETCH, branch.id(), lore);
        }
        lore.add(editable ? preview.message() : "只有编辑中的分支才能执行 Fetch");
        return createActionItem(Material.GRAY_DYE, "执行 Fetch", ACTION_BRANCH_FETCH, branch.id(), lore);
    }

    private ItemStack createCreateOrQueueItem(Player player) {
        BranchManager.MenuCreateState state = branchManager.resolveMenuCreateState(player);
        return switch (state.action()) {
            case CREATE -> createActionItem(
                    Material.CRAFTING_TABLE,
                    state.title(),
                    ACTION_CREATE,
                    null,
                    List.of(state.detail(), "要求：已设置 Pos1 和 Pos2", "点击后会先进入创建确认界面")
            );
            case QUEUE -> createActionItem(
                    Material.HOPPER,
                    state.title(),
                    ACTION_QUEUE,
                    null,
                    List.of(state.detail(), "点击后等待该锁定区域释放")
            );
            case DISABLED -> createActionItem(
                    Material.BARRIER,
                    state.title(),
                    ACTION_CREATE_DISABLED,
                    null,
                    List.of(state.detail())
            );
        };
    }

    private ItemStack createConfirmButtonItem(List<Branch> ownBranches) {
        List<Branch> approvedBranches = ownBranches.stream()
                .filter(branch -> branch.status() == BranchStatus.APPROVED)
                .toList();
        if (approvedBranches.isEmpty()) {
            return createReadonlyItem(
                    Material.GRAY_DYE,
                    "确认合并",
                    List.of("当前没有已通过审核、待确认合并的分支")
            );
        }
        List<String> lore = new ArrayList<>();
        lore.add("可选分支数量：" + approvedBranches.size());
        lore.add("左键：打开分支选择列表");
        lore.add("无论 1 个还是多个，都会先进入选择页面");
        return createActionItem(Material.EMERALD_BLOCK, "确认合并", ACTION_OPEN_MERGE_SELECT, null, lore);
    }

    private ItemStack createAdminMessageItem(List<Branch> ownBranches) {
        Optional<Branch> latestNoteBranch = ownBranches.stream()
                .filter(branch -> branch.reviewNote() != null && !branch.reviewNote().isBlank())
                .findFirst();
        if (latestNoteBranch.isEmpty()) {
            return createReadonlyItem(
                    Material.BOOK,
                    "管理员留言",
                    List.of("当前没有新的管理员留言")
            );
        }
        Branch branch = latestNoteBranch.get();
        List<String> lore = new ArrayList<>();
        lore.add("分支：" + shortId(branch.id()) + " | 状态：" + statusLabel(branch.status()));
        lore.addAll(wrapLine(branch.reviewNote(), 20));
        return createReadonlyItem(Material.WRITABLE_BOOK, "管理员留言", lore);
    }

    private void populateBranchSection(Inventory inventory, int startSlot, int endSlot, List<Branch> branches, String emptyMessage) {
        if (branches.isEmpty()) {
            inventory.setItem(startSlot, createReadonlyItem(Material.GRAY_STAINED_GLASS_PANE, "暂无分支", List.of(emptyMessage)));
            return;
        }
        int slot = startSlot;
        for (Branch branch : branches) {
            if (slot > endSlot) {
                break;
            }
            inventory.setItem(slot++, createBranchItem(branch));
        }
    }

    private void populateWaitingSection(Inventory inventory, int[] slots, List<Branch> waitingBranches, Optional<QueueEntry> queuedSelection) {
        int index = 0;
        if (queuedSelection.isPresent() && index < slots.length) {
            inventory.setItem(slots[index++], createQueueItem(queuedSelection.get()));
        }
        if (waitingBranches.isEmpty() && queuedSelection.isEmpty()) {
            if (slots.length > 0) {
                inventory.setItem(slots[0], createReadonlyItem(Material.GRAY_STAINED_GLASS_PANE, "暂无等待项", List.of("你当前没有等待中的分支或排队。")));
            }
            return;
        }
        for (Branch branch : waitingBranches) {
            if (index >= slots.length) {
                break;
            }
            inventory.setItem(slots[index++], createBranchItem(branch));
        }
    }

    private ItemStack createBranchItem(Branch branch) {
        BranchSyncInfo syncInfo = branchManager.getSyncInfo(branch);
        List<String> lore = new ArrayList<>();
        lore.add("状态：" + statusLabel(branch.status()));
        lore.add("同步：" + syncStateLabel(syncInfo.syncState()));
        lore.add("base/head：" + syncInfo.baseRevision() + " / " + branchManager.currentHeadRevision(branch.mainWorld()));
        lore.add("世界：" + branch.worldName());
        lore.add("创建时间：" + TIME_FORMATTER.format(branch.createdAt().atZone(ZoneId.systemDefault())));
        lore.add("左键：打开分支详情");
        if (syncInfo.unresolvedGroupCount() > 0) {
            lore.add("未解决冲突组：" + syncInfo.unresolvedGroupCount());
        }
        if (branch.status() == BranchStatus.SUBMITTED) {
            lore.add("当前正在等待管理员审核");
        }
        if (branch.reviewNote() != null && !branch.reviewNote().isBlank()) {
            lore.add("管理员留言：");
            lore.addAll(wrapLine(branch.reviewNote(), 20));
        }
        return createActionItem(materialFor(branch.status()), "分支 " + shortId(branch.id()), ACTION_OPEN_BRANCH_DETAIL, branch.id(), lore);
    }

    private ItemStack createQueueItem(QueueEntry entry) {
        List<String> lore = new ArrayList<>();
        lore.add("世界：" + entry.mainWorld());
        lore.add("区域：(" + entry.minX() + ", " + entry.minY() + ", " + entry.minZ()
                + ") -> (" + entry.maxX() + ", " + entry.maxY() + ", " + entry.maxZ() + ")");
        lore.add("排队时间：" + TIME_FORMATTER.format(entry.queuedAt().atZone(ZoneId.systemDefault())));
        lore.add("左键：尝试创建该排队区域");
        lore.add("右键：删除该排队记录");
        return createActionItem(Material.HOPPER, "排队中的区域", ACTION_QUEUE_ENTRY, null, lore);
    }

    private ItemStack createBranchDetailItem(Branch branch) {
        List<String> lore = new ArrayList<>();
        lore.add("状态：" + statusLabel(branch.status()));
        lore.add("世界：" + branch.worldName());
        if (branch.reviewNote() != null && !branch.reviewNote().isBlank()) {
            lore.add("管理员留言：");
            lore.addAll(wrapLine(branch.reviewNote(), 20));
        }
        return createReadonlyItem(Material.PAPER, "目标分支 " + shortId(branch.id()), lore);
    }

    private ItemStack createMergeSelectionItem(Branch branch) {
        List<String> lore = new ArrayList<>();
        lore.add("状态：" + statusLabel(branch.status()));
        lore.add("世界：" + branch.worldName());
        lore.add("创建时间：" + TIME_FORMATTER.format(branch.createdAt().atZone(ZoneId.systemDefault())));
        lore.add("左键：选择该分支并进入最终确认");
        if (branch.reviewNote() != null && !branch.reviewNote().isBlank()) {
            lore.add("管理员留言：");
            lore.addAll(wrapLine(branch.reviewNote(), 20));
        }
        return createActionItem(Material.EMERALD_BLOCK, "待合并分支 " + shortId(branch.id()), null, branch.id(), lore);
    }

    private ItemStack createMergeHistoryItem(Branch branch) {
        List<String> lore = new ArrayList<>();
        lore.add("分支：" + shortId(branch.id()));
        lore.add("合并时间：" + formatInstant(branch.mergedAt()));
        lore.add("建造者：" + joinAndTrim(branchManager.listBuilderNames(branch)));
        lore.add("合并说明：" + normalizeDisplay(branch.mergeMessage(), "未填写合并说明"));
        lore.add("左键：查看详情并选择是否传送");
        return createActionItem(Material.PAPER, "合并记录 " + shortId(branch.id()), ACTION_VIEW_MERGE_RECORD, branch.id(), lore);
    }

    private ItemStack createMergeHistoryDetailItem(Branch branch) {
        List<String> lore = new ArrayList<>();
        lore.add("分支：" + shortId(branch.id()));
        lore.add("世界：" + branch.worldName());
        lore.add("建造者：");
        lore.addAll(wrapLine(joinAndTrim(branchManager.listBuilderNames(branch)), 20));
        lore.add("批准者：" + resolvePlayerName(branch.reviewedBy(), "暂无"));
        lore.add("合并者：" + resolvePlayerName(branch.mergedBy(), "暂无"));
        lore.add("合并时间：" + formatInstant(branch.mergedAt()));
        lore.add("Pos1：(" + branch.minX() + ", " + branch.minY() + ", " + branch.minZ() + ")");
        lore.add("Pos2：(" + branch.maxX() + ", " + branch.maxY() + ", " + branch.maxZ() + ")");
        lore.add("合并说明：");
        lore.addAll(wrapLine(normalizeDisplay(branch.mergeMessage(), "未填写合并说明"), 20));
        return createReadonlyItem(Material.PAPER, "记录详情", lore);
    }

    private ItemStack createHistoryPageButton(boolean enabled, Material material, String title, String action, List<String> lore) {
        if (!enabled) {
            return createReadonlyItem(Material.GRAY_DYE, title, List.of("当前没有更多记录可翻页"));
        }
        return createActionItem(material, title, action, null, lore);
    }

    private ItemStack createSectionLabel(Material material, String title) {
        return createReadonlyItem(material, title, List.of());
    }

    private ItemStack createReadonlyItem(Material material, String title, List<String> lore) {
        return createActionItem(material, title, null, null, lore);
    }

    private ItemStack createActionItem(Material material, String title, String action, String branchId, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MessageUtil.mini("<#f8d06d>" + MessageUtil.escape(title) + "</#f8d06d>"));
        List<Component> loreLines = new ArrayList<>();
        for (String line : lore) {
            loreLines.add(MessageUtil.mini("<gray>" + MessageUtil.escape(line) + "</gray>"));
        }
        meta.lore(loreLines);
        if (action != null && !action.isBlank()) {
            meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        }
        if (branchId != null && !branchId.isBlank()) {
            meta.getPersistentDataContainer().set(branchKey, PersistentDataType.STRING, branchId);
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createGroupedActionItem(Material material, String title, String action, String branchId, int groupIndex, List<String> lore) {
        ItemStack item = createActionItem(material, title, action, branchId, lore);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(groupKey, PersistentDataType.INTEGER, groupIndex);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createConflictBatchActionItem(boolean enabled, Material material, String title, String action, String branchId, List<String> lore) {
        if (!enabled) {
            return createReadonlyItem(Material.GRAY_DYE, title, List.of("请先在列表中选择至少一个未解决冲突组"));
        }
        return createActionItem(material, title, action, branchId, lore);
    }

    private ItemStack createConflictListItem(
            ConflictGroup group,
            String branchId,
            boolean selected,
            RebaseManager.ConflictGroupDetail detail
    ) {
        List<String> lore = new ArrayList<>();
        lore.add("块数：" + group.blockCount());
        lore.add("状态：" + (group.resolved() ? "已解决" : "待处理"));
        lore.add("范围：(" + group.minX() + ", " + group.minY() + ", " + group.minZ()
                + ") -> (" + group.maxX() + ", " + group.maxY() + ", " + group.maxZ() + ")");
        appendConflictPreviewLines(lore, "mine", detail.oursChanges(), 2);
        appendConflictPreviewLines(lore, "theirs", detail.theirsChanges(), 2);
        lore.add(selected ? "左键：取消选中该组" : "左键：选中该组");
        lore.add("右键：查看该组详情");
        return createGroupedActionItem(
                selected ? Material.YELLOW_STAINED_GLASS_PANE
                        : group.resolved() ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE,
                "冲突组 #" + group.groupIndex() + (selected ? " [已选]" : ""),
                ACTION_CONFLICT_OPEN,
                branchId,
                group.groupIndex(),
                lore
        );
    }

    private void appendConflictPreviewLines(
            List<String> lore,
            String label,
            List<RebaseManager.BlockChangeSummary> changes,
            int limit
    ) {
        lore.add(label + "：");
        if (changes.isEmpty()) {
            lore.add("  暂无变化摘要");
            return;
        }
        int capped = Math.min(limit, changes.size());
        for (int index = 0; index < capped; index++) {
            RebaseManager.BlockChangeSummary change = changes.get(index);
            lore.add("  " + formatBlockTransition(change));
        }
        if (changes.size() > capped) {
            lore.add("  其余 " + (changes.size() - capped) + " 种变化见详情");
        }
    }

    private List<String> buildConflictChangeLore(List<RebaseManager.BlockChangeSummary> changes, String header) {
        List<String> lore = new ArrayList<>();
        lore.add(header);
        if (changes.isEmpty()) {
            lore.add("暂无变化摘要");
            return lore;
        }
        int capped = Math.min(6, changes.size());
        for (int index = 0; index < capped; index++) {
            lore.add(formatBlockTransition(changes.get(index)));
        }
        if (changes.size() > capped) {
            lore.add("其余 " + (changes.size() - capped) + " 种变化未展开");
        }
        return lore;
    }

    private String formatBlockTransition(RebaseManager.BlockChangeSummary change) {
        return simplifyBlockState(change.fromState()) + " -> " + simplifyBlockState(change.toState()) + " x" + change.count();
    }

    private String simplifyBlockState(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        String value = raw.replace("minecraft:", "");
        if (value.length() <= 48) {
            return value;
        }
        return value.substring(0, 45) + "...";
    }

    private List<ConflictGroup> conflictPageGroups(List<ConflictGroup> groups, int page) {
        int start = page * CONFLICT_PAGE_SIZE;
        int end = Math.min(start + CONFLICT_PAGE_SIZE, groups.size());
        if (start >= end) {
            return List.of();
        }
        return groups.subList(start, end);
    }

    private Set<Integer> selectedConflictGroups(Player player, String branchId) {
        return new LinkedHashSet<>(selectedConflictGroupsByPlayer
                .computeIfAbsent(player.getUniqueId(), ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(branchId, ignored -> ConcurrentHashMap.newKeySet()));
    }

    private Set<Integer> mutableSelectedConflictGroups(Player player, String branchId) {
        return selectedConflictGroupsByPlayer
                .computeIfAbsent(player.getUniqueId(), ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(branchId, ignored -> ConcurrentHashMap.newKeySet());
    }

    private void toggleConflictSelection(Player player, String branchId, int groupIndex) {
        Set<Integer> selected = mutableSelectedConflictGroups(player, branchId);
        if (!selected.add(groupIndex)) {
            selected.remove(groupIndex);
        }
    }

    private void selectConflictGroups(Player player, String branchId, List<Integer> groupIndexes) {
        mutableSelectedConflictGroups(player, branchId).addAll(groupIndexes);
    }

    private void clearConflictSelection(Player player, String branchId) {
        Map<String, Set<Integer>> byBranch = selectedConflictGroupsByPlayer.get(player.getUniqueId());
        if (byBranch == null) {
            return;
        }
        byBranch.remove(branchId);
        if (byBranch.isEmpty()) {
            selectedConflictGroupsByPlayer.remove(player.getUniqueId());
        }
    }

    private void clearConflictSelection(Player player, String branchId, int groupIndex) {
        Map<String, Set<Integer>> byBranch = selectedConflictGroupsByPlayer.get(player.getUniqueId());
        if (byBranch == null) {
            return;
        }
        Set<Integer> selected = byBranch.get(branchId);
        if (selected == null) {
            return;
        }
        selected.remove(groupIndex);
        if (selected.isEmpty()) {
            byBranch.remove(branchId);
        }
        if (byBranch.isEmpty()) {
            selectedConflictGroupsByPlayer.remove(player.getUniqueId());
        }
    }

    private void pruneConflictSelection(Player player, String branchId, List<ConflictGroup> groups) {
        Set<Integer> validIndexes = groups.stream()
                .filter(group -> !group.resolved())
                .map(ConflictGroup::groupIndex)
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
        Map<String, Set<Integer>> byBranch = selectedConflictGroupsByPlayer.get(player.getUniqueId());
        if (byBranch == null) {
            return;
        }
        Set<Integer> selected = byBranch.get(branchId);
        if (selected == null) {
            return;
        }
        selected.removeIf(groupIndex -> !validIndexes.contains(groupIndex));
        if (selected.isEmpty()) {
            byBranch.remove(branchId);
        }
        if (byBranch.isEmpty()) {
            selectedConflictGroupsByPlayer.remove(player.getUniqueId());
        }
    }

    private void applyConflictBatch(Player player, Branch branch, int page, ConflictBatchMode mode) {
        List<ConflictGroup> groups = branchManager.listConflictGroups(player, branch.id());
        Set<Integer> selectedIndexes = selectedConflictGroups(player, branch.id());
        List<Integer> unresolvedSelected = groups.stream()
                .filter(group -> !group.resolved() && selectedIndexes.contains(group.groupIndex()))
                .map(ConflictGroup::groupIndex)
                .toList();
        if (unresolvedSelected.isEmpty()) {
            throw new IllegalStateException("请先选择至少一个未解决冲突组。");
        }

        for (Integer groupIndex : unresolvedSelected) {
            switch (mode) {
                case OURS -> branchManager.resolveConflictUseOurs(player, branch.id(), groupIndex);
                case THEIRS -> branchManager.resolveConflictUseTheirs(player, branch.id(), groupIndex);
                case MANUAL_DONE -> branchManager.markConflictResolvedManually(player, branch.id(), groupIndex);
            }
        }

        clearConflictSelection(player, branch.id());
        String actionName = switch (mode) {
            case OURS -> "mine";
            case THEIRS -> "theirs";
            case MANUAL_DONE -> "手动完成";
        };
        MessageUtil.sendSuccess(player, "已对 " + unresolvedSelected.size() + " 组冲突执行批量 " + actionName + "。");
        openConflictListMenu(player, branchManager.requireBranch(branch.id()), page);
    }

    private void ensureMenuCompass(Player player) {
        ItemStack expectedMenuCompass = createMenuCompass();
        ItemStack current = player.getInventory().getItem(MENU_COMPASS_SLOT);
        if (isMenuCompass(current)) {
            if (!expectedMenuCompass.isSimilar(current)) {
                player.getInventory().setItem(MENU_COMPASS_SLOT, expectedMenuCompass);
            }
            removeExtraMenuCompasses(player);
            return;
        }
        if (current != null && !current.getType().isAir()) {
            player.getInventory().setItem(MENU_COMPASS_SLOT, null);
            var overflow = player.getInventory().addItem(current.clone());
            if (!overflow.isEmpty()) {
                for (ItemStack overflowItem : overflow.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), overflowItem);
                }
            }
        }
        removeExtraMenuCompasses(player);
        player.getInventory().setItem(MENU_COMPASS_SLOT, expectedMenuCompass);
    }

    private void openSelectionCoordinateInput(Player player, boolean firstPoint) {
        Location base = createTemporarySignLocation(player);
        Block block = base.getBlock();
        BlockData previousBlockData = block.getBlockData().clone();
        block.setType(Material.OAK_SIGN, false);

        if (!(block.getState() instanceof Sign sign)) {
            block.setBlockData(previousBlockData, false);
            throw new IllegalStateException("无法创建告示牌输入框，请站在更开阔的位置重试。");
        }

        sign.getSide(Side.FRONT).line(0, Component.text(firstPoint ? POS1_SIGN_HINT : POS2_SIGN_HINT));
        sign.getSide(Side.FRONT).line(1, Component.text("~"));
        sign.getSide(Side.FRONT).line(2, Component.text("~"));
        sign.getSide(Side.FRONT).line(3, Component.text("~"));
        sign.setWaxed(false);
        sign.setAllowedEditorUniqueId(player.getUniqueId());
        sign.update(true, false);

        pendingSelectionInputs.put(player.getUniqueId(), new PendingSelectionInput(
                firstPoint,
                base,
                previousBlockData,
                player.getLocation().clone()
        ));
        sendSelectionInputGuide(player, firstPoint);
        player.openSign(sign, Side.FRONT);
    }

    private void openMergeMessageInput(Player player, Branch branch) {
        Location base = createTemporarySignLocation(player);
        Block block = base.getBlock();
        BlockData previousBlockData = block.getBlockData().clone();
        block.setType(Material.OAK_SIGN, false);

        if (!(block.getState() instanceof Sign sign)) {
            block.setBlockData(previousBlockData, false);
            throw new IllegalStateException("无法创建告示牌输入框，请站在更开阔的位置重试。");
        }

        sign.getSide(Side.FRONT).line(0, Component.text(MERGE_MESSAGE_SIGN_HINT));
        sign.getSide(Side.FRONT).line(1, Component.empty());
        sign.getSide(Side.FRONT).line(2, Component.empty());
        sign.getSide(Side.FRONT).line(3, Component.empty());
        sign.setWaxed(false);
        sign.setAllowedEditorUniqueId(player.getUniqueId());
        sign.update(true, false);

        pendingMergeInputs.put(player.getUniqueId(), new PendingMergeInput(
                branch.id(),
                base,
                previousBlockData
        ));
        sendMergeMessageGuide(player);
        player.openSign(sign, Side.FRONT);
    }

    private void restoreTemporarySign(PendingSelectionInput pending) {
        restoreTemporarySign(pending.location(), pending.previousBlockData());
    }

    private void restoreTemporarySign(PendingMergeInput pending) {
        restoreTemporarySign(pending.location(), pending.previousBlockData());
    }

    private void restoreTemporarySign(Location location, BlockData previousBlockData) {
        Bukkit.getScheduler().runTask(plugin, () -> location.getBlock().setBlockData(previousBlockData, false));
    }

    private void restoreTemporarySignImmediately(Location location, BlockData previousBlockData) {
        location.getBlock().setBlockData(previousBlockData, false);
    }

    private Location createTemporarySignLocation(Player player) {
        Vector backward = player.getLocation().getDirection().setY(0.0);
        if (backward.lengthSquared() < 1.0E-6) {
            backward = new Vector(0.0, 0.0, 1.0);
        }
        backward.normalize().multiply(-2.0);
        int offsetX = (int) Math.round(backward.getX());
        int offsetZ = (int) Math.round(backward.getZ());
        if (offsetX == 0 && offsetZ == 0) {
            offsetZ = -2;
        }
        return new Location(
                player.getWorld(),
                player.getLocation().getBlockX() + offsetX,
                player.getLocation().getBlockY() + 1,
                player.getLocation().getBlockZ() + offsetZ
        );
    }

    private boolean sameBlock(Location left, Location right) {
        return left.getWorld() != null
                && right.getWorld() != null
                && left.getWorld().equals(right.getWorld())
                && left.getBlockX() == right.getBlockX()
                && left.getBlockY() == right.getBlockY()
                && left.getBlockZ() == right.getBlockZ();
    }

    private Integer parseCoordinateExpression(String raw, int base, String axis) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw new IllegalStateException("坐标 " + axis + " 不能为空");
        }
        if (value.startsWith("~")) {
            if (value.length() == 1) {
                return base;
            }
            String offsetPart = value.substring(1).trim();
            try {
                return base + Integer.parseInt(offsetPart);
            } catch (NumberFormatException exception) {
                throw new IllegalStateException("坐标 " + axis + " 格式无效，支持 ~、~+数字、~-数字 或整数");
            }
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("坐标 " + axis + " 格式无效，支持 ~、~+数字、~-数字 或整数");
        }
    }

    private String[] resolveCoordinateLines(SignChangeEvent event, PendingSelectionInput pending) {
        String line0 = normalizeSignLine(event.getLine(0));
        String line1 = normalizeSignLine(event.getLine(1));
        String line2 = normalizeSignLine(event.getLine(2));
        String line3 = normalizeSignLine(event.getLine(3));

        if (!line1.isEmpty() && !line2.isEmpty() && !line3.isEmpty()) {
            return new String[]{line1, line2, line3};
        }

        if (!line0.isEmpty()
                && !isSelectionHintLine(line0, pending.firstPoint())
                && !line1.isEmpty()
                && !line2.isEmpty()
                && line3.isEmpty()) {
            return new String[]{line0, line1, line2};
        }

        throw new IllegalStateException("请按 X、Y、Z 三行分别输入坐标，不要留空。");
    }

    private String resolveMergeMessage(SignChangeEvent event) {
        List<String> lines = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            String line = normalizeSignLine(event.getLine(index));
            if (line.isEmpty()) {
                continue;
            }
            if (index == 0 && MERGE_MESSAGE_SIGN_HINT.equals(line)) {
                continue;
            }
            lines.add(line);
        }
        return String.join(" ", lines).trim();
    }

    private String normalizeSignLine(String raw) {
        return raw == null ? "" : raw.trim();
    }

    private boolean isSelectionHintLine(String value, boolean firstPoint) {
        return value.equals(firstPoint ? POS1_SIGN_HINT : POS2_SIGN_HINT);
    }

    private void sendSelectionInputGuide(Player player, boolean firstPoint) {
        MessageUtil.sendInfo(player, "正在输入 " + (firstPoint ? "Pos1" : "Pos2") + " 坐标。");
        MessageUtil.sendInfo(player, "方式一：第 2 / 3 / 4 行分别输入 X / Y / Z。");
        MessageUtil.sendInfo(player, "方式二：直接从第 1 / 2 / 3 行输入 X / Y / Z 也可以。");
        MessageUtil.sendInfo(player, "支持格式：整数、~、~+5、~-3。示例：-1917 / 73 / ~-8");
    }

    private void sendMergeMessageGuide(Player player) {
        MessageUtil.sendInfo(player, "请在告示牌中输入本次合并说明。");
        MessageUtil.sendInfo(player, "第 1 到第 4 行都可以填写，多行会自动合并成一条记录。");
        MessageUtil.sendInfo(player, "如果留空，合并记录里会显示为：未填写合并说明。");
    }

    private void refreshMenuBooks() {
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            ensureMenuCompass(onlinePlayer);
        }
    }

    private ItemStack createMenuCompass() {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MessageUtil.mini("<#93c5fd>"
                + MessageUtil.escape(MessageUtil.title("菜单书"))
                + "</#93c5fd>"));
        meta.lore(List.of(
                MessageUtil.mini("<gray>放在快捷栏第 9 格</gray>"),
                MessageUtil.mini("<gray>右键：打开玩家菜单</gray>")
        ));
        meta.getPersistentDataContainer().set(compassKey, PersistentDataType.BYTE, (byte) 1);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isMenuCompass(ItemStack item) {
        if (item == null || item.getType() != Material.BOOK || !item.hasItemMeta()) {
            return false;
        }
        Byte marker = item.getItemMeta().getPersistentDataContainer().get(compassKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    private void removeExtraMenuCompasses(Player player) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            if (slot == MENU_COMPASS_SLOT) {
                continue;
            }
            if (isMenuCompass(player.getInventory().getItem(slot))) {
                player.getInventory().setItem(slot, null);
            }
        }
    }

    private List<Branch> sortedOwnBranches(Player player) {
        return branchManager.listOwnBranches(player).stream()
                .sorted(Comparator.comparing(Branch::createdAt).reversed())
                .toList();
    }

    private boolean isInMainWorld(Player player) {
        return player.getWorld() != null
                && plugin.pluginConfig().mainWorld().equals(player.getWorld().getName());
    }

    private int[] waitingSectionSlots(boolean withReviewButton) {
        List<Integer> slots = new ArrayList<>();
        for (int slot = 28; slot <= 44; slot++) {
            slots.add(slot);
        }
        for (int slot = 47; slot <= (withReviewButton ? 52 : 53); slot++) {
            slots.add(slot);
        }
        return slots.stream().mapToInt(Integer::intValue).toArray();
    }

    private String shortId(String branchId) {
        return branchId.substring(0, Math.min(8, branchId.length()));
    }

    private String statusLabel(BranchStatus status) {
        return switch (status) {
            case ACTIVE -> "正在修改";
            case SUBMITTED -> "等待审核";
            case APPROVED -> "等待确认合并";
            case REJECTED -> "已驳回";
            case MERGED -> "已合并";
            case ABANDONED -> "已关闭";
        };
    }

    private String syncStateLabel(BranchSyncState state) {
        return switch (state) {
            case CLEAN -> "已同步";
            case NEEDS_REBASE -> "需要 Rebase";
            case REBASING -> "Rebasing 中";
            case HAS_CONFLICTS -> "存在冲突";
        };
    }

    private Material materialFor(BranchStatus status) {
        return switch (status) {
            case ACTIVE -> Material.GRASS_BLOCK;
            case SUBMITTED -> Material.YELLOW_CONCRETE;
            case APPROVED -> Material.EMERALD_BLOCK;
            case REJECTED -> Material.RED_CONCRETE;
            case MERGED -> Material.LIGHT_BLUE_CONCRETE;
            case ABANDONED -> Material.GRAY_CONCRETE;
        };
    }

    private String formatInstant(Instant instant) {
        if (instant == null) {
            return "暂无";
        }
        return TIME_FORMATTER.format(instant.atZone(ZoneId.systemDefault()));
    }

    private String resolvePlayerName(UUID playerUuid, String fallback) {
        if (playerUuid == null) {
            return fallback;
        }
        String name = Bukkit.getOfflinePlayer(playerUuid).getName();
        if (name == null || name.isBlank()) {
            return playerUuid.toString().substring(0, 8);
        }
        return name;
    }

    private String joinAndTrim(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "暂无";
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .reduce((left, right) -> left + ", " + right)
                .orElse("暂无");
    }

    private String normalizeDisplay(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private List<String> wrapLine(String message, int maxLength) {
        List<String> lines = new ArrayList<>();
        if (message == null || message.isBlank()) {
            return lines;
        }
        String normalized = message.trim();
        for (int index = 0; index < normalized.length(); index += maxLength) {
            lines.add(normalized.substring(index, Math.min(normalized.length(), index + maxLength)));
        }
        return lines;
    }

    private String formatPoint(PlayerSelectionManager.SelectionPoint point) {
        if (point == null) {
            return "未设置";
        }
        return "(" + point.x() + ", " + point.y() + ", " + point.z() + ")";
    }

    private String formatBounds(RegionCopyManager.SelectionBounds bounds) {
        return "(" + bounds.minX() + ", " + bounds.minY() + ", " + bounds.minZ()
                + ") -> (" + bounds.maxX() + ", " + bounds.maxY() + ", " + bounds.maxZ() + ")";
    }

    private String formatPercent(double value) {
        return String.format(Locale.ROOT, "%.2f%%", value * 100.0D);
    }

    private void requirePermission(Player player, String permission) {
        if (!player.hasPermission(permission)) {
            throw new IllegalStateException("你没有权限执行该操作");
        }
    }

    private enum ConflictBatchMode {
        OURS,
        THEIRS,
        MANUAL_DONE
    }

    private static final class MainMenuHolder implements InventoryHolder {
        private Inventory inventory;

        public void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class ConfirmMenuHolder implements InventoryHolder {
        private final String branchId;
        private final String action;
        private Inventory inventory;

        private ConfirmMenuHolder(String branchId, String action) {
            this.branchId = branchId;
            this.action = action;
        }

        public void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        @SuppressWarnings("unused")
        public String branchId() {
            return branchId;
        }

        @SuppressWarnings("unused")
        public String action() {
            return action;
        }
    }

    private static final class CreateConfirmHolder implements InventoryHolder {
        private final boolean hasOverlap;
        private Inventory inventory;

        private CreateConfirmHolder(boolean hasOverlap) {
            this.hasOverlap = hasOverlap;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        public void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        public boolean hasOverlap() {
            return hasOverlap;
        }
    }

    private static final class MergeSelectMenuHolder implements InventoryHolder {
        private Inventory inventory;

        public void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class BranchDetailHolder implements InventoryHolder {
        private final String branchId;
        private Inventory inventory;

        private BranchDetailHolder(String branchId) {
            this.branchId = branchId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        public void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        public String branchId() {
            return branchId;
        }
    }

    private static final class ConflictListHolder implements InventoryHolder {
        private final String branchId;
        private final int page;
        private final int totalPages;
        private Inventory inventory;

        private ConflictListHolder(String branchId, int page, int totalPages) {
            this.branchId = branchId;
            this.page = page;
            this.totalPages = totalPages;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        public void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        public String branchId() {
            return branchId;
        }

        public int page() {
            return page;
        }

        @SuppressWarnings("unused")
        public int totalPages() {
            return totalPages;
        }
    }

    private static final class ConflictActionHolder implements InventoryHolder {
        private final String branchId;
        private final int groupIndex;
        private final int returnPage;
        private Inventory inventory;

        private ConflictActionHolder(String branchId, int groupIndex, int returnPage) {
            this.branchId = branchId;
            this.groupIndex = groupIndex;
            this.returnPage = returnPage;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        public void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        public String branchId() {
            return branchId;
        }

        public int groupIndex() {
            return groupIndex;
        }

        public int returnPage() {
            return returnPage;
        }
    }

    private static final class MergeHistoryMenuHolder implements InventoryHolder {
        private final int page;
        private final int totalPages;
        private Inventory inventory;

        private MergeHistoryMenuHolder(int page, int totalPages) {
            this.page = page;
            this.totalPages = totalPages;
        }

        public void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        public int page() {
            return page;
        }

        @SuppressWarnings("unused")
        public int totalPages() {
            return totalPages;
        }
    }

    private static final class MergeHistoryDetailHolder implements InventoryHolder {
        private final String branchId;
        private final int returnPage;
        private Inventory inventory;

        private MergeHistoryDetailHolder(String branchId, int returnPage) {
            this.branchId = branchId;
            this.returnPage = returnPage;
        }

        public void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        public String branchId() {
            return branchId;
        }

        public int returnPage() {
            return returnPage;
        }
    }

    private record PendingSelectionInput(
            boolean firstPoint,
            Location location,
            BlockData previousBlockData,
            Location anchor
    ) {
    }

    private record PendingMergeInput(
            String branchId,
            Location location,
            BlockData previousBlockData
    ) {
    }
}

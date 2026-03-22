package com.worldgit.util;

import com.worldgit.WorldGitPlugin;
import com.worldgit.manager.BranchManager;
import com.worldgit.manager.PlayerSelectionManager;
import com.worldgit.model.Branch;
import com.worldgit.model.BranchStatus;
import com.worldgit.model.QueueEntry;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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

    private static final String ACTION_POS1 = "pos1";
    private static final String ACTION_POS2 = "pos2";
    private static final String ACTION_CREATE = "create";
    private static final String ACTION_QUEUE = "queue";
    private static final String ACTION_CREATE_DISABLED = "create-disabled";
    private static final String ACTION_OPEN_MERGE_SELECT = "open-merge-select";
    private static final String ACTION_OPEN_MERGE_HISTORY = "open-merge-history";
    private static final String ACTION_MAIN_WORLD = "main-world";
    private static final String ACTION_REFRESH = "refresh";
    private static final String ACTION_SUBMIT = "submit";
    private static final String ACTION_MERGE = "merge";
    private static final String ACTION_CANCEL = "cancel";
    private static final String ACTION_REVIEW = "review";
    private static final String ACTION_QUEUE_ENTRY = "queue-entry";
    private static final String ACTION_VIEW_MERGE_RECORD = "view-merge-record";
    private static final String ACTION_HISTORY_PREVIOUS = "history-previous";
    private static final String ACTION_HISTORY_NEXT = "history-next";
    private static final String ACTION_TELEPORT_RECORD_BRANCH = "teleport-record-branch";
    private static final String ACTION_BACK_TO_HISTORY = "back-to-history";

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.SIMPLIFIED_CHINESE);

    private final WorldGitPlugin plugin;
    private final BranchManager branchManager;
    private final ReviewMenuManager reviewMenuManager;
    private final NamespacedKey actionKey;
    private final NamespacedKey branchKey;
    private final NamespacedKey compassKey;
    private final Map<UUID, PendingSelectionInput> pendingSelectionInputs = new ConcurrentHashMap<>();
    private final Map<UUID, PendingMergeInput> pendingMergeInputs = new ConcurrentHashMap<>();
    private BukkitTask menuBookTask;

    public PlayerMenuManager(WorldGitPlugin plugin, BranchManager branchManager, ReviewMenuManager reviewMenuManager) {
        this.plugin = plugin;
        this.branchManager = branchManager;
        this.reviewMenuManager = reviewMenuManager;
        this.actionKey = new NamespacedKey(plugin, "player-menu-action");
        this.branchKey = new NamespacedKey(plugin, "player-menu-branch-id");
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
                player.closeInventory();
                Branch branch = branchManager.createBranch(player);
                MessageUtil.sendSuccess(player, "分支创建成功: " + branch.id());
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
        if (leftClick) {
            requirePermission(player, "worldgit.branch.tp");
            player.closeInventory();
            branchManager.teleportToBranch(player, branch.id());
            MessageUtil.sendSuccess(player, "正在传送到分支: " + branch.id());
            return;
        }
        if (!rightClick) {
            return;
        }
        if (branch.status() == BranchStatus.ACTIVE || branch.status() == BranchStatus.REJECTED) {
            requirePermission(player, "worldgit.branch.submit");
            openConfirmMenu(player, branch, ACTION_SUBMIT);
            return;
        }
        if (branch.status() == BranchStatus.APPROVED) {
            requirePermission(player, "worldgit.branch.confirm");
            openConfirmMenu(player, branch, ACTION_MERGE);
            return;
        }
        if (branch.status() == BranchStatus.SUBMITTED) {
            MessageUtil.sendWarning(player, "该分支正在等待管理员审核。");
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
            default -> {
            }
        }
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

        String title = ACTION_SUBMIT.equals(action) ? "确认提交审核" : "Merge";
        String detail = ACTION_SUBMIT.equals(action)
                ? "提交后会进入管理员审核队列"
                : "确认后将开始把分支内容合并回主世界";

        inventory.setItem(11, createActionItem(
                ACTION_SUBMIT.equals(action) ? Material.LIME_CONCRETE : Material.PURPLE_CONCRETE,
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

    private ItemStack createCreateOrQueueItem(Player player) {
        BranchManager.MenuCreateState state = branchManager.resolveMenuCreateState(player);
        return switch (state.action()) {
            case CREATE -> createActionItem(
                    Material.CRAFTING_TABLE,
                    state.title(),
                    ACTION_CREATE,
                    null,
                    List.of(state.detail(), "要求：已设置 Pos1 和 Pos2")
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
        List<String> lore = new ArrayList<>();
        lore.add("状态：" + statusLabel(branch.status()));
        lore.add("世界：" + branch.worldName());
        lore.add("创建时间：" + TIME_FORMATTER.format(branch.createdAt().atZone(ZoneId.systemDefault())));
        lore.add("左键：传送到该分支");
        if (branch.status() == BranchStatus.ACTIVE || branch.status() == BranchStatus.REJECTED) {
            lore.add("右键：提交审核");
        } else if (branch.status() == BranchStatus.APPROVED) {
            lore.add("右键：确认合并");
        } else if (branch.status() == BranchStatus.SUBMITTED) {
            lore.add("右键：当前正在等待管理员审核");
        }
        if (branch.reviewNote() != null && !branch.reviewNote().isBlank()) {
            lore.add("管理员留言：");
            lore.addAll(wrapLine(branch.reviewNote(), 20));
        }
        return createActionItem(materialFor(branch.status()), "分支 " + shortId(branch.id()), null, branch.id(), lore);
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

    private void requirePermission(Player player, String permission) {
        if (!player.hasPermission(permission)) {
            throw new IllegalStateException("你没有权限执行该操作");
        }
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

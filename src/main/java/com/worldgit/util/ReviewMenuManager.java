package com.worldgit.util;

import com.worldgit.WorldGitPlugin;
import com.worldgit.manager.BranchManager;
import com.worldgit.model.Branch;
import com.worldgit.model.BranchSyncInfo;
import com.worldgit.model.BranchSyncState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.block.sign.Side;
import org.bukkit.util.Vector;

/**
 * 管理员审核 Chest UI 与告示牌驳回输入。
 */
public final class ReviewMenuManager implements Listener {

    private static final String ACTION_TITLE_PREFIX = "审核分支 ";
    private static final String ACTION_BRANCH = "branch";
    private static final String ACTION_TELEPORT = "teleport";
    private static final String ACTION_APPROVE = "approve";
    private static final String ACTION_REJECT = "reject";
    private static final String ACTION_BACK = "back";
    private static final String ACTION_PLAYER_MENU = "player-menu";
    private static final String ACTION_TAB_PENDING = "tab-pending";
    private static final String ACTION_TAB_REREVIEW = "tab-rereview";

    private final WorldGitPlugin plugin;
    private final BranchManager branchManager;
    private PlayerMenuService playerMenuService = PlayerMenuService.noop();
    private final NamespacedKey branchKey;
    private final NamespacedKey actionKey;
    private final Map<UUID, PendingRejectInput> pendingRejectInputs = new ConcurrentHashMap<>();

    public ReviewMenuManager(WorldGitPlugin plugin, BranchManager branchManager) {
        this.plugin = plugin;
        this.branchManager = branchManager;
        this.branchKey = new NamespacedKey(plugin, "review-branch-id");
        this.actionKey = new NamespacedKey(plugin, "review-action");
    }

    public void setPlayerMenuService(PlayerMenuService playerMenuService) {
        this.playerMenuService = playerMenuService == null ? PlayerMenuService.noop() : playerMenuService;
    }

    public void openPendingReviewMenu(Player player) {
        openReviewListMenu(player, false);
    }

    private void openReviewListMenu(Player player, boolean reReviewMode) {
        List<Branch> branches = (reReviewMode ? branchManager.listReReviewBranches() : branchManager.listPendingReviews()).stream()
                .filter(branch -> !reReviewMode || branchManager.getSyncInfo(branch).lastReviewedRevision() != null)
                .filter(branch -> reReviewMode || branchManager.getSyncInfo(branch).lastReviewedRevision() == null)
                .toList();
        ReviewListHolder holder = new ReviewListHolder(reReviewMode);
        Inventory inventory = Bukkit.createInventory(holder, 54, reReviewMode ? "复审列表" : "审核列表");
        holder.setInventory(inventory);

        inventory.setItem(0, createItem(
                reReviewMode ? Material.GRAY_DYE : Material.LIME_CONCRETE,
                "待审核",
                null,
                ACTION_TAB_PENDING,
                List.of("左键：查看首次审核列表")
        ));
        inventory.setItem(1, createItem(
                reReviewMode ? Material.ORANGE_CONCRETE : Material.GRAY_DYE,
                "需复审",
                null,
                ACTION_TAB_REREVIEW,
                List.of("左键：查看主线更新后的复审列表")
        ));

        if (branches.isEmpty()) {
            inventory.setItem(22, createItem(
                    Material.GRAY_STAINED_GLASS_PANE,
                    reReviewMode ? "暂无待复审分支" : "暂无待审核分支",
                    null,
                    null,
                    List.of(reReviewMode ? "当前没有待复审分支" : "当前没有待审核分支")
            ));
        } else {
            int slot = 0;
            for (Branch branch : branches) {
                if (slot >= 45) {
                    break;
                }
                if (slot == 0 || slot == 1) {
                    slot = 9;
                }
                inventory.setItem(slot++, createBranchItem(branch, reReviewMode));
            }
        }
        inventory.setItem(49, createItem(
                Material.BOOK,
                "返回玩家菜单",
                null,
                ACTION_PLAYER_MENU,
                List.of("左键：回到玩家主菜单")
        ));
        player.openInventory(inventory);
    }

    private void openBranchActionMenu(Player player, Branch branch) {
        ReviewActionHolder holder = new ReviewActionHolder(branch.id());
        Inventory inventory = Bukkit.createInventory(holder, 27, ACTION_TITLE_PREFIX + shortId(branch.id()));
        holder.setInventory(inventory);
        BranchSyncInfo syncInfo = branchManager.getSyncInfo(branch);
        inventory.setItem(10, createActionItem(Material.ENDER_PEARL, "传送到分支", branch.id(), ACTION_TELEPORT,
                List.of("左键：传送到该分支世界")));
        inventory.setItem(12, createItem(
                Material.PAPER,
                "同步摘要",
                branch.id(),
                null,
                List.of(
                        "sync：" + syncStateLabel(syncInfo.syncState()),
                        "base/head：" + syncInfo.baseRevision() + " / " + branchManager.currentHeadRevision(branch.mainWorld()),
                        "上次审核基线：" + (syncInfo.lastReviewedRevision() == null ? "暂无" : syncInfo.lastReviewedRevision())
                )
        ));
        inventory.setItem(13, createActionItem(Material.LIME_CONCRETE, "批准分支", branch.id(), ACTION_APPROVE,
                List.of("左键：批准该分支", "通过后玩家可执行确认合并")));
        inventory.setItem(16, createActionItem(Material.RED_CONCRETE, "驳回分支", branch.id(), ACTION_REJECT,
                List.of("右键：打开告示牌输入驳回原因", "输入内容后会发给玩家继续修改")));
        inventory.setItem(22, createActionItem(Material.ARROW, "返回列表", branch.id(), ACTION_BACK,
                List.of("左键：回到待审核列表")));
        player.openInventory(inventory);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory topInventory = event.getView().getTopInventory();
        InventoryHolder holder = topInventory.getHolder();
        if (!(holder instanceof ReviewListHolder) && !(holder instanceof ReviewActionHolder)) {
            return;
        }
        event.setCancelled(true);

        ItemStack item = event.getCurrentItem();
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        String action = meta.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (ACTION_PLAYER_MENU.equals(action) && event.isLeftClick()) {
            playerMenuService.openMainMenu(player);
            return;
        }
        if (ACTION_TAB_PENDING.equals(action) && event.isLeftClick()) {
            openReviewListMenu(player, false);
            return;
        }
        if (ACTION_TAB_REREVIEW.equals(action) && event.isLeftClick()) {
            openReviewListMenu(player, true);
            return;
        }
        String branchId = meta.getPersistentDataContainer().get(branchKey, PersistentDataType.STRING);
        if (branchId == null || branchId.isBlank()) {
            return;
        }
        Branch branch = branchManager.requireBranch(branchId);

        if (holder instanceof ReviewListHolder) {
            if (event.isLeftClick()) {
                if (!branch.worldName().equals(player.getWorld().getName())) {
                    branchManager.teleportToBranch(player, branch.id());
                    MessageUtil.sendInfo(player, "已传送到待审核分支，打开操作面板。");
                } else {
                    MessageUtil.sendInfo(player, "已打开当前分支的审核面板。");
                }
                openBranchActionMenu(player, branch);
            }
            return;
        }

        if (ACTION_TELEPORT.equals(action) && event.isLeftClick()) {
            branchManager.teleportToBranch(player, branch.id());
            MessageUtil.sendSuccess(player, "已传送到分支: " + branch.id());
            return;
        }
        if (ACTION_APPROVE.equals(action) && event.isLeftClick()) {
            branchManager.approveBranch(player, branch.id(), "管理员通过了审核");
            MessageUtil.sendSuccess(player, "已批准分支: " + branch.id());
            openPendingReviewMenu(player);
            return;
        }
        if (ACTION_REJECT.equals(action) && event.isRightClick()) {
            player.closeInventory();
            openRejectInput(player, branch.id());
            return;
        }
        if (ACTION_BACK.equals(action) && event.isLeftClick()) {
            openReviewListMenu(player, false);
            return;
        }
        MessageUtil.sendWarning(player, "该操作方式无效，请按物品提示点击。");
    }

    @EventHandler
    public void onSignChange(SignChangeEvent event) {
        PendingRejectInput pending = pendingRejectInputs.remove(event.getPlayer().getUniqueId());
        if (pending == null) {
            return;
        }
        if (!sameBlock(pending.location(), event.getBlock().getLocation())) {
            pendingRejectInputs.put(event.getPlayer().getUniqueId(), pending);
            return;
        }

        String note = extractRejectMessage(event.getLines());
        restoreTemporarySign(pending);

        if (note.isBlank() || "输入驳回原因".equals(note)) {
            MessageUtil.sendWarning(event.getPlayer(), "未填写驳回原因，本次操作已取消。");
            Bukkit.getScheduler().runTask(plugin, () -> openBranchActionMenu(event.getPlayer(), branchManager.requireBranch(pending.branchId())));
            return;
        }

        branchManager.rejectBranch(event.getPlayer(), pending.branchId(), note);
        MessageUtil.sendSuccess(event.getPlayer(), "已驳回分支: " + pending.branchId());
        Bukkit.getScheduler().runTask(plugin, () -> openPendingReviewMenu(event.getPlayer()));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        PendingRejectInput pending = pendingRejectInputs.remove(event.getPlayer().getUniqueId());
        if (pending != null) {
            restoreTemporarySign(pending);
        }
    }

    private void openRejectInput(Player player, String branchId) {
        Location base = createTemporarySignLocation(player);
        Block block = base.getBlock();
        BlockData previousBlockData = block.getBlockData().clone();
        block.setType(Material.OAK_SIGN, false);

        if (!(block.getState() instanceof Sign sign)) {
            block.setBlockData(previousBlockData, false);
            throw new IllegalStateException("无法创建告示牌输入框，请站在更开阔的位置重试。");
        }

        sign.getSide(Side.FRONT).line(0, Component.text("输入驳回原因"));
        sign.getSide(Side.FRONT).line(1, Component.empty());
        sign.getSide(Side.FRONT).line(2, Component.empty());
        sign.getSide(Side.FRONT).line(3, Component.empty());
        sign.setWaxed(false);
        sign.setAllowedEditorUniqueId(player.getUniqueId());
        sign.update(true, false);

        pendingRejectInputs.put(player.getUniqueId(), new PendingRejectInput(branchId, base, previousBlockData));
        MessageUtil.sendInfo(player, "请在告示牌中输入驳回原因，提交后会发给玩家。");
        player.openSign(sign, Side.FRONT);
    }

    private void restoreTemporarySign(PendingRejectInput pending) {
        Bukkit.getScheduler().runTask(plugin, () -> pending.location().getBlock().setBlockData(pending.previousBlockData(), false));
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

    private String extractRejectMessage(String[] lines) {
        List<String> content = new ArrayList<>();
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                content.add(trimmed);
            }
        }
        return String.join(" ", content).trim();
    }

    private boolean sameBlock(Location left, Location right) {
        return left.getWorld() != null
                && right.getWorld() != null
                && left.getWorld().equals(right.getWorld())
                && left.getBlockX() == right.getBlockX()
                && left.getBlockY() == right.getBlockY()
                && left.getBlockZ() == right.getBlockZ();
    }

    private ItemStack createBranchItem(Branch branch, boolean reReviewMode) {
        BranchSyncInfo syncInfo = branchManager.getSyncInfo(branch);
        return createItem(
                reReviewMode ? Material.ORANGE_CONCRETE : Material.CHEST,
                (reReviewMode ? "待复审分支 " : "待审核分支 ") + shortId(branch.id()),
                branch.id(),
                ACTION_BRANCH,
                List.of(
                        "所有者：" + branch.ownerName(),
                        "世界：" + branch.worldName(),
                        "sync：" + syncStateLabel(syncInfo.syncState()),
                        "base/head：" + syncInfo.baseRevision() + " / " + branchManager.currentHeadRevision(branch.mainWorld()),
                        "左键：打开审核面板",
                        reReviewMode ? "该分支曾通过审核，因主线变化需要复审" : "若你不在该分支内，会先自动传送"
                )
        );
    }

    private ItemStack createActionItem(Material material, String title, String branchId, String action, List<String> lore) {
        return createItem(material, title, branchId, action, lore);
    }

    private ItemStack createItem(Material material, String title, String branchId, String action, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MessageUtil.mini("<gradient:#fbbf24:#fb7185><bold>" + MessageUtil.escape(title) + "</bold></gradient>"));
        List<Component> loreLines = new ArrayList<>();
        for (String line : lore) {
            loreLines.add(MessageUtil.mini("<gray>" + MessageUtil.escape(line) + "</gray>"));
        }
        meta.lore(loreLines);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        if (branchId != null && !branchId.isBlank()) {
            meta.getPersistentDataContainer().set(branchKey, PersistentDataType.STRING, branchId);
        }
        if (action != null && !action.isBlank()) {
            meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        }
        item.setItemMeta(meta);
        return item;
    }

    private String shortId(String branchId) {
        return branchId.length() <= 8 ? branchId : branchId.substring(0, 8);
    }

    private String syncStateLabel(BranchSyncState state) {
        return switch (state) {
            case CLEAN -> "已同步";
            case NEEDS_REBASE -> "需要 Rebase";
            case REBASING -> "Rebasing 中";
            case HAS_CONFLICTS -> "存在冲突";
        };
    }

    private record PendingRejectInput(String branchId, Location location, BlockData previousBlockData) {
    }

    private static final class ReviewListHolder implements InventoryHolder {

        private final boolean reReviewMode;

        private Inventory inventory;

        private ReviewListHolder(boolean reReviewMode) {
            this.reReviewMode = reReviewMode;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @SuppressWarnings("unused")
        private boolean reReviewMode() {
            return reReviewMode;
        }
    }

    private static final class ReviewActionHolder implements InventoryHolder {

        private final String branchId;
        private Inventory inventory;

        private ReviewActionHolder(String branchId) {
            this.branchId = branchId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @SuppressWarnings("unused")
        private String branchId() {
            return branchId;
        }
    }
}

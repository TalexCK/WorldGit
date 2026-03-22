package com.worldgit.manager;

import com.worldgit.WorldGitPlugin;
import com.worldgit.config.PluginConfig;
import com.worldgit.database.BranchRepository;
import com.worldgit.model.Branch;
import com.worldgit.model.BranchStatus;
import com.worldgit.model.QueueEntry;
import com.worldgit.model.RegionLock;
import com.worldgit.util.MessageUtil;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

public final class BranchManager {

    private final WorldGitPlugin plugin;
    private final PluginConfig pluginConfig;
    private final BranchRepository branchRepository;
    private final LockManager lockManager;
    private final QueueManager queueManager;
    private final MergeManager mergeManager;
    private final WorldManager worldManager;
    private final RegionCopyManager regionCopyManager;
    private final PlayerSelectionManager selectionManager;
    private final ProtectionManager protectionManager;
    private final ConcurrentHashMap<String, Branch> branchesByWorld = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<UUID>> invitedPlayersByBranch = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Location> lastMainWorldLocations = new ConcurrentHashMap<>();

    public BranchManager(
            WorldGitPlugin plugin,
            PluginConfig pluginConfig,
            BranchRepository branchRepository,
            LockManager lockManager,
            QueueManager queueManager,
            MergeManager mergeManager,
            WorldManager worldManager,
            RegionCopyManager regionCopyManager,
            PlayerSelectionManager selectionManager,
            ProtectionManager protectionManager
    ) {
        this.plugin = plugin;
        this.pluginConfig = pluginConfig;
        this.branchRepository = branchRepository;
        this.lockManager = lockManager;
        this.queueManager = queueManager;
        this.mergeManager = mergeManager;
        this.worldManager = worldManager;
        this.regionCopyManager = regionCopyManager;
        this.selectionManager = selectionManager;
        this.protectionManager = protectionManager;
    }

    public Branch createBranch(Player player) {
        return createBranchForOwner(player, player.getUniqueId(), player.getName(), player);
    }

    public Branch createAssignedBranch(Player selector, Player owner) {
        return createBranchForOwner(selector, owner.getUniqueId(), owner.getName(), owner);
    }

    public void queueSelection(Player player) {
        RegionCopyManager.SelectionBounds selection = readSelection(player);
        List<RegionLock> conflicts = lockManager.findConflicts(
                player.getWorld().getName(),
                selection.minX(),
                selection.minY(),
                selection.minZ(),
                selection.maxX(),
                selection.maxY(),
                selection.maxZ()
        );
        if (conflicts.isEmpty()) {
            throw new IllegalStateException("所选区域当前未被锁定，无需排队");
        }
        queueManager.enqueue(player, selection);
    }

    private Branch createBranchForOwner(Player selector, UUID ownerUuid, String ownerName, Player teleportTarget) {
        ensureInMainWorld(selector, "只能在主世界创建分支");

        long activeCount = branchRepository.countActiveBranches(ownerUuid);
        if (activeCount >= pluginConfig.maxActiveBranches()) {
            throw new IllegalStateException("目标玩家的活跃分支已达上限");
        }

        RegionCopyManager.SelectionBounds selection = readSelection(selector);
        List<RegionLock> conflicts = lockManager.findConflicts(
                selector.getWorld().getName(),
                selection.minX(),
                selection.minY(),
                selection.minZ(),
                selection.maxX(),
                selection.maxY(),
                selection.maxZ()
        );
        if (!conflicts.isEmpty()) {
            throw new IllegalStateException("所选区域已被锁定，可使用 /wg queue 排队");
        }

        return createBranchFromBounds(
                ownerUuid,
                ownerName,
                selector.getWorld(),
                selection,
                teleportTarget,
                selector.getWorld().getSpawnLocation()
        );
    }

    public PlayerSelectionManager.SelectionSnapshot setSelectionPos1(Player player, Integer x, Integer y, Integer z) {
        ensureInMainWorld(player, "只能在主世界设置选区");
        return selectionManager.setPos1(player, x, y, z);
    }

    public PlayerSelectionManager.SelectionSnapshot setSelectionPos2(Player player, Integer x, Integer y, Integer z) {
        ensureInMainWorld(player, "只能在主世界设置选区");
        return selectionManager.setPos2(player, x, y, z);
    }

    public Optional<PlayerSelectionManager.SelectionSnapshot> getSelection(Player player) {
        return selectionManager.getSelection(player);
    }

    public Optional<QueueEntry> getQueuedSelection(Player player) {
        if (player == null) {
            return Optional.empty();
        }
        return queueManager.findByPlayer(player.getUniqueId());
    }

    public MenuCreateState resolveMenuCreateState(Player player) {
        if (player == null || player.getWorld() == null) {
            return new MenuCreateState(
                    MenuCreateAction.DISABLED,
                    "无法创建分支",
                    "当前世界不可用，请稍后重试。"
            );
        }
        if (!protectionManager.isMainWorld(player.getWorld())) {
            return new MenuCreateState(
                    MenuCreateAction.DISABLED,
                    "返回主世界后操作",
                    "只能在 main world 中创建分支或排队。"
            );
        }
        if (getQueuedSelection(player).isPresent()) {
            return new MenuCreateState(
                    MenuCreateAction.DISABLED,
                    "已有排队项",
                    "请在等待区左键尝试创建，或右键删除排队。"
            );
        }
        PlayerSelectionManager.SelectionSnapshot snapshot = selectionManager.getSelection(player).orElse(null);
        if (snapshot == null || !snapshot.complete()) {
            return new MenuCreateState(
                    MenuCreateAction.DISABLED,
                    "未完成选区",
                    "请先设置 Pos1 和 Pos2。"
            );
        }
        if (!player.getWorld().getName().equals(snapshot.worldName())) {
            return new MenuCreateState(
                    MenuCreateAction.DISABLED,
                    "选区世界不匹配",
                    "请回到设置选区的世界重新操作。"
            );
        }

        RegionCopyManager.SelectionBounds bounds;
        try {
            bounds = snapshot.toBounds(player.getWorld(), pluginConfig.useFullHeight());
            regionCopyManager.validate(bounds);
        } catch (IllegalStateException exception) {
            return new MenuCreateState(
                    MenuCreateAction.DISABLED,
                    "选区不可用",
                    exception.getMessage()
            );
        }

        List<RegionLock> conflicts = lockManager.findConflicts(
                player.getWorld().getName(),
                bounds.minX(),
                bounds.minY(),
                bounds.minZ(),
                bounds.maxX(),
                bounds.maxY(),
                bounds.maxZ()
        );
        if (!conflicts.isEmpty()) {
            return new MenuCreateState(
                    MenuCreateAction.QUEUE,
                    "加入排队",
                    "当前选区已被锁定，点击后加入排队队列。"
            );
        }

        return new MenuCreateState(
                MenuCreateAction.CREATE,
                "创建分支",
                "当前选区可用，点击后立即创建分支。"
        );
    }

    public void clearSelection(Player player) {
        selectionManager.clear(player);
    }

    public Branch createBranchFromQueue(Player player) {
        QueueEntry entry = getQueuedSelection(player)
                .orElseThrow(() -> new IllegalStateException("你当前没有排队中的区域"));
        World sourceWorld = Bukkit.getWorld(entry.mainWorld());
        if (sourceWorld == null) {
            throw new IllegalStateException("排队对应的主世界不存在: " + entry.mainWorld());
        }

        long activeCount = branchRepository.countActiveBranches(player.getUniqueId());
        if (activeCount >= pluginConfig.maxActiveBranches()) {
            throw new IllegalStateException("你的活跃分支已达上限");
        }

        RegionCopyManager.SelectionBounds selection = new RegionCopyManager.SelectionBounds(
                requireQueueCoordinate(entry.minX(), "minX"),
                requireQueueCoordinate(entry.minY(), "minY"),
                requireQueueCoordinate(entry.minZ(), "minZ"),
                requireQueueCoordinate(entry.maxX(), "maxX"),
                requireQueueCoordinate(entry.maxY(), "maxY"),
                requireQueueCoordinate(entry.maxZ(), "maxZ")
        );
        regionCopyManager.validate(selection);

        List<RegionLock> conflicts = lockManager.findConflicts(
                sourceWorld.getName(),
                selection.minX(),
                selection.minY(),
                selection.minZ(),
                selection.maxX(),
                selection.maxY(),
                selection.maxZ()
        );
        if (!conflicts.isEmpty()) {
            throw new IllegalStateException("该排队区域仍被锁定，请稍后再试");
        }

        Branch branch = createBranchFromBounds(
                player.getUniqueId(),
                player.getName(),
                sourceWorld,
                selection,
                player,
                sourceWorld.getSpawnLocation()
        );
        queueManager.clearPlayer(player.getUniqueId());
        return branch;
    }

    public void removeQueuedSelection(Player player) {
        QueueEntry entry = getQueuedSelection(player)
                .orElseThrow(() -> new IllegalStateException("你当前没有排队中的区域"));
        queueManager.clearPlayer(player.getUniqueId());
        MessageUtil.sendSuccess(player, "已删除排队区域: (" + entry.minX() + ", " + entry.minY() + ", " + entry.minZ()
                + ") -> (" + entry.maxX() + ", " + entry.maxY() + ", " + entry.maxZ() + ")");
    }

    public List<Branch> listOwnBranches(Player player) {
        return branchRepository.findByOwnerUnchecked(player.getUniqueId());
    }

    public List<Branch> listAllBranches() {
        return branchRepository.findAll();
    }

    public List<Branch> listPendingReviews() {
        return branchRepository.findSubmitted();
    }

    public List<Branch> listOwnApprovedBranches(Player player) {
        return listOwnBranches(player).stream()
                .filter(branch -> branch.status() == BranchStatus.APPROVED)
                .sorted((left, right) -> right.createdAt().compareTo(left.createdAt()))
                .toList();
    }

    public List<Branch> listOwnMergedBranches(Player player) {
        return listOwnBranches(player).stream()
                .filter(branch -> branch.status() == BranchStatus.MERGED)
                .sorted((left, right) -> {
                    Instant leftMergedAt = left.mergedAt() == null ? Instant.EPOCH : left.mergedAt();
                    Instant rightMergedAt = right.mergedAt() == null ? Instant.EPOCH : right.mergedAt();
                    return rightMergedAt.compareTo(leftMergedAt);
                })
                .toList();
    }

    public List<String> listBuilderNames(Branch branch) {
        Set<String> names = ConcurrentHashMap.newKeySet();
        names.add(branch.ownerName());
        for (var invite : branchRepository.listInvites(branch.id())) {
            String name = Bukkit.getOfflinePlayer(invite.playerUuid()).getName();
            names.add(name == null || name.isBlank() ? shortUuid(invite.playerUuid()) : name);
        }
        return names.stream().sorted().toList();
    }

    public Branch requireBranch(String branchId) {
        return branchRepository.findByIdUnchecked(branchId)
                .orElseThrow(() -> new IllegalStateException("分支不存在: " + branchId));
    }

    public Branch resolveOwnedBranch(Player player, String branchId) {
        if (branchId == null || branchId.isBlank()) {
            return branchRepository.findLatestOwnedActiveBranch(player.getUniqueId())
                    .orElseThrow(() -> new IllegalStateException("你当前没有可操作的分支"));
        }
        Branch branch = requireBranch(branchId);
        ensureOwner(branch, player);
        return branch;
    }

    public void submitBranch(Player player, String branchId) {
        Branch branch = resolveOwnedBranch(player, branchId);
        if (branch.status() != BranchStatus.ACTIVE && branch.status() != BranchStatus.REJECTED) {
            throw new IllegalStateException("当前状态不允许提交审核");
        }
        branchRepository.markSubmitted(branch.id(), Instant.now().getEpochSecond());
        refreshCachedBranch(branch.id());
        notifyAdmins("分支已提交审核: " + branch.id() + " by " + player.getName());
    }

    public void approveBranch(Player admin, String branchId, String note) {
        Branch branch = requireBranch(branchId);
        if (branch.status() != BranchStatus.SUBMITTED) {
            throw new IllegalStateException("只有待审核分支可以批准");
        }
        branchRepository.markReviewed(branch.id(), BranchStatus.APPROVED, admin.getUniqueId(), Instant.now().getEpochSecond(), note);
        refreshCachedBranch(branch.id());
        Player owner = Bukkit.getPlayer(branch.ownerUuid());
        if (owner != null && owner.isOnline()) {
            MessageUtil.sendSuccess(owner, "你的分支已通过审核，可执行 /wg confirm；若继续修改，需要重新提交审核。");
        }
    }

    public void rejectBranch(Player admin, String branchId, String note) {
        if (note == null || note.isBlank()) {
            throw new IllegalStateException("拒绝分支时必须提供原因");
        }
        Branch branch = requireBranch(branchId);
        if (branch.status() != BranchStatus.SUBMITTED) {
            throw new IllegalStateException("只有待审核分支可以拒绝");
        }
        branchRepository.markReviewed(branch.id(), BranchStatus.REJECTED, admin.getUniqueId(), Instant.now().getEpochSecond(), note);
        refreshCachedBranch(branch.id());
        Player owner = Bukkit.getPlayer(branch.ownerUuid());
        if (owner != null && owner.isOnline()) {
            MessageUtil.sendError(owner, "你的分支被驳回: " + note);
        }
    }

    public void confirmBranch(Player player, String branchId) {
        confirmBranch(player, branchId, "未填写合并说明");
    }

    public void confirmBranch(Player player, String branchId, String mergeMessage) {
        Branch branch = resolveOwnedApprovedBranch(player, branchId);
        mergeManager.confirmMerge(player, branch, mergeMessage);
    }

    public Branch forceEditBranch(Player player, String branchId) {
        Branch branch = resolveForceEditableApprovedBranch(player, branchId);
        if (!branchRepository.reopenApprovedBranchUnchecked(branch.id())) {
            throw new IllegalStateException("该分支当前不处于已审核通过状态");
        }
        refreshCachedBranch(branch.id());

        Player owner = Bukkit.getPlayer(branch.ownerUuid());
        if (owner != null && owner.isOnline() && !owner.getUniqueId().equals(player.getUniqueId())) {
            MessageUtil.sendWarning(owner, "分支 " + branch.id() + " 已被协作者切回“正在修改”，需要重新提交审核。");
        }
        return requireBranch(branch.id());
    }

    public void abandonBranch(Player player, String branchId) {
        Branch branch = resolveOwnedBranch(player, branchId);
        closeBranch(branch, BranchStatus.ABANDONED, Instant.now().getEpochSecond(), "用户主动放弃");
    }

    public void forceCloseBranch(String branchId, String note) {
        Branch branch = requireBranch(branchId);
        closeBranch(branch, BranchStatus.ABANDONED, Instant.now().getEpochSecond(), note == null ? "管理员关闭" : note);
    }

    public void teleportToBranch(Player player, String branchId) {
        Branch branch = resolveAccessibleBranch(player, branchId);
        World world = worldManager.createBranchWorld(branch.worldName());
        rememberMainWorldLocation(player);
        player.teleportAsync(worldManager.createBranchSpawn(
                world,
                branch.minX(),
                branch.maxX(),
                branch.minY(),
                branch.maxY(),
                branch.minZ(),
                branch.maxZ()
        ));
    }

    public void returnToMainWorld(Player player) {
        Location location = lastMainWorldLocations.get(player.getUniqueId());
        if (location == null || location.getWorld() == null || !pluginConfig.mainWorld().equals(location.getWorld().getName())) {
            location = worldManager.getMainWorld().getSpawnLocation();
        } else {
            location = location.clone();
        }
        player.teleportAsync(location);
    }

    public void forgetCachedPlayerState(Player player) {
        if (player == null) {
            return;
        }
        lastMainWorldLocations.remove(player.getUniqueId());
    }

    public void invitePlayer(Player inviter, Player invited, String branchId) {
        Branch branch = resolveOwnedBranch(inviter, branchId);
        branchRepository.addInvite(branch.id(), invited.getUniqueId(), inviter.getUniqueId(), Instant.now().getEpochSecond());
        invitedPlayersByBranch
                .computeIfAbsent(branch.id(), ignored -> ConcurrentHashMap.newKeySet())
                .add(invited.getUniqueId());
        MessageUtil.sendSuccess(invited, "你被邀请进入分支: " + branch.id());
    }

    public void uninvitePlayer(Player inviter, UUID invitedUuid, String branchId) {
        Branch branch = resolveOwnedBranch(inviter, branchId);
        branchRepository.removeInvite(branch.id(), invitedUuid);
        invitedPlayersByBranch.computeIfPresent(branch.id(), (ignored, players) -> {
            players.remove(invitedUuid);
            return players.isEmpty() ? null : players;
        });
    }

    public Optional<Branch> findByWorld(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return Optional.empty();
        }
        Branch cached = branchesByWorld.get(worldName);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<Branch> branch = branchRepository.findByWorldNameUnchecked(worldName);
        branch.ifPresent(value -> branchesByWorld.put(worldName, value));
        return branch;
    }

    public boolean canAccessBranch(Player player, Branch branch) {
        return canModifyBranch(player, branch)
                || player.hasPermission("worldgit.admin.review")
                || player.hasPermission("worldgit.admin.bypass");
    }

    public boolean canModifyBranch(Player player, Branch branch) {
        return isOwner(branch, player) || isInvited(branch, player.getUniqueId());
    }

    public boolean isOwner(Branch branch, Player player) {
        return branch.ownerUuid().equals(player.getUniqueId());
    }

    private Branch resolveAccessibleBranch(Player player, String branchId) {
        Branch branch = requireBranch(branchId);
        if (!canAccessBranch(player, branch)) {
            throw new IllegalStateException("你无权访问该分支");
        }
        return branch;
    }

    private Branch resolveOwnedApprovedBranch(Player player, String branchId) {
        if (branchId == null || branchId.isBlank()) {
            return listOwnApprovedBranches(player).stream()
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("你当前没有待确认合并的已批准分支"));
        }
        Branch branch = requireBranch(branchId);
        ensureOwner(branch, player);
        if (branch.status() != BranchStatus.APPROVED) {
            throw new IllegalStateException("只有已批准的分支才能确认合并");
        }
        return branch;
    }

    private Branch resolveForceEditableApprovedBranch(Player player, String branchId) {
        if (branchId != null && !branchId.isBlank()) {
            Branch branch = requireBranch(branchId);
            ensureCanForceEdit(branch, player);
            return branch;
        }

        World currentWorld = player.getWorld();
        if (currentWorld != null && worldManager.isBranchWorld(currentWorld)) {
            Branch currentBranch = findByWorld(currentWorld.getName()).orElse(null);
            if (currentBranch != null && canModifyBranch(player, currentBranch) && currentBranch.status() == BranchStatus.APPROVED) {
                return currentBranch;
            }
        }

        return listOwnApprovedBranches(player).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("你当前没有可切回编辑状态的已批准分支"));
    }

    private void ensureCanForceEdit(Branch branch, Player player) {
        if (!canModifyBranch(player, branch)) {
            throw new IllegalStateException("你无权将该分支切回编辑状态");
        }
        if (branch.status() != BranchStatus.APPROVED) {
            throw new IllegalStateException("只有已批准的分支才能切回编辑状态");
        }
    }

    private void ensureOwner(Branch branch, Player player) {
        if (!branch.ownerUuid().equals(player.getUniqueId())) {
            throw new IllegalStateException("只能操作自己的分支");
        }
    }

    private void closeBranch(Branch branch, BranchStatus finalStatus, long closedAt, String note) {
        Location fallback = worldManager.createReturnLocation(branch.minX(), branch.maxX(), branch.minZ(), branch.maxZ());
        if (!worldManager.unloadWorld(branch.worldName(), fallback)) {
            throw new IllegalStateException("无法卸载分支世界: " + branch.worldName());
        }
        lockManager.unlockBranch(branch.id());
        queueManager.notifyRegionUnlocked(
                branch.mainWorld(),
                branch.minX(),
                branch.minY(),
                branch.minZ(),
                branch.maxX(),
                branch.maxY(),
                branch.maxZ()
        );
        branchRepository.markClosed(branch.id(), finalStatus, closedAt, note);
    }

    private void notifyAdmins(String message) {
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            if (onlinePlayer.hasPermission("worldgit.admin.review")) {
                MessageUtil.sendInfo(onlinePlayer, message);
            }
        }
    }

    private void refreshCachedBranch(String branchId) {
        branchRepository.findByIdUnchecked(branchId).ifPresent(branch -> branchesByWorld.put(branch.worldName(), branch));
    }

    private Branch createBranchFromBounds(
            UUID ownerUuid,
            String ownerName,
            World sourceWorld,
            RegionCopyManager.SelectionBounds selection,
            Player teleportTarget,
            Location failureFallback
    ) {
        String branchId = UUID.randomUUID().toString().replace("-", "");
        String worldName = worldManager.createBranchWorldName(branchId);
        Branch branch = new Branch(
                branchId,
                ownerUuid,
                ownerName,
                worldName,
                sourceWorld.getName(),
                selection.minX(),
                selection.minY(),
                selection.minZ(),
                selection.maxX(),
                selection.maxY(),
                selection.maxZ(),
                BranchStatus.ACTIVE,
                Instant.now(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        branchRepository.insert(branch);
        World branchWorld = null;
        try {
            lockManager.createLock(
                    branch.id(),
                    branch.mainWorld(),
                    branch.minX(),
                    branch.minY(),
                    branch.minZ(),
                    branch.maxX(),
                    branch.maxY(),
                    branch.maxZ()
            );

            branchWorld = worldManager.createBranchWorld(branch.worldName());
            regionCopyManager.copyRegion(sourceWorld, branchWorld, regionCopyManager.expandForCopy(sourceWorld, selection));
        } catch (RuntimeException exception) {
            cleanupFailedBranchCreation(branch, failureFallback, branchWorld);
            throw exception;
        }
        branchesByWorld.put(branch.worldName(), branch);
        rememberMainWorldLocation(teleportTarget);
        teleportTarget.teleportAsync(worldManager.createBranchSpawn(
                branchWorld,
                branch.minX(),
                branch.maxX(),
                branch.minY(),
                branch.maxY(),
                branch.minZ(),
                branch.maxZ()
        ));
        return branch;
    }

    private void cleanupFailedBranchCreation(Branch branch, Location fallbackLocation, World branchWorld) {
        try {
            lockManager.unlockBranch(branch.id());
        } catch (RuntimeException ignored) {
            // 清理阶段尽力而为，保留原始异常。
        }
        try {
            if (branchWorld != null || Bukkit.getWorld(branch.worldName()) != null) {
                worldManager.deleteWorld(branch.worldName(), fallbackLocation);
            }
        } catch (RuntimeException ignored) {
            // 清理阶段尽力而为，保留原始异常。
        }
        branchesByWorld.remove(branch.worldName());
        invitedPlayersByBranch.remove(branch.id());
        try {
            branchRepository.deleteByIdQuietly(branch.id());
        } catch (RuntimeException ignored) {
            // 清理阶段尽力而为，保留原始异常。
        }
    }

    private void rememberMainWorldLocation(Player player) {
        if (player == null || player.getWorld() == null) {
            return;
        }
        if (!pluginConfig.mainWorld().equals(player.getWorld().getName())) {
            return;
        }
        lastMainWorldLocations.put(player.getUniqueId(), player.getLocation().clone());
    }

    private int requireQueueCoordinate(Integer coordinate, String name) {
        if (coordinate == null) {
            throw new IllegalStateException("排队区域坐标缺失: " + name);
        }
        return coordinate;
    }

    private RegionCopyManager.SelectionBounds readSelection(Player player) {
        ensureInMainWorld(player, "只能在主世界操作区域");
        RegionCopyManager.SelectionBounds bounds = selectionManager.requireSelection(player, pluginConfig.useFullHeight());
        regionCopyManager.validate(bounds);
        return bounds;
    }

    private void ensureInMainWorld(Player player, String message) {
        if (!protectionManager.isMainWorld(player.getWorld())) {
            throw new IllegalStateException(message);
        }
    }

    private boolean isInvited(Branch branch, UUID playerUuid) {
        Set<UUID> invitedPlayers = invitedPlayersByBranch.get(branch.id());
        if (invitedPlayers != null && invitedPlayers.contains(playerUuid)) {
            return true;
        }
        boolean invited = branchRepository.isInvited(branch.id(), playerUuid);
        if (invited) {
            invitedPlayersByBranch
                    .computeIfAbsent(branch.id(), ignored -> ConcurrentHashMap.newKeySet())
                    .add(playerUuid);
        }
        return invited;
    }

    private String shortUuid(UUID uuid) {
        String value = uuid.toString().replace("-", "");
        return value.substring(0, Math.min(8, value.length()));
    }

    public enum MenuCreateAction {
        CREATE,
        QUEUE,
        DISABLED
    }

    public record MenuCreateState(MenuCreateAction action, String title, String detail) {
    }
}

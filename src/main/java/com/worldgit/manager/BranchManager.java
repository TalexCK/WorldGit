package com.worldgit.manager;

import com.worldgit.WorldGitPlugin;
import com.worldgit.config.PluginConfig;
import com.worldgit.database.BranchRepository;
import com.worldgit.model.Branch;
import com.worldgit.model.BranchInviteRequest;
import com.worldgit.model.BranchSyncInfo;
import com.worldgit.model.BranchStatus;
import com.worldgit.model.ConflictGroup;
import com.worldgit.model.QueueEntry;
import com.worldgit.model.RegionLock;
import com.worldgit.model.WorldCommit;
import com.worldgit.util.BranchDisplayUtil;
import com.worldgit.util.MessageUtil;
import org.bukkit.command.CommandSender;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
    private final RebaseManager rebaseManager;
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
            RebaseManager rebaseManager,
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
        this.rebaseManager = rebaseManager;
        this.worldManager = worldManager;
        this.regionCopyManager = regionCopyManager;
        this.selectionManager = selectionManager;
        this.protectionManager = protectionManager;
    }

    public Branch createBranch(Player player) {
        return createBranch(player, null);
    }

    public Branch createBranch(Player player, String label) {
        CreateBranchPreview preview = previewCreateBranch(player);
        if (preview.hasOverlap()) {
            throw new IllegalStateException("当前选区与已有未合并编辑区重叠，请先通过玩家面板确认后再创建。");
        }
        return createBranchConfirmed(player, preview.selection(), label);
    }

    public Branch createAssignedBranch(Player selector, Player owner) {
        RegionCopyManager.SelectionBounds selection = readSelection(selector);
        return createBranchForOwner(selector, owner.getUniqueId(), owner.getName(), null, owner, selection);
    }

    public void queueSelection(Player player) {
        throw new IllegalStateException("1.1.0 已取消编辑期排队；当前允许同一区域并发创建分支。");
    }

    public CreateBranchPreview previewCreateBranch(Player player) {
        ensureInMainWorld(player, "只能在主世界创建分支");

        long activeCount = branchRepository.countActiveBranches(player.getUniqueId());
        if (activeCount >= pluginConfig.maxActiveBranches()) {
            throw new IllegalStateException("你的活跃分支已达上限");
        }

        RegionCopyManager.SelectionBounds selection = readSelection(player);
        List<SelectionOverlap> overlaps = new ArrayList<>();
        for (Branch branch : listEditingBranches(player.getWorld().getName())) {
            RegionCopyManager.SelectionBounds overlapBounds = intersect(selection, editableBounds(branch));
            if (overlapBounds == null) {
                continue;
            }
            overlaps.add(new SelectionOverlap(
                    branch.id(),
                    branch.ownerName(),
                    branch.label(),
                    branch.status(),
                    editableBounds(branch),
                    overlapBounds,
                    selectionVolume(overlapBounds)
            ));
        }
        overlaps.sort(Comparator.comparingLong(SelectionOverlap::overlapBlockCount).reversed());

        long totalBlockCount = selectionVolume(selection);
        long overlapBlockCount = unionVolume(overlaps.stream()
                .map(SelectionOverlap::overlapBounds)
                .toList());

        return new CreateBranchPreview(
                selection,
                totalBlockCount,
                overlapBlockCount,
                totalBlockCount == 0L ? 0.0D : (double) overlapBlockCount / (double) totalBlockCount,
                overlaps
        );
    }

    public Branch createBranchConfirmed(Player player, RegionCopyManager.SelectionBounds selection) {
        return createBranchConfirmed(player, selection, null);
    }

    public Branch createBranchConfirmed(Player player, RegionCopyManager.SelectionBounds selection, String label) {
        ensureInMainWorld(player, "只能在主世界创建分支");
        if (selection == null) {
            throw new IllegalStateException("确认创建失败：缺少选区信息。");
        }

        long activeCount = branchRepository.countActiveBranches(player.getUniqueId());
        if (activeCount >= pluginConfig.maxActiveBranches()) {
            throw new IllegalStateException("你的活跃分支已达上限");
        }

        regionCopyManager.validate(selection);
        return createBranchForOwner(player, player.getUniqueId(), player.getName(), normalizeOptionalBranchLabel(label), player, selection);
    }

    public List<Branch> listEditingBranches() {
        return branchRepository.findAll().stream()
                .filter(Branch::hasRegion)
                .filter(this::isEditingBranch)
                .sorted(Comparator.comparing(Branch::createdAt))
                .toList();
    }

    public List<Branch> listEditingBranches(String mainWorld) {
        return listEditingBranches().stream()
                .filter(branch -> branch.mainWorld().equals(mainWorld))
                .toList();
    }

    private Branch createBranchForOwner(
            Player selector,
            UUID ownerUuid,
            String ownerName,
            String label,
            Player teleportTarget,
            RegionCopyManager.SelectionBounds selection
    ) {
        ensureInMainWorld(selector, "只能在主世界创建分支");

        long activeCount = branchRepository.countActiveBranches(ownerUuid);
        if (activeCount >= pluginConfig.maxActiveBranches()) {
            throw new IllegalStateException("目标玩家的活跃分支已达上限");
        }

        return createBranchFromBounds(
                ownerUuid,
                ownerName,
                label,
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

        return new MenuCreateState(
                MenuCreateAction.CREATE,
                "创建分支",
                "当前选区可创建分支，点击后进入确认界面。"
        );
    }

    public void clearSelection(Player player) {
        selectionManager.clear(player);
    }

    public Branch createBranchFromQueue(Player player) {
        throw new IllegalStateException("1.1.0 已取消编辑期排队，请直接使用创建分支。");
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

    public List<Branch> listEditableBranches(Player player) {
        return branchRepository.findAll().stream()
                .filter(branch -> canModifyBranch(player, branch))
                .toList();
    }

    public List<String> listPendingInviteBranchIds(Player player) {
        return branchRepository.listInviteRequestsForPlayer(player.getUniqueId()).stream()
                .map(BranchInviteRequest::branchId)
                .distinct()
                .toList();
    }

    public List<Branch> listAllBranches() {
        return branchRepository.findAll();
    }

    public void bootstrapLegacyBranches() {
        for (Branch branch : branchRepository.findAll()) {
            if (branch.status() == BranchStatus.MERGED || branch.status() == BranchStatus.ABANDONED) {
                continue;
            }
            rebaseManager.ensureSyncInfo(branch);
            if (branch.status() == BranchStatus.SUBMITTED || branch.status() == BranchStatus.APPROVED) {
                branchRepository.resetToActiveUnchecked(branch.id(), "1.1.0 升级后请重新提交审核。");
                rebaseManager.markNeedsRebase(branch, "1.1.0 升级后请执行 rebase，再重新提交审核。");
                refreshCachedBranch(branch.id());
            }
        }
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
        BranchSyncInfo syncInfo = rebaseManager.ensureSyncInfo(branch);
        if (syncInfo.unresolvedGroupCount() > 0 || syncInfo.syncState() == com.worldgit.model.BranchSyncState.HAS_CONFLICTS) {
            throw new IllegalStateException("当前分支还有未解决冲突，请先进入冲突中心完成处理。");
        }
        List<WorldCommit> incomingCommits = rebaseManager.findIncomingCommits(branch);
        if (!incomingCommits.isEmpty()) {
            rebaseManager.markNeedsRebase(branch, "提交前检测到主线已更新，请先 rebase。");
            throw new IllegalStateException("主线已有重叠更新，请先执行 rebase 并处理冲突。");
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
        BranchSyncInfo syncInfo = rebaseManager.ensureSyncInfo(branch);
        if (syncInfo.unresolvedGroupCount() > 0 || syncInfo.syncState() != com.worldgit.model.BranchSyncState.CLEAN) {
            branchRepository.resetToActiveUnchecked(branch.id(), "审核前检测到同步状态异常，请先 rebase 后重新提交。");
            rebaseManager.markNeedsRebase(branch, "审核前检测到同步状态异常，请先 rebase。");
            refreshCachedBranch(branch.id());
            throw new IllegalStateException("该分支当前不是干净基线，已退回编辑状态，请先 rebase。");
        }
        if (rebaseManager.hasIncomingCommits(branch)) {
            branchRepository.resetToActiveUnchecked(branch.id(), "审核前主线已更新，请先 rebase 后重新提交。");
            rebaseManager.markNeedsRebase(branch, "审核前检测到主线已更新，请先 rebase。");
            refreshCachedBranch(branch.id());
            throw new IllegalStateException("主线已更新，审核失效，分支已退回编辑状态。");
        }
        branchRepository.markReviewed(branch.id(), BranchStatus.APPROVED, admin.getUniqueId(), Instant.now().getEpochSecond(), note);
        rebaseManager.recordReviewed(branch);
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
        BranchSyncInfo syncInfo = rebaseManager.ensureSyncInfo(branch);
        long reviewRevision = syncInfo.lastReviewedRevision() == null ? syncInfo.baseRevision() : syncInfo.lastReviewedRevision();
        if (rebaseManager.hasIncomingCommitsSince(branch, reviewRevision)) {
            branchRepository.resetToActiveUnchecked(branch.id(), "确认合并前检测到主线已更新，请 rebase 后重新提交审核。");
            rebaseManager.markNeedsRebase(branch, "确认合并前检测到主线已更新，需要 rebase 并重新审核。");
            refreshCachedBranch(branch.id());
            throw new IllegalStateException("主线在审核后发生了变化，已撤销审核，请先 rebase 并重新提交。");
        }
        mergeManager.confirmMerge(player, branch, mergeMessage);
    }

    public Branch forceMergeBranch(CommandSender sender, String branchId, String mergeMessage) {
        Branch branch = requireBranch(branchId);
        if (branch.status() == BranchStatus.ABANDONED) {
            throw new IllegalStateException("已废弃分支不能再强制合并。");
        }
        if (!branch.hasRegion()) {
            throw new IllegalStateException("该分支没有有效区域，无法执行强制合并。");
        }

        UUID mergedBy = sender instanceof Player player ? player.getUniqueId() : null;
        mergeManager.forceMerge(branch, mergedBy, mergeMessage);
        refreshCachedBranch(branch.id());

        Player owner = Bukkit.getPlayer(branch.ownerUuid());
        boolean actorIsOwner = sender instanceof Player player && player.getUniqueId().equals(branch.ownerUuid());
        if (owner != null && owner.isOnline() && !actorIsOwner) {
            MessageUtil.sendWarning(owner, "管理员已将分支 " + branch.id() + " 强制合并到主世界。");
        }
        return requireBranch(branch.id());
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
        teleportToBranchWorld(player, branch);
    }

    public void teleportToOverviewBranch(Player player, String branchId) {
        Branch branch = requireBranch(branchId);
        if (!isEditingBranch(branch)) {
            throw new IllegalStateException("该分支已合并或关闭，无法从总览列表传送。");
        }
        teleportToBranchWorld(player, branch);
    }

    private void teleportToBranchWorld(Player player, Branch branch) {
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
        if (invited.getUniqueId().equals(branch.ownerUuid())) {
            throw new IllegalStateException("分支所有者无需再次邀请自己。");
        }
        if (isInvited(branch, invited.getUniqueId())) {
            throw new IllegalStateException("该玩家已经是这个分支的协作者。");
        }
        boolean alreadyPending = branchRepository.hasInviteRequest(branch.id(), invited.getUniqueId());
        branchRepository.addInviteRequest(branch.id(), invited.getUniqueId(), inviter.getUniqueId(), Instant.now().getEpochSecond());
        MessageUtil.sendSuccess(invited, inviter.getName() + " 邀请你加入分支: " + branch.id());
        MessageUtil.sendInfo(invited, "输入 /wg invite accept " + branch.id() + " 接受邀请。");
        if (alreadyPending) {
            MessageUtil.sendWarning(inviter, "该玩家已有待处理邀请，已刷新邀请提示。");
        }
    }

    public void uninvitePlayer(Player inviter, UUID invitedUuid, String branchId) {
        Branch branch = resolveOwnedBranch(inviter, branchId);
        branchRepository.removeInvite(branch.id(), invitedUuid);
        branchRepository.removeInviteRequest(branch.id(), invitedUuid);
        invitedPlayersByBranch.computeIfPresent(branch.id(), (ignored, players) -> {
            players.remove(invitedUuid);
            return players.isEmpty() ? null : players;
        });
    }

    public Branch acceptInvite(Player player, String branchId) {
        BranchInviteRequest inviteRequest = resolveInviteRequest(player, branchId);
        Branch branch = requireBranch(inviteRequest.branchId());
        if (branch.status() == BranchStatus.MERGED || branch.status() == BranchStatus.ABANDONED) {
            branchRepository.removeInviteRequest(branch.id(), player.getUniqueId());
            throw new IllegalStateException("该分支已合并或关闭，邀请已失效。");
        }
        if (isInvited(branch, player.getUniqueId())) {
            branchRepository.removeInviteRequest(branch.id(), player.getUniqueId());
            return branch;
        }
        branchRepository.acceptInviteRequest(branch.id(), player.getUniqueId())
                .orElseThrow(() -> new IllegalStateException("该邀请已失效，请让分支所有者重新邀请。"));
        invitedPlayersByBranch
                .computeIfAbsent(branch.id(), ignored -> ConcurrentHashMap.newKeySet())
                .add(player.getUniqueId());

        Player owner = Bukkit.getPlayer(branch.ownerUuid());
        if (owner != null && owner.isOnline() && !owner.getUniqueId().equals(player.getUniqueId())) {
            MessageUtil.sendSuccess(owner, player.getName() + " 已接受分支邀请: " + branch.id());
        }
        return requireBranch(branch.id());
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

    public Branch setBranchLabel(Player player, String branchId, String label) {
        Branch branch = requireBranch(branchId);
        ensureOwner(branch, player);
        branchRepository.saveLabel(branch.id(), normalizeRequiredBranchLabel(label));
        refreshCachedBranch(branch.id());
        return requireBranch(branch.id());
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

    private String normalizeOptionalBranchLabel(String label) {
        if (label == null) {
            return null;
        }
        String normalized = label.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > BranchDisplayUtil.MAX_LABEL_LENGTH) {
            throw new IllegalStateException("分支标签最多 " + BranchDisplayUtil.MAX_LABEL_LENGTH + " 个字符。");
        }
        return normalized;
    }

    private String normalizeRequiredBranchLabel(String label) {
        String normalized = normalizeOptionalBranchLabel(label);
        if (normalized == null) {
            throw new IllegalStateException("分支标签不能为空。");
        }
        return normalized;
    }

    private BranchInviteRequest resolveInviteRequest(Player player, String branchId) {
        if (branchId != null && !branchId.isBlank()) {
            Branch branch = requireBranch(branchId);
            if (isInvited(branch, player.getUniqueId())) {
                return new BranchInviteRequest(branch.id(), player.getUniqueId(), branch.ownerUuid(), Instant.now());
            }
            return branchRepository.findInviteRequest(branch.id(), player.getUniqueId())
                    .orElseThrow(() -> new IllegalStateException("你当前没有这个分支的待接受邀请。"));
        }

        List<BranchInviteRequest> pendingRequests = branchRepository.listInviteRequestsForPlayer(player.getUniqueId());
        if (pendingRequests.isEmpty()) {
            throw new IllegalStateException("你当前没有待接受的分支邀请。");
        }
        if (pendingRequests.size() > 1) {
            String availableBranches = pendingRequests.stream()
                    .map(BranchInviteRequest::branchId)
                    .distinct()
                    .sorted()
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("");
            throw new IllegalStateException("你有多个待接受邀请，请使用 /wg invite accept <分支ID>。可选分支: " + availableBranches);
        }
        return pendingRequests.get(0);
    }

    private void closeBranch(Branch branch, BranchStatus finalStatus, long closedAt, String note) {
        Location fallback = worldManager.createReturnLocation(branch.minX(), branch.maxX(), branch.minZ(), branch.maxZ());
        if (!worldManager.unloadWorld(branch.worldName(), fallback)) {
            throw new IllegalStateException("无法卸载分支世界: " + branch.worldName());
        }
        lockManager.unlockBranch(branch.id());
        branchRepository.markClosed(branch.id(), finalStatus, closedAt, note);
        rebaseManager.cleanupBranchArtifacts(branch.id());
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
            String label,
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
                label,
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
            rebaseManager.ensureSyncInfo(branch);
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
            if (branchWorld != null || Bukkit.getWorld(branch.worldName()) != null) {
                worldManager.deleteWorld(branch.worldName(), fallbackLocation);
            }
        } catch (RuntimeException ignored) {
            // 清理阶段尽力而为，保留原始异常。
        }
        branchesByWorld.remove(branch.worldName());
        invitedPlayersByBranch.remove(branch.id());
        try {
            rebaseManager.cleanupBranchArtifacts(branch.id());
        } catch (RuntimeException ignored) {
            // 清理阶段尽力而为，保留原始异常。
        }
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

    private boolean isEditingBranch(Branch branch) {
        return branch.status() != BranchStatus.MERGED && branch.status() != BranchStatus.ABANDONED;
    }

    private RegionCopyManager.SelectionBounds intersect(
            RegionCopyManager.SelectionBounds left,
            RegionCopyManager.SelectionBounds right
    ) {
        int minX = Math.max(left.minX(), right.minX());
        int minY = Math.max(left.minY(), right.minY());
        int minZ = Math.max(left.minZ(), right.minZ());
        int maxX = Math.min(left.maxX(), right.maxX());
        int maxY = Math.min(left.maxY(), right.maxY());
        int maxZ = Math.min(left.maxZ(), right.maxZ());
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            return null;
        }
        return new RegionCopyManager.SelectionBounds(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private long selectionVolume(RegionCopyManager.SelectionBounds bounds) {
        long width = (long) bounds.maxX() - bounds.minX() + 1L;
        long height = (long) bounds.maxY() - bounds.minY() + 1L;
        long depth = (long) bounds.maxZ() - bounds.minZ() + 1L;
        return width * height * depth;
    }

    private long unionVolume(List<RegionCopyManager.SelectionBounds> boundsList) {
        if (boundsList.isEmpty()) {
            return 0L;
        }

        List<Integer> xs = new ArrayList<>();
        List<Integer> ys = new ArrayList<>();
        List<Integer> zs = new ArrayList<>();
        for (RegionCopyManager.SelectionBounds bounds : boundsList) {
            xs.add(bounds.minX());
            xs.add(bounds.maxX() + 1);
            ys.add(bounds.minY());
            ys.add(bounds.maxY() + 1);
            zs.add(bounds.minZ());
            zs.add(bounds.maxZ() + 1);
        }

        List<Integer> xPoints = xs.stream().distinct().sorted().toList();
        List<Integer> yPoints = ys.stream().distinct().sorted().toList();
        List<Integer> zPoints = zs.stream().distinct().sorted().toList();

        long volume = 0L;
        for (int xi = 0; xi < xPoints.size() - 1; xi++) {
            int x0 = xPoints.get(xi);
            int x1 = xPoints.get(xi + 1);
            for (int yi = 0; yi < yPoints.size() - 1; yi++) {
                int y0 = yPoints.get(yi);
                int y1 = yPoints.get(yi + 1);
                for (int zi = 0; zi < zPoints.size() - 1; zi++) {
                    int z0 = zPoints.get(zi);
                    int z1 = zPoints.get(zi + 1);
                    boolean covered = false;
                    for (RegionCopyManager.SelectionBounds bounds : boundsList) {
                        if (bounds.minX() <= x0 && bounds.maxX() + 1 >= x1
                                && bounds.minY() <= y0 && bounds.maxY() + 1 >= y1
                                && bounds.minZ() <= z0 && bounds.maxZ() + 1 >= z1) {
                            covered = true;
                            break;
                        }
                    }
                    if (covered) {
                        volume += (long) (x1 - x0) * (y1 - y0) * (z1 - z0);
                    }
                }
            }
        }
        return volume;
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

    public record SelectionOverlap(
            String branchId,
            String ownerName,
            String branchLabel,
            BranchStatus status,
            RegionCopyManager.SelectionBounds branchBounds,
            RegionCopyManager.SelectionBounds overlapBounds,
            long overlapBlockCount
    ) {
    }

    public record CreateBranchPreview(
            RegionCopyManager.SelectionBounds selection,
            long totalBlockCount,
            long overlapBlockCount,
            double overlapRatio,
            List<SelectionOverlap> overlaps
    ) {
        public boolean hasOverlap() {
            return overlapBlockCount > 0L && !overlaps.isEmpty();
        }
    }

    public BranchSyncInfo getSyncInfo(Branch branch) {
        return rebaseManager.ensureSyncInfo(branch);
    }

    public BranchSyncInfo getSyncInfo(String branchId) {
        return rebaseManager.ensureSyncInfo(requireBranch(branchId));
    }

    public RebaseManager.RebaseResult rebaseBranch(Player player, String branchId) {
        Branch branch = resolveOwnedBranch(player, branchId);
        if (branch.status() != BranchStatus.ACTIVE && branch.status() != BranchStatus.REJECTED) {
            throw new IllegalStateException("只有编辑中的分支才能执行 rebase。");
        }
        return rebaseManager.rebase(branch);
    }

    public FetchPreview previewFetch(Player player, String branchId) {
        return previewFetch(player, resolveOwnedBranch(player, branchId));
    }

    public FetchPreview previewFetch(Player player, Branch branch) {
        World mainWorld = Bukkit.getWorld(branch.mainWorld());
        if (mainWorld == null) {
            throw new IllegalStateException("主世界不存在: " + branch.mainWorld());
        }
        RegionCopyManager.SelectionBounds editableBounds = editableBounds(branch);
        RegionCopyManager.SelectionBounds copiedBounds = regionCopyManager.expandForCopy(mainWorld, editableBounds);
        return new FetchPreview(
                true,
                "只拉取主线最新状态，不会改动分支方块；外围和编辑区会在 Rebase 时一起同步。",
                editableBounds,
                copiedBounds
        );
    }

    public FetchResult fetchOutsideSelection(Player player, String branchId) {
        Branch branch = resolveOwnedBranch(player, branchId);
        if (branch.status() != BranchStatus.ACTIVE && branch.status() != BranchStatus.REJECTED) {
            throw new IllegalStateException("只有编辑中的分支才能执行 fetch。");
        }
        FetchPreview preview = previewFetch(player, branch);
        if (!preview.available()) {
            throw new IllegalStateException(preview.message());
        }
        int pendingOutsideBlocks = rebaseManager.countPendingOutsideSelectionBlocks(branch);
        return new FetchResult(pendingOutsideBlocks, preview.protectedBounds(), preview.branchBounds());
    }

    public List<ConflictGroup> listConflictGroups(Player player, String branchId) {
        Branch branch = resolveOwnedBranch(player, branchId);
        return rebaseManager.listConflictGroups(branch.id());
    }

    public RebaseManager.ConflictGroupDetail describeConflictGroup(Player player, String branchId, int groupIndex) {
        Branch branch = resolveOwnedBranch(player, branchId);
        return rebaseManager.describeConflictGroup(branch, groupIndex);
    }

    public List<RebaseManager.ConflictBlockView> listConflictBlocks(Player player, String branchId, int groupIndex) {
        Branch branch = resolveOwnedBranch(player, branchId);
        return rebaseManager.listConflictBlocks(branch, groupIndex);
    }

    public void resolveConflictUseOurs(Player player, String branchId, int groupIndex) {
        Branch branch = resolveOwnedBranch(player, branchId);
        rebaseManager.resolveConflictUseOurs(branch, groupIndex);
    }

    public void resolveConflictUseTheirs(Player player, String branchId, int groupIndex) {
        Branch branch = resolveOwnedBranch(player, branchId);
        rebaseManager.resolveConflictUseTheirs(branch, groupIndex);
    }

    public void markConflictResolvedManually(Player player, String branchId, int groupIndex) {
        Branch branch = resolveOwnedBranch(player, branchId);
        rebaseManager.markConflictResolvedManually(branch, groupIndex);
    }

    public Location getConflictTeleportLocation(Player player, String branchId, int groupIndex) {
        Branch branch = resolveOwnedBranch(player, branchId);
        return rebaseManager.createConflictTeleportLocation(branch, groupIndex);
    }

    public List<Branch> listReReviewBranches() {
        return branchRepository.findSubmitted().stream()
                .filter(branch -> {
                    BranchSyncInfo syncInfo = rebaseManager.ensureSyncInfo(branch);
                    return syncInfo.lastReviewedRevision() != null;
                })
                .toList();
    }

    public long currentHeadRevision(String mainWorld) {
        return rebaseManager.currentHeadRevision(mainWorld);
    }

    public List<WorldCommit> listIncomingCommits(Branch branch) {
        return rebaseManager.findIncomingCommits(branch);
    }

    private RegionCopyManager.SelectionBounds editableBounds(Branch branch) {
        return new RegionCopyManager.SelectionBounds(
                branch.minX(),
                branch.minY(),
                branch.minZ(),
                branch.maxX(),
                branch.maxY(),
                branch.maxZ()
        );
    }

    public record FetchPreview(
            boolean available,
            String message,
            RegionCopyManager.SelectionBounds protectedBounds,
            RegionCopyManager.SelectionBounds branchBounds
    ) {
    }

    public record FetchResult(
            int pendingOutsideBlocks,
            RegionCopyManager.SelectionBounds protectedBounds,
            RegionCopyManager.SelectionBounds branchBounds
    ) {
    }
}

package com.worldgit.manager;

import com.worldgit.database.BranchRepository;
import com.worldgit.database.BranchSyncRepository;
import com.worldgit.database.RevisionRepository;
import com.worldgit.model.Branch;
import com.worldgit.model.BranchSyncInfo;
import com.worldgit.model.BranchSyncState;
import com.worldgit.model.ConflictGroup;
import com.worldgit.model.WorldCommit;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

/**
 * Rebase、冲突解决与同步状态管理。
 */
public final class RebaseManager {

    private static final int CONFLICT_MAGIC = 0x57474331;
    private static final String PHASE_STARTED = "STARTED";
    private static final String PHASE_TARGET_CAPTURED = "TARGET_CAPTURED";
    private static final String PHASE_COMPLETE = "COMPLETE";

    private final BranchRepository branchRepository;
    private final BranchSyncRepository branchSyncRepository;
    private final RevisionRepository revisionRepository;
    private final WorldManager worldManager;
    private final RegionCopyManager regionCopyManager;
    private final SnapshotManager snapshotManager;

    public RebaseManager(
            BranchRepository branchRepository,
            BranchSyncRepository branchSyncRepository,
            RevisionRepository revisionRepository,
            WorldManager worldManager,
            RegionCopyManager regionCopyManager,
            SnapshotManager snapshotManager
    ) {
        this.branchRepository = branchRepository;
        this.branchSyncRepository = branchSyncRepository;
        this.revisionRepository = revisionRepository;
        this.worldManager = worldManager;
        this.regionCopyManager = regionCopyManager;
        this.snapshotManager = snapshotManager;
    }

    public BranchSyncInfo ensureSyncInfo(Branch branch) {
        Optional<BranchSyncInfo> existing = branchSyncRepository.findOptionalUnchecked(branch.id());
        if (existing.isPresent()
                && existing.get().snapshotPath() != null
                && !existing.get().snapshotPath().isBlank()
                && Files.exists(Path.of(existing.get().snapshotPath()))) {
            return existing.get();
        }

        World mainWorld = Bukkit.getWorld(branch.mainWorld());
        if (mainWorld == null) {
            throw new IllegalStateException("主世界不存在: " + branch.mainWorld());
        }
        RegionCopyManager.SelectionBounds bounds = editableBounds(branch);
        long headRevision = revisionRepository.getHeadRevisionUnchecked(branch.mainWorld());
        Path snapshotPath = snapshotManager.createBaseSnapshotPath(branch.id());
        snapshotManager.capture(mainWorld, bounds, snapshotPath);

        BranchSyncInfo created = new BranchSyncInfo(
                branch.id(),
                headRevision,
                headRevision,
                null,
                BranchSyncState.CLEAN,
                snapshotPath.toString(),
                null,
                null,
                null,
                0,
                null
        );
        branchSyncRepository.saveUnchecked(created);
        return created;
    }

    public long currentHeadRevision(String mainWorld) {
        return revisionRepository.getHeadRevisionUnchecked(mainWorld);
    }

    public List<WorldCommit> findIncomingCommits(Branch branch) {
        BranchSyncInfo syncInfo = ensureSyncInfo(branch);
        return findIncomingCommitsSince(branch, syncInfo.baseRevision());
    }

    public List<WorldCommit> findIncomingCommitsSince(Branch branch, long afterRevision) {
        return revisionRepository.findOverlappingSinceUnchecked(
                branch.mainWorld(),
                afterRevision,
                branch.minX(),
                branch.minY(),
                branch.minZ(),
                branch.maxX(),
                branch.maxY(),
                branch.maxZ()
        );
    }

    public boolean hasIncomingCommits(Branch branch) {
        return !findIncomingCommits(branch).isEmpty();
    }

    public boolean hasIncomingCommitsSince(Branch branch, long afterRevision) {
        return !findIncomingCommitsSince(branch, afterRevision).isEmpty();
    }

    public BranchSyncInfo markNeedsRebase(Branch branch, String staleReason) {
        BranchSyncInfo current = ensureSyncInfo(branch);
        BranchSyncInfo updated = new BranchSyncInfo(
                current.branchId(),
                current.baseRevision(),
                current.lastRebasedRevision(),
                current.lastReviewedRevision(),
                current.unresolvedGroupCount() > 0 ? BranchSyncState.HAS_CONFLICTS : BranchSyncState.NEEDS_REBASE,
                current.snapshotPath(),
                current.pendingRebaseRevision(),
                current.pendingSnapshotPath(),
                current.workingSnapshotPath(),
                current.unresolvedGroupCount(),
                staleReason
        );
        branchSyncRepository.saveUnchecked(updated);
        return updated;
    }

    public BranchSyncInfo recordReviewed(Branch branch) {
        BranchSyncInfo current = ensureSyncInfo(branch);
        long reviewedRevision = revisionRepository.getHeadRevisionUnchecked(branch.mainWorld());
        BranchSyncInfo updated = new BranchSyncInfo(
                current.branchId(),
                current.baseRevision(),
                current.lastRebasedRevision(),
                reviewedRevision,
                current.syncState(),
                current.snapshotPath(),
                current.pendingRebaseRevision(),
                current.pendingSnapshotPath(),
                current.workingSnapshotPath(),
                current.unresolvedGroupCount(),
                current.staleReason()
        );
        branchSyncRepository.saveUnchecked(updated);
        return updated;
    }

    public RebaseResult rebase(Branch branch) {
        BranchSyncInfo syncInfo = ensureSyncInfo(branch);
        if (syncInfo.unresolvedGroupCount() > 0) {
            throw new IllegalStateException("当前分支还有未解决冲突，请先处理完再继续。");
        }

        List<WorldCommit> incomingCommits = findIncomingCommits(branch);
        if (incomingCommits.isEmpty()) {
            BranchSyncInfo cleanInfo = new BranchSyncInfo(
                    syncInfo.branchId(),
                    syncInfo.baseRevision(),
                    syncInfo.lastRebasedRevision(),
                    syncInfo.lastReviewedRevision(),
                    BranchSyncState.CLEAN,
                    syncInfo.snapshotPath(),
                    null,
                    null,
                    null,
                    0,
                    null
            );
            branchSyncRepository.saveUnchecked(cleanInfo);
            return new RebaseResult(cleanInfo, List.of(), 0, 0, false);
        }

        World mainWorld = Bukkit.getWorld(branch.mainWorld());
        if (mainWorld == null) {
            throw new IllegalStateException("主世界不存在: " + branch.mainWorld());
        }
        World branchWorld = worldManager.createBranchWorld(branch.worldName());
        RegionCopyManager.SelectionBounds bounds = editableBounds(branch);
        long targetRevision = revisionRepository.getHeadRevisionUnchecked(branch.mainWorld());

        Path workingSnapshotPath = snapshotManager.createWorkingSnapshotPath(branch.id());
        Path pendingSnapshotPath = snapshotManager.createPendingSnapshotPath(branch.id(), targetRevision);
        snapshotManager.deleteQuietly(workingSnapshotPath);
        snapshotManager.deleteQuietly(pendingSnapshotPath);

        branchSyncRepository.upsertRebaseJournalUnchecked(branch.id(), PHASE_STARTED, Instant.now());
        SnapshotManager.RegionSnapshot oursSnapshot = snapshotManager.capture(branchWorld, bounds, workingSnapshotPath);
        SnapshotManager.RegionSnapshot theirsSnapshot = snapshotManager.capture(mainWorld, bounds, pendingSnapshotPath);
        branchSyncRepository.upsertRebaseJournalUnchecked(branch.id(), PHASE_TARGET_CAPTURED, Instant.now());

        SnapshotManager.RegionSnapshot baseSnapshot = snapshotManager.loadSnapshot(syncInfo.snapshotPath());
        ComputedRebase computed = computeRebase(baseSnapshot, oursSnapshot, theirsSnapshot);
        applyAutoMergedBlocks(branchWorld, computed.autoMergedBlocks());

        if (computed.conflictGroups().isEmpty()) {
            finalizeCleanRebase(syncInfo, targetRevision, pendingSnapshotPath, workingSnapshotPath);
            branchSyncRepository.deleteRebaseJournalUnchecked(branch.id());
            return new RebaseResult(
                    branchSyncRepository.findByBranchIdUnchecked(branch.id()),
                    incomingCommits,
                    computed.autoMergedCount(),
                    0,
                    false
            );
        }

        persistConflictGroups(branch.id(), computed.conflictGroups());
        BranchSyncInfo conflicted = new BranchSyncInfo(
                syncInfo.branchId(),
                syncInfo.baseRevision(),
                syncInfo.lastRebasedRevision(),
                syncInfo.lastReviewedRevision(),
                BranchSyncState.HAS_CONFLICTS,
                syncInfo.snapshotPath(),
                targetRevision,
                pendingSnapshotPath.toString(),
                null,
                computed.conflictGroups().size(),
                "rebase 发现冲突，请进入冲突中心处理。"
        );
        branchSyncRepository.saveUnchecked(conflicted);
        snapshotManager.deleteQuietly(workingSnapshotPath);
        branchSyncRepository.deleteRebaseJournalUnchecked(branch.id());
        return new RebaseResult(conflicted, incomingCommits, computed.autoMergedCount(), computed.conflictBlockCount(), true);
    }

    public List<ConflictGroup> listConflictGroups(String branchId) {
        return branchSyncRepository.listConflictGroupsUnchecked(branchId);
    }

    public ConflictGroup requireConflictGroup(String branchId, int groupIndex) {
        return branchSyncRepository.findConflictGroupUnchecked(branchId, groupIndex)
                .orElseThrow(() -> new IllegalStateException("冲突组不存在: #" + groupIndex));
    }

    public void resolveConflictUseOurs(Branch branch, int groupIndex) {
        ConflictGroup group = requireConflictGroup(branch.id(), groupIndex);
        List<ConflictBlockRecord> records = loadConflictDetail(Path.of(group.detailPath()));
        World branchWorld = worldManager.createBranchWorld(branch.worldName());
        applyConflictRecords(branchWorld, records, ConflictValueType.OURS);
        markConflictResolved(branch, groupIndex, "OURS");
    }

    public void resolveConflictUseTheirs(Branch branch, int groupIndex) {
        ConflictGroup group = requireConflictGroup(branch.id(), groupIndex);
        List<ConflictBlockRecord> records = loadConflictDetail(Path.of(group.detailPath()));
        World branchWorld = worldManager.createBranchWorld(branch.worldName());
        applyConflictRecords(branchWorld, records, ConflictValueType.THEIRS);
        markConflictResolved(branch, groupIndex, "THEIRS");
    }

    public void markConflictResolvedManually(Branch branch, int groupIndex) {
        requireConflictGroup(branch.id(), groupIndex);
        markConflictResolved(branch, groupIndex, "MANUAL");
    }

    public ConflictGroupDetail describeConflictGroup(Branch branch, int groupIndex) {
        ConflictGroup group = requireConflictGroup(branch.id(), groupIndex);
        List<ConflictBlockRecord> records = loadConflictDetail(Path.of(group.detailPath()));
        return new ConflictGroupDetail(
                group,
                summarizeTransitions(records, true),
                summarizeTransitions(records, false)
        );
    }

    public List<ConflictBlockView> listConflictBlocks(Branch branch, int groupIndex) {
        ConflictGroup group = requireConflictGroup(branch.id(), groupIndex);
        List<ConflictBlockRecord> records = loadConflictDetail(Path.of(group.detailPath()));
        return records.stream()
                .map(record -> new ConflictBlockView(record.x(), record.y(), record.z(), record.ours(), record.theirs()))
                .toList();
    }

    public int fetchOutsideSelection(Branch branch) {
        BranchSyncInfo syncInfo = ensureSyncInfo(branch);
        if (syncInfo.unresolvedGroupCount() > 0 || syncInfo.syncState() == BranchSyncState.HAS_CONFLICTS) {
            throw new IllegalStateException("当前分支还有未解决冲突，请先完成冲突处理后再 fetch。");
        }

        World mainWorld = Bukkit.getWorld(branch.mainWorld());
        if (mainWorld == null) {
            throw new IllegalStateException("主世界不存在: " + branch.mainWorld());
        }
        World branchWorld = worldManager.createBranchWorld(branch.worldName());
        RegionCopyManager.SelectionBounds editableBounds = editableBounds(branch);
        return regionCopyManager.copyRegionOutsideExclusion(
                mainWorld,
                branchWorld,
                regionCopyManager.expandForCopy(mainWorld, editableBounds),
                editableBounds
        );
    }

    public Location createConflictTeleportLocation(Branch branch, int groupIndex) {
        ConflictGroup group = requireConflictGroup(branch.id(), groupIndex);
        World branchWorld = worldManager.createBranchWorld(branch.worldName());
        int centerX = (group.minX() + group.maxX()) / 2;
        int centerY = Math.min(group.maxY() + 2, branchWorld.getMaxHeight() - 1);
        int centerZ = (group.minZ() + group.maxZ()) / 2;
        return new Location(branchWorld, centerX + 0.5, centerY, centerZ + 0.5);
    }

    public void recoverIncompleteRebases() {
        for (String branchId : branchSyncRepository.listIncompleteRebaseBranchIdsUnchecked()) {
            try {
                rollbackIncompleteRebase(branchId);
            } catch (Exception ignored) {
                // 恢复阶段尽力而为，避免阻塞插件启动。
            }
        }
    }

    public void cleanupBranchArtifacts(String branchId) {
        deleteConflictArtifacts(branchId);
        snapshotManager.deleteBranchDirectoryQuietly(branchId);
        branchSyncRepository.deleteRebaseJournalUnchecked(branchId);
    }

    private void rollbackIncompleteRebase(String branchId) {
        Branch branch = branchRepository.findByIdUnchecked(branchId)
                .orElse(null);
        BranchSyncInfo syncInfo = branchSyncRepository.findOptionalUnchecked(branchId).orElse(null);
        if (branch == null || syncInfo == null) {
            branchSyncRepository.deleteRebaseJournalUnchecked(branchId);
            return;
        }

        if (syncInfo.workingSnapshotPath() != null && !syncInfo.workingSnapshotPath().isBlank()) {
            Path workingPath = Path.of(syncInfo.workingSnapshotPath());
            if (Files.exists(workingPath)) {
                World branchWorld = worldManager.createBranchWorld(branch.worldName());
                snapshotManager.applySnapshot(branchWorld, snapshotManager.loadSnapshot(workingPath));
            }
        }

        snapshotManager.deleteQuietly(syncInfo.pendingSnapshotPath());
        snapshotManager.deleteQuietly(syncInfo.workingSnapshotPath());
        deleteConflictArtifacts(branchId);

        BranchSyncInfo recovered = new BranchSyncInfo(
                syncInfo.branchId(),
                syncInfo.baseRevision(),
                syncInfo.lastRebasedRevision(),
                syncInfo.lastReviewedRevision(),
                BranchSyncState.NEEDS_REBASE,
                syncInfo.snapshotPath(),
                null,
                null,
                null,
                0,
                "检测到未完成的 rebase，系统已回退，请重新执行 rebase。"
        );
        branchSyncRepository.saveUnchecked(recovered);
        branchSyncRepository.deleteRebaseJournalUnchecked(branchId);
    }

    private void finalizeCleanRebase(
            BranchSyncInfo syncInfo,
            long targetRevision,
            Path pendingSnapshotPath,
            Path workingSnapshotPath
    ) {
        Path baseSnapshotPath = snapshotManager.createBaseSnapshotPath(syncInfo.branchId());
        snapshotManager.deleteQuietly(syncInfo.snapshotPath());
        snapshotManager.moveSnapshot(pendingSnapshotPath, baseSnapshotPath);
        snapshotManager.deleteQuietly(workingSnapshotPath);
        deleteConflictArtifacts(syncInfo.branchId());

        BranchSyncInfo updated = new BranchSyncInfo(
                syncInfo.branchId(),
                targetRevision,
                targetRevision,
                syncInfo.lastReviewedRevision(),
                BranchSyncState.CLEAN,
                baseSnapshotPath.toString(),
                null,
                null,
                null,
                0,
                null
        );
        branchSyncRepository.saveUnchecked(updated);
    }

    private void persistConflictGroups(String branchId, List<List<ConflictBlockRecord>> groupedRecords) {
        deleteConflictArtifacts(branchId);
        List<ConflictGroup> summaries = new ArrayList<>();
        Instant now = Instant.now();
        for (int index = 0; index < groupedRecords.size(); index++) {
            int groupIndex = index + 1;
            List<ConflictBlockRecord> records = groupedRecords.get(index);
            Bounds bounds = boundsOf(records);
            Path detailPath = snapshotManager.createConflictDetailPath(branchId, groupIndex);
            saveConflictDetail(detailPath, records);
            summaries.add(new ConflictGroup(
                    0L,
                    branchId,
                    groupIndex,
                    bounds.minX(),
                    bounds.minY(),
                    bounds.minZ(),
                    bounds.maxX(),
                    bounds.maxY(),
                    bounds.maxZ(),
                    records.size(),
                    "OPEN",
                    null,
                    detailPath.toString(),
                    now,
                    null
            ));
        }
        branchSyncRepository.replaceConflictGroupsUnchecked(branchId, summaries);
    }

    private void deleteConflictArtifacts(String branchId) {
        for (ConflictGroup group : branchSyncRepository.listConflictGroupsUnchecked(branchId)) {
            snapshotManager.deleteQuietly(group.detailPath());
        }
        branchSyncRepository.deleteConflictGroupsUnchecked(branchId);
    }

    private void markConflictResolved(Branch branch, int groupIndex, String resolution) {
        BranchSyncInfo syncInfo = ensureSyncInfo(branch);
        branchSyncRepository.markConflictGroupResolvedUnchecked(branch.id(), groupIndex, resolution, Instant.now());
        int remaining = (int) branchSyncRepository.listConflictGroupsUnchecked(branch.id()).stream()
                .filter(group -> !group.resolved())
                .count();
        if (remaining > 0) {
            BranchSyncInfo updated = new BranchSyncInfo(
                    syncInfo.branchId(),
                    syncInfo.baseRevision(),
                    syncInfo.lastRebasedRevision(),
                    syncInfo.lastReviewedRevision(),
                    BranchSyncState.HAS_CONFLICTS,
                    syncInfo.snapshotPath(),
                    syncInfo.pendingRebaseRevision(),
                    syncInfo.pendingSnapshotPath(),
                    null,
                    remaining,
                    "rebase 发现冲突，请进入冲突中心处理。"
            );
            branchSyncRepository.saveUnchecked(updated);
            return;
        }

        if (syncInfo.pendingRebaseRevision() == null || syncInfo.pendingSnapshotPath() == null || syncInfo.pendingSnapshotPath().isBlank()) {
            throw new IllegalStateException("缺少待提升的 rebase 基线快照，无法完成冲突收尾。");
        }

        Path baseSnapshotPath = snapshotManager.createBaseSnapshotPath(branch.id());
        snapshotManager.deleteQuietly(syncInfo.snapshotPath());
        snapshotManager.moveSnapshot(Path.of(syncInfo.pendingSnapshotPath()), baseSnapshotPath);
        deleteConflictArtifacts(branch.id());
        snapshotManager.deleteQuietly(syncInfo.workingSnapshotPath());

        BranchSyncInfo finalized = new BranchSyncInfo(
                syncInfo.branchId(),
                syncInfo.pendingRebaseRevision(),
                syncInfo.pendingRebaseRevision(),
                syncInfo.lastReviewedRevision(),
                BranchSyncState.CLEAN,
                baseSnapshotPath.toString(),
                null,
                null,
                null,
                0,
                null
        );
        branchSyncRepository.saveUnchecked(finalized);
    }

    private ComputedRebase computeRebase(
            SnapshotManager.RegionSnapshot baseSnapshot,
            SnapshotManager.RegionSnapshot oursSnapshot,
            SnapshotManager.RegionSnapshot theirsSnapshot
    ) {
        RegionCopyManager.SelectionBounds bounds = baseSnapshot.bounds();
        List<AutoMergedBlock> autoMergedBlocks = new ArrayList<>();
        List<ConflictBlockRecord> conflicts = new ArrayList<>();

        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                    String base = baseSnapshot.blockStateAt(x, y, z);
                    String ours = oursSnapshot.blockStateAt(x, y, z);
                    String theirs = theirsSnapshot.blockStateAt(x, y, z);

                    if (Objects.equals(ours, base)) {
                        if (!Objects.equals(theirs, base)) {
                            autoMergedBlocks.add(new AutoMergedBlock(x, y, z, theirs));
                        }
                        continue;
                    }
                    if (Objects.equals(theirs, base) || Objects.equals(ours, theirs)) {
                        continue;
                    }
                    conflicts.add(new ConflictBlockRecord(x, y, z, base, ours, theirs));
                }
            }
        }

        return new ComputedRebase(
                autoMergedBlocks,
                groupConflicts(conflicts),
                conflicts.size()
        );
    }

    private List<List<ConflictBlockRecord>> groupConflicts(List<ConflictBlockRecord> conflicts) {
        if (conflicts.isEmpty()) {
            return List.of();
        }
        Map<BlockPos, ConflictBlockRecord> byPos = new LinkedHashMap<>();
        for (ConflictBlockRecord record : conflicts) {
            byPos.put(new BlockPos(record.x(), record.y(), record.z()), record);
        }

        List<List<ConflictBlockRecord>> groups = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        for (BlockPos start : byPos.keySet()) {
            if (!visited.add(start)) {
                continue;
            }
            ArrayDeque<BlockPos> queue = new ArrayDeque<>();
            queue.add(start);
            List<ConflictBlockRecord> group = new ArrayList<>();
            while (!queue.isEmpty()) {
                BlockPos current = queue.removeFirst();
                ConflictBlockRecord record = byPos.get(current);
                if (record != null) {
                    group.add(record);
                }
                for (BlockPos neighbor : current.neighbors()) {
                    if (byPos.containsKey(neighbor) && visited.add(neighbor)) {
                        queue.addLast(neighbor);
                    }
                }
            }
            groups.add(group);
        }
        return groups;
    }

    private void applyAutoMergedBlocks(World branchWorld, List<AutoMergedBlock> autoMergedBlocks) {
        Map<String, BlockData> cache = new HashMap<>();
        for (AutoMergedBlock block : autoMergedBlocks) {
            BlockData blockData = cache.computeIfAbsent(block.blockState(), Bukkit::createBlockData);
            branchWorld.getBlockAt(block.x(), block.y(), block.z()).setBlockData(blockData, false);
        }
    }

    private void applyConflictRecords(World branchWorld, List<ConflictBlockRecord> records, ConflictValueType valueType) {
        Map<String, BlockData> cache = new HashMap<>();
        for (ConflictBlockRecord record : records) {
            String blockState = valueType == ConflictValueType.OURS ? record.ours() : record.theirs();
            BlockData blockData = cache.computeIfAbsent(blockState, Bukkit::createBlockData);
            branchWorld.getBlockAt(record.x(), record.y(), record.z()).setBlockData(blockData, false);
        }
    }

    private List<BlockChangeSummary> summarizeTransitions(List<ConflictBlockRecord> records, boolean oursSide) {
        Map<ChangeKey, Integer> counts = new HashMap<>();
        for (ConflictBlockRecord record : records) {
            String toState = oursSide ? record.ours() : record.theirs();
            ChangeKey key = new ChangeKey(record.base(), toState);
            counts.merge(key, 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .map(entry -> new BlockChangeSummary(entry.getKey().fromState(), entry.getKey().toState(), entry.getValue()))
                .sorted((left, right) -> {
                    int countCompare = Integer.compare(right.count(), left.count());
                    if (countCompare != 0) {
                        return countCompare;
                    }
                    int fromCompare = left.fromState().compareTo(right.fromState());
                    if (fromCompare != 0) {
                        return fromCompare;
                    }
                    return left.toState().compareTo(right.toState());
                })
                .toList();
    }

    private void saveConflictDetail(Path path, List<ConflictBlockRecord> records) {
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            try (DataOutputStream outputStream = new DataOutputStream(
                    new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(path)))
            )) {
                outputStream.writeInt(CONFLICT_MAGIC);
                outputStream.writeInt(records.size());
                for (ConflictBlockRecord record : records) {
                    outputStream.writeInt(record.x());
                    outputStream.writeInt(record.y());
                    outputStream.writeInt(record.z());
                    outputStream.writeUTF(record.base());
                    outputStream.writeUTF(record.ours());
                    outputStream.writeUTF(record.theirs());
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("保存冲突详情失败: " + path, exception);
        }
    }

    private List<ConflictBlockRecord> loadConflictDetail(Path path) {
        try (DataInputStream inputStream = new DataInputStream(
                new BufferedInputStream(new GZIPInputStream(Files.newInputStream(path)))
        )) {
            int magic = inputStream.readInt();
            if (magic != CONFLICT_MAGIC) {
                throw new IllegalStateException("无法识别的冲突详情文件: " + path);
            }
            int size = inputStream.readInt();
            List<ConflictBlockRecord> records = new ArrayList<>(size);
            for (int index = 0; index < size; index++) {
                records.add(new ConflictBlockRecord(
                        inputStream.readInt(),
                        inputStream.readInt(),
                        inputStream.readInt(),
                        inputStream.readUTF(),
                        inputStream.readUTF(),
                        inputStream.readUTF()
                ));
            }
            return records;
        } catch (IOException exception) {
            throw new IllegalStateException("读取冲突详情失败: " + path, exception);
        }
    }

    private Bounds boundsOf(List<ConflictBlockRecord> records) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (ConflictBlockRecord record : records) {
            minX = Math.min(minX, record.x());
            minY = Math.min(minY, record.y());
            minZ = Math.min(minZ, record.z());
            maxX = Math.max(maxX, record.x());
            maxY = Math.max(maxY, record.y());
            maxZ = Math.max(maxZ, record.z());
        }
        return new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
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

    public record RebaseResult(
            BranchSyncInfo syncInfo,
            List<WorldCommit> incomingCommits,
            int autoMergedBlocks,
            int conflictBlocks,
            boolean hasConflicts
    ) {
    }

    public record ConflictGroupDetail(
            ConflictGroup group,
            List<BlockChangeSummary> oursChanges,
            List<BlockChangeSummary> theirsChanges
    ) {
    }

    public record BlockChangeSummary(String fromState, String toState, int count) {
    }

    public record ConflictBlockView(int x, int y, int z, String ours, String theirs) {
    }

    private record ComputedRebase(
            List<AutoMergedBlock> autoMergedBlocks,
            List<List<ConflictBlockRecord>> conflictGroups,
            int conflictBlockCount
    ) {

        private int autoMergedCount() {
            return autoMergedBlocks.size();
        }
    }

    private record AutoMergedBlock(int x, int y, int z, String blockState) {
    }

    private record ConflictBlockRecord(int x, int y, int z, String base, String ours, String theirs) {
    }

    private record ChangeKey(String fromState, String toState) {
    }

    private enum ConflictValueType {
        OURS,
        THEIRS
    }

    private record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    }

    private record BlockPos(int x, int y, int z) {
        private List<BlockPos> neighbors() {
            return List.of(
                    new BlockPos(x + 1, y, z),
                    new BlockPos(x - 1, y, z),
                    new BlockPos(x, y + 1, z),
                    new BlockPos(x, y - 1, z),
                    new BlockPos(x, y, z + 1),
                    new BlockPos(x, y, z - 1)
            );
        }
    }
}

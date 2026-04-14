package com.worldgit.manager;

import com.worldgit.model.Branch;
import com.worldgit.model.BranchSyncInfo;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.World;

/**
 * 计算分支相对基线快照的改动摘要，并提供审核高亮所需的采样点。
 */
public final class BranchChangeManager {

    private static final int MAX_MARKER_POINTS = 96;

    private final SnapshotManager snapshotManager;
    private final WorldManager worldManager;

    public BranchChangeManager(SnapshotManager snapshotManager, WorldManager worldManager) {
        this.snapshotManager = Objects.requireNonNull(snapshotManager, "快照管理器不能为空");
        this.worldManager = Objects.requireNonNull(worldManager, "世界管理器不能为空");
    }

    public BranchChangeSummary describeBranchChanges(Branch branch, BranchSyncInfo syncInfo) {
        Objects.requireNonNull(branch, "分支不能为空");
        Objects.requireNonNull(syncInfo, "同步信息不能为空");

        World branchWorld = worldManager.createBranchWorld(branch.worldName());
        SnapshotManager.RegionSnapshot baseSnapshot = snapshotManager.loadSnapshot(syncInfo.snapshotPath());
        RegionCopyManager.SelectionBounds bounds = baseSnapshot.bounds();

        long changedBlockCount = 0L;
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        Map<ChangeKey, Long> transitions = new HashMap<>();
        List<ChangeMarker> markers = new ArrayList<>(MAX_MARKER_POINTS);

        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                    String baseState = baseSnapshot.blockStateAt(x, y, z);
                    String currentState = branchWorld.getBlockAt(x, y, z).getBlockData().getAsString();
                    if (Objects.equals(baseState, currentState)) {
                        continue;
                    }

                    changedBlockCount++;
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    minZ = Math.min(minZ, z);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                    maxZ = Math.max(maxZ, z);
                    transitions.merge(new ChangeKey(baseState, currentState), 1L, Long::sum);
                    sampleMarker(markers, changedBlockCount, new ChangeMarker(x, y, z));
                }
            }
        }

        if (changedBlockCount == 0L) {
            return new BranchChangeSummary(0L, null, List.of(), List.of());
        }

        return new BranchChangeSummary(
                changedBlockCount,
                new RegionCopyManager.SelectionBounds(minX, minY, minZ, maxX, maxY, maxZ),
                transitions.entrySet().stream()
                        .map(entry -> new BlockTransitionSummary(
                                entry.getKey().fromState(),
                                entry.getKey().toState(),
                                entry.getValue()
                        ))
                        .sorted(Comparator
                                .comparingLong(BlockTransitionSummary::count).reversed()
                                .thenComparing(BlockTransitionSummary::fromState)
                                .thenComparing(BlockTransitionSummary::toState))
                        .toList(),
                List.copyOf(markers)
        );
    }

    private void sampleMarker(List<ChangeMarker> markers, long changedBlockCount, ChangeMarker marker) {
        if (markers.size() < MAX_MARKER_POINTS) {
            markers.add(marker);
            return;
        }
        long replaceIndex = ThreadLocalRandom.current().nextLong(changedBlockCount);
        if (replaceIndex < MAX_MARKER_POINTS) {
            markers.set((int) replaceIndex, marker);
        }
    }

    public record BranchChangeSummary(
            long changedBlockCount,
            RegionCopyManager.SelectionBounds changedBounds,
            List<BlockTransitionSummary> transitions,
            List<ChangeMarker> markers
    ) {

        public boolean hasChanges() {
            return changedBlockCount > 0L && changedBounds != null;
        }
    }

    public record BlockTransitionSummary(String fromState, String toState, long count) {
    }

    public record ChangeMarker(int x, int y, int z) {
    }

    private record ChangeKey(String fromState, String toState) {
    }
}

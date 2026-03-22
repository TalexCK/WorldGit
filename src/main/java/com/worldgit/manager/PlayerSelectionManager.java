package com.worldgit.manager;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * 管理玩家通过坐标命令维护的临时选区。
 */
public final class PlayerSelectionManager {

    private final Map<UUID, SelectionSnapshot> selections = new ConcurrentHashMap<>();

    public SelectionSnapshot setPos1(Player player, Integer x, Integer y, Integer z) {
        return update(player, 1, x, y, z);
    }

    public SelectionSnapshot setPos2(Player player, Integer x, Integer y, Integer z) {
        return update(player, 2, x, y, z);
    }

    public Optional<SelectionSnapshot> getSelection(Player player) {
        return Optional.ofNullable(selections.get(player.getUniqueId()));
    }

    public RegionCopyManager.SelectionBounds requireSelection(Player player, boolean useFullHeight) {
        SelectionSnapshot snapshot = selections.get(player.getUniqueId());
        if (snapshot == null || !snapshot.complete()) {
            throw new IllegalStateException("请先使用 /wg pos1 和 /wg pos2 设置完整选区");
        }
        if (!snapshot.worldName().equals(player.getWorld().getName())) {
            throw new IllegalStateException("当前选区不在你所在的世界，请重新设置坐标");
        }
        return snapshot.toBounds(player.getWorld(), useFullHeight);
    }

    public void clear(Player player) {
        selections.remove(player.getUniqueId());
    }

    public int countSelectedPoints(Player player) {
        return getSelection(player)
                .map(SelectionSnapshot::selectedPoints)
                .orElse(0);
    }

    private SelectionSnapshot update(Player player, int pointIndex, Integer x, Integer y, Integer z) {
        SelectionPoint point = SelectionPoint.from(player.getLocation(), x, y, z);
        SelectionSnapshot current = selections.get(player.getUniqueId());
        String worldName = player.getWorld().getName();
        SelectionSnapshot base = current != null && worldName.equals(current.worldName())
                ? current
                : new SelectionSnapshot(worldName, null, null);
        SelectionSnapshot updated = pointIndex == 1
                ? new SelectionSnapshot(worldName, point, base.pos2())
                : new SelectionSnapshot(worldName, base.pos1(), point);
        selections.put(player.getUniqueId(), updated);
        return updated;
    }

    public record SelectionSnapshot(String worldName, SelectionPoint pos1, SelectionPoint pos2) {

        public boolean complete() {
            return pos1 != null && pos2 != null;
        }

        public int selectedPoints() {
            int count = 0;
            if (pos1 != null) {
                count++;
            }
            if (pos2 != null) {
                count++;
            }
            return count;
        }

        public RegionCopyManager.SelectionBounds toBounds(World world, boolean useFullHeight) {
            if (!complete()) {
                throw new IllegalStateException("选区尚未设置完成");
            }
            int minX = Math.min(pos1.x(), pos2.x());
            int maxX = Math.max(pos1.x(), pos2.x());
            int minZ = Math.min(pos1.z(), pos2.z());
            int maxZ = Math.max(pos1.z(), pos2.z());
            int minY = useFullHeight ? world.getMinHeight() : Math.min(pos1.y(), pos2.y());
            int maxY = useFullHeight ? world.getMaxHeight() - 1 : Math.max(pos1.y(), pos2.y());
            return new RegionCopyManager.SelectionBounds(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }

    public record SelectionPoint(int x, int y, int z) {

        public static SelectionPoint from(Location location, Integer x, Integer y, Integer z) {
            return new SelectionPoint(
                    x == null ? location.getBlockX() : x,
                    y == null ? location.getBlockY() : y,
                    z == null ? location.getBlockZ() : z
            );
        }
    }
}

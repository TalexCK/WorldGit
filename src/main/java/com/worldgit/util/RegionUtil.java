package com.worldgit.util;

import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Optional;

/**
 * WorldEdit 区域辅助工具。
 */
public final class RegionUtil {

    private RegionUtil() {
    }

    public static Optional<SelectionSnapshot> getSelection(Player player) {
        try {
            LocalSession session = WorldEdit.getInstance().getSessionManager().get(BukkitAdapter.adapt(player));
            Region selection = session.getSelection(BukkitAdapter.adapt(player.getWorld()));
            BlockVector3 minimum = selection.getMinimumPoint();
            BlockVector3 maximum = selection.getMaximumPoint();
            return Optional.of(new SelectionSnapshot(
                    player.getWorld().getName(),
                    minimum,
                    maximum
            ));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    public static boolean withinSizeLimits(SelectionSnapshot snapshot, int maxRegionSizeX, int maxRegionSizeZ, boolean useFullHeight) {
        int sizeX = snapshot.max().x() - snapshot.min().x() + 1;
        int sizeZ = snapshot.max().z() - snapshot.min().z() + 1;
        if (sizeX > maxRegionSizeX || sizeZ > maxRegionSizeZ) {
            return false;
        }
        if (!useFullHeight) {
            return true;
        }
        return snapshot.max().y() >= snapshot.min().y();
    }

    public static CuboidRegion toCuboidRegion(World world, SelectionSnapshot snapshot) {
        return new CuboidRegion(
                BukkitAdapter.adapt(world),
                snapshot.min(),
                snapshot.max()
        );
    }

    public static String describe(SelectionSnapshot snapshot) {
        return String.format(
                "世界=%s, 最小点=(%d, %d, %d), 最大点=(%d, %d, %d)",
                snapshot.worldName(),
                snapshot.min().x(),
                snapshot.min().y(),
                snapshot.min().z(),
                snapshot.max().x(),
                snapshot.max().y(),
                snapshot.max().z()
        );
    }

    public static record SelectionSnapshot(String worldName, BlockVector3 min, BlockVector3 max) {
    }
}

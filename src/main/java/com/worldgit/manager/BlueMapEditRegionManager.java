package com.worldgit.manager;

import com.worldgit.WorldGitPlugin;
import com.worldgit.model.Branch;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;

/**
 * 通过 BlueMap API 反射同步未合并编辑区标记。
 */
public final class BlueMapEditRegionManager {

    private static final long REFRESH_INTERVAL_TICKS = 20L * 5L;
    private static final float LINE_ALPHA = 1.0F;
    private static final float FILL_ALPHA = 0.18F;

    private final WorldGitPlugin plugin;
    private final BranchManager branchManager;
    private BukkitTask refreshTask;
    private boolean warningLogged;

    public BlueMapEditRegionManager(WorldGitPlugin plugin, BranchManager branchManager) {
        this.plugin = Objects.requireNonNull(plugin, "插件不能为空");
        this.branchManager = Objects.requireNonNull(branchManager, "分支管理器不能为空");
    }

    public void start() {
        stop();
        refreshMarkers();
        refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshMarkers, 20L, REFRESH_INTERVAL_TICKS);
    }

    public void stop() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
        clearMarkers();
    }

    private void refreshMarkers() {
        BlueMapHandle handle = resolveHandle();
        if (handle == null) {
            return;
        }

        try {
            clearAllMarkerSets(handle);
            Map<String, List<Branch>> branchesByWorld = branchManager.listEditingBranches().stream()
                    .collect(Collectors.groupingBy(Branch::mainWorld));

            for (Map.Entry<String, List<Branch>> entry : branchesByWorld.entrySet()) {
                World world = Bukkit.getWorld(entry.getKey());
                if (world == null) {
                    continue;
                }
                Optional<?> blueMapWorld = (Optional<?>) handle.blueMapApiGetWorld.invoke(handle.api, world);
                if (blueMapWorld.isEmpty()) {
                    continue;
                }
                Collection<?> maps = (Collection<?>) handle.blueMapWorldGetMaps.invoke(blueMapWorld.get());
                for (Object map : maps) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> markerSets = (Map<String, Object>) handle.blueMapMapGetMarkerSets.invoke(map);
                    markerSets.put(
                            EditingRegionVisuals.BLUEMAP_MARKER_SET_ID,
                            createMarkerSet(handle, entry.getValue())
                    );
                }
            }
        } catch (ReflectiveOperationException exception) {
            logWarningOnce("BlueMap 标记同步失败: " + exception.getMessage());
        }
    }

    private void clearMarkers() {
        BlueMapHandle handle = resolveHandle();
        if (handle == null) {
            return;
        }
        try {
            clearAllMarkerSets(handle);
        } catch (ReflectiveOperationException exception) {
            logWarningOnce("清理 BlueMap 标记失败: " + exception.getMessage());
        }
    }

    private void clearAllMarkerSets(BlueMapHandle handle) throws ReflectiveOperationException {
        @SuppressWarnings("unchecked")
        Collection<Object> maps = (Collection<Object>) handle.blueMapApiGetMaps.invoke(handle.api);
        for (Object map : maps) {
            @SuppressWarnings("unchecked")
            Map<String, Object> markerSets = (Map<String, Object>) handle.blueMapMapGetMarkerSets.invoke(map);
            markerSets.remove(EditingRegionVisuals.BLUEMAP_MARKER_SET_ID);
        }
    }

    private Object createMarkerSet(BlueMapHandle handle, List<Branch> branches) throws ReflectiveOperationException {
        Object markerSetBuilder = handle.markerSetBuilder.invoke(null);
        handle.markerBuilderLabel.invoke(markerSetBuilder, EditingRegionVisuals.BLUEMAP_MARKER_SET_LABEL);
        Object markerSet = handle.markerBuilderBuild.invoke(markerSetBuilder);

        @SuppressWarnings("unchecked")
        Map<String, Object> markers = (Map<String, Object>) handle.markerSetGetMarkers.invoke(markerSet);
        for (Branch branch : branches) {
            markers.put("branch-" + branch.id(), createBranchMarker(handle, branch));
        }
        return markerSet;
    }

    private Object createBranchMarker(BlueMapHandle handle, Branch branch) throws ReflectiveOperationException {
        Object markerBuilder = handle.extrudeMarkerBuilder.invoke(null);
        handle.markerBuilderLabel.invoke(markerBuilder, branch.ownerName() + " | " + shortId(branch.id()));

        Object shape = handle.shapeCreateRect.invoke(
                null,
                (double) branch.minX(),
                (double) branch.minZ(),
                (double) branch.maxX() + 1.0D,
                (double) branch.maxZ() + 1.0D
        );
        handle.extrudeMarkerBuilderShape.invoke(
                markerBuilder,
                shape,
                branch.minY().floatValue(),
                branch.maxY().floatValue() + 1.0F
        );
        handle.extrudeMarkerBuilderLineColor.invoke(markerBuilder, createColor(handle, LINE_ALPHA));
        handle.extrudeMarkerBuilderFillColor.invoke(markerBuilder, createColor(handle, FILL_ALPHA));
        handle.extrudeMarkerBuilderLineWidth.invoke(markerBuilder, 2);
        handle.extrudeMarkerBuilderDepthTest.invoke(markerBuilder, false);
        return handle.markerBuilderBuild.invoke(markerBuilder);
    }

    private Object createColor(BlueMapHandle handle, float alpha) throws ReflectiveOperationException {
        return handle.colorConstructor.newInstance(
                EditingRegionVisuals.REGION_RED,
                EditingRegionVisuals.REGION_GREEN,
                EditingRegionVisuals.REGION_BLUE,
                alpha
        );
    }

    private BlueMapHandle resolveHandle() {
        if (plugin.getServer().getPluginManager().getPlugin("BlueMap") == null) {
            return null;
        }
        try {
            Class<?> apiClass = Class.forName("de.bluecolored.bluemap.api.BlueMapAPI");
            Optional<?> optionalApi = (Optional<?>) apiClass.getMethod("getInstance").invoke(null);
            if (optionalApi.isEmpty()) {
                return null;
            }

            Class<?> blueMapWorldClass = Class.forName("de.bluecolored.bluemap.api.BlueMapWorld");
            Class<?> blueMapMapClass = Class.forName("de.bluecolored.bluemap.api.BlueMapMap");
            Class<?> markerSetClass = Class.forName("de.bluecolored.bluemap.api.markers.MarkerSet");
            Class<?> extrudeMarkerClass = Class.forName("de.bluecolored.bluemap.api.markers.ExtrudeMarker");
            Class<?> shapeClass = Class.forName("de.bluecolored.bluemap.api.math.Shape");
            Class<?> colorClass = Class.forName("de.bluecolored.bluemap.api.math.Color");

            return new BlueMapHandle(
                    optionalApi.get(),
                    apiClass.getMethod("getMaps"),
                    apiClass.getMethod("getWorld", World.class),
                    blueMapWorldClass.getMethod("getMaps"),
                    blueMapMapClass.getMethod("getMarkerSets"),
                    markerSetClass.getMethod("builder"),
                    markerSetClass.getMethod("getMarkers"),
                    extrudeMarkerClass.getMethod("builder"),
                    extrudeMarkerClass.getMethod("builder").getReturnType().getMethod("shape", shapeClass, float.class, float.class),
                    extrudeMarkerClass.getMethod("builder").getReturnType().getMethod("lineColor", colorClass),
                    extrudeMarkerClass.getMethod("builder").getReturnType().getMethod("fillColor", colorClass),
                    extrudeMarkerClass.getMethod("builder").getReturnType().getMethod("lineWidth", int.class),
                    extrudeMarkerClass.getMethod("builder").getReturnType().getMethod("depthTestEnabled", boolean.class),
                    extrudeMarkerClass.getMethod("builder").getReturnType().getMethod("label", String.class),
                    extrudeMarkerClass.getMethod("builder").getReturnType().getMethod("build"),
                    markerSetClass.getMethod("builder").getReturnType().getMethod("label", String.class),
                    markerSetClass.getMethod("builder").getReturnType().getMethod("build"),
                    shapeClass.getMethod("createRect", double.class, double.class, double.class, double.class),
                    colorClass.getConstructor(int.class, int.class, int.class, float.class),
                    blueMapWorldClass,
                    blueMapMapClass
            );
        } catch (ReflectiveOperationException exception) {
            logWarningOnce("BlueMap API 不可用，已跳过地图边界标记: " + exception.getMessage());
            return null;
        }
    }

    private void logWarningOnce(String message) {
        if (warningLogged) {
            return;
        }
        warningLogged = true;
        plugin.getLogger().warning(message);
    }

    private String shortId(String branchId) {
        return branchId.substring(0, Math.min(8, branchId.length()));
    }

    private record BlueMapHandle(
            Object api,
            Method blueMapApiGetMaps,
            Method blueMapApiGetWorld,
            Method blueMapWorldGetMaps,
            Method blueMapMapGetMarkerSets,
            Method markerSetBuilder,
            Method markerSetGetMarkers,
            Method extrudeMarkerBuilder,
            Method extrudeMarkerBuilderShape,
            Method extrudeMarkerBuilderLineColor,
            Method extrudeMarkerBuilderFillColor,
            Method extrudeMarkerBuilderLineWidth,
            Method extrudeMarkerBuilderDepthTest,
            Method markerBuilderLabel,
            Method markerBuilderBuild,
            Method markerSetBuilderLabel,
            Method markerSetBuilderBuild,
            Method shapeCreateRect,
            Constructor<?> colorConstructor,
            Class<?> blueMapWorldClass,
            Class<?> blueMapMapClass
    ) {
    }
}

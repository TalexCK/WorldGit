package com.worldgit.manager;

import com.worldgit.config.PluginConfig;
import com.worldgit.util.ProtectionService;
import org.bukkit.World;
import org.bukkit.entity.Player;

public final class ProtectionManager implements ProtectionService {

    private final PluginConfig pluginConfig;

    public ProtectionManager(PluginConfig pluginConfig) {
        this.pluginConfig = pluginConfig;
    }

    @Override
    public boolean isMainWorld(World world) {
        return world != null && pluginConfig.mainWorld().equals(world.getName());
    }

    public boolean hasBypass(Player player) {
        return player.hasPermission("worldgit.admin.bypass");
    }

    @Override
    public boolean canBypass(Player player) {
        return player != null && hasBypass(player);
    }
}

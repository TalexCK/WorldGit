package com.worldgit.manager;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.events.PacketListener;
import com.comphenix.protocol.wrappers.CustomPacketPayloadWrapper;
import com.worldgit.WorldGitPlugin;
import com.worldgit.util.MessageUtil;
import com.worldgit.util.ProtectionService;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * 基于 ProtocolLib 的 Axiom 改块包拦截器。
 */
public final class ProtocolLibAxiomProtectionHook implements AxiomProtectionHook {

    private static final Set<String> AXIOM_BLOCK_EDIT_CHANNELS = Set.of(
            "axiom:set_block",
            "axiom:set_block_buffer"
    );
    private static final long AXIOM_WARNING_COOLDOWN_MILLIS = 1500L;

    private final WorldGitPlugin plugin;
    private final ProtectionService protectionService;
    private final Map<UUID, Long> lastAxiomWarningAt = new ConcurrentHashMap<>();

    private PacketListener packetListener;

    public ProtocolLibAxiomProtectionHook(WorldGitPlugin plugin, ProtectionService protectionService) {
        this.plugin = Objects.requireNonNull(plugin, "插件实例不能为空");
        this.protectionService = Objects.requireNonNull(protectionService, "保护服务不能为空");
    }

    @Override
    public void start() {
        stop();
        ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();
        packetListener = new PacketAdapter(
                PacketAdapter.params()
                        .plugin(plugin)
                        .clientSide()
                        .listenerPriority(ListenerPriority.LOWEST)
                        .optionSync()
                        .types(PacketType.Play.Client.CUSTOM_PAYLOAD)
        ) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                handleCustomPayload(event);
            }
        };
        protocolManager.addPacketListener(packetListener);
    }

    @Override
    public void stop() {
        if (packetListener == null) {
            return;
        }
        ProtocolLibrary.getProtocolManager().removePacketListener(packetListener);
        packetListener = null;
    }

    private void handleCustomPayload(PacketEvent event) {
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }

        World world = player.getWorld();
        if (world == null || !protectionService.isMainWorld(world) || protectionService.canBypass(player)) {
            return;
        }

        CustomPacketPayloadWrapper payload = event.getPacket()
                .getModifier()
                .withType(
                        CustomPacketPayloadWrapper.getCustomPacketPayloadClass(),
                        CustomPacketPayloadWrapper.getConverter()
                )
                .readSafely(0);
        if (payload == null || payload.getId() == null) {
            return;
        }
        if (!AXIOM_BLOCK_EDIT_CHANNELS.contains(payload.getId().getFullKey())) {
            return;
        }

        event.setCancelled(true);
        warnAxiomEditBlocked(player);
    }

    private void warnAxiomEditBlocked(Player player) {
        long now = System.currentTimeMillis();
        Long previous = lastAxiomWarningAt.put(player.getUniqueId(), now);
        if (previous != null && now - previous < AXIOM_WARNING_COOLDOWN_MILLIS) {
            return;
        }
        MessageUtil.sendWarning(player, "主世界已阻止 Axiom 改块操作。");
    }
}

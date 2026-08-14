package com.worldgit.listener;

import com.worldgit.config.PluginConfig;
import com.worldgit.util.MessageUtil;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.event.server.TabCompleteEvent;

/**
 * 为普通玩家提供受控的 `/tp` 能力，只支持传送自己到坐标或在线玩家。
 */
public final class PlayerTeleportCommandListener implements Listener {

    private static final List<String> ROOT_LABELS = List.of("tp", "teleport");
    private static final List<String> VANILLA_TELEPORT_PERMISSIONS = List.of(
            "minecraft.command.teleport",
            "minecraft.command.tp",
            "bukkit.command.teleport"
    );

    private final PluginConfig pluginConfig;

    public PlayerTeleportCommandListener(PluginConfig pluginConfig) {
        this.pluginConfig = pluginConfig;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerCommandSend(PlayerCommandSendEvent event) {
        Player player = event.getPlayer();
        if (!shouldHandle(player)) {
            return;
        }
        event.getCommands().add("tp");
        event.getCommands().add("teleport");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTabComplete(TabCompleteEvent event) {
        if (!(event.getSender() instanceof Player player) || !shouldHandle(player)) {
            return;
        }

        ParsedTeleportCommand parsed = parse(event.getBuffer());
        if (parsed == null || parsed.args().length != 1) {
            return;
        }

        String prefix = parsed.args()[0].toLowerCase(Locale.ROOT);
        List<String> completions = Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        event.setCompletions(completions);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!shouldHandle(player)) {
            return;
        }

        ParsedTeleportCommand parsed = parse(event.getMessage());
        if (parsed == null) {
            return;
        }

        event.setCancelled(true);
        try {
            handleTeleport(player, parsed.args());
        } catch (IllegalStateException exception) {
            MessageUtil.sendError(player, exception.getMessage());
        }
    }

    private void handleTeleport(Player player, String[] args) {
        if (args.length == 1) {
            teleportToPlayer(player, args[0]);
            return;
        }
        if (args.length == 3) {
            teleportToCoordinates(player, args);
            return;
        }
        throw new IllegalStateException("用法: /tp <玩家ID> 或 /tp <x> <y> <z>");
    }

    private void teleportToPlayer(Player player, String targetName) {
        if (targetName == null || targetName.isBlank()) {
            throw new IllegalStateException("玩家ID不能为空");
        }

        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            List<Player> matches = Bukkit.matchPlayer(targetName);
            if (matches.size() == 1) {
                target = matches.getFirst();
            } else if (matches.size() > 1) {
                throw new IllegalStateException("玩家ID不唯一，请输入更完整的名字");
            }
        }
        if (target == null) {
            throw new IllegalStateException("目标玩家不在线: " + targetName);
        }

        player.teleportAsync(target.getLocation());
        MessageUtil.sendSuccess(player, "正在传送到玩家: " + target.getName());
    }

    private void teleportToCoordinates(Player player, String[] args) {
        Location current = player.getLocation();
        double x = parseCoordinate(args[0], current.getX(), "x");
        double y = parseCoordinate(args[1], current.getY(), "y");
        double z = parseCoordinate(args[2], current.getZ(), "z");
        Location destination = new Location(
                current.getWorld(),
                x,
                y,
                z,
                current.getYaw(),
                current.getPitch()
        );
        player.teleportAsync(destination);
        MessageUtil.sendSuccess(player, "正在传送到坐标: "
                + formatCoordinate(x) + ", "
                + formatCoordinate(y) + ", "
                + formatCoordinate(z));
    }

    private double parseCoordinate(String value, double origin, String axis) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("坐标 " + axis + " 不能为空");
        }
        if (value.startsWith("~")) {
            if (value.length() == 1) {
                return origin;
            }
            try {
                return origin + Double.parseDouble(value.substring(1));
            } catch (NumberFormatException exception) {
                throw new IllegalStateException("坐标 " + axis + " 格式无效，支持整数、小数、~、~+数字、~-数字");
            }
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("坐标 " + axis + " 格式无效，支持整数、小数、~、~+数字、~-数字");
        }
    }

    private String formatCoordinate(double value) {
        if (Math.rint(value) == value) {
            return Long.toString(Math.round(value));
        }
        return Double.toString(value);
    }

    private boolean shouldHandle(Player player) {
        return player != null
                && pluginConfig.forceEnablePlayerTeleportCommand()
                && !hasVanillaTeleportPermission(player);
    }

    private boolean hasVanillaTeleportPermission(Player player) {
        if (player.isOp()) {
            return true;
        }
        for (String permission : VANILLA_TELEPORT_PERMISSIONS) {
            if (player.hasPermission(permission)) {
                return true;
            }
        }
        return false;
    }

    private ParsedTeleportCommand parse(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank() || rawMessage.charAt(0) != '/') {
            return null;
        }
        String commandLine = rawMessage.substring(1).trim();
        if (commandLine.isEmpty()) {
            return null;
        }
        String[] parts = commandLine.split("\\s+");
        if (parts.length == 0) {
            return null;
        }
        String root = parts[0].toLowerCase(Locale.ROOT);
        if (!ROOT_LABELS.contains(root)) {
            return null;
        }
        return new ParsedTeleportCommand(root, Arrays.copyOfRange(parts, 1, parts.length));
    }

    private record ParsedTeleportCommand(String root, String[] args) {
    }
}

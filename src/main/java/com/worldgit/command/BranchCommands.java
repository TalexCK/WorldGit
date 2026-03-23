package com.worldgit.command;

import com.worldgit.util.BranchService;
import com.worldgit.util.MessageUtil;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * 分支相关命令集合。
 */
public final class BranchCommands {

    private final BranchService branchService;

    public BranchCommands() {
        this(BranchService.noop());
    }

    public BranchCommands(BranchService branchService) {
        this.branchService = branchService;
    }

    public boolean execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sendBranchHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();
        String[] tail = slice(args);
        return switch (subCommand) {
            case "create" -> handleCreate(sender);
            case "abandon" -> handleAbandon(sender, tail);
            case "list" -> handleList(sender);
            case "info" -> handleInfo(sender, tail);
            case "tp", "teleport" -> handleTeleport(sender, tail);
            case "return" -> handleReturn(sender);
            case "pos1" -> handlePos1(sender, tail);
            case "pos2" -> handlePos2(sender, tail);
            case "selection" -> handleSelection(sender);
            case "clearselection" -> handleClearSelection(sender);
            default -> {
                sendBranchHelp(sender);
                yield true;
            }
        };
    }

    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length <= 1) {
            return prefixMatch(args, List.of("create", "abandon", "list", "info", "tp", "return",
                    "pos1", "pos2", "selection", "clearselection"));
        }
        String subCommand = args[0].toLowerCase();
        return switch (subCommand) {
            case "abandon", "info", "tp" -> branchService.suggestBranchIds(sender, args[1]);
            default -> List.of();
        };
    }

    private boolean handleCreate(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            MessageUtil.sendError(sender, "只有玩家可以执行该命令。");
            return true;
        }
        if (!branchService.create(player)) {
            MessageUtil.sendWarning(sender, "分支创建尚未接入实际管理器。");
        }
        return true;
    }

    private boolean handleAbandon(CommandSender sender, String[] args) {
        if (args.length == 0) {
            MessageUtil.sendError(sender, "用法: /wg abandon <分支ID>");
            return true;
        }
        if (!branchService.abandon(sender, args[0])) {
            MessageUtil.sendWarning(sender, "放弃分支操作尚未接入实际管理器。");
        }
        return true;
    }

    private boolean handleList(CommandSender sender) {
        if (!branchService.list(sender)) {
            MessageUtil.sendWarning(sender, "分支列表尚未接入实际管理器。");
        }
        return true;
    }

    private boolean handleInfo(CommandSender sender, String[] args) {
        if (args.length == 0) {
            MessageUtil.sendError(sender, "用法: /wg info <分支ID>");
            return true;
        }
        if (!branchService.info(sender, args[0])) {
            MessageUtil.sendWarning(sender, "分支信息查询尚未接入实际管理器。");
        }
        return true;
    }

    private boolean handleTeleport(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.sendError(sender, "只有玩家可以传送到分支世界。");
            return true;
        }
        if (args.length == 0) {
            MessageUtil.sendError(sender, "用法: /wg tp <分支ID>");
            return true;
        }
        if (!branchService.teleport(player, args[0])) {
            MessageUtil.sendWarning(sender, "传送到分支世界尚未接入实际管理器。");
        }
        return true;
    }

    private boolean handleReturn(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            MessageUtil.sendError(sender, "只有玩家可以返回主世界。");
            return true;
        }
        if (!branchService.returnToMain(player)) {
            MessageUtil.sendWarning(sender, "返回主世界尚未接入实际管理器。");
        }
        return true;
    }

    private boolean handlePos1(CommandSender sender, String[] args) {
        return handleSelectionPoint(sender, args, true);
    }

    private boolean handlePos2(CommandSender sender, String[] args) {
        return handleSelectionPoint(sender, args, false);
    }

    private boolean handleSelection(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            MessageUtil.sendError(sender, "只有玩家可以查看选区。");
            return true;
        }
        if (!branchService.selection(player)) {
            MessageUtil.sendWarning(sender, "选区查看尚未接入实际管理器。");
        }
        return true;
    }

    private boolean handleClearSelection(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            MessageUtil.sendError(sender, "只有玩家可以清除选区。");
            return true;
        }
        if (!branchService.clearSelection(player)) {
            MessageUtil.sendWarning(sender, "清除选区尚未接入实际管理器。");
        }
        return true;
    }

    private void sendBranchHelp(CommandSender sender) {
        MessageUtil.sendInfo(sender, "可用命令: /wg pos1 [x y z], /wg pos2 [x y z], /wg selection, /wg clearselection, /wg create");
    }

    private static String[] slice(String[] args) {
        if (args.length <= 1) {
            return new String[0];
        }
        String[] tail = new String[args.length - 1];
        System.arraycopy(args, 1, tail, 0, tail.length);
        return tail;
    }

    private static List<String> prefixMatch(String[] args, List<String> candidates) {
        if (args.length == 0 || args[0].isBlank()) {
            return candidates;
        }
        String prefix = args[0].toLowerCase();
        return candidates.stream()
                .filter(option -> option.startsWith(prefix))
                .toList();
    }

    private boolean handleSelectionPoint(CommandSender sender, String[] args, boolean firstPoint) {
        if (!(sender instanceof Player player)) {
            MessageUtil.sendError(sender, "只有玩家可以设置选区。");
            return true;
        }
        Integer[] coordinates = parseCoordinates(args, firstPoint ? "pos1" : "pos2");
        boolean handled = firstPoint
                ? branchService.setPos1(player, coordinates[0], coordinates[1], coordinates[2])
                : branchService.setPos2(player, coordinates[0], coordinates[1], coordinates[2]);
        if (!handled) {
            MessageUtil.sendWarning(sender, "坐标选区尚未接入实际管理器。");
        }
        return true;
    }

    private Integer[] parseCoordinates(String[] args, String commandName) {
        if (args.length == 0) {
            return new Integer[]{null, null, null};
        }
        if (args.length != 3) {
            throw new IllegalStateException("用法: /wg " + commandName + " [x y z]");
        }
        return new Integer[]{
                parseInteger(args[0], "x"),
                parseInteger(args[1], "y"),
                parseInteger(args[2], "z")
        };
    }

    private int parseInteger(String value, String axis) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(axis + " 坐标必须是整数");
        }
    }
}

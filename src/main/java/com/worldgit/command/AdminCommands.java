package com.worldgit.command;

import com.worldgit.util.AdminService;
import com.worldgit.util.MessageUtil;
import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * 管理员命令集合。
 */
public final class AdminCommands {

    private final AdminService adminService;

    public AdminCommands() {
        this(AdminService.noop());
    }

    public AdminCommands(AdminService adminService) {
        this.adminService = adminService;
    }

    public boolean execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();
        String[] tail = slice(args);
        return switch (subCommand) {
            case "close" -> handleClose(sender, tail);
            case "forcemerge" -> handleForceMerge(sender, tail);
            case "assign" -> handleAssign(sender, tail);
            case "backup" -> handleBackup(sender);
            case "sync" -> handleSync(sender);
            case "locks" -> handleLocks(sender);
            case "list" -> handleList(sender, tail);
            case "reload" -> handleReload(sender);
            default -> {
                sendHelp(sender);
                yield true;
            }
        };
    }

    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length <= 1) {
            return prefixMatch(args, List.of("close", "forcemerge", "assign", "backup", "sync", "locks", "list", "reload"));
        }
        String subCommand = args[0].toLowerCase();
        return switch (subCommand) {
            case "assign", "list" -> adminService.suggestPlayerNames(sender, args[1]);
            case "close", "forcemerge" -> completeBranchOperations(sender, args);
            default -> List.of();
        };
    }

    private boolean handleClose(CommandSender sender, String[] args) {
        if (args.length == 0) {
            MessageUtil.sendError(sender, "用法: /wg admin close <分支ID>");
            return true;
        }
        if (!adminService.close(sender, args[0])) {
            MessageUtil.sendWarning(sender, "强制关闭尚未接入实际管理器。");
        }
        return true;
    }

    private boolean handleForceMerge(CommandSender sender, String[] args) {
        if (args.length == 0) {
            MessageUtil.sendError(sender, "用法: /wg admin forcemerge <分支ID> [confirm]");
            return true;
        }
        if (args.length > 2) {
            MessageUtil.sendError(sender, "用法: /wg admin forcemerge <分支ID> [confirm]");
            return true;
        }
        boolean confirmed = args.length >= 2 && "confirm".equalsIgnoreCase(args[1]);
        if (args.length == 2 && !confirmed) {
            MessageUtil.sendError(sender, "二次确认请使用: /wg admin forcemerge <分支ID> confirm");
            return true;
        }
        if (!adminService.forceMerge(sender, args[0], confirmed)) {
            MessageUtil.sendWarning(sender, "管理员强制合并尚未接入实际管理器。");
        }
        return true;
    }

    private boolean handleAssign(CommandSender sender, String[] args) {
        if (args.length == 0) {
            MessageUtil.sendError(sender, "用法: /wg admin assign <玩家名>");
            return true;
        }
        if (!adminService.assign(sender, args[0])) {
            MessageUtil.sendWarning(sender, "区域分配尚未接入实际管理器。");
        }
        return true;
    }

    private boolean handleBackup(CommandSender sender) {
        if (!adminService.backup(sender)) {
            MessageUtil.sendWarning(sender, "手动备份尚未接入实际管理器。");
        }
        return true;
    }

    private boolean handleSync(CommandSender sender) {
        if (!adminService.sync(sender)) {
            MessageUtil.sendWarning(sender, "手动 GitHub 同步尚未接入实际管理器。");
        }
        return true;
    }

    private boolean handleLocks(CommandSender sender) {
        if (!adminService.locks(sender)) {
            MessageUtil.sendWarning(sender, "锁定列表尚未接入实际管理器。");
        }
        return true;
    }

    private boolean handleList(CommandSender sender, String[] args) {
        String playerName = args.length > 0 ? args[0] : "";
        if (!adminService.list(sender, playerName)) {
            MessageUtil.sendWarning(sender, "分支总列表尚未接入实际管理器。");
        }
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!adminService.reload(sender)) {
            MessageUtil.sendWarning(sender, "重载配置尚未接入实际管理器。");
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        MessageUtil.sendInfo(sender, "可用命令: /wg admin close <id>, /wg admin forcemerge <id> [confirm], /wg admin assign <player>, /wg admin backup, /wg admin sync, /wg admin locks, /wg admin list [player], /wg admin reload");
    }

    private List<String> completeBranchOperations(CommandSender sender, String[] args) {
        if (args.length == 2) {
            return adminService.suggestBranchIds(sender, args[1]);
        }
        if ("forcemerge".equalsIgnoreCase(args[0]) && args.length == 3) {
            return prefixMatch(new String[]{args[2]}, List.of("confirm"));
        }
        return List.of();
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
}

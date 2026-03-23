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
            return prefixMatch(args, List.of("close", "assign", "backup", "sync", "locks", "list", "reload"));
        }
        String subCommand = args[0].toLowerCase();
        return switch (subCommand) {
            case "assign", "list" -> adminService.suggestPlayerNames(sender, args[1]);
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
        MessageUtil.sendInfo(sender, "可用命令: /wg admin close <id>, /wg admin assign <player>, /wg admin backup, /wg admin sync, /wg admin locks, /wg admin list [player], /wg admin reload");
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

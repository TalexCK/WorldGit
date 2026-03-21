package com.worldgit.command;

import com.worldgit.util.InviteService;
import com.worldgit.util.MessageUtil;
import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * 邀请命令集合。
 */
public final class InviteCommands {

    private final InviteService inviteService;

    public InviteCommands() {
        this(InviteService.noop());
    }

    public InviteCommands(InviteService inviteService) {
        this.inviteService = inviteService;
    }

    public boolean execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();
        String[] tail = slice(args);
        return switch (subCommand) {
            case "invite" -> handleInvite(sender, tail);
            case "uninvite" -> handleUninvite(sender, tail);
            default -> {
                sendHelp(sender);
                yield true;
            }
        };
    }

    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length <= 1) {
            return prefixMatch(args, List.of("invite", "uninvite"));
        }
        String subCommand = args[0].toLowerCase();
        return switch (subCommand) {
            case "invite", "uninvite" -> inviteService.suggestTargets(sender, args[1]);
            default -> List.of();
        };
    }

    private boolean handleInvite(CommandSender sender, String[] args) {
        if (args.length == 0) {
            MessageUtil.sendError(sender, "用法: /wg invite <玩家名> [分支ID]");
            return true;
        }
        String target = args[0];
        String branchId = args.length > 1 ? args[1] : "";
        if (!inviteService.invite(sender, target, branchId)) {
            MessageUtil.sendWarning(sender, "邀请功能尚未接入实际管理器。");
        }
        return true;
    }

    private boolean handleUninvite(CommandSender sender, String[] args) {
        if (args.length == 0) {
            MessageUtil.sendError(sender, "用法: /wg uninvite <玩家名> [分支ID]");
            return true;
        }
        String target = args[0];
        String branchId = args.length > 1 ? args[1] : "";
        if (!inviteService.uninvite(sender, target, branchId)) {
            MessageUtil.sendWarning(sender, "取消邀请功能尚未接入实际管理器。");
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        MessageUtil.sendInfo(sender, "可用命令: /wg invite <player> [id], /wg uninvite <player> [id]");
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

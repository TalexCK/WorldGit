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
            case "invite" -> handleInviteCommand(sender, tail);
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
            case "invite" -> completeInvite(sender, args);
            case "uninvite" -> completeUninvite(sender, args);
            default -> List.of();
        };
    }

    private boolean handleInviteCommand(CommandSender sender, String[] args) {
        if (args.length > 0 && isAcceptKeyword(args[0])) {
            return handleAccept(sender, slice(args));
        }
        return handleInvite(sender, args);
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

    private boolean handleAccept(CommandSender sender, String[] args) {
        String branchId = args.length > 0 ? args[0] : "";
        if (!inviteService.accept(sender, branchId)) {
            MessageUtil.sendWarning(sender, "接受邀请功能尚未接入实际管理器。");
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
        MessageUtil.sendInfo(sender, "可用命令: /wg invite <player> [id], /wg invite accept [id], /wg uninvite <player> [id]");
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

    private List<String> completeInvite(CommandSender sender, String[] args) {
        if (args.length == 2) {
            List<String> suggestions = new java.util.ArrayList<>();
            suggestions.addAll(prefixMatch(new String[]{args[1]}, List.of("accept", "accpet")));
            suggestions.addAll(inviteService.suggestTargets(sender, args[1]));
            return suggestions.stream().distinct().toList();
        }
        if (args.length == 3 && isAcceptKeyword(args[1])) {
            return inviteService.suggestPendingBranchIds(sender, args[2]);
        }
        if (args.length == 3) {
            return inviteService.suggestBranchIds(sender, args[2]);
        }
        return List.of();
    }

    private List<String> completeUninvite(CommandSender sender, String[] args) {
        if (args.length == 2) {
            return inviteService.suggestTargets(sender, args[1]);
        }
        if (args.length == 3) {
            return inviteService.suggestBranchIds(sender, args[2]);
        }
        return List.of();
    }

    private boolean isAcceptKeyword(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.toLowerCase();
        return "accept".equals(normalized) || "accpet".equals(normalized);
    }
}

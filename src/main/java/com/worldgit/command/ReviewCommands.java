package com.worldgit.command;

import com.worldgit.util.MessageUtil;
import com.worldgit.util.ReviewService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * 提交、审核与确认合并命令集合。
 */
public final class ReviewCommands {

    private final ReviewService reviewService;

    public ReviewCommands() {
        this(ReviewService.noop());
    }

    public ReviewCommands(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    public boolean execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            return handleList(sender);
        }

        String subCommand = args[0].toLowerCase();
        String[] tail = slice(args);
        return switch (subCommand) {
            case "submit" -> handleSubmit(sender, tail);
            case "confirm" -> handleConfirm(sender, tail);
            case "forceedit" -> handleForceEdit(sender, tail);
            case "list" -> handleList(sender);
            case "approve" -> handleApprove(sender, tail);
            case "reject" -> handleReject(sender, tail);
            default -> {
                sendHelp(sender);
                yield true;
            }
        };
    }

    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length <= 1) {
            return prefixMatch(args, List.of("submit", "confirm", "forceedit", "list", "approve", "reject"));
        }
        String subCommand = args[0].toLowerCase();
        return switch (subCommand) {
            case "confirm", "forceedit" -> reviewService.suggestConfirmIds(sender, args[1]);
            case "approve", "reject" -> reviewService.suggestReviewIds(sender, args[1]);
            default -> List.of();
        };
    }

    private boolean handleSubmit(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.sendError(sender, "只有玩家可以提交分支。");
            return true;
        }
        String branchId = args.length > 0 ? args[0] : "";
        if (!reviewService.submit(player, branchId)) {
            MessageUtil.sendWarning(sender, "提交审核尚未接入实际管理器。");
        }
        return true;
    }

    private boolean handleConfirm(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.sendError(sender, "只有玩家可以确认合并。");
            return true;
        }
        String branchId = args.length > 0 ? args[0] : "";
        if (!reviewService.confirm(player, branchId)) {
            MessageUtil.sendWarning(sender, "确认合并尚未接入实际管理器。");
        }
        return true;
    }

    private boolean handleForceEdit(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.sendError(sender, "只有玩家可以切回编辑状态。");
            return true;
        }
        String branchId = args.length > 0 ? args[0] : "";
        if (!reviewService.forceEdit(player, branchId)) {
            MessageUtil.sendWarning(sender, "切回编辑状态尚未接入实际管理器。");
        }
        return true;
    }

    private boolean handleList(CommandSender sender) {
        if (!reviewService.list(sender)) {
            MessageUtil.sendWarning(sender, "审核列表尚未接入实际管理器。");
        }
        return true;
    }

    private boolean handleApprove(CommandSender sender, String[] args) {
        if (args.length == 0) {
            MessageUtil.sendError(sender, "用法: /wg review approve <分支ID> [备注]");
            return true;
        }
        String branchId = args[0];
        String note = joinTail(args, 1);
        if (!reviewService.approve(sender, branchId, note)) {
            MessageUtil.sendWarning(sender, "审核通过尚未接入实际管理器。");
        }
        return true;
    }

    private boolean handleReject(CommandSender sender, String[] args) {
        if (args.length < 2) {
            MessageUtil.sendError(sender, "用法: /wg review reject <分支ID> <备注>");
            return true;
        }
        String branchId = args[0];
        String note = joinTail(args, 1);
        if (!reviewService.reject(sender, branchId, note)) {
            MessageUtil.sendWarning(sender, "审核拒绝尚未接入实际管理器。");
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        MessageUtil.sendInfo(sender, "可用命令: /wg submit [id], /wg confirm [id], /wg forceedit [id], /wg review list, /wg review approve <id> [备注], /wg review reject <id> <备注>");
    }

    private static String[] slice(String[] args) {
        if (args.length <= 1) {
            return new String[0];
        }
        String[] tail = new String[args.length - 1];
        System.arraycopy(args, 1, tail, 0, tail.length);
        return tail;
    }

    private static String joinTail(String[] args, int startIndex) {
        if (startIndex >= args.length) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = startIndex; i < args.length; i++) {
            if (i > startIndex) {
                builder.append(' ');
            }
            builder.append(args[i]);
        }
        return builder.toString();
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

package com.worldgit.command;

import com.worldgit.util.MessageUtil;
import com.worldgit.util.PlayerMenuService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * `/wg` 根命令分发器。
 */
public final class WorldGitCommand implements CommandExecutor, TabCompleter {

    private final BranchCommands branchCommands;
    private final ReviewCommands reviewCommands;
    private final AdminCommands adminCommands;
    private final InviteCommands inviteCommands;
    private final PlayerMenuService playerMenuService;

    public WorldGitCommand() {
        this(new BranchCommands(), new ReviewCommands(), new AdminCommands(), new InviteCommands(), PlayerMenuService.noop());
    }

    public WorldGitCommand(BranchCommands branchCommands,
                           ReviewCommands reviewCommands,
                           AdminCommands adminCommands,
                           InviteCommands inviteCommands,
                           PlayerMenuService playerMenuService) {
        this.branchCommands = branchCommands;
        this.reviewCommands = reviewCommands;
        this.adminCommands = adminCommands;
        this.inviteCommands = inviteCommands;
        this.playerMenuService = playerMenuService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            if (args.length == 0) {
                if (sender instanceof Player player && playerMenuService.openMainMenu(player)) {
                    return true;
                }
                sendHelp(sender);
                return true;
            }

            String head = args[0].toLowerCase();
            String[] tail = slice(args);
            return switch (head) {
                case "create", "abandon", "list", "info", "tp", "teleport", "return",
                        "pos1", "pos2", "selection", "clearselection" -> branchCommands.execute(sender, args);
                case "submit", "confirm", "forceedit", "review" -> routeReview(sender, head, tail);
                case "admin" -> adminCommands.execute(sender, tail);
                case "invite", "uninvite" -> inviteCommands.execute(sender, args);
                case "help", "?" -> {
                    sendHelp(sender);
                    yield true;
                }
                default -> {
                    MessageUtil.sendWarning(sender, "未知子命令，输入 /wg help 查看帮助。");
                    yield true;
                }
            };
        } catch (IllegalStateException exception) {
            MessageUtil.sendError(sender, exception.getMessage());
            return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 0) {
            return rootSuggestions("");
        }
        if (args.length == 1) {
            return rootSuggestions(args[0]);
        }

        String head = args[0].toLowerCase();
        String[] tail = slice(args);
        return switch (head) {
            case "create", "abandon", "list", "info", "tp", "teleport", "return",
                    "pos1", "pos2", "selection", "clearselection" -> branchCommands.complete(sender, args);
            case "submit", "confirm", "forceedit", "review" -> reviewComplete(sender, head, tail);
            case "admin" -> adminCommands.complete(sender, tail);
            case "invite", "uninvite" -> inviteCommands.complete(sender, args);
            default -> List.of();
        };
    }

    private boolean routeReview(CommandSender sender, String head, String[] tail) {
        if ("review".equals(head)) {
            return reviewCommands.execute(sender, tail);
        }
        return reviewCommands.execute(sender, prepend(head, tail));
    }

    private List<String> reviewComplete(CommandSender sender, String head, String[] tail) {
        if ("review".equals(head)) {
            return reviewCommands.complete(sender, tail);
        }
        return reviewCommands.complete(sender, prepend(head, tail));
    }

    private void sendHelp(CommandSender sender) {
        MessageUtil.sendInfo(sender, "可用命令: /wg pos1, /wg pos2, /wg create, /wg submit [id], /wg confirm [id], /wg forceedit [id], /wg invite <player> [id], /wg invite accept [id], /wg review list, /wg admin <sub>");
    }

    private List<String> rootSuggestions(String prefix) {
        List<String> candidates = List.of(
                "create", "abandon", "list", "info", "tp", "return",
                "pos1", "pos2", "selection", "clearselection",
                "submit", "confirm", "forceedit", "review",
                "admin",
                "invite", "uninvite",
                "help"
        );
        if (prefix == null || prefix.isBlank()) {
            return candidates;
        }
        String lower = prefix.toLowerCase();
        List<String> suggestions = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate.startsWith(lower)) {
                suggestions.add(candidate);
            }
        }
        return suggestions;
    }

    private static String[] slice(String[] args) {
        if (args.length <= 1) {
            return new String[0];
        }
        String[] tail = new String[args.length - 1];
        System.arraycopy(args, 1, tail, 0, tail.length);
        return tail;
    }

    private static String[] prepend(String head, String[] tail) {
        String[] merged = new String[tail.length + 1];
        merged[0] = head;
        System.arraycopy(tail, 0, merged, 1, tail.length);
        return merged;
    }
}

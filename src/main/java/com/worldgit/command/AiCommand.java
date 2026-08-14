package com.worldgit.command;

import com.worldgit.ai.WorldGitAiService;
import com.worldgit.util.MessageUtil;
import java.util.List;
import java.util.Objects;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class AiCommand implements CommandExecutor, TabCompleter {

    private final WorldGitAiService aiService;

    public AiCommand(WorldGitAiService aiService) {
        this.aiService = Objects.requireNonNull(aiService, "AI 服务不能为空");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            if (args.length == 0 || "help".equalsIgnoreCase(args[0])) {
                sendHelp(sender);
                return true;
            }
            if (!(sender instanceof Player player)) {
                MessageUtil.sendError(sender, "只有玩家可以执行 /ai 命令。");
                return true;
            }
            if ("secret".equalsIgnoreCase(args[0])) {
                MessageUtil.sendInfo(player, "AI Secret 已改为 Web Secret，请使用 /secret 重新生成。");
                return true;
            }
            if (!player.hasPermission("worldgit.ai.use")) {
                throw new IllegalStateException("你没有权限执行该命令");
            }
            if ("keep".equalsIgnoreCase(args[0])) {
                aiService.keepPreview(player, args.length >= 2 ? args[1] : null);
                return true;
            }
            if ("drop".equalsIgnoreCase(args[0])) {
                aiService.dropPreview(player, args.length >= 2 ? args[1] : null);
                return true;
            }
            if ("preview".equalsIgnoreCase(args[0])) {
                aiService.enterPreview(player, args.length >= 2 ? args[1] : null);
                return true;
            }
            sendHelp(sender);
            return true;
        } catch (IllegalStateException exception) {
            MessageUtil.sendError(sender, exception.getMessage());
            return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            return List.of("preview", "keep", "drop", "help").stream()
                    .filter(option -> option.startsWith(prefix))
                    .toList();
        }
        if (args.length == 2
                && sender instanceof Player player
                && ("preview".equalsIgnoreCase(args[0])
                || "keep".equalsIgnoreCase(args[0])
                || "drop".equalsIgnoreCase(args[0]))) {
            return aiService.suggestPreviewBranchIds(player, args[1]);
        }
        return List.of();
    }

    private void sendHelp(CommandSender sender) {
        MessageUtil.sendInfo(sender, "可用命令: /ai preview <预览ID>, /ai keep <预览ID>, /ai drop <预览ID>。Web 登录 Secret 请使用 /secret。");
    }
}

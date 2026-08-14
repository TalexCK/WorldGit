package com.worldgit.command;

import com.worldgit.ai.WorldGitAiService;
import com.worldgit.config.PluginConfig;
import com.worldgit.util.MessageUtil;
import com.worldgit.web.WebLoginRequestService;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class SecretCommand implements CommandExecutor {

    private final WorldGitAiService aiService;
    private final PluginConfig pluginConfig;
    private final WebLoginRequestService webLoginRequestService;

    public SecretCommand(
            WorldGitAiService aiService,
            PluginConfig pluginConfig,
            WebLoginRequestService webLoginRequestService
    ) {
        this.aiService = Objects.requireNonNull(aiService, "AI 服务不能为空");
        this.pluginConfig = Objects.requireNonNull(pluginConfig, "插件配置不能为空");
        this.webLoginRequestService = Objects.requireNonNull(webLoginRequestService, "Web 登录请求服务不能为空");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.sendError(sender, "只有玩家可以生成 Web Secret。");
            return true;
        }

        try {
            if (args.length >= 2 && "accept".equalsIgnoreCase(args[0])) {
                webLoginRequestService.accept(player, args[1]);
                MessageUtil.sendSuccess(player, "已允许本次 WorldGit Web 登录。");
                return true;
            }
            if (args.length >= 2 && "deny".equalsIgnoreCase(args[0])) {
                webLoginRequestService.deny(player, args[1]);
                MessageUtil.sendInfo(player, "已拒绝本次 WorldGit Web 登录。");
                return true;
            }

            String secret = aiService.rotateSecret(player);
            MessageUtil.sendSuccess(player, "Web Secret 已重新生成。旧 Secret 会立即失效，旧网页会话也会一并失效。");
            player.sendMessage(copyableSecretMessage(secret));
            if (pluginConfig.webEnabled()) {
                MessageUtil.sendInfo(player, "新版 Web 登录可直接在网页输入玩家ID，并在游戏内确认。AI 会按当前 worldgit.ai.use 权限判断。");
            }
        } catch (IllegalStateException exception) {
            MessageUtil.sendError(sender, exception.getMessage());
        }
        return true;
    }

    private Component copyableSecretMessage(String secret) {
        Component copyButton = Component.text(" [点击复制]", NamedTextColor.GREEN)
                .clickEvent(ClickEvent.copyToClipboard(secret))
                .hoverEvent(HoverEvent.showText(Component.text("点击后复制 Web Secret", NamedTextColor.GREEN)));
        return MessageUtil.prefix()
                .append(Component.text("Web Secret: ", NamedTextColor.YELLOW))
                .append(Component.text(secret, NamedTextColor.GOLD)
                        .clickEvent(ClickEvent.copyToClipboard(secret))
                        .hoverEvent(HoverEvent.showText(Component.text("点击复制到剪贴板", NamedTextColor.YELLOW))))
                .append(copyButton);
    }
}

package com.worldgit.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;

/**
 * 消息格式化工具。
 */
public final class MessageUtil {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private MessageUtil() {
    }

    public static Component mini(String message) {
        return MINI_MESSAGE.deserialize(message);
    }

    public static Component legacy(String message) {
        return LEGACY.deserialize(message);
    }

    public static Component prefix() {
        return mini("<gold>[<bold>WorldGit</bold>]</gold> ");
    }

    public static Component info(String message) {
        return prefix().append(mini("<white>" + escape(message) + "</white>"));
    }

    public static Component success(String message) {
        return prefix().append(mini("<green>" + escape(message) + "</green>"));
    }

    public static Component warning(String message) {
        return prefix().append(mini("<yellow>" + escape(message) + "</yellow>"));
    }

    public static Component error(String message) {
        return prefix().append(mini("<red>" + escape(message) + "</red>"));
    }

    public static void sendInfo(CommandSender sender, String message) {
        sender.sendMessage(info(message));
    }

    public static void sendSuccess(CommandSender sender, String message) {
        sender.sendMessage(success(message));
    }

    public static void sendWarning(CommandSender sender, String message) {
        sender.sendMessage(warning(message));
    }

    public static void sendError(CommandSender sender, String message) {
        sender.sendMessage(error(message));
    }

    public static String escape(String message) {
        return MINI_MESSAGE.escapeTags(message);
    }
}

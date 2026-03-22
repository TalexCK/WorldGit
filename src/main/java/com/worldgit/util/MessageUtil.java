package com.worldgit.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;

/**
 * 消息格式化工具。
 */
public final class MessageUtil {

    private static final String DEFAULT_DISPLAY_PREFIX = "WorldGit";
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static volatile String displayPrefix = DEFAULT_DISPLAY_PREFIX;

    private MessageUtil() {
    }

    public static Component mini(String message) {
        return MINI_MESSAGE.deserialize(message);
    }

    public static Component legacy(String message) {
        return LEGACY.deserialize(message);
    }

    public static void setDisplayPrefix(String value) {
        if (value == null || value.isBlank()) {
            displayPrefix = DEFAULT_DISPLAY_PREFIX;
            return;
        }
        displayPrefix = value.trim();
    }

    public static String displayPrefixText() {
        return displayPrefix;
    }

    public static String title(String suffix) {
        return displayPrefixText() + " " + suffix;
    }

    public static Component prefix() {
        return mini("<#f59e0b>✦ "
                + escape(displayPrefixText())
                + " ✦ » </#f59e0b>");
    }

    public static Component info(String message) {
        return prefix().append(mini("<#dbeafe>" + escape(message) + "</#dbeafe>"));
    }

    public static Component success(String message) {
        return prefix().append(mini("<#86efac>" + escape(message) + "</#86efac>"));
    }

    public static Component warning(String message) {
        return prefix().append(mini("<#fde68a>" + escape(message) + "</#fde68a>"));
    }

    public static Component error(String message) {
        return prefix().append(mini("<#fca5a5>" + escape(message) + "</#fca5a5>"));
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

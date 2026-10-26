package top.mores.bFBackpack.util;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * 聊天栏 / 动作栏输出，统一用 &amp; 作为颜色占位符。
 */
public final class Message {

    private Message() {
    }

    public static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    public static void send(CommandSender sender, String text) {
        sender.sendMessage(color(text));
    }

    public static void actionBar(Player player, String text) {
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(color(text)));
    }
}

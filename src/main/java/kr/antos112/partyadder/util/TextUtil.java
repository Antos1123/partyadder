package kr.antos112.partyadder.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;

public final class TextUtil {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private TextUtil() {}

    public static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    public static Component component(String text) {
        return LEGACY.deserialize(color(text));
    }

    public static String apply(String text, Map<String, String> placeholders) {
        if (text == null) return "";
        String result = text;
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                result = result.replace(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
            }
        }
        return result;
    }

    public static Map<String, String> map(String... pairs) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    public static void send(CommandSender sender, String text) {
        sender.sendMessage(color(text));
    }

    public static void send(Player player, Component component) {
        player.sendMessage(component);
    }
}

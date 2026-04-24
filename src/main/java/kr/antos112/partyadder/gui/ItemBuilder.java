package kr.antos112.partyadder.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

public final class ItemBuilder {
    private ItemBuilder() {}

    public static ItemStack item(Material material, Component name, List<Component> lore, int amount) {
        ItemStack stack = new ItemStack(material, Math.max(1, amount));
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            if (lore != null) meta.lore(lore);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    public static ItemStack item(Material material, Component name, List<Component> lore, int amount, int cmd) {
        ItemStack stack = new ItemStack(material, Math.max(1, amount));
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            if (lore != null) meta.lore(lore);
            meta.setCustomModelData(cmd);
            stack.setItemMeta(meta);
        }
        return stack;
    }
}

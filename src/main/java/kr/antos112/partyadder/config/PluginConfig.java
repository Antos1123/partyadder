package kr.antos112.partyadder.config;

import kr.antos112.partyadder.util.TextUtil;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

public class PluginConfig {
    private FileConfiguration config;

    public PluginConfig(FileConfiguration config) {
        this.config = config;
    }

    public FileConfiguration raw() { return config; }

    public int maxMembers() { return config.getInt("settings.max-members", 8); }
    public boolean allowRename() { return config.getBoolean("settings.allow-rename", true); }
    public boolean allowTeleport() { return config.getBoolean("settings.allow-teleport", true); }
    public int teleportCooldownSeconds() { return config.getInt("settings.teleport-cooldown-seconds", 60); }
    public boolean enableXpShare() { return config.getBoolean("settings.enable-xp-share", true); }
    public double xpSharePercent() { return config.getDouble("settings.xp-share-percent", 20.0D); }
    public boolean kickOfflineMembers() { return config.getBoolean("settings.kick-offline-members", true); }
    public int offlineKickSeconds() { return config.getInt("settings.offline-kick-seconds", 300); }
    public boolean deleteLeaderOffline() { return config.getBoolean("settings.delete-party-when-leader-offline", true); }
    public int confirmSeconds() { return config.getInt("settings.confirm-seconds", 10); }
    public boolean clickableText() { return config.getBoolean("settings.clickable-text", true); }
    public String gui(String path) { return TextUtil.color(config.getString("gui." + path, "")); }
    public String sound(String path) { return config.getString(path).toUpperCase(); }
    public String setting(String path) { return config.getString("message-settings." + path); }
    public int maxLength() { return config.getInt("settings.max-length"); }

    public Material material(String path, Material def) {
        String raw = config.getString(path);
        if (raw == null) return def;
        try { return Material.valueOf(raw.toUpperCase()); } catch (IllegalArgumentException e) { return def; }
    }

    public String itemName(String path, String def) { return TextUtil.color(config.getString(path + ".name", def)); }
    public int itemcmd(String path, int def) { return config.getInt(path + ".custom_model_data", def); }

    public void setConfig(FileConfiguration config) {
        this.config = config;
    }
}

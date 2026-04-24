package kr.antos112.partyadder;

import kr.antos112.partyadder.command.PartyCommand;
import kr.antos112.partyadder.config.PluginConfig;
import kr.antos112.partyadder.db.DatabaseManager;
import kr.antos112.partyadder.db.JdbcPartyRepository;
import kr.antos112.partyadder.listener.PartyListener;
import kr.antos112.partyadder.service.PartyService;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PartyAdder extends JavaPlugin {
    private static PluginConfig configMessages;
    private DatabaseManager databaseManager;
    private JdbcPartyRepository repository;
    private static PartyService partyService;
    private final Map<String, Long> confirmMap = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> offlineTasks = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        init();
        register();
        partyService.load();
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> partyService.expirePending(), 20L * 30L, 20L * 30L);
        getLogger().info("PartyAdder enabled");
    }

    private void init() {
        FileConfiguration cfg = getConfig();
        configMessages = new PluginConfig(cfg);
        this.databaseManager = new DatabaseManager(this);
        this.databaseManager.init();
        repository = new JdbcPartyRepository(databaseManager);
        repository.initTables();
        partyService = new PartyService(this, configMessages, repository);
    }

    private void register() {
        PartyCommand command = new PartyCommand(this, partyService);
        getCommand("파티").setExecutor(command);
        getCommand("파티").setTabCompleter(command);
        Bukkit.getPluginManager().registerEvents(new PartyListener(partyService, configMessages), this);
    }

    public void reloadEverything() {
        reloadConfig();
        configMessages.setConfig(getConfig());
    }

    @Override
    public void onDisable() {
        offlineTasks.values().forEach(BukkitTask::cancel);
        offlineTasks.clear();
        if (databaseManager != null) databaseManager.close();
    }

    public void async(Runnable task) {
        Bukkit.getScheduler().runTaskAsynchronously(this, task);
    }

    public void registerOfflineTask(UUID uuid, BukkitTask task) {
        BukkitTask old = offlineTasks.put(uuid, task);
        if (old != null) old.cancel();
    }

    public void cancelOfflineTask(UUID uuid) {
        BukkitTask task = offlineTasks.remove(uuid);
        if (task != null) task.cancel();
    }

    public Map<String, Long> getConfirmMap() { return confirmMap; }
    public static PluginConfig getConfigMessages() { return configMessages; }
    public static PartyService getPartyService() { return partyService; }
}

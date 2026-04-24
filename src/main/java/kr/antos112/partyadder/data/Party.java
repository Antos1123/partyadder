package kr.antos112.partyadder.data;

import kr.antos112.partyadder.PartyAdder;
import kr.antos112.partyadder.service.PartyService;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Party {
    private final long id;
    private String name;
    private UUID leader;
    private boolean publicParty;
    private boolean pvpEnabled;
    private final Map<UUID, PartyMember> members = new ConcurrentHashMap<>();

    public Party(long id, String name, UUID leader, boolean publicParty, boolean pvpEnabled) {
        this.id = id;
        this.name = name;
        this.leader = leader;
        this.publicParty = publicParty;
        this.pvpEnabled = pvpEnabled;
    }

    public long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public UUID getLeader() { return leader; }
    public void setLeader(UUID leader) { this.leader = leader; }
    public boolean isPublicParty() { return publicParty; }
    public void setPublicParty(boolean publicParty) { this.publicParty = publicParty; }
    public boolean isPvpEnabled() { return pvpEnabled; }
    public void setPvpEnabled(boolean pvpEnabled) { this.pvpEnabled = pvpEnabled; }
    public Map<UUID, PartyMember> getMembers() { return members; }
    public int size() { return members.size(); }
    public boolean isLeader(UUID uuid) { return leader != null && leader.equals(uuid); }
    public void sucessBroadcast(String m) {
        for (UUID member : members.keySet()) {
            Player viewer = Bukkit.getPlayer(member);
            if (viewer != null && viewer.isOnline()) {
                if (isLeader(viewer.getUniqueId())) continue;
                viewer.sendMessage("㌳㌴㌴㌴§a" + m + " §7(§f㖓 §7파티공지)");
                viewer.playSound(viewer.getLocation(), Sound.valueOf(PartyAdder.getConfigMessages().sound("sound.sucess-sound")), 0.8f, 1.0f);
            }
        }
    }
    public void errorBroadcast(String m) {
        for (UUID member : members.keySet()) {
            Player viewer = Bukkit.getPlayer(member);
            if (viewer != null && viewer.isOnline()) {
                if (isLeader(viewer.getUniqueId())) continue;
                viewer.sendMessage("ሱ㌴㌴㌴§c" + m + " §7(§f㖓 §7파티공지)");
                viewer.playSound(viewer.getLocation(), Sound.valueOf(PartyAdder.getConfigMessages().sound("sound.error-sound")), 0.8f, 1.0f);
            }
        }
    }
}

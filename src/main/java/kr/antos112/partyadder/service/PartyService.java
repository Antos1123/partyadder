package kr.antos112.partyadder.service;

import kr.antos112.partyadder.PartyAdder;
import kr.antos112.partyadder.config.PluginConfig;
import kr.antos112.partyadder.data.*;
import kr.antos112.partyadder.db.PartyRepository;
import kr.antos112.partyadder.gui.GuiHolder;
import kr.antos112.partyadder.gui.ItemBuilder;
import kr.antos112.partyadder.util.TextUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.inventory.view.AnvilView;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class PartyService {
    private final PartyAdder plugin;
    private final PluginConfig cfg;
    private final PartyRepository repo;

    private final Map<Long, Party> partiesById = new ConcurrentHashMap<>();
    private final Map<String, Long> partyNameIndex = new ConcurrentHashMap<>();
    private final Map<UUID, Long> memberIndex = new ConcurrentHashMap<>();

    private final Map<UUID, Map<Long, PendingInvite>> pendingInvites = new ConcurrentHashMap<>();
    private final Map<Long, Map<UUID, PendingRequest>> pendingRequests = new ConcurrentHashMap<>();
    private final Map<UUID, Long> teleportCooldownUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> partyChatMode = new ConcurrentHashMap<>();
    private final Set<UUID> xpBypass = ConcurrentHashMap.newKeySet();

    public PartyService(PartyAdder plugin, PluginConfig cfg, PartyRepository repo) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.repo = repo;
    }

    public void load() {
        Map<Long, Party> loaded = repo.loadAll();
        partiesById.clear();
        partyNameIndex.clear();
        memberIndex.clear();
        partiesById.putAll(loaded);
        for (Party party : loaded.values()) {
            partyNameIndex.put(normalize(party.getName()), party.getId());
            for (UUID uuid : party.getMembers().keySet()) {
                memberIndex.put(uuid, party.getId());
            }
        }
    }

    public Collection<Party> getAllParties() {
        return partiesById.values();
    }

    public Party getParty(UUID uuid) {
        Long id = memberIndex.get(uuid);
        return id == null ? null : partiesById.get(id);
    }

    public Party getPartyByName(String name) {
        Long id = partyNameIndex.get(normalize(name));
        return id == null ? null : partiesById.get(id);
    }

    public boolean isLeader(UUID uuid) {
        Party party = getParty(uuid);
        return party != null && party.isLeader(uuid);
    }

    public boolean isInParty(UUID uuid) {
        return memberIndex.containsKey(uuid);
    }

    public boolean canUseTeleport(UUID uuid) {
        long until = teleportCooldownUntil.getOrDefault(uuid, 0L);
        return System.currentTimeMillis() >= until;
    }

    public long remainingTeleportSeconds(UUID uuid) {
        long until = teleportCooldownUntil.getOrDefault(uuid, 0L);
        long diff = Math.max(0L, until - System.currentTimeMillis());
        return (diff + 999L) / 1000L;
    }

    public boolean createParty(Player player, String name) {
        if (isInParty(player.getUniqueId())) {
            sendErrorMessage(player, "이미 파티에 소속되어 있습니다");
            return false;
        }

        if (name.isBlank()) {
            sendErrorMessage(player, "파티 이름을 입력해주세요");
            return false;
        }

        if (name.length() > cfg.maxLength()) {
            sendErrorMessage(player, "파티 이름은 최대 " + cfg.maxLength() + "글자까지만 설정할 수 있습니다");
            return false;
        }

        if (getPartyByName(name) != null) {
            sendErrorMessage(player, "이미 같은 이름을 가진 파티가 존재합니다");
            return false;
        }

        if (name.contains(" ")) {
            sendErrorMessage(player,"파티 이름에 띄워쓰기는 포함될 수 없습니다");
            return false;
        }

        try {
            long id = repo.createParty(name, player.getUniqueId(), true, false);
            Party party = new Party(id, name, player.getUniqueId(), true, false);
            PartyMember leader = new PartyMember(player.getUniqueId(), Role.LEADER, System.currentTimeMillis(), System.currentTimeMillis());
            party.getMembers().put(player.getUniqueId(), leader);
            partiesById.put(id, party);
            partyNameIndex.put(normalize(name), id);
            memberIndex.put(player.getUniqueId(), id);
            plugin.async(() -> repo.addMember(id, leader));
            sendSucessMessage(player,"파티 '" + name + "'(이)가 생성되었습니다");
            return true;
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Failed to create party: " + e.getMessage());
            player.sendMessage(TextUtil.color("&c파티 생성에 실패했습니다."));
            return false;
        }
    }

    public boolean requestDelete(Player player, String partyName) {
        Party party = getParty(player.getUniqueId());
        if (party == null || !party.isLeader(player.getUniqueId())) {
            sendErrorMessage(player, "파티장만 사용할 수 있습니다");
            return false;
        }
        if (!party.getName().equalsIgnoreCase(partyName)) {
            sendErrorMessage(player, "해당 파티를 찾을 수 없습니다");
            return false;
        }
        String key = "delete:" + player.getUniqueId();
        Long ts = plugin.getConfirmMap().get(key);
        long now = System.currentTimeMillis();
        if (ts == null || now - ts > cfg.confirmSeconds() * 1000L) {
            plugin.getConfirmMap().put(key, now);
            sendSucessMessage(player, "같은 명령어를 " + cfg.confirmSeconds() + "초 안에 다시 입력하면 삭제됩니다");
            return false;
        }
        plugin.getConfirmMap().remove(key);
        sendSucessMessage(player, "성공적으로 파티가 삭제되었습니다");
        deleteParty(party, true, "파티장 " + player.getName() + "님이 파티를 삭제하였습니다");
        return true;
    }

    public void deleteParty(Party party, boolean broadcast, String m) {
        partiesById.remove(party.getId());
        partyNameIndex.remove(normalize(party.getName()));
        for (UUID member : new ArrayList<>(party.getMembers().keySet())) {
            memberIndex.remove(member);
        }
        plugin.async(() -> repo.deleteParty(party.getId()));
        for (UUID member : party.getMembers().keySet()) {
            Player p = Bukkit.getPlayer(member);
            if (p != null && p.isOnline()) {
                if (p.getOpenInventory().getTopInventory().getHolder() instanceof GuiHolder) p.closeInventory();
            }
            plugin.cancelOfflineTask(member);
        }

        if (broadcast) {
            party.errorBroadcast(m);
        }
    }

    public boolean invite(Player leader, String targetName) {
        Party party = getParty(leader.getUniqueId());
        if (party == null) {  return false; }
        if (!party.isLeader(leader.getUniqueId())) { sendErrorMessage(leader, "파티장만 사용할 수 있는 명령어입니다"); return false; }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) { sendErrorMessage(leader, "대상을 찾을 수 없습니다"); return false; }
        if (isInParty(target.getUniqueId())) { sendErrorMessage(leader, "대상은 이미 파티에 소속되어 있습니다"); return false; }
        if (party.size() >= cfg.maxMembers()) { sendErrorMessage(leader, "파티가 이미 가득 찼습니다"); return false; }

        long expire = System.currentTimeMillis() + cfg.confirmSeconds() * 1000L;
        pendingInvites.computeIfAbsent(target.getUniqueId(), k -> new ConcurrentHashMap<>())
                .put(party.getId(), new PendingInvite(leader.getUniqueId(), party.getId(), target.getUniqueId(), expire));
        sendSucessMessage(leader, targetName + "님에게 초대를 보냈습니다");
        sendInviteMessage(target, party, leader.getName());
        return true;
    }

    public boolean acceptInvite(Player target, long partyId) {
        Map<Long, PendingInvite> invites = pendingInvites.get(target.getUniqueId());
        if (invites == null || !invites.containsKey(partyId)) {
            sendErrorMessage(target, "유효한 초대가 없습니다");
            return false;
        }
        PendingInvite invite = invites.remove(partyId);
        if (System.currentTimeMillis() > invite.getExpireAt()) {
            sendErrorMessage(target, "파티 초대가 만료되었습니다");
            return false;
        }
        Party party = partiesById.get(partyId);
        if (party == null) { sendErrorMessage(target, "해당 파티를 찾을 수 없습니다"); return false; }
        if (party.size() >= cfg.maxMembers()) { sendErrorMessage(target, "파티가 인원이 가득 찼습니다"); return false; }
        if (isInParty(target.getUniqueId())) { sendErrorMessage(target, "이미 파티에 소속되어있습니다"); return false; }

        joinParty(party, target.getUniqueId(), Role.MEMBER);
        sendSucessMessage(target, party.getName() + "파티에 가입되었습니다");
        party.sucessBroadcast(target.getName() + "님이 새롭게 파티에 가입하셨습니다");
        return true;
    }

    public boolean declineInvite(Player target, long partyId) {
        Map<Long, PendingInvite> invites = pendingInvites.get(target.getUniqueId());
        if (invites != null) invites.remove(partyId);
        sendErrorMessage(target, "초대를 거절하였습니다");
        Party party = partiesById.get(partyId);
        if (party == null) return false;
        Player p = Bukkit.getPlayer(party.getLeader());
        if (p == null) return false;
        sendErrorMessage(p, target.getName() + "님이 초대를 거절하였습니다");
        return true;
    }

    public boolean sendJoinRequest(Player player, String partyName) {
        if (isInParty(player.getUniqueId())) { sendErrorMessage(player, "이미 파티에 소속되어 있습니다"); return false; }
        Party party = getPartyByName(partyName);
        if (party == null) { sendErrorMessage(player, "해당 파티를 찾을 수 없습니다"); return false; }
        if (!party.isPublicParty()) { sendErrorMessage(player, "해당 파티는 비공개 파티입니다"); return false; }
        if (party.size() >= cfg.maxMembers()) { sendErrorMessage(player, "해당 파티에 인원이 이미 가득 차 있습니다"); return false; }

        long expire = System.currentTimeMillis() + cfg.confirmSeconds() * 1000L;
        pendingRequests.computeIfAbsent(party.getId(), k -> new ConcurrentHashMap<>())
                .put(player.getUniqueId(), new PendingRequest(party.getLeader(), party.getId(), player.getUniqueId(), expire));
        sendSucessMessage(player, "파티 '" + partyName + "'에 가입신청을 보냈습니다");

        Player leader = Bukkit.getPlayer(party.getLeader());
        if (leader != null) {
            sendJoinRequestMessage(leader, player.getName(), party);
        }
        return true;
    }

    public boolean acceptJoinRequest(Player leader, UUID requester) {
        Party party = getParty(leader.getUniqueId());
        if (party == null || !party.isLeader(leader.getUniqueId())) { sendErrorMessage(leader, "파티장만 사용할 수 있는 명령어입니다"); return false; }
        Map<UUID, PendingRequest> requests = pendingRequests.get(party.getId());
        if (requests == null || !requests.containsKey(requester)) {
            leader.sendMessage(TextUtil.color("&c유효한 가입신청이 없습니다."));
            return false;
        }
        PendingRequest request = requests.remove(requester);
        if (System.currentTimeMillis() > request.getExpireAt()) {
            sendErrorMessage(leader, "가입신청이 만료되었습니다");
            return false;
        }
        Player target = Bukkit.getPlayer(requester);
        if (target == null) {
            sendErrorMessage(leader, "해당 유저가 오프라인 상태입니다");
            return false;
        }
        if (party.size() >= cfg.maxMembers()) { sendErrorMessage(leader, "파티가 이미 가득 찼습니다"); return false; }
        joinParty(party, requester, Role.MEMBER);
        sendSucessMessage(leader, "가입신청을 수락하였습니다");
        sendSucessMessage(target, "성공적으로 파티에 가입되었습니다");
        party.sucessBroadcast(target.getName() + "님이 새롭게 파티에 가입하였습니다");
        return true;
    }

    public boolean declineJoinRequest(Player leader, UUID requester) {
        Party party = getParty(leader.getUniqueId());
        if (party == null || !party.isLeader(leader.getUniqueId())) { sendErrorMessage(leader, "파티장만 사용할 수 있는 명령어입니다"); return false; }
        Map<UUID, PendingRequest> requests = pendingRequests.get(party.getId());
        if (requests != null) requests.remove(requester);
        sendErrorMessage(leader, "가입신청을 거절하였습니다");
        Player p = Bukkit.getPlayer(requester);
        if (p == null) return false;
        sendErrorMessage(p, "가입신청이 거절되었습니다");
        return true;
    }

    public boolean kick(Player leader, String targetName) {
        Party party = getParty(leader.getUniqueId());
        if (party == null) { sendErrorMessage(leader, "소속된 파티가 없습니다"); return false; }
        if (!party.isLeader(leader.getUniqueId())) { sendErrorMessage(leader, "파티장만 사용할 수 있는 명령어입니다"); return false; }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) { sendErrorMessage(leader, "오프라인인 파티원은 '/파티 메뉴'를 통해 추방해주세요"); return false; }
        if (!party.getMembers().containsKey(target.getUniqueId())) { sendErrorMessage(leader, "대상이 파티에 소속되어 있지않습니다"); return false; }
        if (target.getUniqueId().equals(leader.getUniqueId())) {
            sendErrorMessage(leader, "자기 자신은 추방할 수 없습니다");
            return false;
        }
        removeMember(party, target.getUniqueId(), true);
        sendSucessMessage(leader, "성공적으로 " + targetName + "님을 추방하셨습니다");
        sendErrorMessage(target, "파티에서 추방되셨습니다");
        party.errorBroadcast("파티장 " + leader.getName() + "님이 파티원 " + targetName + "님을 추방하셨습니다");
        return true;
    }

    public boolean transfer(Player leader, String targetName) {
        Party party = getParty(leader.getUniqueId());
        if (party == null) { sendErrorMessage(leader, "소속된 파티가 없습니다"); return false; }
        if (!party.isLeader(leader.getUniqueId())) { sendErrorMessage(leader, "파티장만 사용할 수 있는 명령어입니다"); return false; }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) { sendErrorMessage(leader, "오프라인인 플레이어에게 파티를 양도할 경우 '/파티 메뉴'를 이용해주세요"); return false; }
        if (!party.getMembers().containsKey(target.getUniqueId())) { sendErrorMessage(leader, "대상이 파티에 소속되어 있지않습니다"); return false; }
        if (target.getUniqueId().equals(leader.getUniqueId())) return false;
        party.setLeader(target.getUniqueId());
        party.getMembers().get(leader.getUniqueId()).setRole(Role.MEMBER);
        party.getMembers().get(target.getUniqueId()).setRole(Role.LEADER);
        plugin.async(() -> {
            repo.updateLeader(party.getId(), target.getUniqueId());
            repo.updateMemberRole(party.getId(), leader.getUniqueId(), Role.MEMBER);
            repo.updateMemberRole(party.getId(), target.getUniqueId(), Role.LEADER);
        });
        sendSucessMessage(leader, "성공적으로 파티원 " + targetName + "님에게 파티를 양도하였습니다");
        sendSucessMessage(target, "파티장 " + leader.getName() + "님으로부터 파티를 양도받으셨습니다");
        party.sucessBroadcast("파티장 " + leader.getName() + "님이 파티원 " + targetName + "님에게 파티를 양도하였습니다");
        return true;
    }

    public boolean rename(Player leader, String newName) {
        if (!cfg.allowRename()) { sendErrorMessage(leader, "현재 파티 이름변경이 비활성화되어 있습니다"); return false; }
        Party party = getParty(leader.getUniqueId());
        if (party == null) { sendErrorMessage(leader, "소속된 파티가 없습니다"); return false; }
        if (!party.isLeader(leader.getUniqueId())) { sendErrorMessage(leader, "파티장만 사용할 수 있는 명령어입니다"); return false; }
        if (getPartyByName(newName) != null) { sendErrorMessage(leader, "이미 해당 이름을 가진 파티가 존재합니다"); return false; }
        String oldNorm = normalize(party.getName());
        partyNameIndex.remove(oldNorm);
        party.setName(newName);
        partyNameIndex.put(normalize(newName), party.getId());
        plugin.async(() -> repo.renameParty(party.getId(), newName));
        sendSucessMessage(leader, "파티 이름이 성공적으로 " + newName + "(으)로 변경되었습니다");
        party.sucessBroadcast("파티장 " + leader.getName() + "님이 파티 이름을 " + newName + "(으)로 변경하였습니다");
        return true;
    }

    public boolean togglePvp(Player leader) {
        Party party = getParty(leader.getUniqueId());
        if (party == null) { sendErrorMessage(leader, "소속된 파티가 없습니다"); return false; }
        if (!party.isLeader(leader.getUniqueId())) { sendErrorMessage(leader, "파티장만 사용할 수 있는 명령어입니다"); return false; }
        return setPvp(leader, !party.isPvpEnabled());
    }

    public boolean setPvp(Player leader, boolean enabled) {
        Party party = getParty(leader.getUniqueId());
        if (party == null) { sendErrorMessage(leader, "소속된 파티가 없습니다"); return false; }
        if (!party.isLeader(leader.getUniqueId())) { sendErrorMessage(leader, "파티장만 사용할 수 있는 명령어입니다"); return false; }
        party.setPvpEnabled(enabled);
        plugin.async(() -> repo.updatePvp(party.getId(), enabled));
        sendSucessMessage(leader, enabled ? "파티 pvp가 활성화되었습니다" : "파티 pvp가 비활성화되었습니다");
        return true;
    }

    public boolean togglePublic(Player leader) {
        Party party = getParty(leader.getUniqueId());
        if (party == null) { sendErrorMessage(leader, "소속된 파티가 없습니다"); return false; }
        if (!party.isLeader(leader.getUniqueId())) { sendErrorMessage(leader, "파티장만 사용할 수 있는 명령어입니다"); return false; }
        return setPublic(leader, !party.isPublicParty());
    }

    public boolean setPublic(Player leader, boolean pub) {
        Party party = getParty(leader.getUniqueId());
        if (party == null) { sendErrorMessage(leader, "소속된 파티가 없습니다"); return false; }
        if (!party.isLeader(leader.getUniqueId())) { sendErrorMessage(leader, "파티장만 사용할 수 있는 명령어입니다"); return false; }
        party.setPublicParty(pub);
        plugin.async(() -> repo.updatePublic(party.getId(), pub));
        sendSucessMessage(leader, pub ? "파티가 공개로 설정되었습니다" : "파티가 비공개로 설정되었습니다");
        return true;
    }

    public boolean toggleChat(Player player) {
        if (!isInParty(player.getUniqueId())) { sendErrorMessage(player, "소속된 파티가 없습니다"); return false; }
        return setChatMode(player, !partyChatMode.getOrDefault(player.getUniqueId(), false));
    }

    public boolean setChatMode(Player player, boolean on) {
        if (!isInParty(player.getUniqueId())) { sendErrorMessage(player, "소속된 파티가 없습니다"); return false; }
        partyChatMode.put(player.getUniqueId(), on);
        sendSucessMessage(player, on ? "파티 채팅을 활성화하였습니다" : "파티 채팅을 비활성화하였습니다");
        return true;
    }

    public boolean teleportMember(Player leader, String targetName) {
        if (!cfg.allowTeleport()) { sendErrorMessage(leader, "파티원 tp가 비활성화되있습니다"); playErrorSound(leader); return false; }
        Party party = getParty(leader.getUniqueId());
        if (party == null) { sendErrorMessage(leader, "소속된 파티가 없습니다"); return false; }
        if (!party.isLeader(leader.getUniqueId())) { sendErrorMessage(leader, "파티장만 사용할 수 있는 명령어입니다"); playErrorSound(leader); return false; }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) { sendErrorMessage(leader, "대상을 찾을 수 없습니다"); playErrorSound(leader); return false; }
        if (!party.getMembers().containsKey(target.getUniqueId())) { sendErrorMessage(leader, "대상이 파티에 소속되어 있지않습니다"); playErrorSound(leader); return false; }
        if (target.getUniqueId().equals(leader.getUniqueId())) return false;
        if (!canUseTeleport(leader.getUniqueId())) {
            sendErrorMessage(leader, "아직 tp 쿨타임이 존재합니다. 남은 시간 : " + remainingTeleportSeconds(leader.getUniqueId()));
            return false;
        }
        target.teleportAsync(leader.getLocation());
        startTeleportCooldown(leader.getUniqueId());
        sendSucessMessage(leader, targetName + "님을 소환했습니다");
        sendSucessMessage(target, "파티장 " + leader.getName() + "님이 소환하셨습니다");
        return true;
    }

    public boolean teleportAll(Player leader) {
        if (!cfg.allowTeleport()) { leader.sendMessage(TextUtil.color("&c파티 TP가 비활성화되어 있습니다.")); return false; }
        Party party = getParty(leader.getUniqueId());
        if (party == null) { sendErrorMessage(leader, "소속된 파티가 없습니다"); return false; }
        if (!party.isLeader(leader.getUniqueId())) { sendErrorMessage(leader, "파티장만 사용할 수 있는 명령어입니다"); return false; }
        if (!canUseTeleport(leader.getUniqueId())) {
            sendErrorMessage(leader, "아직 tp 쿨타임이 존재합니다. 남은 시간 : " + remainingTeleportSeconds(leader.getUniqueId()));
            return false;
        }
        Location loc = leader.getLocation();
        for (UUID member : party.getMembers().keySet()) {
            Player p = Bukkit.getPlayer(member);
            if (p != null && p.isOnline() && !p.getUniqueId().equals(leader.getUniqueId())) {
                p.teleportAsync(loc);
            }
        }
        startTeleportCooldown(leader.getUniqueId());
        return true;
    }

    private void startTeleportCooldown(UUID uuid) {
        teleportCooldownUntil.put(uuid, System.currentTimeMillis() + cfg.teleportCooldownSeconds() * 1000L);
    }

    public boolean isPartyChatEnabled(UUID uuid) {
        return partyChatMode.getOrDefault(uuid, false);
    }

    public void sendPartyChat(Player sender, String message) {
        Party party = getParty(sender.getUniqueId());
        if (party == null) return;
        String raw = cfg.setting("chat-format")
                .replace("%player%", sender.getName())
                .replace("%message%", message);
        Component component = TextUtil.component(raw);
        for (UUID member : party.getMembers().keySet()) {
            Player viewer = Bukkit.getPlayer(member);
            if (viewer != null && viewer.isOnline()) viewer.sendMessage(component);
        }
    }

    public void shareXp(Player source, int exp) {
        if (!cfg.enableXpShare() || exp <= 0) return;
        Party party = getParty(source.getUniqueId());
        if (party == null) return;

        int shared = (int) Math.floor(exp * (cfg.xpSharePercent() / 100.0D));
        if (shared <= 0) return;

        for (UUID member : party.getMembers().keySet()) {
            if (member.equals(source.getUniqueId())) continue;
            Player viewer = Bukkit.getPlayer(member);
            if (viewer != null && viewer.isOnline()) {
                if (xpBypass.contains(viewer.getUniqueId())) continue;
                xpBypass.add(viewer.getUniqueId());
                try {
                    viewer.giveExp(shared);
                } finally {
                    xpBypass.remove(viewer.getUniqueId());
                }
            }
        }
        source.sendActionBar(Component.text("㌳㌴㌴㌴§a파티원과 경험치 " + shared + "을/를 공유하였습니다"));
    }

    public boolean isXpBypassed(UUID uuid) {
        return xpBypass.contains(uuid);
    }

    public void handleJoin(Player player) {
        plugin.cancelOfflineTask(player.getUniqueId());
    }

    public void handleQuit(Player player) {
        Party party = getParty(player.getUniqueId());
        if (party == null) return;

        if (!cfg.kickOfflineMembers()) return;
        scheduleOfflineKick(player.getUniqueId());
    }

    public void scheduleOfflineKick(UUID uuid) {
        plugin.cancelOfflineTask(uuid);
        long delay = cfg.offlineKickSeconds() * 20L;
        plugin.registerOfflineTask(uuid, Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player online = Bukkit.getPlayer(uuid);
            if (online != null && online.isOnline()) return;
            Party party = getParty(uuid);
            if (party == null) return;
            if (party.isLeader(uuid) && cfg.deleteLeaderOffline()) {
                deleteParty(party, true, "파티장이 오프라인이 되어 자동으로 파티가 삭제되었습니다");
            } else {
                removeMember(party, uuid, true);
                String name = Bukkit.getOfflinePlayer(uuid).getName() == null ? uuid.toString() : Bukkit.getOfflinePlayer(uuid).getName();
                party.errorBroadcast("파티원" + name + "님이 오프라인이 되어 자동으로 추방당하셨습니다");
            }
        }, delay));
    }

    public void cancelOfflineKick(UUID uuid) {
        plugin.cancelOfflineTask(uuid);
    }

    public void expirePending() {
        long now = System.currentTimeMillis();
        pendingInvites.values().forEach(map -> map.values().removeIf(v -> v.getExpireAt() < now));
        pendingRequests.values().forEach(map -> map.values().removeIf(v -> v.getExpireAt() < now));
    }

    public void joinParty(Party party, UUID uuid, Role role) {
        PartyMember member = new PartyMember(uuid, role, System.currentTimeMillis(), System.currentTimeMillis());
        party.getMembers().put(uuid, member);
        memberIndex.put(uuid, party.getId());
        plugin.async(() -> repo.addMember(party.getId(), member));
    }

    public void removeMember(Party party, UUID uuid, boolean notifyDb) {
        party.getMembers().remove(uuid);
        memberIndex.remove(uuid);
        if (notifyDb) plugin.async(() -> repo.removeMember(party.getId(), uuid));
    }

    public boolean hasPartyChatEnabled(UUID uuid) {
        return partyChatMode.getOrDefault(uuid, false);
    }

    public Component clickCommand(String label, String command) {
        Component c = TextUtil.component(label);
        return c.clickEvent(ClickEvent.runCommand(command)).hoverEvent(HoverEvent.showText(Component.text(command, NamedTextColor.YELLOW)));
    }

    public void sendInviteMessage(Player target, Party party, String inviterName) {
        if (!cfg.clickableText()) {
            sendSucessMessage(target, "파티 '" + party.getName() + "'에 초대되었습니다");
            target.sendMessage(TextUtil.color("&a/파티 초대수락 &7또는 &c/파티 초대거절"));
            return;
        }
        Component base = TextUtil.component("㌳㌴㌴㌴§a파티 '"+ party.getName() + "'에 초대되었습니다");
        Component accept = clickCommand(" &a[클릭 시 파티초대 수락]", "/파티 초대수락 " + party.getId());
        Component deny = clickCommand(" &c[클릭 시 파티초대 거절]", "/파티 초대거절 " + party.getId());
        target.sendMessage(base.append(accept).append(deny));
    }

    public void sendJoinRequestMessage(Player leader, String requesterName, Party party) {
        if (!cfg.clickableText()) {
            sendSucessMessage(leader, requesterName + "님이 파티 가입을 신청했습니다");
            leader.sendMessage(TextUtil.color("&a/파티 가입신청 수락 " + requesterName + " &7또는 &c/파티 가입신청 거절 " + requesterName));
            return;
        }
        Component base = TextUtil.component("㌳㌴㌴㌴§a"+ requesterName + "님이 파티 가입을 신청했습니다");
        Component accept = clickCommand(" &a[클릭 시 파티 가입신청 수락]", "/파티 가입신청 수락 " + requesterName);
        Component deny = clickCommand(" &c[클릭 시 파티 가입신청 거절]", "/파티 가입신청 거절 " + requesterName);
        leader.sendMessage(base.append(accept).append(deny));
    }

    public Inventory createMainMenu(Player player) {
        Party party = getParty(player.getUniqueId());
        GuiHolder holder = new GuiHolder(GuiHolder.Type.MAIN, player.getUniqueId(), party == null ? -1 : party.getId(), null, 0);
        Inventory inv = Bukkit.createInventory(holder, 54, TextUtil.component(cfg.gui("party-settings")));
        holder.setInventory(inv);
        if (party == null) {
            MiniMessage mm = MiniMessage.miniMessage();

            inv = Bukkit.createInventory(holder, 27, TextUtil.component(cfg.gui("main-title")));

            for (int i = 0; i<3; i++) {
                for (int j = 0; j<3; j++) {
                    inv.setItem(9*i+j, simpleItem("gui.items.public-list", Material.PAPER, mm.deserialize(cfg.itemName("gui.items.public-list", "&a공개 파티 목록")),
                            List.of(mm.deserialize(" "), mm.deserialize("<italic:false><#FFD8D8>공개 파티를 둘러보거나 랜덤 매칭을 사용할 수 있습니다."), mm.deserialize("<italic:false><#FFD8D8>다양한 파티에 참가해보세요!"), mm.deserialize(""), mm.deserialize("<italic:false><#FFFFFF>静 <#F15F5F>클릭 시 <#FFD8D8>파티 목록<#F15F5F>을 확인할 수 있습니다")),
                            cfg.itemcmd("gui.items.public-list", 1)));
                }
            }

            for (int i = 0; i<3; i++) {
                for (int j = 0; j<3; j++) {
                    inv.setItem(9*i+j+3, simpleItem("gui.items.random-match", Material.COMPASS, mm.deserialize(cfg.itemName("gui.items.random-match", "&6랜덤 매칭")),
                            List.of(mm.deserialize(""), mm.deserialize("<italic:false><#EAEAEA>비어 있는 공개 파티 중 한 곳에 무작위 매칭됩니다."), mm.deserialize("<italic:false><#EAEAEA>랜덤매칭 기능을 통해 다양한 유저들과 함께 플레이해보세요!"), mm.deserialize(""), mm.deserialize("<italic:false><#FFFFFF>静 <#8C8C8C>클릭 시 파티를 <#EAEAEA>랜덤매칭<#8C8C8C>합니다"))
                            , cfg.itemcmd("gui.items.random-match",1)));
                }
            }
        } else {
            int slot = 0;
            for (UUID memberId : party.getMembers().keySet()) {
                if (slot >= 45) break;
                if (tgtIsSelf(player, memberId)) inv.setItem(slot++, memberHead_2(party, memberId));
                else if (isLeader(player.getUniqueId())) inv.setItem(slot++, memberHead_1(party, memberId));
                else inv.setItem(slot++, memberHead_2(party, memberId));
            }

            MiniMessage mm = MiniMessage.miniMessage();
            // 파티 챗 토글
            inv.setItem(49, simpleItem("gui.items.toggle-chat", Material.PAPER, mm.deserialize(cfg.itemName("gui.items.toggle-chat", "<italic:false><#86E57F>[ <#FFFFFF>파티 <#B7F0B1>채팅 <#86E57F>]")), List.of(mm.deserialize("<italic:false><#FFFFFF>静 <#FFD8D8>클릭 시 <#F15F5F>파티 채팅<#FFD8D8>을 <#B7F0B1>활성화<#BDBDBD>/<#FFA7A7>비활성화<#FFD8D8>로 설정합니다"), mm.deserialize("<italic:false><#FFFFFF>㍧ <#BDBDBD>현재 상태 : " + (isPartyChatEnabled(player.getUniqueId()) ? "<#B7F0B1>활성화" : "<#FFA7A7>비활성화"))), cfg.itemcmd("gui.items.toggle-chat", 47)));
            if (isLeader(player.getUniqueId())) {
                // 초대 메뉴
                inv.setItem(45, simpleItem("gui.items.invite", Material.PAPER, mm.deserialize(cfg.itemName("gui.items.invite", "<italic:false><#86E57F>[ <#FFFFFF>플레이어 <#B7F0B1>초대하기 <#86E57F>]")), List.of(mm.deserialize("<italic:false><#FFFFFF>静 <#CEFBC9>클릭 시 <#86E57F>플레이어 초대 메뉴<#CEFBC9>를 엽니다")), cfg.itemcmd("gui.items.invite", 49)));
                // 가입신청 목록
                inv.setItem(46, simpleItem("gui.items.request", Material.PAPER, mm.deserialize(cfg.itemName("gui.items.request", "<italic:false><#86E57F>[ <#FFFFFF>가입신청 <#B7F0B1>목록 <#86E57F>]")), List.of(mm.deserialize("<italic:false><#FFFFFF>静 <#CEFBC9>클릭 시 <#86E57F>가입신청 목록<#CEFBC9>을 엽니다")), cfg.itemcmd("gui.items.request", 47)));
                // 파티 공개 설정
                inv.setItem(47, simpleItem("gui.items.toggle-public", Material.PAPER, mm.deserialize(cfg.itemName("gui.items.toggle-public", "<italic:false><#FFE400>[ <#FFFFFF>파티 <#FAED7D>공개 설정 <#FFE400>]")), List.of(mm.deserialize("<italic:false><#FFFFFF>静 <#FAF4C0>클릭 시 파티를 <#B7F0B1>공개<#BDBDBD>/<#FFA7A7>비공개<#FAF4C0>로 설정합니다"), mm.deserialize("<italic:false><#FFFFFF>㍧ <#BDBDBD>현재 상태 : " + (party.isPublicParty() ? "<#B7F0B1>공개" : "<#FFA7A7>비공개"))), cfg.itemcmd("gui.items.toggle-public", 47)));
                // pvp 토글
                inv.setItem(48, simpleItem("gui.items.toggle-pvp", Material.PAPER, mm.deserialize(cfg.itemName("gui.items.toggle-pvp", "<italic:false><#FFA7A7>[ <#FFD8D8>PVP <#FFFFFF>토글 <#FFA7A7>]")), List.of(mm.deserialize("<italic:false><#FFFFFF>静 <#FFD8D8>클릭 시 <#F15F5F>파티 pvp<#FFD8D8>를 <#B7F0B1>공개<#BDBDBD>/<#FFA7A7>비공개<#FFD8D8>로 설정합니다"), mm.deserialize("<italic:false><#FFFFFF>㍧ <#BDBDBD>현재 상태 : " + (party.isPvpEnabled() ? "<#B7F0B1>활성화" : "<#FFA7A7>비활성화"))), cfg.itemcmd("gui.items.toggle-pvp", 48)));
                //파티원 전체 소환
                inv.setItem(50, simpleItem("gui.items.teleport-all", Material.PAPER, mm.deserialize(cfg.itemName("gui.items.teleport-all", "<italic:false><#B2EBF4>[ <#FFFFFF>파티원 <#D4F4FA>전체 소환 <#B2EBF4>]")), List.of(mm.deserialize("<italic:false><#FFFFFF>静 <#D4F4FA>클릭 시 <#B2EBF4>모든 파티원을 현재 위치로 이동<#D4F4FA>합니다.")), cfg.itemcmd("gui.items.trleport-all", 47)));
                // 파티 이름 변경
                inv.setItem(51, simpleItem("gui.items.rename", Material.PAPER, mm.deserialize(cfg.itemName("gui.items.rename", "<italic:false><#86E57F>[ <#FFFFFF>파티 <#B7F0B1>이름변경 <#86E57F>]")), List.of(mm.deserialize("<italic:false><#FFFFFF>静 <#CEFBC9>클릭 시 <#86E57F>파티 이름을 변경<#CEFBC9>합니다")), cfg.itemcmd("gui.items.rename", 47)));
                // 파티 삭제
                inv.setItem(53, simpleItem("gui.items.delete", Material.PAPER, mm.deserialize(cfg.itemName("gui.items.delete", "<italic:false><#FFA7A7>[ <#FFFFFF>파티 <#FFD8D8>삭제 <#FFA7A7>]")), List.of(mm.deserialize("<italic:false><#FFFFFF>静 <#FFD8D8>클릭 시 <#F15F5F>파티를 삭제<#FFD8D8>합니다")), cfg.itemcmd("gui.items.delete", 47)));
            } else {
                // 파티 탈퇴
                inv.setItem(45, simpleItem("gui.items.party-quit", Material.PAPER, mm.deserialize(cfg.itemName("gui.items.party-quit", "<italic:false><#FFA7A7>[ <#FFFFFF>파티 <#FFD8D8>탈퇴 <#FFA7A7>]")), List.of(mm.deserialize("<italic:false><#FFFFFF>静 <#FFD8D8>클릭 시 <#F15F5F>파티를 탈퇴<#FFD8D8>합니다")), cfg.itemcmd("gui.items.party-quit", 47)));
            }
        }
        return inv;
    }

    public Inventory createPublicList(Player player, int page) {
        GuiHolder holder = new GuiHolder(GuiHolder.Type.PUBLIC_LIST, player.getUniqueId(), -1, null, page);
        Inventory inv = Bukkit.createInventory(holder, 54, TextUtil.component(cfg.gui("public-title")));
        holder.setInventory(inv);
        fillGlass(inv);

        List<Party> parties = new ArrayList<>();
        for (Party p : partiesById.values()) if (p.isPublicParty()) parties.add(p);
        parties.sort(Comparator.comparing(Party::getName, String.CASE_INSENSITIVE_ORDER));
        int start = page * 28;
        int idx = 0;
        for (int i = start; i < parties.size() && idx < 28; i++, idx++) {
            Party p = parties.get(i);
            inv.setItem(10 + (idx % 7) + (idx / 7) * 9, partyInfoItem(p));
        }
        inv.setItem(45, simpleItem("gui.items.back", Material.ARROW, cfg.itemName("gui.items.back", "&7뒤로"), List.of(Component.text("메인 메뉴로 돌아갑니다."))));
        inv.setItem(49, simpleItem("gui.items.random-match", Material.COMPASS, cfg.itemName("gui.items.random-match", "&6랜덤 매칭"), List.of(Component.text("공개 파티 중 한 곳에 매칭합니다."))));
        return inv;
    }

    public Inventory createMemberActionsMenu(Player player, UUID target) {
        Party party = getParty(player.getUniqueId());
        GuiHolder holder = new GuiHolder(GuiHolder.Type.MEMBER_ACTIONS, player.getUniqueId(), party == null ? -1 : party.getId(), target, 0);
        Inventory inv = Bukkit.createInventory(holder, 9, TextUtil.component(cfg.gui("member-actions-title")));
        holder.setInventory(inv);
        inv.setItem(4, memberHead(target));
        MiniMessage mm = MiniMessage.miniMessage();
        inv.setItem(0, simpleItem("gui.items.back", Material.PAPER, mm.deserialize(cfg.itemName("gui.items.back", "<italic:false><#FFA7A7>[ <#FFD8D8>파티 메뉴<#FFFFFF>로 돌아가기<#FFA7A7>]")), List.of(mm.deserialize("<italic:false><#FFFFFF>静 <#F15F5F>클릭 시 <#FFD8D8>파티 메뉴<#F15F5F>로 돌아갑니다")), cfg.itemcmd("gui.items.back", 47)));
        return inv;
    }

//    public Inventory createInviteList(Player player) {
//        Party party = getParty(player.getUniqueId());
//        int rows = cfg.guiRows("invite");
//        GuiHolder holder = new GuiHolder(GuiHolder.Type.INVITE_LIST, player.getUniqueId(), party == null ? -1 : party.getId(), null, 0);
//        Inventory inv = Bukkit.createInventory(holder, rows * 9, TextUtil.component(cfg.gui("invite-list-title")));
//        holder.setInventory(inv);
//        fillGlass(inv);
//        if (party == null) return inv;
//        int slot = 10;
//        for (Player online : Bukkit.getOnlinePlayers()) {
//            if (slot >= inv.getSize() - 9) break;
//            if (isInParty(online.getUniqueId())) continue;
//            inv.setItem(slot++, playerHead(online.getUniqueId(), online.getName()));
//        }
//        inv.setItem(inv.getSize() - 9, simpleItem("gui.items.back", Material.ARROW, cfg.itemName("gui.items.back", "&7뒤로"), List.of(Component.text("메인 메뉴로 돌아갑니다."))));
//        return inv;
//    }

//    public Inventory createRequestList(Player player) {
//        Party party = getParty(player.getUniqueId());
//        int rows = cfg.guiRows("request");
//        GuiHolder holder = new GuiHolder(GuiHolder.Type.REQUEST_LIST, player.getUniqueId(), party == null ? -1 : party.getId(), null, 0);
//        Inventory inv = Bukkit.createInventory(holder, rows * 9, TextUtil.component(cfg.gui("request-list-title")));
//        holder.setInventory(inv);
//        fillGlass(inv);
//        if (party == null) return inv;
//        Map<UUID, PendingRequest> map = pendingRequests.getOrDefault(party.getId(), Map.of());
//        int slot = 10;
//        for (PendingRequest req : map.values()) {
//            if (slot >= inv.getSize() - 9) break;
//            OfflinePlayer off = Bukkit.getOfflinePlayer(req.getRequester());
//            inv.setItem(slot++, playerHead(req.getRequester(), off.getName() == null ? req.getRequester().toString() : off.getName()));
//        }
//        inv.setItem(inv.getSize() - 9, simpleItem("gui.items.back", Material.ARROW, cfg.itemName("gui.items.back", "&7뒤로"), List.of(Component.text("메인 메뉴로 돌아갑니다."))));
//        return inv;
//    }

    public Inventory createKickMenu(Player player, UUID target) {
        Party party = getParty(player.getUniqueId());
        GuiHolder holder = new GuiHolder(GuiHolder.Type.KICK, player.getUniqueId(), party == null ? -1 : party.getId(), target, 0);
        Inventory inv = Bukkit.createInventory(holder, 27, TextUtil.component(cfg.gui("kick-title")));
        holder.setInventory(inv);

        MiniMessage mm = MiniMessage.miniMessage();

        for (int i = 19; i < 22; i++) {
            inv.setItem(i, simpleItem("gui.items.kick_accept", Material.PAPER, mm.deserialize(cfg.itemName("gui.items.kick_accept", "<italic:false><#B7F0B1>[ <#CEFBC9>클릭 시 파티원을 추방합니다 <#B7F0B1>]")), List.of(mm.deserialize("<italic:false><#FFFFFF>静 <#CEF279>클릭 시 <#ABF200>파티원을 추방<#CEF279>합니다")), cfg.itemcmd("gui.items.kick_accept",1)));
        }

        for (int i = 23; i < 26; i++) {
            inv.setItem(i, simpleItem("gui.items.kick_deny", Material.PAPER, mm.deserialize(cfg.itemName("gui.items.kick_deny", "<italic:false><#FFA7A7>[ <#FFD8D8>클릭 시 <#F15F5F>파티원 추방을 취소<#FFD8D8>합니다 <#FFA7A7>]")), List.of(mm.deserialize("<italic:false><#FFFFFF>静 <#FFD8D8>클릭 시 <#F15F5F>파티원 추방을 취소<#FFD8D8>합니다")), cfg.itemcmd("gui.items.kick_deny",1)));
        }

        return inv;
    }

    public Inventory createDeleteMenu(Player player) {
        Party party = getParty(player.getUniqueId());
        GuiHolder holder = new GuiHolder(GuiHolder.Type.DELETE, player.getUniqueId(), party == null ? -1 : party.getId(), null, 0);
        Inventory inv = Bukkit.createInventory(holder, 27, TextUtil.component(cfg.gui("delete-title")));
        holder.setInventory(inv);

        MiniMessage mm = MiniMessage.miniMessage();

        for (int i = 19; i < 22; i++) {
            inv.setItem(i, simpleItem("gui.items.delete_accept", Material.PAPER, mm.deserialize(cfg.itemName("gui.items.delete_accept", "<italic:false><#B7F0B1>[ <#CEFBC9>클릭 시 파티를 삭제합니다 <#B7F0B1>]")), List.of(mm.deserialize("<italic:false><#FFFFFF>静 <#CEF279>클릭 시 <#ABF200>파티를 삭제<#CEF279>합니다")), cfg.itemcmd("gui.items.delete_accept",1)));
        }

        for (int i = 23; i < 26; i++) {
            inv.setItem(i, simpleItem("gui.items.delete_deny", Material.PAPER, mm.deserialize(cfg.itemName("gui.items.delete_deny", "<italic:false><#FFA7A7>[ <#FFD8D8>클릭 시 <#F15F5F>파티 삭제를 취소<#FFD8D8>합니다 <#FFA7A7>]")), List.of(mm.deserialize("<italic:false><#FFFFFF>静 <#FFD8D8>클릭 시 <#F15F5F>파티 삭제를 취소<#FFD8D8>합니다")), cfg.itemcmd("gui.items.delete_deny",1)));
        }

        return inv;
    }

    public Inventory createRenameMenu(Player player) {
        Party party = getParty(player.getUniqueId());
        GuiHolder holder = new GuiHolder(GuiHolder.Type.RENAME, player.getUniqueId(), party == null ? -1 : party.getId(), null, 0);
        Inventory inv = Bukkit.createInventory(holder, InventoryType.ANVIL, TextUtil.component(cfg.gui("rename-title")));
        holder.setInventory(inv);

        MiniMessage mm = MiniMessage.miniMessage();

        inv.setItem(0, simpleItem("gui.items.blank", Material.PAPER, mm.deserialize(""), List.of(mm.deserialize("")), cfg.itemcmd("gui.items.blank", 1)));
        inv.setItem(1, simpleItem("gui.items.rename_accept", Material.PAPER, mm.deserialize(cfg.itemName("gui.items.rename_accept", "<italic:false><#B7F0B1>[ <#CEFBC9>클릭 시 파티 이름을 변경합니다 <#B7F0B1>]")), List.of(mm.deserialize("<italic:false><#FFFFFF>静 <#CEF279>클릭 시 <#ABF200>파티 이름을 변경<#CEF279>합니다")), cfg.itemcmd("gui.items.rename_accept",1)));
        inv.setItem(2, simpleItem("gui.items.rename_deny", Material.PAPER, mm.deserialize(cfg.itemName("gui.items.rename_deny", "<italic:false><#FFA7A7>[ <#FFD8D8>클릭 시 <#F15F5F>파티 이름 변경을 취소<#FFD8D8>합니다 <#FFA7A7>]")), List.of(mm.deserialize("<italic:false><#FFFFFF>静 <#FFD8D8>클릭 시 <#F15F5F>파티 이름 변경을 취소<#FFD8D8>합니다")), cfg.itemcmd("gui.items.rename_deny",1)));

        return inv;
    }

    public Inventory createTransferMenu(Player player, UUID target) {
        Party party = getParty(player.getUniqueId());
        GuiHolder holder = new GuiHolder(GuiHolder.Type.TRANSFER, player.getUniqueId(), party == null ? -1 : party.getId(), target, 0);
        Inventory inv = Bukkit.createInventory(holder, 27, TextUtil.component(cfg.gui("transfer-title")));
        holder.setInventory(inv);

        MiniMessage mm = MiniMessage.miniMessage();

        for (int i = 19; i < 22; i++) {
            inv.setItem(i, simpleItem("gui.items.transfer_accept", Material.PAPER, mm.deserialize(cfg.itemName("gui.items.transfer_accept", "<italic:false><#B7F0B1>[ <#CEFBC9>클릭 시 파티원에게 파티를 위임합니다 <#B7F0B1>]")), List.of(mm.deserialize("<italic:false><#FFFFFF>静 <#CEF279>클릭 시 <#ABF200>파티를 양도<#CEF279>합니다")), cfg.itemcmd("gui.items.transfer_accept",1)));
        }

        for (int i = 23; i < 26; i++) {
            inv.setItem(i, simpleItem("gui.items.transfer_deny", Material.PAPER, mm.deserialize(cfg.itemName("gui.items.transfer_deny", "<italic:false><#FFA7A7>[ <#FFD8D8>클릭 시 <#F15F5F>파티 양도를 취소<#FFD8D8>합니다 <#FFA7A7>]")), List.of(mm.deserialize("<italic:false><#FFFFFF>静 <#FFD8D8>클릭 시 <#F15F5F>파티 양도를 취소<#FFD8D8>합니다")), cfg.itemcmd("gui.items.transfer_deny",1)));
        }

        return inv;
    }

    public void handleMainClick(Player player, int slot, InventoryClickEvent event) {
        Integer[] i1 = {0,1,2,9,10,11,18,19,20};
        Integer[] i2 = {3,4,5,12,13,14,21,22,23};
        Party party = getParty(player.getUniqueId());
        if (party == null) {
            if (Arrays.asList(i1).contains(slot)) {
                player.playSound(player.getLocation(), Sound.valueOf(PartyAdder.getConfigMessages().sound("sound.sucess-sound")), 0.8f, 1.0f);
                player.openInventory(createPublicList(player, 0));
            }
            else if (Arrays.asList(i2).contains(slot)) {
                randomMatch(player);
            }
            return;
        }
//        if (slot == 12) player.openInventory(createMembersMenu(player));
//        else if (slot == 14 && party.isLeader(player.getUniqueId())) player.openInventory(createInviteList(player));
//        else if (slot == 16 && party.isLeader(player.getUniqueId())) player.openInventory(createRequestList(player));
//        else if (slot == 28 && party.isLeader(player.getUniqueId())) togglePublic(player);
//        else if (slot == 30 && party.isLeader(player.getUniqueId())) togglePvp(player);
//        else if (slot == 32) toggleChat(player);
//        else if (slot == 34 && party.isLeader(player.getUniqueId())) player.openInventory(createMembersMenu(player));
//        else if (slot == 39 && party.isLeader(player.getUniqueId())) teleportAll(player);
//        else if (slot == 41 && party.isLeader(player.getUniqueId())) {
//            player.sendMessage(TextUtil.color("&e명령어로 입력하세요: &f/파티 이름변경 <새 이름>"));
//            player.closeInventory();
//        } else if (slot == 43 && party.isLeader(player.getUniqueId())) {
//            player.sendMessage(TextUtil.color("&c삭제 확인을 위해 /파티 삭제 " + party.getName() + " 를 다시 입력하세요."));
//            player.closeInventory();
//        }
        switch (slot) {
            case 49 -> {
                toggleChat(player);
                player.openInventory(createMainMenu(player));
                return;
            }
        }

        if (isLeader(player.getUniqueId())) {
            switch (slot) {
                case 48 -> {
                    togglePvp(player);
                    player.openInventory(createMainMenu(player));
                    return;
                }
                case 51 -> {
                    player.openInventory(createRenameMenu(player));
                    playSucessSound(player);
                    return;
                }
                case 53 -> {
                    player.openInventory(createDeleteMenu(player));
                    playSucessSound(player);
                    return;
                }
            }
        }

        if (event.getCurrentItem() != null){
            handleMembersClick(player, slot);
        }
    }

    public void handlePublicClick(Player player, int slot, int page) {
        List<Party> parties = new ArrayList<>();
        for (Party p : partiesById.values()) if (p.isPublicParty()) parties.add(p);
        parties.sort(Comparator.comparing(Party::getName, String.CASE_INSENSITIVE_ORDER));
        int index = slot - 10;
        if (index >= 0 && index < 28 && page * 28 + index < parties.size()) {
            Party p = parties.get(page * 28 + index);
            sendJoinRequest(player, p.getName());
        } else if (slot == player.getOpenInventory().getTopInventory().getSize() - 9) {
            player.openInventory(createMainMenu(player));
        } else if (slot == player.getOpenInventory().getTopInventory().getSize() - 5) {
            randomMatch(player);
        }
    }

    public void handleKickClick(Player player, UUID target, int slot) {
        Party party = getParty(player.getUniqueId());

        if (slot == 19 || slot == 20 || slot == 21) {
            removeMember(party,target,true);
            OfflinePlayer off = Bukkit.getOfflinePlayer(target);
            sendSucessMessage(player, off.getName() + "님을 파티에서 추방했습니다");
            if (off.isOnline()) {
                sendErrorMessage((Player) off, "파티장 " + player.getName() + "님이 당신을 파티에서 추방하였습니다");
                if (((Player) off).getOpenInventory().getTopInventory().getHolder() instanceof GuiHolder) ((Player) off).closeInventory();
            }
            party.errorBroadcast("파티장 " + player.getName() + "님이 파티원 " + off.getName() + "님을 추방하였습니다");
            player.openInventory(createMainMenu(player));
        } else if (slot == 23 || slot == 24 || slot == 25) {
            playSucessSound(player);
            player.openInventory(createMainMenu(player));
        }
    }

    public void handleTransferClick(Player player, UUID target, int slot) {
        Party party = getParty(player.getUniqueId());

        if (slot == 19 || slot == 20 || slot == 21) {
            party.setLeader(target);
            party.getMembers().get(player.getUniqueId()).setRole(Role.MEMBER);
            party.getMembers().get(target).setRole(Role.LEADER);
            plugin.async(() -> {
                repo.updateLeader(party.getId(), target);
                repo.updateMemberRole(party.getId(), player.getUniqueId(), Role.MEMBER);
                repo.updateMemberRole(party.getId(), target, Role.LEADER);
            });

            OfflinePlayer off = Bukkit.getOfflinePlayer(target);
            sendSucessMessage(player, off.getName() + "님에게 파티를 양도하였습니다");
            if (off.isOnline()) sendSucessMessage((Player) off, "파티장 " + player.getName() + "님이 당신에게 파티를 양도하였습니다");
            party.sucessBroadcast("파티장 " + player.getName() + "님이 파티원 " + off.getName() + "님에게 파티를 양도하였습니다");
            player.openInventory(createMainMenu(player));
        } else if (slot == 23 || slot == 24 || slot == 25) {
            playSucessSound(player);
            player.openInventory(createMainMenu(player));
        }
    }

    public void handleMembersClick(Player player, int slot) {
        Party party = getParty(player.getUniqueId());
        if (party == null) return;
        int index = slot;
        if (index < 0) return;
        List<UUID> ids = new ArrayList<>(party.getMembers().keySet());
        if (index >= ids.size()) return;
        if (!isLeader(player.getUniqueId())) return;
        if (tgtIsSelf(player, ids.get(index))) return;
        player.openInventory(createMemberActionsMenu(player, ids.get(index)));
        playSucessSound(player);
    }

    public void handleMemberActionsClick(Player player, int slot, UUID target, InventoryClickEvent event) {
        Party party = getParty(player.getUniqueId());
        if (party == null) return;
        if (slot == 0) {
            player.openInventory(createMainMenu(player));
            playSucessSound(player);
            return;
        }
        if (!party.isLeader(player.getUniqueId())) return;
        OfflinePlayer off = Bukkit.getOfflinePlayer(target);
        String targetName = off.getName() == null ? target.toString() : off.getName();
        if (event.getCurrentItem() != null) {
            if (event.getClick().isShiftClick()) {
                player.openInventory(createTransferMenu(player, target));
                playSucessSound(player);
            } else if (event.getClick().isRightClick()) {
                if (off.isOnline()) {
                    Player t = Bukkit.getPlayer(off.getUniqueId());
                    if (t == null) return;
                    teleportMember(player, targetName);
                    playSucessSound(player);
                } else {
                    sendErrorMessage(player, "해당 플레이어는 오프라인입니다");
                }
            } else if (event.getClick().isLeftClick()) {
                player.openInventory(createKickMenu(player, target));
                playSucessSound(player);
            }
        }
    }

    public void handleInviteListClick(Player player, int slot) {
        Party party = getParty(player.getUniqueId());
        if (party == null || !party.isLeader(player.getUniqueId())) return;
        if (slot == player.getOpenInventory().getTopInventory().getSize() - 9) {
            player.openInventory(createMainMenu(player));
            return;
        }
        int index = slot - 10;
        List<Player> targets = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) if (!isInParty(online.getUniqueId())) targets.add(online);
        if (index < 0 || index >= targets.size()) return;
        invite(player, targets.get(index).getName());
    }

    public void handleRequestListClick(Player player, int slot) {
        Party party = getParty(player.getUniqueId());
        if (party == null || !party.isLeader(player.getUniqueId())) return;
        if (slot == player.getOpenInventory().getTopInventory().getSize() - 9) {
            player.openInventory(createMainMenu(player));
            return;
        }
        int index = slot - 10;
        Map<UUID, PendingRequest> map = pendingRequests.getOrDefault(party.getId(), Map.of());
        List<UUID> requests = new ArrayList<>(map.keySet());
        if (index < 0 || index >= requests.size()) return;
        UUID requester = requests.get(index);
        acceptJoinRequest(player, requester);
        player.openInventory(createMainMenu(player));
    }

    public void handleDeleteClick(Player player, int slot) {
        Party party = getParty(player.getUniqueId());

        if (slot == 19 || slot == 20 || slot == 21) {
            sendSucessMessage(player, "성공적으로 파티를 삭제하였습니다");
            deleteParty(party, true, "파티장 " + player.getName() + "님이 파티를 삭제하셨습니다");
            player.closeInventory();
        } else if (slot == 23 || slot == 24 || slot == 25) {
            playSucessSound(player);
            player.openInventory(createMainMenu(player));
        }
    }

    public void handleRenameClick(Player player, int slot, AnvilView view) {
        if (slot == 1) {
            createParty(player, view.getRenameText());
            player.openInventory(createRenameMenu(player));
        } else if (slot == 2) {
            player.openInventory(createMainMenu(player));
            playSucessSound(player);
        }
    }


    public void randomMatch(Player player) {
        if (isInParty(player.getUniqueId())) {
            sendErrorMessage(player, "이미 파티에 소속되어있습니다");
            return;
        }
        List<Party> candidates = new ArrayList<>();
        for (Party p : partiesById.values()) {
            if (p.isPublicParty() && p.size() < cfg.maxMembers()) candidates.add(p);
        }
        if (candidates.isEmpty()) {
            sendErrorMessage(player, "매칭할 공개 파티가 없습니다");
            return;
        }
        Party selected = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        sendJoinRequest(player, selected.getName());
        sendSucessMessage(player, "성공적으로 파티 '" + selected.getName() + "'과 매칭되었습니다");
    }

    private boolean tgtIsSelf(Player leader, UUID target) { return leader.getUniqueId().equals(target); }

    private void fillGlass(Inventory inv) {
        ItemStack glass = ItemBuilder.item(cfg.material("gui.items.glass.material", Material.GRAY_STAINED_GLASS_PANE), TextUtil.component(cfg.itemName("gui.items.glass", " ")), List.of(), 1);
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) inv.setItem(i, glass);
        }
    }

    private ItemStack simpleItem(String path, Material def, String name, List<Component> lore) {
        return ItemBuilder.item(cfg.material(path + ".material", def), TextUtil.component(name), lore, 1);
    }

    private ItemStack simpleItem(String path, Material def, Component name, List<Component> lore) {
        return ItemBuilder.item(cfg.material(path + ".material", def), name, lore, 1);
    }

    private ItemStack simpleItem(String path, Material def, Component name, List<Component> lore, int cmd) {
        return ItemBuilder.item(cfg.material(path + ".material", def), name, lore, 1, cmd);
    }

    private ItemStack partyInfoItem(Party party) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("파티장: " + nameOf(party.getLeader()), NamedTextColor.YELLOW));
        lore.add(Component.text("인원: " + party.size() + "/" + cfg.maxMembers(), NamedTextColor.GRAY));
        lore.add(Component.text("공개: " + (party.isPublicParty() ? "ON" : "OFF"), NamedTextColor.GRAY));
        lore.add(Component.text("PVP: " + (party.isPvpEnabled() ? "ON" : "OFF"), NamedTextColor.GRAY));
        return ItemBuilder.item(Material.NETHER_STAR, TextUtil.component(cfg.itemName("gui.items.party-info", "&e파티 정보")), lore, 1);
    }

    private ItemStack memberHead(UUID uuid) {
        MiniMessage mm = MiniMessage.miniMessage();
        String name = nameOf(uuid);
        List<Component> lore = new ArrayList<>();
        lore.add(mm.deserialize("<italic:false><#FFFFFF>静 <#F15F5F>클릭 시 <#FFD8D8>멤버를 추방<#F15F5F>합니다"));
        lore.add(mm.deserialize("<italic:false><#FFFFFF>ㅩ <#F15F5F>클릭 시 <#FFD8D8>멤버를 tp<#F15F5F>시킵니다 <#BDBDBD>(쿨타임 : " + cfg.teleportCooldownSeconds() + "초)"));
        lore.add(mm.deserialize("<italic:false><#FFFFFF>ㅸ + 静 <#F15F5F>클릭 시 멤버에게 <#FFD8D8>파티를 위임<#F15F5F>합니다"));
        return playerHead_2(uuid, name, lore);
    }

    public void playSucessSound(Player player) {
        player.playSound(player.getLocation(), Sound.valueOf(PartyAdder.getConfigMessages().sound("sound.sucess-sound")), 0.8f, 1.0f);
    }

    public void playErrorSound(Player player) {
        player.playSound(player.getLocation(), Sound.valueOf(PartyAdder.getConfigMessages().sound("sound.error-sound")), 0.8f, 1.0f);
    }

    public void sendErrorMessage(Player p, String message) {
        p.sendMessage("ሱ㌴㌴㌴§c" + message);
        p.playSound(p.getLocation(), Sound.valueOf(PartyAdder.getConfigMessages().sound("sound.error-sound")), 0.8f, 1.0f);
    }

    public void sendSucessMessage(Player p, String message) {
        p.sendMessage("㌳㌴㌴㌴§a" + message);
        p.playSound(p.getLocation(), Sound.valueOf(PartyAdder.getConfigMessages().sound("sound.sucess-sound")), 0.8f, 1.0f);
    }

    private ItemStack memberHead_1(Party party, UUID uuid) {
        MiniMessage mm = MiniMessage.miniMessage();
        String name = nameOf(uuid);
        OfflinePlayer off = Bukkit.getOfflinePlayer(uuid);
        boolean online = off.isOnline();
        List<Component> lore = new ArrayList<>();
        lore.add(mm.deserialize("<italic:false><#FFFFFF>㍧ 상태: " + (online ? "<#86E57F>ONLINE" : "<#F15F5F>OFFLINE")));
        if (online && off.getPlayer() != null) {
            lore.add(mm.deserialize("<italic:false><#FFFFFF>㍩ 레벨: <#86E57F>" + off.getPlayer().getLevel()));
            lore.add(mm.deserialize("<italic:false><#FFFFFF>㍩ 경험치: <#FAF4C0>" + Math.round(off.getPlayer().getExp()*100) + "%"));
            lore.add(mm.deserialize("<italic:false><#FFFFFF>㍰ 체력: <#F15F5F>" + Math.ceil(off.getPlayer().getHealth())));
        }
        lore.add(mm.deserialize("<italic:false><#FFFFFF>㍱ 역할: <#B2CCFF>" + (party.isLeader(uuid) ? "파티장" : "파티원")));
        lore.add(mm.deserialize(""));
        lore.add(mm.deserialize("<italic:false><#FFFFFF>静 <#F15F5F>클릭 시 <#FFD8D8>멤버 설정창<#F15F5F>을 확인할 수 있습니다"));
        return playerHead(uuid, name, lore);
    }

    private ItemStack memberHead_2(Party party, UUID uuid) {
        MiniMessage mm = MiniMessage.miniMessage();
        String name = nameOf(uuid);
        OfflinePlayer off = Bukkit.getOfflinePlayer(uuid);
        boolean online = off.isOnline();
        List<Component> lore = new ArrayList<>();
        lore.add(mm.deserialize("<italic:false><#FFFFFF>㍧ 상태: " + (online ? "<#86E57F>ONLINE" : "<#F15F5F>OFFLINE")));
        if (online && off.getPlayer() != null) {
            lore.add(mm.deserialize("<italic:false><#FFFFFF>㍩ 레벨: <#86E57F>" + off.getPlayer().getLevel()));
            lore.add(mm.deserialize("<italic:false><#FFFFFF>㍩ 경험치: <#FAF4C0>" + Math.round(off.getPlayer().getExp()*100) + "%"));
            lore.add(mm.deserialize("<italic:false><#FFFFFF>㍰ 체력: <#F15F5F>" + Math.ceil(off.getPlayer().getHealth())));
        }
        lore.add(mm.deserialize("<italic:false><#FFFFFF>㍱ 역할: <#B2CCFF>" + (party.isLeader(uuid) ? "파티장" : "파티원")));
        return playerHead(uuid, name, lore);
    }

    private ItemStack playerHead(UUID uuid, String name) {
        return playerHead(uuid, name, List.of());
    }

    private ItemStack playerHead(UUID uuid, String name, List<Component> lore) {
        ItemStack stack = new ItemStack(Material.PLAYER_HEAD, 1);
        SkullMeta meta = (SkullMeta) stack.getItemMeta();
        if (meta != null) {
            meta.displayName(MiniMessage.miniMessage().deserialize("<italic:false><#FFA7A7>" + name + "의 <#FFFFFF>정보"));
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(uuid));
            if (!lore.isEmpty()) meta.lore(lore);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack playerHead_2(UUID uuid, String name, List<Component> lore) {
        ItemStack stack = new ItemStack(Material.PLAYER_HEAD, 1);
        SkullMeta meta = (SkullMeta) stack.getItemMeta();
        if (meta != null) {
            meta.displayName(MiniMessage.miniMessage().deserialize("<italic:false><#FFFFFF>파티원 <#FFA7A7>" + name + " <#FFFFFF>설정"));
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(uuid));
            if (!lore.isEmpty()) meta.lore(lore);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private String nameOf(UUID uuid) {
        OfflinePlayer off = Bukkit.getOfflinePlayer(uuid);
        return off.getName() == null ? uuid.toString().substring(0, 8) : off.getName();
    }

    private String normalize(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }
}

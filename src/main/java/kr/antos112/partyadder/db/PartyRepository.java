package kr.antos112.partyadder.db;

import kr.antos112.partyadder.data.Party;
import kr.antos112.partyadder.data.PartyMember;
import kr.antos112.partyadder.data.Role;

import java.util.Map;
import java.util.UUID;

public interface PartyRepository {
    Map<Long, Party> loadAll();
    long createParty(String name, UUID leader, boolean publicParty, boolean pvpEnabled);
    void deleteParty(long partyId);
    void renameParty(long partyId, String newName);
    void updateLeader(long partyId, UUID leader);
    void updatePublic(long partyId, boolean publicParty);
    void updatePvp(long partyId, boolean enabled);
    void addMember(long partyId, PartyMember member);
    void updateMemberRole(long partyId, UUID uuid, Role role);
    void updateMemberLastSeen(long partyId, UUID uuid, long lastSeen);
    void removeMember(long partyId, UUID uuid);
}

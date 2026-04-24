package kr.antos112.partyadder.data;

import java.util.UUID;

public class PartyMember {
    private final UUID uuid;
    private Role role;
    private long joinedAt;
    private long lastSeen;

    public PartyMember(UUID uuid, Role role, long joinedAt, long lastSeen) {
        this.uuid = uuid;
        this.role = role;
        this.joinedAt = joinedAt;
        this.lastSeen = lastSeen;
    }

    public UUID getUuid() { return uuid; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public long getJoinedAt() { return joinedAt; }
    public void setJoinedAt(long joinedAt) { this.joinedAt = joinedAt; }
    public long getLastSeen() { return lastSeen; }
    public void setLastSeen(long lastSeen) { this.lastSeen = lastSeen; }
}

package kr.antos112.partyadder.data;

import java.util.UUID;

public class PendingInvite {
    private final UUID partyLeader;
    private final long partyId;
    private final UUID target;
    private final long expireAt;

    public PendingInvite(UUID partyLeader, long partyId, UUID target, long expireAt) {
        this.partyLeader = partyLeader;
        this.partyId = partyId;
        this.target = target;
        this.expireAt = expireAt;
    }

    public UUID getPartyLeader() { return partyLeader; }
    public long getPartyId() { return partyId; }
    public UUID getTarget() { return target; }
    public long getExpireAt() { return expireAt; }
}

package kr.antos112.partyadder.data;

import java.util.UUID;

public class PendingRequest {
    private final UUID partyLeader;
    private final long partyId;
    private final UUID requester;
    private final long expireAt;

    public PendingRequest(UUID partyLeader, long partyId, UUID requester, long expireAt) {
        this.partyLeader = partyLeader;
        this.partyId = partyId;
        this.requester = requester;
        this.expireAt = expireAt;
    }

    public UUID getPartyLeader() { return partyLeader; }
    public long getPartyId() { return partyId; }
    public UUID getRequester() { return requester; }
    public long getExpireAt() { return expireAt; }
}

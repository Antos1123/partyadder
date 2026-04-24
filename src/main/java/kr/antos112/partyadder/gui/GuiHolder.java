package kr.antos112.partyadder.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

public class GuiHolder implements InventoryHolder {
    public enum Type {
        MAIN, PUBLIC_LIST, MEMBER_ACTIONS, INVITE_LIST, REQUEST_LIST, RANDOM_MATCH, KICK, TRANSFER, DELETE, RENAME
    }

    private final Type type;
    private final UUID owner;
    private final long partyId;
    private final UUID target;
    private final int page;
    private Inventory inventory;

    public GuiHolder(Type type, UUID owner, long partyId, UUID target, int page) {
        this.type = type;
        this.owner = owner;
        this.partyId = partyId;
        this.target = target;
        this.page = page;
    }

    public Type getType() { return type; }
    public UUID getOwner() { return owner; }
    public long getPartyId() { return partyId; }
    public UUID getTarget() { return target; }
    public int getPage() { return page; }

    @Override
    public Inventory getInventory() { return inventory; }
    public void setInventory(Inventory inventory) { this.inventory = inventory; }
}

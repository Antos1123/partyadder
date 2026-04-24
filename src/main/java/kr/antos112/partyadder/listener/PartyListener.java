package kr.antos112.partyadder.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import kr.antos112.partyadder.config.PluginConfig;
import kr.antos112.partyadder.data.Party;
import kr.antos112.partyadder.service.PartyService;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.view.AnvilView;

public class PartyListener implements Listener {
    private final PartyService service;
    private final PluginConfig cfg;

    public PartyListener(PartyService service, PluginConfig cfg) {
        this.service = service;
        this.cfg = cfg;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        service.handleJoin(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        service.handleQuit(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        Player player = e.getPlayer();
        if (!service.hasPartyChatEnabled(player.getUniqueId())) return;
        if (!service.isInParty(player.getUniqueId())) return;
        e.setCancelled(true);
        String msg = PlainTextComponentSerializer.plainText().serialize(e.message());
        service.sendPartyChat(player, msg);
    }

    @EventHandler(ignoreCancelled = true)
    public void onExp(org.bukkit.event.player.PlayerExpChangeEvent e) {
        if (service.isXpBypassed(e.getPlayer().getUniqueId())) return;
        service.shareXp(e.getPlayer(), e.getAmount());
    }

    @EventHandler(ignoreCancelled = true)
    public void onInv(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;
        if (!(e.getView().getTopInventory().getHolder() instanceof kr.antos112.partyadder.gui.GuiHolder holder)) return;
        e.setCancelled(true);
        int slot = e.getRawSlot();
        if (slot >= e.getView().getTopInventory().getSize()) return;
        switch (holder.getType()) {
            case MAIN -> service.handleMainClick(player, slot, e);
            case PUBLIC_LIST -> service.handlePublicClick(player, slot, holder.getPage());
            case MEMBER_ACTIONS -> service.handleMemberActionsClick(player, slot, holder.getTarget(), e);
            case INVITE_LIST -> service.handleInviteListClick(player, slot);
            case REQUEST_LIST -> service.handleRequestListClick(player, slot);
            case KICK -> service.handleKickClick(player, holder.getTarget(), slot);
            case TRANSFER -> service.handleTransferClick(player, holder.getTarget(), slot);
            case DELETE -> service.handleDeleteClick(player, slot);
            case RENAME -> {
                if (e.getView() instanceof AnvilView) {
                    service.handleRenameClick(player, slot, (AnvilView) e.getView());
                }
            }
        }
    }

    @EventHandler
    public void onPVP(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player && e.getEntity() instanceof Player)) { return; }
        Player d = (Player) e.getDamager();
        Player v = (Player) e.getEntity();

        Party p1 = service.getParty(d.getUniqueId());
        if (p1 == null) { return; }
        if (p1.isPvpEnabled()) { return; }

        Party p2 = service.getParty(v.getUniqueId());
        if (p2 == null) { return; }
        if (p2.isPvpEnabled()) { return; }
        if (!p1.getName().equals(p2.getName())) { return; }

        e.setCancelled(true);

        service.sendErrorMessage(d, "파티원끼리의 pvp가 비활성화되있습니다");
    }
}

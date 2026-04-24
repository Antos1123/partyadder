package kr.antos112.partyadder.command;

import kr.antos112.partyadder.PartyAdder;
import kr.antos112.partyadder.data.Party;
import kr.antos112.partyadder.service.PartyService;
import kr.antos112.partyadder.util.TextUtil;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class PartyCommand implements CommandExecutor, TabCompleter {
    private final PartyAdder plugin;
    private final PartyService service;

    public PartyCommand(PartyAdder plugin, PartyService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(TextUtil.color("&c플레이어만 사용할 수 있습니다."));
            return true;
        }
        if (args.length == 0) {
            printHelp(player);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "생성" -> {
                if (args.length < 2) {
                    player.playSound(player.getLocation(), Sound.valueOf(PartyAdder.getConfigMessages().sound("sound.error-sound")), 0.8f, 0.9f);
                    return usage(player, "/파티 생성 <파티 이름>");
                }
                service.createParty(player, join(args, 1));
            }
            case "삭제" -> {
                if (args.length < 2) return usage(player, "/파티 삭제 <파티 이름>");
                service.requestDelete(player, join(args, 1));
            }
            case "초대" -> {
                if (args.length < 2) return usage(player, "/파티 초대 <유저>");
                service.invite(player, args[1]);
            }
            case "초대수락" -> {
                if (args.length < 2) return usage(player, "/파티 초대수락 <파티ID>");
                service.acceptInvite(player, parseLong(args[1]));
            }
            case "초대거절" -> {
                if (args.length < 2) return usage(player, "/파티 초대거절 <파티ID>");
                service.declineInvite(player, parseLong(args[1]));
            }
            case "가입신청" -> {
                if (args.length < 2) return usage(player, "/파티 가입신청 <파티 이름>");
                if (args.length >= 3 && args[1].equals("수락")) {
                    acceptJoinRequest(player, join(args, 2));
                } else if (args.length >= 3 && args[1].equals("거절")) {
                    declineJoinRequest(player, join(args, 2));
                } else {
                    service.sendJoinRequest(player, join(args, 1));
                }
            }
            case "추방" -> {
                if (args.length < 2) return usage(player, "/파티 추방 <유저>");
                service.kick(player, args[1]);
            }
            case "위임" -> {
                if (args.length < 2) return usage(player, "/파티 위임 <유저>");
                service.transfer(player, args[1]);
            }
            case "이름변경" -> {
                if (args.length < 2) return usage(player, "/파티 이름변경 <파티 이름>");
                service.rename(player, join(args, 1));
            }
            case "tp" -> {
                if (args.length < 2) return usage(player, "/파티 tp <유저>");
                service.teleportMember(player, args[1]);
            }
            case "tpall" -> service.teleportAll(player);
            case "test" -> player.sendMessage(player.getInventory().getItemInMainHand().getItemMeta().getCustomModelData() + "");
            case "pvp" -> {
                if (args.length < 2) return usage(player, "/파티 pvp on/off");
                if (args[1].equalsIgnoreCase("on")) service.setPvp(player, true);
                else if (args[1].equalsIgnoreCase("off")) service.setPvp(player, false);
                else service.togglePvp(player);
            }
            case "메뉴" -> {
                player.openInventory(service.createMainMenu(player));
                service.playSucessSound(player);
            }
            case "채팅" -> service.toggleChat(player);
            case "reload" -> {
                if (!player.isOp() && !player.hasPermission("partyadder.reload")) {
                    service.sendErrorMessage(player, "권한이 없습니다");
                    return true;
                }
                plugin.reloadEverything();
                service.sendSucessMessage(player, "성공적으로 config가 리로드되었습니다");
            }
            default -> printHelp(player);
        }
        return true;
    }

    private void acceptJoinRequest(Player leader, String requesterName) {
        Player target = Bukkit.getPlayerExact(requesterName);
        if (target == null) {
            leader.sendMessage(TextUtil.color("&c대상을 찾을 수 없습니다."));
            return;
        }
        service.acceptJoinRequest(leader, target.getUniqueId());
    }

    private void declineJoinRequest(Player leader, String requesterName) {
        Player target = Bukkit.getPlayerExact(requesterName);
        if (target == null) {
            leader.sendMessage(TextUtil.color("&c대상을 찾을 수 없습니다."));
            return;
        }
        service.declineJoinRequest(leader, target.getUniqueId());
    }

    private boolean usage(Player player, String text) {
        player.sendMessage(TextUtil.color("&e사용법: " + text));
        return true;
    }

    private void printHelp(Player p) {
        Party party = PartyAdder.getPartyService().getParty(p.getUniqueId());

        MiniMessage mm = MiniMessage.miniMessage();
        p.sendMessage("");
        p.sendMessage(mm.deserialize("<gradient:#EF6D6D:#FFFFFF><st>                                                        </st></gradient>"));
        p.sendMessage("");
        p.sendMessage(" /파티 생성 §6<파티 이름> §7- 파티를 생성합니다");
        p.sendMessage(" /파티 가입신청 §6<파티 이름> §7- 파티에 가입을 신청합니다");
        p.sendMessage(" /파티 메뉴 §7- 파티 관련 메뉴를 엽니다");

        // 파티장 전용 명령어
        if (party != null && party.isLeader(p.getUniqueId())) {
            p.sendMessage(" /파티 삭제 §6<파티 이름> §7- 파티를 삭제합니다");
            p.sendMessage(" /파티 초대 §6<유저> §7- 파티에 초대합니다");
            p.sendMessage(" /파티 추방 §6<유저> §7- 파티에서 추방합니다");
            p.sendMessage(" /파티 위임 §6<유저> §7- 파티장을 위임합니다");
            p.sendMessage(" /파티 이름변경 §6<파티 이름> §7- 파티 이름을 변경합니다");
            p.sendMessage(" /파티 tp §6<유저> §7- 파티원을 본인에게 tp시킵니다");
            p.sendMessage(" /파티 tpall §7- 모든 파티원을 본인에게 tp시킵니다");
            p.sendMessage(" /파티 pvp on/off §7- 파티원 pvp를 on/off합니다");
        }

        // 파티멤버 전용 명령어
        if (party != null) {
            p.sendMessage(" /파티 채팅 §7- 파티 전용채팅을 on/off 합니다");
        }


        // op전용 명령어
        if (p.isOp()) {
            p.sendMessage(" /파티 reload §7- config를 리로드합니다");
        }

        p.sendMessage("");
        p.sendMessage(mm.deserialize("<gradient:#EF6D6D:#FFFFFF><st>                                                        </st></gradient>"));
        p.sendMessage("");
    }

    private long parseLong(String s) {
        try { return Long.parseLong(s); } catch (Exception e) { return -1L; }
    }

    private String join(String[] args, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            if (i > from) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            List<String> subs = List.of("생성","삭제","초대","초대수락","초대거절","가입신청","추방","위임","이름변경","tp","tpall","pvp","메뉴","채팅","reload");
            for (String s : subs) if (s.startsWith(args[0])) out.add(s);
            return out;
        }
        return out;
    }
}

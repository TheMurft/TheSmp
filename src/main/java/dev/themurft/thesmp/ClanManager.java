package dev.themurft.thesmp;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

final class ClanManager implements Listener {
    enum Role { LEADER, OFFICER, MEMBER }
    static final class Clan {
        String name, tag;
        Location home;
        double bank;
        int level=1,points;
        final Map<UUID, Role> members = new LinkedHashMap<>();
        Clan(String name, String tag) { this.name=name; this.tag=tag; }
    }
    record Invite(String clanId, long expires) {}
    public record ClanHolder(UUID viewer) implements InventoryHolder { public Inventory getInventory(){return null;} }

    private final TheSmp plugin;
    private final Map<String, Clan> clans = new LinkedHashMap<>();
    private final Map<UUID, String> memberships = new HashMap<>();
    private final Map<UUID, Invite> invites = new HashMap<>();
    private final Map<UUID, Boolean> privateChat = new HashMap<>();
    private final Map<UUID, Long> disbandConfirmations = new HashMap<>();
    private File file;
    private YamlConfiguration yaml;

    ClanManager(TheSmp plugin) { this.plugin=plugin; }
    void enable(){file=new File(plugin.getDataFolder(),"clanes.yml");yaml=YamlConfiguration.loadConfiguration(file);load();Bukkit.getPluginManager().registerEvents(this,plugin);}
    void disable(){save();}
    void reload(){save();yaml=YamlConfiguration.loadConfiguration(file);load();}
    private String id(String text){return ChatColor.stripColor(text).toLowerCase(Locale.ROOT);}
    private Clan clan(Player p){String key=memberships.get(p.getUniqueId());return key==null?null:clans.get(key);}
    String profileClan(UUID id){String key=memberships.get(id);Clan c=key==null?null:clans.get(key);return c==null?"None":c.name+" ["+c.tag+"] Lv."+c.level;}
    private boolean staff(Player p,Clan c){Role role=c.members.get(p.getUniqueId());return role==Role.LEADER||role==Role.OFFICER;}
    private boolean leader(Player p,Clan c){return c.members.get(p.getUniqueId())==Role.LEADER;}

    boolean command(CommandSender sender,String command,String[] args){
        if(!(sender instanceof Player p)){plugin.message(sender,"only-player");return true;}
        if(!p.hasPermission("thesmp.clan.use")){plugin.message(p,"no-permission");return true;}
        if(command.equals("cc")||command.equals("ac")){if(args.length==1&&args[0].equalsIgnoreCase("off")){switchChat(p,null);return true;}if(args.length==0){switchChat(p,false);return true;}sendPrivate(p,String.join(" ",args),false);return true;}
        if(args.length==0){openMenu(p);return true;}
        String sub=args[0].toLowerCase(Locale.ROOT);
        return switch(sub){
            case "create","crear"->create(p,args);
            case "invite","invitar"->invite(p,args);
            case "accept","aceptar"->accept(p);
            case "leave","salir"->leave(p);
            case "kick","expulsar"->kick(p,args);
            case "disband","disolver"->disband(p);
            case "info"->{info(p,args);yield true;}
            case "list","lista"->{list(p);yield true;}
            case "chat"->{if(args.length>1&&args[1].equalsIgnoreCase("off"))switchChat(p,null);else switchChat(p,false);yield true;}
            default->{plugin.message(p,"clan.usage");yield true;}
        };
    }
    boolean advanced(Player p,String command,String[] args){if(!p.hasPermission("thesmp.clan.use")){plugin.message(p,"no-permission");return true;}Clan c=clan(p);if(command.equals("clantop")){clans.values().stream().sorted((a,b)->Integer.compare(b.points,a.points)).limit(10).forEach(x->p.sendMessage(plugin.tr(p,"clan-advanced.top-entry","%clan%",x.name,"%tag%",x.tag,"%level%",String.valueOf(x.level),"%points%",String.valueOf(x.points))));return true;}if(c==null){plugin.message(p,"clan.no-clan");return true;}if(command.equals("clanhome")){if(c.home==null||c.home.getWorld()==null){plugin.message(p,"clan-advanced.no-home");return true;}p.teleportAsync(c.home).thenAccept(success->Bukkit.getScheduler().runTask(plugin,()->plugin.message(p,success?"clan-advanced.home-teleported":"teleport-failed")));return true;}if(command.equals("setclanhome")){if(!staff(p,c)){plugin.message(p,"clan.staff-only");return true;}c.home=p.getLocation();save();plugin.message(p,"clan-advanced.home-updated");return true;}if(command.equals("clanbank")){if(args.length==0){plugin.message(p,"clan-advanced.bank","%amount%",String.valueOf(c.bank));return true;}if(args.length<2){plugin.message(p,"clan-advanced.bank-usage");return true;}double amount;try{amount=Double.parseDouble(args[1]);}catch(Exception e){plugin.message(p,"clan-advanced.invalid-amount");return true;}if(!Double.isFinite(amount)||amount<=0){plugin.message(p,"clan-advanced.invalid-amount");return true;}if(args[0].equalsIgnoreCase("deposit")){if(!plugin.chargePlayerAmount(p,amount))return true;c.bank+=amount;c.points+=(int)Math.max(1,amount/100);}else if(args[0].equalsIgnoreCase("withdraw")){if(!staff(p,c)){plugin.message(p,"clan.staff-only");return true;}if(c.bank<amount){plugin.message(p,"clan-advanced.bank-insufficient");return true;}if(!plugin.creditPlayer(p,amount))return true;c.bank-=amount;}else return true;c.level=1+c.points/100;save();plugin.message(p,"clan-advanced.bank-balance","%amount%",String.valueOf(c.bank));return true;}if(command.equals("clanlevel")){plugin.message(p,"clan-advanced.level","%clan%",c.name,"%level%",String.valueOf(c.level),"%points%",String.valueOf(c.points));return true;}return false;}

    private boolean create(Player p,String[] a){
        if(!p.hasPermission("thesmp.clan.create")){plugin.message(p,"no-permission");return true;}if(clan(p)!=null){plugin.message(p,"clan.already");return true;}if(a.length<3){plugin.message(p,"clan.usage");return true;}
        String name=a[1],tag=a[2].toUpperCase(Locale.ROOT);int nmin=Math.max(1,plugin.getConfig().getInt("clans.name-min-length",3)),nmax=Math.max(nmin,plugin.getConfig().getInt("clans.name-max-length",16)),tmin=Math.max(1,plugin.getConfig().getInt("clans.tag-min-length",2)),tmax=Math.max(tmin,plugin.getConfig().getInt("clans.tag-max-length",5));
        if(name.length()<nmin||name.length()>nmax||tag.length()<tmin||tag.length()>tmax||!name.matches("[A-Za-z0-9_]+")||!tag.matches("[A-Z0-9]+")){plugin.message(p,"clan.invalid");return true;}
        String key=id(name);if(clans.containsKey(key)||clans.values().stream().anyMatch(c->c.tag.equalsIgnoreCase(tag))){plugin.message(p,"clan.exists");return true;}if(!plugin.chargePlayer(p,"clan-create"))return true;
        Clan c=new Clan(name,tag);c.members.put(p.getUniqueId(),Role.LEADER);clans.put(key,c);memberships.put(p.getUniqueId(),key);save();plugin.message(p,"clan.created","%clan%",name,"%tag%",tag);openMenu(p);return true;
    }
    private boolean invite(Player p,String[] a){Clan c=clan(p);if(c==null){plugin.message(p,"clan.no-clan");return true;}if(!staff(p,c)){plugin.message(p,"clan.staff-only");return true;}if(a.length<2){plugin.message(p,"clan.usage");return true;}Player target=Bukkit.getPlayerExact(a[1]);if(target==null){plugin.message(p,"player-not-found");return true;}if(clan(target)!=null){plugin.message(p,"clan.already");return true;}if(c.members.size()>=plugin.getConfig().getInt("clans.max-members",20)){plugin.message(p,"clan.staff-only");return true;}invites.put(target.getUniqueId(),new Invite(id(c.name),System.currentTimeMillis()+plugin.getConfig().getLong("clans.invite-seconds",120)*1000));plugin.message(p,"clan.invite-sent","%player%",target.getName());plugin.message(target,"clan.invite-received","%player%",p.getName(),"%clan%",c.name);return true;}
    private boolean accept(Player p){if(clan(p)!=null){plugin.message(p,"clan.already");return true;}Invite invite=invites.remove(p.getUniqueId());if(invite==null||invite.expires<System.currentTimeMillis()||!clans.containsKey(invite.clanId)){plugin.message(p,"clan.invite-none");return true;}Clan c=clans.get(invite.clanId);c.members.put(p.getUniqueId(),Role.MEMBER);memberships.put(p.getUniqueId(),invite.clanId);save();plugin.message(p,"clan.joined","%clan%",c.name);return true;}
    private boolean leave(Player p){Clan c=clan(p);if(c==null){plugin.message(p,"clan.no-clan");return true;}if(leader(p,c)){plugin.message(p,"clan.leader-leave");return true;}c.members.remove(p.getUniqueId());memberships.remove(p.getUniqueId());save();plugin.message(p,"clan.left");return true;}
    private boolean kick(Player p,String[] a){Clan c=clan(p);if(c==null||!staff(p,c)){plugin.message(p,"clan.staff-only");return true;}if(a.length<2){plugin.message(p,"clan.usage");return true;}UUID target=c.members.keySet().stream().filter(x->safeName(x).equalsIgnoreCase(a[1])).findFirst().orElse(null);if(target==null||target.equals(p.getUniqueId())||c.members.get(target)==Role.LEADER){plugin.message(p,"clan.staff-only");return true;}c.members.remove(target);memberships.remove(target);save();plugin.message(p,"clan.kicked","%player%",safeName(target));Player online=Bukkit.getPlayer(target);if(online!=null)plugin.message(online,"clan.left");return true;}
    private boolean disband(Player p){Clan c=clan(p);if(c==null||!leader(p,c)){plugin.message(p,"clan.leader-only");return true;}long now=System.currentTimeMillis(),confirmation=disbandConfirmations.getOrDefault(p.getUniqueId(),0L);if(confirmation<now){disbandConfirmations.put(p.getUniqueId(),now+10000);plugin.message(p,"clan-safety.disband-confirm");return true;}disbandConfirmations.remove(p.getUniqueId());String key=id(c.name);for(UUID member:new ArrayList<>(c.members.keySet())){memberships.remove(member);privateChat.remove(member);Player online=Bukkit.getPlayer(member);if(online!=null)plugin.message(online,"clan.disbanded");}clans.remove(key);save();return true;}
    private void info(Player p,String[] a){Clan c=a.length>1?clans.get(id(a[1])):clan(p);if(c==null){plugin.message(p,"clan.no-clan");return;}p.sendMessage(plugin.color("&8&m                    &r &b&l"+c.name+" &8[&f"+c.tag+"&8] &8&m                    "));p.sendMessage(plugin.color("&7Members: &f"+c.members.entrySet().stream().map(e->safeName(e.getKey())+" &8("+e.getValue().name().toLowerCase(Locale.ROOT)+"&8)").collect(Collectors.joining("&7, &f"))));}
    private void list(Player p){p.sendMessage(plugin.color("&bClans: &f"+clans.values().stream().map(c->c.name+" &8["+c.members.size()+"]").collect(Collectors.joining("&7, &f"))));}

    void openMenu(Player p){Clan c=clan(p);Inventory inv=Bukkit.createInventory(new ClanHolder(p.getUniqueId()),27,plugin.tr("clan.menu-title"));for(int i=0;i<27;i++)inv.setItem(i,plugin.item(Material.GRAY_STAINED_GLASS_PANE," ",null));if(c==null){inv.setItem(11,plugin.item(Material.ANVIL,"&bCreate a clan",List.of("&7Use: &f/clan create <name> <tag>")));inv.setItem(15,plugin.item(Material.LIME_DYE,"&aAccept invitation",List.of("&7Click to run /clan accept")));}else{inv.setItem(10,plugin.item(Material.SHIELD,"&b"+c.name+" &8[&f"+c.tag+"&8]",List.of("&7Members: &f"+c.members.size()+"/"+plugin.getConfig().getInt("clans.max-members",20),"&7Your role: &f"+c.members.get(p.getUniqueId()).name().toLowerCase(Locale.ROOT))));inv.setItem(12,plugin.item(Material.PLAYER_HEAD,"&bMembers",c.members.entrySet().stream().limit(8).map(e->"&7• &f"+safeName(e.getKey())+" &8("+e.getValue().name().toLowerCase(Locale.ROOT)+")").toList()));inv.setItem(14,plugin.item(Material.PAPER,"&bClan information",List.of("&7Click to view details.")));inv.setItem(16,plugin.item(leader(p,c)?Material.TNT:Material.OAK_DOOR,leader(p,c)?"&cDisband clan":"&eLeave clan",List.of("&7Click to continue.")));}inv.setItem(22,plugin.item(Material.BOOK,"&bCommands",List.of("&f/clan invite <player>","&f/clan kick <player>","&f/cc <message>","&f/clan info")));p.openInventory(inv);}
    void click(InventoryClickEvent event,Player p,ClanHolder holder){event.setCancelled(true);if(!holder.viewer.equals(p.getUniqueId()))return;int slot=event.getRawSlot();if(slot<0||slot>=27)return;Clan c=clan(p);String command=null;if(c==null&&slot==15)command="clan accept";else if(c!=null&&slot==14)command="clan info";else if(c!=null&&slot==16)command=leader(p,c)?"clan disband":"clan leave";if(command!=null){p.closeInventory();p.performCommand(command);}}

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void chat(AsyncPlayerChatEvent event){Boolean ally=privateChat.get(event.getPlayer().getUniqueId());if(ally==null)return;event.setCancelled(true);String message=event.getMessage();Bukkit.getScheduler().runTask(plugin,()->{if(clan(event.getPlayer())==null){privateChat.remove(event.getPlayer().getUniqueId());return;}sendPrivate(event.getPlayer(),message,ally);});}
    private void switchChat(Player player,Boolean ally){if(ally==null){privateChat.remove(player.getUniqueId());plugin.message(player,"clan-safety.chat-global");return;}if(clan(player)==null){plugin.message(player,"clan.no-clan");return;}privateChat.put(player.getUniqueId(),ally);plugin.message(player,ally?"clan-safety.chat-ally":"clan-safety.chat-clan");}
    private void sendPrivate(Player sender,String message,boolean ally){Clan c=clan(sender);if(c==null){plugin.message(sender,"clan.no-clan");return;}String line=plugin.tr(ally?"clan.ally-format":"clan.chat-format","%player%",sender.getName(),"%message%",message);for(UUID id:c.members.keySet()){Player online=Bukkit.getPlayer(id);if(online!=null)online.sendMessage(line);}}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void friendlyFire(EntityDamageByEntityEvent event){if(plugin.getConfig().getBoolean("clans.friendly-fire",false)||!(event.getEntity() instanceof Player victim))return;Player attacker=null;if(event.getDamager() instanceof Player p)attacker=p;else if(event.getDamager() instanceof Projectile shot&&shot.getShooter() instanceof Player p)attacker=p;if(attacker==null)return;String a=memberships.get(attacker.getUniqueId()),v=memberships.get(victim.getUniqueId());if(a!=null&&a.equals(v))event.setCancelled(true);}
    private String safeName(UUID id){OfflinePlayer p=Bukkit.getOfflinePlayer(id);return p.getName()==null?id.toString().substring(0,8):p.getName();}
    private void load(){clans.clear();memberships.clear();ConfigurationSection root=yaml.getConfigurationSection("clans");if(root==null)return;for(String key:root.getKeys(false)){String base="clans."+key;Clan c=new Clan(yaml.getString(base+".name",key),yaml.getString(base+".tag","CLAN"));c.home=yaml.getLocation(base+".home");c.bank=yaml.getDouble(base+".bank",0);c.level=yaml.getInt(base+".level",1);c.points=yaml.getInt(base+".points",0);ConfigurationSection members=yaml.getConfigurationSection(base+".members");if(members!=null)for(String raw:members.getKeys(false))try{UUID uuid=UUID.fromString(raw);String value=members.getString(raw,"MEMBER").replace("LIDER","LEADER").replace("OFICIAL","OFFICER").replace("MIEMBRO","MEMBER");Role role=Role.valueOf(value);c.members.put(uuid,role);memberships.put(uuid,key);}catch(Exception ignored){}clans.put(key,c);}}
    private synchronized void save(){yaml.set("clans",null);for(Map.Entry<String,Clan> entry:clans.entrySet()){String base="clans."+entry.getKey();Clan c=entry.getValue();yaml.set(base+".name",c.name);yaml.set(base+".tag",c.tag);yaml.set(base+".home",c.home);yaml.set(base+".bank",c.bank);yaml.set(base+".level",c.level);yaml.set(base+".points",c.points);for(Map.Entry<UUID,Role> member:c.members.entrySet())yaml.set(base+".members."+member.getKey(),member.getValue().name());}try{yaml.save(file);}catch(IOException e){plugin.getLogger().severe("Could not save clanes.yml: "+e.getMessage());}}
}

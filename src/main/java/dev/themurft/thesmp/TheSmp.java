package dev.themurft.thesmp;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.server.TabCompleteEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class TheSmp extends JavaPlugin implements Listener {
    private record TpRequest(UUID sender, boolean targetToSender, long expires) {}
    private record TradeRequest(UUID sender, long expires) {}
    private record Warmup(Location origin, BukkitTask task) {}
    private record HomesHolder(UUID owner) implements InventoryHolder { public Inventory getInventory() { return null; } }
    private record ReportsHolder(List<Integer> ids, String filter) implements InventoryHolder { public Inventory getInventory() { return null; } }
    private record ReportsDashboardHolder() implements InventoryHolder { public Inventory getInventory() { return null; } }
    private record ReportDetailsHolder(int id, String previousFilter) implements InventoryHolder { public Inventory getInventory() { return null; } }
    private record ReportCategoryHolder(UUID target, String targetName) implements InventoryHolder { public Inventory getInventory() { return null; } }
    private record CustomHolder(String id) implements InventoryHolder { public Inventory getInventory() { return null; } }
    private record GuideHolder() implements InventoryHolder { public Inventory getInventory() { return null; } }
    private record LanguageHolder(boolean global) implements InventoryHolder { public Inventory getInventory() { return null; } }
    private record FriendsHolder(String view,List<UUID> ids) implements InventoryHolder { public Inventory getInventory() { return null; } }

    private final Map<UUID, TpRequest> tpRequests = new HashMap<>();
    private final Map<UUID, TradeRequest> tradeRequests = new HashMap<>();
    private final Map<UUID, Warmup> warmups = new HashMap<>();
    private final Map<UUID, Long> rtpCooldowns = new HashMap<>();
    private final Map<UUID, Long> reportCooldowns = new HashMap<>();
    private final Map<UUID, UUID> lastMessages = new HashMap<>();
    private final Map<UUID, ArmorStand> seats = new HashMap<>();
    private final Map<UUID, TradeSession> activeTrades = new HashMap<>();
    private final Set<String> hiddenSpectators = new HashSet<>();
    private File dataFile;
    private File reportsFile;
    private YamlConfiguration data;
    private YamlConfiguration reports;
    private YamlConfiguration lang;
    private final Map<String,YamlConfiguration> languages=new HashMap<>();
    private YamlConfiguration menus;
    private YamlConfiguration warpMenus;
    private ClanManager clans;
    private SmpExpansion expansion;
    private BukkitTask spectatorTask;
    private BukkitTask poseTask;

    @Override public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);saveConfig();
        File langDirectory=new File(getDataFolder(),"lang");if(!langDirectory.exists())langDirectory.mkdirs();
        File legacyLang=new File(getDataFolder(),"lang.yml"),englishLang=new File(langDirectory,"en.yml");
        if(legacyLang.exists()&&!englishLang.exists())try{Files.copy(legacyLang.toPath(),englishLang.toPath(),StandardCopyOption.COPY_ATTRIBUTES);}catch(IOException error){getLogger().warning("Could not migrate lang.yml: "+error.getMessage());}
        for(String code:List.of("en","es","pt","fr"))if(!new File(langDirectory,code+".yml").exists())saveResource("lang/"+code+".yml",false);
        if (!new File(getDataFolder(), "menus.yml").exists()) saveResource("menus.yml", false);
        loadFiles();
        clans = new ClanManager(this);
        clans.enable();
        expansion = new SmpExpansion(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        if (getConfig().getBoolean("spectator-tab.enabled", true)) {
            spectatorTask = Bukkit.getScheduler().runTaskTimer(this, this::syncSpectators, 20L,
                    Math.max(1L, getConfig().getLong("spectator-tab.sync-ticks", 40L)));
        }
        poseTask = Bukkit.getScheduler().runTaskTimer(this, this::synchronizePoses, 1L, 1L);
        Bukkit.getScheduler().runTaskTimer(this, this::refreshOpenQuestMenus, 20L, 20L);
        getLogger().info("TheSmp enabled: homes, teleports, messages, friends, welcomes, custom commands, trades, reports, menus, clans and SpectatorTab.");
    }

    @Override public void onDisable() {
        if (spectatorTask != null) spectatorTask.cancel();
        if (poseTask != null) poseTask.cancel();
        new ArrayList<>(activeTrades.values()).forEach(x -> x.cancel(false));
        seats.values().forEach(ArmorStand::remove);
        seats.clear();
        restoreSpectators();
        if (clans != null) clans.disable();
        if (expansion != null) expansion.save();
        saveData();
        saveReports();
    }

    private void loadFiles() {
        dataFile = new File(getDataFolder(), "data.yml");
        reportsFile = new File(getDataFolder(), "reports.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        reports = YamlConfiguration.loadConfiguration(reportsFile);
        String language=getConfig().getString("language","en").toLowerCase(Locale.ROOT);if(!List.of("en","es","pt","fr").contains(language)){getLogger().warning("Unknown language '"+language+"'; using en.");language="en";}
        languages.clear();for(String code:List.of("en","es","pt","fr")){File file=new File(new File(getDataFolder(),"lang"),code+".yml");YamlConfiguration translation=YamlConfiguration.loadConfiguration(file);mergeBundledDefaults(translation,"lang/"+code+".yml",file);languages.put(code,translation);}lang=languages.get(language);
        menus = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "menus.yml"));
        mergeBundledDefaults(menus, "menus.yml", new File(getDataFolder(), "menus.yml"));
        File warpMenuFile=new File(getDataFolder(),"warps-menu.yml");warpMenus=YamlConfiguration.loadConfiguration(warpMenuFile);
        if(!warpMenuFile.exists()){ConfigurationSection legacy=menus.getConfigurationSection("warp-menu");if(legacy!=null)for(Map.Entry<String,Object> entry:legacy.getValues(true).entrySet())warpMenus.set(entry.getKey(),entry.getValue());}
        mergeBundledDefaults(warpMenus,"warps-menu.yml",warpMenuFile);
    }

    private void mergeBundledDefaults(YamlConfiguration target, String resource, File destination) {
        try (InputStreamReader reader = new InputStreamReader(getResource(resource), StandardCharsets.UTF_8)) {
            target.setDefaults(YamlConfiguration.loadConfiguration(reader));
            target.options().copyDefaults(true);
            target.save(destination);
        } catch (Exception error) {
            getLogger().warning("Could not merge defaults into " + resource + ": " + error.getMessage());
        }
    }

    public String color(String value) { return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value); }
    YamlConfiguration warpMenuConfig(){return warpMenus;}
    public String tr(String path, String... replacements) {
        return translate(lang,path,replacements);
    }
    public String tr(CommandSender sender,String path,String... replacements){return translate(languageFor(sender),path,replacements);}
    private YamlConfiguration languageFor(CommandSender sender){if(sender instanceof Player p){String personal=data.getString("languages."+p.getUniqueId(),"");if(languages.containsKey(personal))return languages.get(personal);}return lang;}
    private String translate(YamlConfiguration source,String path,String... replacements){
        String value = source.getString(path, "&cMissing language key: " + path);
        for (int i = 0; i + 1 < replacements.length; i += 2) value = value.replace(replacements[i], replacements[i + 1]);
        return color(value);
    }
    public void message(CommandSender sender, String path, String... replacements) {
        String prefix=getConfig().getString("prefix","&8[&bTheSmp&8]");sender.sendMessage(color(prefix+(prefix.endsWith(" ")?"":" ")) + tr(sender,path,replacements));
    }
    public ItemStack item(Material material, String name, List<String> lore) {
        if (material == null || !material.isItem() || material.isAir()) material = Material.STONE;
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return new ItemStack(Material.STONE);
        meta.setDisplayName(color(name));
        if (lore != null) meta.setLore(lore.stream().map(this::color).toList());
        stack.setItemMeta(meta);
        return stack;
    }
    private Player player(CommandSender sender) { if (sender instanceof Player p) return p; message(sender, "only-player"); return null; }
    private boolean permit(Player p, String permission) { if (p.hasPermission(permission)) return true; message(p, "no-permission"); return false; }
    boolean chargePlayer(Player p,String action){return expansion==null||expansion.charge(p,action);}
    boolean chargePlayerAmount(Player p,double amount){return expansion!=null&&expansion.chargeAmount(p,amount);}
    boolean creditPlayer(Player p,double amount){return expansion!=null&&expansion.credit(p,amount);}
    int friendCount(UUID id){return data.getStringList("friends."+id).size();}
    String clanProfile(UUID id){return clans.profileClan(id);}

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("clan") || name.equals("cc") || name.equals("ac")) return clans.command(sender, name, args);
        if(List.of("clanhome","setclanhome","clanbank","clanlevel","clantop").contains(name)){Player cp=player(sender);return cp==null||clans.advanced(cp,name,args);}
        if (name.equals("thesmp")) {
            if (args.length > 0 && (args[0].equalsIgnoreCase("language") || args[0].equalsIgnoreCase("lang"))) {
                if (!sender.hasPermission("thesmp.admin")) { message(sender, "no-permission"); return true; }
                if(args.length<2){message(sender,"language.usage");message(sender,"language.available");return true;}
                String language=args[1].toLowerCase(Locale.ROOT);if(!List.of("en","es","pt","fr").contains(language)){message(sender,"language.available");return true;}
                getConfig().set("language",language);saveConfig();loadFiles();clans.reload();message(sender,"language.changed","%language%",language);return true;
            }
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                if (!sender.hasPermission("thesmp.admin")) { message(sender, "no-permission"); return true; }
                reloadConfig(); loadFiles(); clans.reload(); message(sender, "reload"); return true;
            }
            Player guidePlayer = player(sender); if (guidePlayer != null) openGuide(guidePlayer); return true;
        }
        Player p = player(sender); if (p == null) return true;
        if(!permit(p,"thesmp.use"))return true;
        if(expansion!=null&&expansion.handles(name))return expansion.command(p,name,args);
        return switch (name) {
            case "sethome" -> setHome(p, args);
            case "home" -> home(p, args);
            case "homes" -> { if (permit(p,"thesmp.homes.default")) openHomes(p); yield true; }
            case "delhome" -> permit(p,"thesmp.homes.default") && deleteHome(p, args.length == 0 ? "" : args[0]);
            case "tpa" -> teleportRequest(p, args, false);
            case "tphere" -> teleportRequest(p, args, true);
            case "tpallow" -> acceptTeleport(p);
            case "tpdeny" -> denyTeleport(p);
            case "rtp" -> randomTeleport(p);
            case "spawn" -> teleportSpawn(p);
            case "setspawn" -> setSpawn(p);
            case "sit" -> toggleSit(p);
            case "trade" -> trade(p, args);
            case "report" -> report(p, args);
            case "reports" -> reportsCommand(p, args);
            case "menu" -> menuCommand(p, args);
            case "lang" -> { openLanguageMenu(p,false); yield true; }
            case "langg" -> { openLanguageMenu(p,true); yield true; }
            case "friend", "friends" -> friendCommand(p,args);
            case "msg" -> privateMessage(p,args);
            case "reply" -> replyMessage(p,args);
            default -> false;
        };
    }
    private boolean privateMessage(Player sender,String[] args){
        if(!permit(sender,"thesmp.msg"))return true;
        if(args.length<2){message(sender,"private-message.usage");return true;}
        Player target=Bukkit.getPlayerExact(args[0]);
        if(target==null){message(sender,"player-not-found");return true;}
        if(target.equals(sender)){message(sender,"private-message.self");return true;}
        return sendPrivateMessage(sender,target,String.join(" ",java.util.Arrays.copyOfRange(args,1,args.length)));
    }
    private boolean replyMessage(Player sender,String[] args){
        if(!permit(sender,"thesmp.msg"))return true;
        if(args.length==0){message(sender,"private-message.reply-usage");return true;}
        UUID targetId=lastMessages.get(sender.getUniqueId());
        Player target=targetId==null?null:Bukkit.getPlayer(targetId);
        if(target==null){message(sender,"private-message.no-reply");return true;}
        return sendPrivateMessage(sender,target,String.join(" ",args));
    }
    private boolean sendPrivateMessage(Player sender,Player target,String text){
        if(expansion!=null&&expansion.ignored(target.getUniqueId(),sender.getUniqueId())){message(sender,"private-message.ignored");return true;}
        sender.sendMessage(tr(sender,"private-message.sent","%player%",target.getName(),"%message%",text));
        target.sendMessage(tr(target,"private-message.received","%player%",sender.getName(),"%message%",text));
        lastMessages.put(sender.getUniqueId(),target.getUniqueId());lastMessages.put(target.getUniqueId(),sender.getUniqueId());return true;
    }
    private List<String> uuidList(String path){return new ArrayList<>(data.getStringList(path));}
    private boolean isFriend(UUID first,UUID second){return data.getStringList("friends."+first).contains(second.toString());}
    private void addUuid(String path,UUID id){List<String> values=uuidList(path);if(!values.contains(id.toString()))values.add(id.toString());data.set(path,values);}
    private void removeUuid(String path,UUID id){List<String> values=uuidList(path);values.remove(id.toString());data.set(path,values);}
    private boolean friendCommand(Player p,String[] args){
        if(!permit(p,"thesmp.friends"))return true;if(args.length==0){openFriends(p,"dashboard");return true;}String action=args[0].toLowerCase(Locale.ROOT);
        if(action.equals("list")){openFriends(p,"list");return true;}if(action.equals("requests")){openFriends(p,"requests");return true;}if(args.length<2){message(p,"friends.usage");return true;}
        OfflinePlayer target=Bukkit.getOfflinePlayer(args[1]);UUID targetId=target.getUniqueId();String targetName=target.getName()==null?args[1]:target.getName();if(targetId.equals(p.getUniqueId())){message(p,"friends.self");return true;}
        switch(action){case "add"->{if(isFriend(p.getUniqueId(),targetId)){message(p,"friends.already");return true;}if(data.getStringList("friend-requests."+targetId).contains(p.getUniqueId().toString())){message(p,"friends.request-exists");return true;}addUuid("friend-requests."+targetId,p.getUniqueId());saveData();message(p,"friends.request-sent","%player%",targetName);Player online=Bukkit.getPlayer(targetId);if(online!=null)message(online,"friends.request-received","%player%",p.getName());}case "accept"->{if(!data.getStringList("friend-requests."+p.getUniqueId()).contains(targetId.toString())){message(p,"friends.no-request");return true;}removeUuid("friend-requests."+p.getUniqueId(),targetId);addUuid("friends."+p.getUniqueId(),targetId);addUuid("friends."+targetId,p.getUniqueId());saveData();message(p,"friends.accepted","%player%",targetName);Player online=Bukkit.getPlayer(targetId);if(online!=null)message(online,"friends.accepted-other","%player%",p.getName());}case "deny"->{if(!data.getStringList("friend-requests."+p.getUniqueId()).contains(targetId.toString())){message(p,"friends.no-request");return true;}removeUuid("friend-requests."+p.getUniqueId(),targetId);saveData();message(p,"friends.denied","%player%",targetName);}case "remove"->{if(!isFriend(p.getUniqueId(),targetId)){message(p,"friends.not-friend");return true;}removeUuid("friends."+p.getUniqueId(),targetId);removeUuid("friends."+targetId,p.getUniqueId());saveData();message(p,"friends.removed","%player%",targetName);}case "msg"->{if(args.length<3){message(p,"friends.msg-usage");return true;}if(!isFriend(p.getUniqueId(),targetId)){message(p,"friends.not-friend");return true;}Player online=Bukkit.getPlayer(targetId);if(online==null){message(p,"friends.offline");return true;}String text=String.join(" ",java.util.Arrays.copyOfRange(args,2,args.length));p.sendMessage(tr(p,"friends.msg-sent","%player%",online.getName(),"%message%",text));online.sendMessage(tr(online,"friends.msg-received","%player%",p.getName(),"%message%",text));}default->message(p,"friends.usage");}return true;
    }
    private void openFriends(Player p,String view){if(!permit(p,"thesmp.friends"))return;if(view.equals("dashboard")){Inventory inv=Bukkit.createInventory(new FriendsHolder(view,List.of()),27,tr(p,"friends.menu-title"));ItemStack fill=item(Material.LIGHT_BLUE_STAINED_GLASS_PANE," ",null);for(int i=0;i<27;i++)inv.setItem(i,fill);inv.setItem(11,item(Material.PLAYER_HEAD,tr(p,"friends.list-name"),List.of(tr(p,"friends.count","%count%",String.valueOf(uuidList("friends."+p.getUniqueId()).size())),"","&eClick")));inv.setItem(15,item(Material.WRITABLE_BOOK,tr(p,"friends.requests-name"),List.of(tr(p,"friends.count","%count%",String.valueOf(uuidList("friend-requests."+p.getUniqueId()).size())),"","&eClick")));inv.setItem(13,item(Material.PAPER,tr(p,"friends.help-name"),List.of("&f/friend add <player>","&f/friend accept <player>","&f/friend deny <player>","&f/friend remove <player>","&f/friend msg <player> <message>")));p.openInventory(inv);return;}List<UUID> ids=uuidList((view.equals("requests")?"friend-requests.":"friends.")+p.getUniqueId()).stream().map(x->{try{return UUID.fromString(x);}catch(Exception e){return null;}}).filter(java.util.Objects::nonNull).limit(45).toList();Inventory inv=Bukkit.createInventory(new FriendsHolder(view,ids),54,tr(p,view.equals("requests")?"friends.requests-title":"friends.list-title"));for(int i=0;i<ids.size();i++){OfflinePlayer target=Bukkit.getOfflinePlayer(ids.get(i));boolean online=target.isOnline();inv.setItem(i,item(Material.PLAYER_HEAD,(online?"&a":"&7")+(target.getName()==null?ids.get(i).toString().substring(0,8):target.getName()),view.equals("requests")?List.of("&aLeft-click: accept","&cRight-click: deny"):List.of(online?tr(p,"friends.online"):tr(p,"friends.offline-status"),"","&eClick to send /tpa")));}if(ids.isEmpty())inv.setItem(22,item(Material.PAPER,tr(p,view.equals("requests")?"friends.no-requests":"friends.empty"),List.of()));inv.setItem(49,item(Material.ARROW,tr(p,"report.back"),List.of()));p.openInventory(inv);}
    private void notifyFriendStatus(Player player,boolean joined){if(!getConfig().getBoolean("friends.notify-status",true))return;for(String raw:uuidList("friends."+player.getUniqueId()))try{Player friend=Bukkit.getPlayer(UUID.fromString(raw));if(friend!=null)message(friend,joined?"friends.joined":"friends.left","%player%",player.getName());}catch(IllegalArgumentException ignored){}}
    private void openLanguageMenu(Player p,boolean global) {
        if(!permit(p,global?"thesmp.language.global":"thesmp.language"))return;
        Inventory inv=Bukkit.createInventory(new LanguageHolder(global),27,tr(p,global?"language.global-title":"language.menu-title"));ItemStack filler=item(Material.GRAY_STAINED_GLASS_PANE," ",null);for(int i=0;i<27;i++)inv.setItem(i,filler);
        String active=global?getConfig().getString("language","en").toLowerCase(Locale.ROOT):data.getString("languages."+p.getUniqueId(),getConfig().getString("language","en")).toLowerCase(Locale.ROOT);String selected=tr(p,"language.selected"),choose=tr(p,"language.choose");
        inv.setItem(10,item(Material.BLUE_WOOL,"&bEnglish",List.of(active.equals("en")?selected:choose,"&7en")));
        inv.setItem(12,item(Material.RED_WOOL,"&cEspañol",List.of(active.equals("es")?selected:choose,"&7es")));
        inv.setItem(14,item(Material.LIME_WOOL,"&aPortuguês",List.of(active.equals("pt")?selected:choose,"&7pt")));
        inv.setItem(16,item(Material.WHITE_WOOL,"&fFrançais",List.of(active.equals("fr")?selected:choose,"&7fr")));
        p.openInventory(inv);
    }
    private void selectLanguage(Player p,String language,boolean global){if(global){if(!p.hasPermission("thesmp.language.global")){message(p,"no-permission");return;}getConfig().set("language",language);saveConfig();loadFiles();clans.reload();message(p,"language.global-changed","%language%",language);}else{if(!p.hasPermission("thesmp.language")){message(p,"no-permission");return;}data.set("languages."+p.getUniqueId(),language);saveData();message(p,"language.changed","%language%",language);}openLanguageMenu(p,global);}

    private int homeLimit(Player p) {
        if (p.hasPermission("thesmp.homes.unlimited")) return Integer.MAX_VALUE;
        int best = getConfig().getInt("homes.default-limit", 1);
        for (int value : List.of(2, 3, 5, 10, 20, 50, 100)) if (p.hasPermission("thesmp.homes." + value)) best = Math.max(best, value);
        return best;
    }
    private String cleanHome(String raw) { return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", ""); }
    private boolean setHome(Player p, String[] args) {
        if (!permit(p, "thesmp.homes.default")) return true;
        String home = cleanHome(args.length == 0 ? "home" : args[0]); if (home.isBlank()) home = "home";
        ConfigurationSection section = data.getConfigurationSection("homes." + p.getUniqueId());
        int count = section == null ? 0 : section.getKeys(false).size();
        if (!data.contains("homes." + p.getUniqueId() + "." + home) && count >= homeLimit(p)) { message(p, "homes.limit", "%limit%", String.valueOf(homeLimit(p))); return true; }
        if(!chargePlayer(p,"sethome"))return true;
        data.set("homes." + p.getUniqueId() + "." + home, p.getLocation()); saveData(); message(p, "homes.set", "%home%", home); return true;
    }
    private boolean home(Player p, String[] args) {
        if (!permit(p, "thesmp.homes.default")) return true;
        if (args.length == 0) {
            ConfigurationSection section = data.getConfigurationSection("homes." + p.getUniqueId());
            if (section != null && section.getKeys(false).size() == 1) return teleportHome(p, section.getKeys(false).iterator().next());
            openHomes(p); return true;
        }
        return teleportHome(p, cleanHome(args[0]));
    }
    private boolean teleportHome(Player p, String home) {
        Location location = data.getLocation("homes." + p.getUniqueId() + "." + home);
        if (location == null) { message(p, "homes.not-found", "%home%", home); return true; }
        p.teleportAsync(location).thenAccept(success -> Bukkit.getScheduler().runTask(this, () -> { if(success) message(p,"homes.teleported","%home%",home); else message(p,"teleport-failed"); })); return true;
    }
    private boolean deleteHome(Player p, String home) {
        home = cleanHome(home); String path = "homes." + p.getUniqueId() + "." + home;
        if (home.isBlank() || !data.contains(path)) { message(p, "homes.not-found", "%home%", home); return true; }
        data.set(path, null); saveData(); message(p, "homes.deleted", "%home%", home); return true;
    }
    private void openHomes(Player p) {
        ConfigurationSection section = data.getConfigurationSection("homes." + p.getUniqueId());
        if (section == null || section.getKeys(false).isEmpty()) { message(p, "homes.empty"); return; }
        List<String> names = new ArrayList<>(section.getKeys(false));
        int size = Math.min(54, Math.max(9, ((names.size() + 8) / 9) * 9));
        Inventory inv = Bukkit.createInventory(new HomesHolder(p.getUniqueId()), size, tr("homes.menu-title"));
        for (int i = 0; i < names.size() && i < size; i++) {
            String home = names.get(i); Location l = data.getLocation("homes." + p.getUniqueId() + "." + home);
            if(l==null||l.getWorld()==null)continue;
            List<String> lore = languageFor(p).getStringList("homes.lore").stream().map(x -> x.replace("%world%", l.getWorld().getName()).replace("%x%", String.valueOf(l.getBlockX())).replace("%y%", String.valueOf(l.getBlockY())).replace("%z%", String.valueOf(l.getBlockZ()))).toList();
            inv.setItem(i, item(Material.RED_BED, "&b" + home, lore));
        }
        p.openInventory(inv);
    }

    private boolean teleportRequest(Player p, String[] args, boolean here) {
        if (!permit(p, "thesmp.teleport")) return true;
        if (args.length == 0) { message(p, "teleport.usage", "%usage%", here ? "/tphere <player>" : "/tpa <player>"); return true; }
        Player target = Bukkit.getPlayerExact(args[0]); if (target == null) { message(p, "player-not-found"); return true; }
        if (target.equals(p)) { message(p, "teleport.self"); return true; }
        long requestSeconds=Math.max(1,Math.min(86400,getConfig().getLong("teleport-request-seconds",60)));
        tpRequests.put(target.getUniqueId(), new TpRequest(p.getUniqueId(), here, System.currentTimeMillis() + requestSeconds * 1000L));
        message(p, "teleport.sent", "%player%", target.getName());
        message(target, here ? "teleport.received-here" : "teleport.received-to", "%player%", p.getName()); return true;
    }
    private boolean acceptTeleport(Player target) {
        TpRequest request = tpRequests.remove(target.getUniqueId());
        if (request == null) { message(target, "teleport.none"); return true; }
        if (request.expires < System.currentTimeMillis()) { message(target, "teleport.expired"); return true; }
        Player sender = Bukkit.getPlayer(request.sender); if (sender == null) { message(target, "player-not-found"); return true; }
        Player moving = request.targetToSender ? target : sender; Player destination = request.targetToSender ? sender : target;
        startWarmup(moving, destination.getLocation()); return true;
    }
    private boolean denyTeleport(Player target) {
        TpRequest request = tpRequests.remove(target.getUniqueId()); if (request == null) { message(target, "teleport.none"); return true; }
        message(target, "teleport.denied"); Player sender = Bukkit.getPlayer(request.sender); if (sender != null) message(sender, "teleport.denied-sender", "%player%", target.getName()); return true;
    }
    private void startWarmup(Player p, Location destination) {
        startWarmup(p, destination, "teleport.success");
    }
    private void startWarmup(Player p, Location destination, String successMessage) {
        int seconds = Math.max(0,Math.min(300,getConfig().getInt("teleport-delay-seconds", 3))); Location origin = p.getLocation().clone();
        message(p, "teleport.warmup", "%seconds%", String.valueOf(seconds));
        Warmup previous=warmups.remove(p.getUniqueId());if(previous!=null)previous.task.cancel();
        BukkitTask task = Bukkit.getScheduler().runTaskLater(this, () -> { warmups.remove(p.getUniqueId()); p.teleportAsync(destination).thenAccept(success -> Bukkit.getScheduler().runTask(this,()->{if(success)message(p,successMessage);else message(p,"teleport-failed");})); }, seconds * 20L);
        warmups.put(p.getUniqueId(), new Warmup(origin, task));
    }
    private boolean setSpawn(Player p) {
        if (!permit(p, "thesmp.setspawn")) return true;
        data.set("spawn", p.getLocation()); saveData(); message(p, "spawn.set"); return true;
    }
    private boolean teleportSpawn(Player p) {
        if (!permit(p, "thesmp.spawn")) return true;
        Location spawn=data.getLocation("spawn");
        if(spawn==null){message(p,"spawn.not-set");return true;}
        startWarmup(p,spawn,"spawn.teleported");return true;
    }
    private boolean randomTeleport(Player p) {
        if (!permit(p, "thesmp.rtp")) return true;
        long now = System.currentTimeMillis(), until = rtpCooldowns.getOrDefault(p.getUniqueId(), 0L);
        if (!p.hasPermission("thesmp.admin") && until > now) { message(p, "rtp.cooldown", "%seconds%", String.valueOf((until - now + 999) / 1000)); return true; }
        World world = Bukkit.getWorld(getConfig().getString("rtp.world", "world")); if (world == null) world = p.getWorld();
        int min=Math.max(0,getConfig().getInt("rtp.min-radius",500)),max=Math.max(min+1,getConfig().getInt("rtp.max-radius",5000)),attempts=Math.max(1,Math.min(100,getConfig().getInt("rtp.attempts",20)));
        message(p, "rtp.searching");
        findRandomLocation(p, world, min, max, attempts);
        return true;
    }
    private void findRandomLocation(Player p, World world, int min, int max, int remaining) {
        if (!p.isOnline()) return;
        if (remaining <= 0) { message(p, "rtp.failed"); return; }
        double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        int radius = ThreadLocalRandom.current().nextInt(min, max + 1);
        int x = (int) (Math.cos(angle) * radius), z = (int) (Math.sin(angle) * radius);
        world.getChunkAtAsync(x >> 4, z >> 4, true).thenAccept(chunk -> Bukkit.getScheduler().runTask(this, () -> {
            if (!p.isOnline()) return;
            int y = world.getHighestBlockYAt(x, z) + 1;
            Material floor = world.getBlockAt(x, y - 1, z).getType();
            boolean safe = floor.isSolid() && floor != Material.LAVA && floor != Material.MAGMA_BLOCK
                    && world.getBlockAt(x, y, z).isPassable() && world.getBlockAt(x, y + 1, z).isPassable();
            if (!safe) { findRandomLocation(p, world, min, max, remaining - 1); return; }
            Location found = new Location(world, x + .5, y, z + .5);
            if(!chargePlayer(p,"rtp"))return;
            p.teleportAsync(found).thenAccept(success -> Bukkit.getScheduler().runTask(this,()->{if(success){long cooldown=Math.max(0,Math.min(604800,getConfig().getLong("rtp.cooldown-seconds",300)));rtpCooldowns.put(p.getUniqueId(),System.currentTimeMillis()+cooldown*1000L);message(p,"rtp.success","%x%",String.valueOf(found.getBlockX()),"%y%",String.valueOf(found.getBlockY()),"%z%",String.valueOf(found.getBlockZ()));}else message(p,"teleport-failed");}));
        })).exceptionally(error -> { Bukkit.getScheduler().runTask(this, () -> findRandomLocation(p, world, min, max, remaining - 1)); return null; });
    }

    private boolean toggleSit(Player p) {
        if (!permit(p, "thesmp.pose")) return true;
        if (seats.containsKey(p.getUniqueId())) { clearPose(p); message(p, "pose.stop"); return true; }
        boolean supported=p.isOnGround()&&p.getLocation().clone().subtract(0,.2,0).getBlock().getType().isSolid();
        if(!supported||p.isFlying()||p.isGliding()||p.isSwimming()||p.isInsideVehicle()){message(p,"safety.requires-ground");return true;}
        // Marker armor stands do not use the normal armor-stand passenger offset.
        // Keep the mount near the player's feet so the seated body remains above the floor.
        Location seatLocation = p.getLocation().clone().subtract(0, 0.12, 0);
        ArmorStand seat = p.getWorld().spawn(seatLocation, ArmorStand.class, stand -> { stand.setInvisible(true); stand.setMarker(true); stand.setGravity(false); stand.setInvulnerable(true); stand.setSilent(true); stand.setPersistent(false); });
        if (!seat.addPassenger(p)) { seat.remove(); return true; }
        seats.put(p.getUniqueId(), seat); message(p, "pose.sit"); return true;
    }
    private void clearPose(Player p) { ArmorStand seat = seats.remove(p.getUniqueId()); if (seat != null) seat.remove(); }
    private void synchronizePoses() {
        for (Map.Entry<UUID, ArmorStand> entry : new ArrayList<>(seats.entrySet())) {
            Player player = Bukkit.getPlayer(entry.getKey()); ArmorStand seat = entry.getValue();
            if (player == null || !player.isOnline() || !seat.isValid()) { seat.remove(); seats.remove(entry.getKey()); continue; }
            if (!seat.getPassengers().contains(player)) { seat.remove(); seats.remove(entry.getKey()); }
        }
    }
    private void refreshOpenQuestMenus(){if(expansion==null)return;for(Player player:Bukkit.getOnlinePlayers()){Inventory top=player.getOpenInventory().getTopInventory();if(top.getHolder() instanceof SmpExpansion.QuestsHolder holder)expansion.refreshQuests(player,top,holder);}}

    private boolean trade(Player p, String[] args) {
        if (!permit(p, "thesmp.trade")) return true;
        if (args.length == 0) { message(p, "trade.usage"); return true; }
        Player target = Bukkit.getPlayerExact(args[0]); if (target == null) { message(p, "player-not-found"); return true; }
        if (target.equals(p)) { message(p, "teleport.self"); return true; }
        TradeRequest incoming = tradeRequests.get(p.getUniqueId());
        if (incoming != null && incoming.sender.equals(target.getUniqueId())) {
            tradeRequests.remove(p.getUniqueId()); if (incoming.expires < System.currentTimeMillis()) { message(p, "trade.expired"); return true; }
            if (activeTrades.containsKey(p.getUniqueId()) || activeTrades.containsKey(target.getUniqueId())) { message(p, "trade.busy"); return true; }
            TradeSession session = new TradeSession(target, p); activeTrades.put(p.getUniqueId(), session); activeTrades.put(target.getUniqueId(), session); session.open(); return true;
        }
        tradeRequests.put(target.getUniqueId(), new TradeRequest(p.getUniqueId(), System.currentTimeMillis() + getConfig().getLong("trade.request-seconds", 60) * 1000));
        message(p, "trade.sent", "%player%", target.getName()); message(target, "trade.received", "%player%", p.getName()); return true;
    }
    private boolean report(Player p, String[] args) {
        if (!permit(p, "thesmp.report")) return true;
        if (args.length < 1) { message(p, "report.usage"); return true; }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]); if (target.getUniqueId().equals(p.getUniqueId())) { message(p, "report.self"); return true; }
        String targetName = target.getName() == null ? args[0] : target.getName();
        if (args.length == 1) { openReportCategories(p, target.getUniqueId(), targetName); return true; }
        submitReport(p, target, targetName, "Custom", String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length))); return true;
    }
    private void openReportCategories(Player p, UUID target, String targetName) {
        if (!canSubmitReport(p, target, false)) return;
        List<String> categories = getConfig().getStringList("reports.categories");
        if (categories.isEmpty()) categories = List.of("Cheating", "Chat abuse", "Griefing", "Scamming", "Other");
        int size = Math.max(9, Math.min(54, ((categories.size() + 8) / 9) * 9));
        Inventory inv = Bukkit.createInventory(new ReportCategoryHolder(target, targetName), size, tr("report.category-title", "%player%", targetName));
        Material[] icons = {Material.DIAMOND_SWORD, Material.WRITABLE_BOOK, Material.TNT, Material.EMERALD, Material.PAPER};
        for (int i = 0; i < categories.size() && i < size; i++) inv.setItem(i, item(icons[i % icons.length], "&c" + categories.get(i), List.of("&7Report &f" + targetName, "", "&eClick to submit")));
        p.openInventory(inv);
    }
    private boolean canSubmitReport(Player p, UUID target, boolean notify) {
        long remaining = reportCooldowns.getOrDefault(p.getUniqueId(), 0L) - System.currentTimeMillis();
        if (remaining > 0) { message(p, "report.cooldown", "%seconds%", String.valueOf((remaining + 999) / 1000)); return false; }
        ConfigurationSection root = reports.getConfigurationSection("reports");
        if (root != null) for (String id : root.getKeys(false)) {
            String base = "reports." + id;
            if (p.getUniqueId().toString().equals(reports.getString(base + ".reporter-uuid")) && target.toString().equals(reports.getString(base + ".target-uuid")) && List.of("OPEN", "REVIEWING").contains(reports.getString(base + ".status", "OPEN"))) {
                message(p, "report.duplicate", "%id%", id); return false;
            }
        }
        return true;
    }
    private void submitReport(Player p, OfflinePlayer target, String targetName, String category, String reason) {
        if (!canSubmitReport(p, target.getUniqueId(), true)) return;
        int id = reports.getInt("next-id", 1); String base = "reports." + id;
        Location location = p.getLocation();
        reports.set(base + ".reporter-uuid", p.getUniqueId().toString()); reports.set(base + ".reporter", p.getName()); reports.set(base + ".target-uuid", target.getUniqueId().toString()); reports.set(base + ".target", targetName); reports.set(base + ".category", category); reports.set(base + ".reason", reason); reports.set(base + ".created", Instant.now().toString()); reports.set(base + ".status", "OPEN"); reports.set(base + ".context.world", location.getWorld().getName()); reports.set(base + ".context.x", location.getBlockX()); reports.set(base + ".context.y", location.getBlockY()); reports.set(base + ".context.z", location.getBlockZ()); reports.set("next-id", id + 1); saveReports();
        reportCooldowns.put(p.getUniqueId(), System.currentTimeMillis() + getConfig().getLong("reports.cooldown-seconds", 60) * 1000L);
        message(p, "report.submitted", "%player%", targetName, "%id%", String.valueOf(id));
        for (Player online : Bukkit.getOnlinePlayers()) if (online.hasPermission(getConfig().getString("reports.notify-permission", "thesmp.reports.view"))) message(online, "report.notify", "%id%", String.valueOf(id), "%reporter%", p.getName(), "%target%", targetName, "%category%", category, "%reason%", reason);
    }
    private boolean reportsCommand(Player p, String[] args) {
        if (!permit(p, "thesmp.reports.view")) return true;
        if (args.length == 0) { openReportsDashboard(p); return true; }
        if (args[0].equalsIgnoreCase("pending") || args[0].equalsIgnoreCase("active")) { openReports(p, "PENDING"); return true; }
        if (args[0].equalsIgnoreCase("resolved")) { openReports(p, "RESOLVED"); return true; }
        if (args[0].equalsIgnoreCase("rejected") || args[0].equalsIgnoreCase("dismissed")) { openReports(p, "REJECTED"); return true; }
        if (args[0].matches("\\d+")) { try{openReportDetails(p,Integer.parseInt(args[0]),"PENDING");}catch(NumberFormatException e){message(p,"report.admin-usage");} return true; }
        if (args.length >= 2 && List.of("review", "resolve", "dismiss", "reopen").contains(args[0].toLowerCase(Locale.ROOT))) {
            if (!permit(p, "thesmp.reports.manage")) return true;
            try { updateReportStatus(p, Integer.parseInt(args[1]), switch (args[0].toLowerCase(Locale.ROOT)) { case "review" -> "REVIEWING"; case "resolve" -> "RESOLVED"; case "dismiss" -> "DISMISSED"; default -> "OPEN"; }); } catch (NumberFormatException e) { message(p, "report.admin-usage"); }
            return true;
        }
        message(p, "report.admin-usage"); return true;
    }
    private void openReportsDashboard(Player p) {
        if (!permit(p, "thesmp.reports.view")) return;
        Inventory inv = Bukkit.createInventory(new ReportsDashboardHolder(), 27, tr("report.dashboard-title"));
        ItemStack filler=item(Material.BLACK_STAINED_GLASS_PANE," ",null);for(int i=0;i<inv.getSize();i++)inv.setItem(i,filler);
        inv.setItem(11,item(Material.CLOCK,tr("report.pending-name"),List.of(tr("report.count","%count%",String.valueOf(countReports("PENDING"))),"","&eClick to open")));
        inv.setItem(13,item(Material.LIME_DYE,tr("report.resolved-name"),List.of(tr("report.count","%count%",String.valueOf(countReports("RESOLVED"))),"","&eClick to open")));
        inv.setItem(15,item(Material.BARRIER,tr("report.rejected-name"),List.of(tr("report.count","%count%",String.valueOf(countReports("REJECTED"))),"","&eClick to open")));
        p.openInventory(inv);
    }
    private List<Integer> validReportIds(){ConfigurationSection root=reports.getConfigurationSection("reports");if(root==null)return List.of();List<Integer> ids=new ArrayList<>();for(String key:root.getKeys(false))try{ids.add(Integer.parseInt(key));}catch(NumberFormatException ignored){getLogger().warning("Ignoring invalid report id: "+key);}return ids;}
    private long countReports(String filter){return validReportIds().stream().filter(id->reportMatches(id,filter)).count();}
    private void openReports(Player p, String filter) {
        if (!permit(p, "thesmp.reports.view")) return;
        List<Integer> ids = validReportIds().stream().filter(id -> reportMatches(id, filter)).sorted((a,b) -> Integer.compare(b,a)).limit(45).toList();
        Inventory inv = Bukkit.createInventory(new ReportsHolder(ids, filter), 54, tr("report.list-title", "%section%", tr("report.section-"+filter.toLowerCase(Locale.ROOT))));
        for (int i=0;i<ids.size();i++) { int id=ids.get(i); String base="reports."+id, status=reports.getString(base+".status","OPEN"); Material icon=switch(status){case "REVIEWING"->Material.CLOCK;case "RESOLVED"->Material.LIME_DYE;case "DISMISSED"->Material.BARRIER;default->Material.REDSTONE;}; inv.setItem(i,item(icon,"&cReport #"+id,List.of("&7Target: &f"+reports.getString(base+".target"),"&7Reporter: &f"+reports.getString(base+".reporter"),"&7Category: &f"+reports.getString(base+".category","Custom"),"&7Reason: &f"+reports.getString(base+".reason"),"&7Status: &f"+status,"","&eClick to manage"))); }
        if(ids.isEmpty())inv.setItem(22,item(Material.PAPER,tr("report.empty-filter","%filter%",filter),List.of()));
        inv.setItem(49,item(Material.ARROW,tr("report.back"),List.of("&7Return to report sections")));
        p.openInventory(inv);
    }
    private boolean reportMatches(int id, String filter) { String status=reports.getString("reports."+id+".status","OPEN"); return (filter.equals("PENDING") && (status.equals("OPEN") || status.equals("REVIEWING"))) || (filter.equals("REJECTED") && status.equals("DISMISSED")) || filter.equals(status); }
    private void openReportDetails(Player p, int id, String previousFilter) {
        String base="reports."+id; if(!reports.contains(base)){message(p,"report.not-found","%id%",String.valueOf(id));return;}
        Inventory inv=Bukkit.createInventory(new ReportDetailsHolder(id,previousFilter),45,tr("report.details-title","%id%",String.valueOf(id)));
        ItemStack filler=item(Material.GRAY_STAINED_GLASS_PANE," ",null);for(int i=0;i<inv.getSize();i++)inv.setItem(i,filler);
        inv.setItem(13,item(Material.BOOK,"&fReport #"+id,List.of("&7Target: &f"+reports.getString(base+".target","?"),"&7Reporter: &f"+reports.getString(base+".reporter","?"),"&7Category: &f"+reports.getString(base+".category","Custom"),"&7Reason: &f"+reports.getString(base+".reason","?"),"&7Status: &f"+reports.getString(base+".status","OPEN"),"&7Created: &f"+reports.getString(base+".created","?"),"&7Location: &f"+reports.getString(base+".context.world","?")+" "+reports.getInt(base+".context.x")+", "+reports.getInt(base+".context.y")+", "+reports.getInt(base+".context.z"),"&7Handled by: &f"+reports.getString(base+".handled-by","-"))));
        inv.setItem(22,item(Material.ENDER_PEARL,tr("report.teleport-target"),List.of("&7Teleport to the reported player")));
        inv.setItem(29,item(Material.CLOCK,tr("report.mark-pending"),List.of("&7Return this report to pending")));
        inv.setItem(31,item(Material.LIME_DYE,tr("report.mark-resolved"),List.of("&7Mark this as a valid, handled report")));
        inv.setItem(33,item(Material.BARRIER,tr("report.mark-rejected"),List.of("&7Reject random, false or invalid reports")));
        inv.setItem(40,item(Material.ARROW,tr("report.back"),List.of("&7Return to the previous list")));p.openInventory(inv);
    }
    private void updateReportStatus(Player p, int id, String status) {
        String base="reports."+id;if(!reports.contains(base)){message(p,"report.not-found","%id%",String.valueOf(id));return;} reports.set(base+".status",status);reports.set(base+".handled-by",p.getName());reports.set(base+".handled-at",Instant.now().toString());saveReports();message(p,"report.status-updated","%id%",String.valueOf(id),"%status%",status);
    }
    private void teleportToReportTarget(Player p,int id){String raw=reports.getString("reports."+id+".target-uuid","");try{Player target=Bukkit.getPlayer(UUID.fromString(raw));if(target==null){message(p,"report.target-offline");return;}p.closeInventory();p.teleportAsync(target.getLocation());message(p,"report.teleported","%player%",target.getName());}catch(IllegalArgumentException ignored){message(p,"report.target-offline");}}

    private boolean menuCommand(Player p, String[] args) {
        if (!permit(p, "thesmp.menu")) return true;
        if (args.length == 0) { ConfigurationSection root=menus.getConfigurationSection("menus"); message(p,"menus.list","%menus%",root==null?"":String.join(", ",root.getKeys(false))); return true; }
        openCustomMenu(p, args[0]); return true;
    }
    private void openGuide(Player p) {
        Inventory inv = Bukkit.createInventory(new GuideHolder(), 45, tr(p,"guide.title"));
        ItemStack filler = item(Material.CYAN_STAINED_GLASS_PANE, " ", null);
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
        inv.setItem(10, item(Material.RED_BED, tr(p,"guide.homes"), List.of("&f/sethome [name]", "&f/home [name]", "&f/homes", "&f/delhome <name>", "", "&eClick to open your homes")));
        inv.setItem(12, item(Material.ENDER_PEARL, tr(p,"guide.teleport"), List.of("&f/spawn", "&f/tpa <player>", "&f/tphere <player>", "&f/tpallow", "&f/tpdeny", "&f/rtp")));
        inv.setItem(14, item(Material.OAK_STAIRS, tr(p,"guide.poses"), List.of("&f/sit")));
        inv.setItem(16, item(Material.EMERALD, tr(p,"guide.trade"), List.of("&f/trade <player>", "", "&7Both players must accept.")));
        inv.setItem(28, item(Material.PAPER, tr(p,"guide.reports"), List.of("&f/report <player> [reason]", "&f/reports &8(staff)", "", "&7Categories, tracking and moderation states")));
        inv.setItem(30, item(Material.SHIELD, tr(p,"guide.clans"), List.of("&f/clan", "&f/cc <message>", "&f/ac <message>", "", "&eClick to open clan headquarters")));
        inv.setItem(32, item(Material.CHEST, tr(p,"guide.menus"), List.of("&f/menu", "&f/menu <name>", "", "&eClick to open the main menu")));
        inv.setItem(34, item(Material.PLAYER_HEAD, tr(p,"guide.friends"), List.of("&f/friends", "&f/friend add <player>", "&f/friend msg <player> <message>", "", "&eClick to open your friends")));
        p.openInventory(inv);
    }
    private void openCustomMenu(Player p, String id) {
        String base="menus."+id; if(!menus.contains(base)){message(p,"menus.not-found","%menu%",id);return;}
        String permission=menus.getString(base+".permission","thesmp.menu");if(!p.hasPermission(permission)){message(p,"no-permission");return;}
        int size=Math.max(9,Math.min(54,((menus.getInt(base+".size",27)+8)/9)*9));Inventory inv=Bukkit.createInventory(new CustomHolder(id),size,color(menus.getString(base+".title",id)));
        ConfigurationSection items=menus.getConfigurationSection(base+".items");if(items!=null)for(String key:items.getKeys(false)){String path=base+".items."+key;Material mat=Material.matchMaterial(menus.getString(path+".material","STONE"));if(mat==null)mat=Material.STONE;int slot=menus.getInt(path+".slot",0);if(slot>=0&&slot<size)inv.setItem(slot,item(mat,menus.getString(path+".name",key),menus.getStringList(path+".lore")));}p.openInventory(inv);
    }
    private String menuPlaceholders(Player p, String value) {
        return value.replace("%player%", p.getName()).replace("%world%", p.getWorld().getName())
                .replace("%x%", String.valueOf(p.getLocation().getBlockX())).replace("%y%", String.valueOf(p.getLocation().getBlockY()))
                .replace("%z%", String.valueOf(p.getLocation().getBlockZ()));
    }
    private void executeMenuItem(Player p, String path) {
        for (String line : menus.getStringList(path + ".messages")) p.sendMessage(color(menuPlaceholders(p, line)));
        for (String raw : menus.getStringList(path + ".commands")) {
            String action = menuPlaceholders(p, raw);
            if (action.startsWith("message:")) p.sendMessage(color(action.substring(8)));
            else if (action.startsWith("broadcast:")) Bukkit.broadcastMessage(color(action.substring(10)));
            else if (action.startsWith("console:")) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), action.substring(8));
            else if (action.startsWith("player:")) p.performCommand(action.substring(7));
            else if (action.startsWith("open:")) openCustomMenu(p, action.substring(5));
            else if (action.startsWith("title:")) {
                String[] parts = action.substring(6).split("\\|", 2);
                p.sendTitle(color(parts[0]), parts.length > 1 ? color(parts[1]) : "", 10, 50, 10);
            } else if (!action.equalsIgnoreCase("close") && !action.isBlank()) p.performCommand(action);
        }
    }

    @EventHandler public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return; InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (holder instanceof HomesHolder homes) { event.setCancelled(true); if (!homes.owner.equals(p.getUniqueId()) || event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return; ItemStack clicked=event.getCurrentItem();if(clicked==null||!clicked.hasItemMeta())return;String name=ChatColor.stripColor(clicked.getItemMeta().getDisplayName());p.closeInventory();if(event.getClick()==ClickType.RIGHT)deleteHome(p,name);else teleportHome(p,name); }
        else if (holder instanceof ReportCategoryHolder category) { event.setCancelled(true); if(event.getRawSlot()<0||event.getRawSlot()>=event.getView().getTopInventory().getSize())return;ItemStack clicked=event.getCurrentItem();if(clicked==null||!clicked.hasItemMeta())return;String selected=ChatColor.stripColor(clicked.getItemMeta().getDisplayName());p.closeInventory();submitReport(p,Bukkit.getOfflinePlayer(category.target),category.targetName,selected,selected); }
        else if(holder instanceof ReportsDashboardHolder){event.setCancelled(true);if(event.getRawSlot()==11)openReports(p,"PENDING");else if(event.getRawSlot()==13)openReports(p,"RESOLVED");else if(event.getRawSlot()==15)openReports(p,"REJECTED");}
        else if (holder instanceof ReportsHolder reportMenu) { event.setCancelled(true);if(event.getRawSlot()==49){openReportsDashboard(p);return;}if(event.getRawSlot()<0||event.getRawSlot()>=reportMenu.ids.size())return;openReportDetails(p,reportMenu.ids.get(event.getRawSlot()),reportMenu.filter); }
        else if(holder instanceof ReportDetailsHolder details){event.setCancelled(true);if(event.getRawSlot()==40){openReports(p,details.previousFilter);return;}if(event.getRawSlot()==22){teleportToReportTarget(p,details.id);return;}if(!List.of(29,31,33).contains(event.getRawSlot()))return;if(!permit(p,"thesmp.reports.manage"))return;String status=event.getRawSlot()==29?"OPEN":event.getRawSlot()==31?"RESOLVED":"DISMISSED";updateReportStatus(p,details.id,status);openReports(p,status.equals("DISMISSED")?"REJECTED":status.equals("OPEN")?"PENDING":"RESOLVED");}
        else if (holder instanceof GuideHolder) { event.setCancelled(true); if (event.getRawSlot() == 10) { p.closeInventory(); p.performCommand("homes"); } else if (event.getRawSlot() == 30) { p.closeInventory(); p.performCommand("clan"); } else if (event.getRawSlot() == 32) { p.closeInventory(); p.performCommand("menu main"); } else if(event.getRawSlot()==34){p.closeInventory();p.performCommand("friends");} }
        else if(holder instanceof LanguageHolder languageMenu){event.setCancelled(true);String language=switch(event.getRawSlot()){case 10->"en";case 12->"es";case 14->"pt";case 16->"fr";default->null;};if(language!=null)selectLanguage(p,language,languageMenu.global);}
        else if(holder instanceof FriendsHolder friendsMenu){event.setCancelled(true);if(event.getRawSlot()==49){openFriends(p,"dashboard");return;}if(friendsMenu.view.equals("dashboard")){if(event.getRawSlot()==11)openFriends(p,"list");else if(event.getRawSlot()==15)openFriends(p,"requests");return;}if(event.getRawSlot()<0||event.getRawSlot()>=friendsMenu.ids.size())return;OfflinePlayer target=Bukkit.getOfflinePlayer(friendsMenu.ids.get(event.getRawSlot()));String targetName=target.getName()==null?friendsMenu.ids.get(event.getRawSlot()).toString():target.getName();p.closeInventory();if(friendsMenu.view.equals("requests"))p.performCommand("friend "+(event.isRightClick()?"deny ":"accept ")+targetName);else if(target.isOnline())p.performCommand("tpa "+targetName);else message(p,"friends.offline");}
        else if (holder instanceof CustomHolder custom) { event.setCancelled(true); if(event.getRawSlot()<0||event.getRawSlot()>=event.getView().getTopInventory().getSize())return;ConfigurationSection items=menus.getConfigurationSection("menus."+custom.id+".items");if(items==null)return;for(String key:items.getKeys(false)){String path="menus."+custom.id+".items."+key;if(menus.getInt(path+".slot",-1)==event.getRawSlot()){String permission=menus.getString(path+".permission","");if(!permission.isBlank()&&!p.hasPermission(permission)){message(p,"no-permission");return;}p.closeInventory();executeMenuItem(p,path);return;}} }
        else if(holder instanceof SmpExpansion.WarpsHolder warps){event.setCancelled(true);expansion.clickWarp(p,warps,event.getRawSlot());}
        else if(holder instanceof SmpExpansion.RewardsHolder){event.setCancelled(true);expansion.clickRewards(p,event.getRawSlot());}
        else if(holder instanceof SmpExpansion.QuestsHolder quests){event.setCancelled(true);expansion.clickQuest(p,quests,event.getRawSlot());}
        else if (holder instanceof TradeSession session) session.click(event, p);
        else if (holder instanceof ClanManager.ClanHolder clanHolder) clans.click(event, p, clanHolder);
    }
    @EventHandler public void onDrag(InventoryDragEvent event){if(!(event.getWhoClicked() instanceof Player p))return;InventoryHolder holder=event.getView().getTopInventory().getHolder();if(holder instanceof TradeSession session){session.drag(event,p);return;}if(isProtectedMenu(holder)&&event.getRawSlots().stream().anyMatch(slot->slot<event.getView().getTopInventory().getSize()))event.setCancelled(true);}
    private boolean isProtectedMenu(InventoryHolder holder){return holder instanceof HomesHolder||holder instanceof ReportsHolder||holder instanceof ReportsDashboardHolder||holder instanceof ReportDetailsHolder||holder instanceof ReportCategoryHolder||holder instanceof CustomHolder||holder instanceof GuideHolder||holder instanceof LanguageHolder||holder instanceof FriendsHolder||holder instanceof SmpExpansion.WarpsHolder||holder instanceof SmpExpansion.RewardsHolder||holder instanceof SmpExpansion.QuestsHolder||holder instanceof ClanManager.ClanHolder;}
    @EventHandler public void onClose(InventoryCloseEvent event) { InventoryHolder holder=event.getInventory().getHolder();if(holder instanceof TradeSession session&&!session.finishing)Bukkit.getScheduler().runTask(this,()->session.cancel(true)); }
    @EventHandler public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        String raw=event.getMessage().substring(1);String[] parts=raw.split(" ");String label=parts[0];String[] args=java.util.Arrays.copyOfRange(parts,1,parts.length);
        ConfigurationSection root=menus.getConfigurationSection("menus");if(root!=null)for(String id:root.getKeys(false))if(label.equalsIgnoreCase(menus.getString("menus."+id+".open-command","__none__"))){event.setCancelled(true);openCustomMenu(event.getPlayer(),id);return;}
        ConfigurationSection commands=getConfig().getConfigurationSection("custom-commands");if(commands==null)return;
        for(String id:commands.getKeys(false)){String base="custom-commands."+id;List<String> names=new ArrayList<>();names.add(id);names.addAll(getConfig().getStringList(base+".aliases"));if(names.stream().noneMatch(x->x.equalsIgnoreCase(label)))continue;event.setCancelled(true);Player p=event.getPlayer();String permission=getConfig().getString(base+".permission","");if(!permission.isBlank()&&!p.hasPermission(permission)){message(p,"no-permission");return;}for(String line:getConfig().getStringList(base+".messages"))p.sendMessage(color(commandPlaceholders(p,line,args)));for(String action:getConfig().getStringList(base+".actions")){action=commandPlaceholders(p,action,args);if(action.startsWith("console:"))Bukkit.dispatchCommand(Bukkit.getConsoleSender(),action.substring(8));else if(action.startsWith("player:"))Bukkit.dispatchCommand(p,action.substring(7));else if(action.startsWith("broadcast:"))Bukkit.broadcastMessage(color(action.substring(10)));else if(action.startsWith("message:"))p.sendMessage(color(action.substring(8)));}return;}
    }
    @EventHandler public void onAvailableCommands(PlayerCommandSendEvent event){
        ConfigurationSection menuRoot=menus.getConfigurationSection("menus");if(menuRoot!=null)for(String id:menuRoot.getKeys(false)){String command=menus.getString("menus."+id+".open-command","");String permission=menus.getString("menus."+id+".permission","thesmp.menu");if(!command.isBlank()&&(permission.isBlank()||event.getPlayer().hasPermission(permission)))event.getCommands().add(command.toLowerCase(Locale.ROOT));}
        ConfigurationSection root=getConfig().getConfigurationSection("custom-commands");if(root==null)return;for(String id:root.getKeys(false)){String permission=getConfig().getString("custom-commands."+id+".permission","");if(!permission.isBlank()&&!event.getPlayer().hasPermission(permission))continue;event.getCommands().add(id.toLowerCase(Locale.ROOT));for(String alias:getConfig().getStringList("custom-commands."+id+".aliases"))event.getCommands().add(alias.toLowerCase(Locale.ROOT));}
    }
    @EventHandler public void onTabComplete(TabCompleteEvent event){
        if(!(event.getSender() instanceof Player p)||!event.isCommand())return;String raw=event.getBuffer().substring(1);String[] parts=raw.split(" ",-1);String label=parts[0];ConfigurationSection root=getConfig().getConfigurationSection("custom-commands");if(root==null)return;
        for(String id:root.getKeys(false)){String base="custom-commands."+id;List<String> names=new ArrayList<>();names.add(id);names.addAll(getConfig().getStringList(base+".aliases"));if(names.stream().noneMatch(x->x.equalsIgnoreCase(label)))continue;String permission=getConfig().getString(base+".permission","");if(!permission.isBlank()&&!p.hasPermission(permission)){event.setCompletions(List.of());return;}int argument=Math.max(1,parts.length-1);String current=parts.length>1?parts[parts.length-1]:"";List<String> configured=getConfig().getStringList(base+".tab-completions."+argument);List<String> suggestions=new ArrayList<>();for(String value:configured)if(value.equalsIgnoreCase("%players%"))Bukkit.getOnlinePlayers().stream().map(Player::getName).forEach(suggestions::add);else suggestions.add(commandPlaceholders(p,value,java.util.Arrays.copyOfRange(parts,1,parts.length)));event.setCompletions(suggestions.stream().filter(x->x.toLowerCase(Locale.ROOT).startsWith(current.toLowerCase(Locale.ROOT))).distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList());return;}
    }
    private String commandPlaceholders(Player p,String value,String[] args){String result=value.replace("%player%",p.getName()).replace("%world%",p.getWorld().getName()).replace("%args%",String.join(" ",args));for(int i=0;i<args.length;i++)result=result.replace("%arg"+(i+1)+"%",args[i]);return result;}
    private void handleWelcome(PlayerJoinEvent event){
        if(!getConfig().getBoolean("welcome.enabled",true))return;Player p=event.getPlayer();String base=!p.hasPlayedBefore()&&getConfig().getBoolean("welcome.first-join.enabled",true)?"welcome.first-join":"welcome.join";if(!getConfig().getBoolean(base+".enabled",true))return;
        List<String> messages=getConfig().getStringList(base+".messages");boolean broadcast=getConfig().getBoolean(base+".broadcast",true);for(String line:messages){String rendered=color(commandPlaceholders(p,line,new String[0]).replace("%online%",String.valueOf(Bukkit.getOnlinePlayers().size())).replace("%max%",String.valueOf(Bukkit.getMaxPlayers())));if(broadcast)Bukkit.broadcastMessage(rendered);else p.sendMessage(rendered);}for(String action:getConfig().getStringList(base+".commands")){String rendered=commandPlaceholders(p,action,new String[0]);if(rendered.startsWith("player:"))Bukkit.dispatchCommand(p,rendered.substring(7));else Bukkit.dispatchCommand(Bukkit.getConsoleSender(),rendered.startsWith("console:")?rendered.substring(8):rendered);}
    }
    @EventHandler(ignoreCancelled=true) public void onMove(PlayerMoveEvent event) { if(event.getTo()==null)return;Warmup warm=warmups.get(event.getPlayer().getUniqueId());if(warm!=null&&(!warm.origin.getWorld().equals(event.getTo().getWorld())||warm.origin.distanceSquared(event.getTo())>.04)){warm.task.cancel();warmups.remove(event.getPlayer().getUniqueId());message(event.getPlayer(),"teleport.moved");} }
    @EventHandler(ignoreCancelled=true) public void onSneak(PlayerToggleSneakEvent event) { if (event.isSneaking() && seats.containsKey(event.getPlayer().getUniqueId())) { clearPose(event.getPlayer()); message(event.getPlayer(), "pose.stop"); } }
    @EventHandler public void onQuit(PlayerQuitEvent event) { if(getConfig().getBoolean("welcome.hide-vanilla-messages",true))event.setQuitMessage(null);UUID id=event.getPlayer().getUniqueId();lastMessages.remove(id);lastMessages.entrySet().removeIf(x->x.getValue().equals(id));notifyFriendStatus(event.getPlayer(),false);clearPose(event.getPlayer());Warmup w=warmups.remove(id);if(w!=null)w.task.cancel();TradeSession t=activeTrades.get(id);if(t!=null)t.cancel(false);hiddenSpectators.removeIf(x->x.startsWith(id+":")||x.endsWith(":"+id)); }
    @EventHandler(ignoreCancelled=true) public void onDamage(EntityDamageEvent event) { if(event.getEntity() instanceof Player p){Warmup w=warmups.remove(p.getUniqueId());if(w!=null){w.task.cancel();message(p,"teleport.moved");}} }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void onGameMode(PlayerGameModeChangeEvent e){Bukkit.getScheduler().runTask(this,()->syncTarget(e.getPlayer()));}
    @EventHandler(priority=EventPriority.MONITOR) public void onJoin(PlayerJoinEvent e){if(getConfig().getBoolean("welcome.hide-vanilla-messages",true))e.setJoinMessage(null);handleWelcome(e);Bukkit.getScheduler().runTaskLater(this,()->{syncViewer(e.getPlayer());syncTarget(e.getPlayer());notifyFriendStatus(e.getPlayer(),true);},10L);}
    @EventHandler(priority=EventPriority.MONITOR) public void onWorld(PlayerChangedWorldEvent e){Bukkit.getScheduler().runTaskLater(this,()->{syncViewer(e.getPlayer());syncTarget(e.getPlayer());},2L);}

    private void syncSpectators(){for(Player target:Bukkit.getOnlinePlayers())syncTarget(target);} private void syncViewer(Player viewer){for(Player target:Bukkit.getOnlinePlayers())applySpectator(viewer,target);} private void syncTarget(Player target){for(Player viewer:Bukkit.getOnlinePlayers())applySpectator(viewer,target);} private void applySpectator(Player viewer,Player target){String key=viewer.getUniqueId()+":"+target.getUniqueId();boolean hide=target.getGameMode()==GameMode.SPECTATOR&&!viewer.hasPermission("thesmp.spectatortab.see");if(hide){viewer.unlistPlayer(target);hiddenSpectators.add(key);}else if(hiddenSpectators.remove(key))viewer.listPlayer(target);} private void restoreSpectators(){for(Player viewer:Bukkit.getOnlinePlayers())for(Player target:Bukkit.getOnlinePlayers())if(hiddenSpectators.contains(viewer.getUniqueId()+":"+target.getUniqueId()))viewer.listPlayer(target);hiddenSpectators.clear();}

    private void saveData(){try{data.save(dataFile);}catch(IOException e){getLogger().severe("Could not save data.yml: "+e.getMessage());}} private void saveReports(){try{reports.save(reportsFile);}catch(IOException e){getLogger().severe("Could not save reports.yml: "+e.getMessage());}}

    private final class TradeSession implements InventoryHolder {
        final Player left,right;final Inventory inventory;boolean leftAccepted,rightAccepted,finishing;
        final Set<Integer> leftSlots=Set.of(0,1,2,3,9,10,11,12,18,19,20,21,27,28,29,30,36,37,38,39);final Set<Integer> rightSlots=Set.of(5,6,7,8,14,15,16,17,23,24,25,26,32,33,34,35,41,42,43,44);
        TradeSession(Player left,Player right){this.left=left;this.right=right;inventory=Bukkit.createInventory(this,54,tr("trade.title","%left%",left.getName(),"%right%",right.getName()));updateButtons();}
        public Inventory getInventory(){return inventory;} void open(){left.openInventory(inventory);right.openInventory(inventory);} void updateButtons(){inventory.setItem(45,item(leftAccepted?Material.LIME_DYE:Material.GRAY_DYE,leftAccepted?tr("trade.accepted"):tr("trade.click-accept"),List.of("&7"+left.getName())));inventory.setItem(53,item(rightAccepted?Material.LIME_DYE:Material.GRAY_DYE,rightAccepted?tr("trade.accepted"):tr("trade.click-accept"),List.of("&7"+right.getName())));for(int slot:new int[]{4,13,22,31,40,46,47,48,49,50,51,52})inventory.setItem(slot,item(Material.BLACK_STAINED_GLASS_PANE," ",null));}
        void click(InventoryClickEvent e,Player p){boolean normal=e.getClick()==ClickType.LEFT||e.getClick()==ClickType.RIGHT;if(!normal){e.setCancelled(true);return;}boolean top=e.getRawSlot()>=0&&e.getRawSlot()<54;if(!top)return;Set<Integer> allowed=p.equals(left)?leftSlots:rightSlots;if((p.equals(left)&&e.getRawSlot()==45)||(p.equals(right)&&e.getRawSlot()==53)){e.setCancelled(true);if(p.equals(left))leftAccepted=!leftAccepted;else rightAccepted=!rightAccepted;updateButtons();if(leftAccepted&&rightAccepted)complete();return;}if(!allowed.contains(e.getRawSlot())){e.setCancelled(true);return;}resetAcceptance();}
        void drag(InventoryDragEvent e,Player p){Set<Integer> allowed=p.equals(left)?leftSlots:rightSlots;boolean touchesTop=e.getRawSlots().stream().anyMatch(slot->slot<54);if(!touchesTop)return;if(e.getRawSlots().stream().anyMatch(slot->slot<54&&!allowed.contains(slot))){e.setCancelled(true);return;}resetAcceptance();}
        void resetAcceptance(){leftAccepted=false;rightAccepted=false;Bukkit.getScheduler().runTask(TheSmp.this,this::updateButtons);}
        List<ItemStack> collect(Set<Integer> slots){List<ItemStack> list=new ArrayList<>();for(int slot:slots){ItemStack s=inventory.getItem(slot);if(s!=null&&!s.getType().isAir()){list.add(s.clone());inventory.setItem(slot,null);}}return list;}void give(Player p,List<ItemStack> items){for(ItemStack stack:items){Map<Integer,ItemStack> extra=p.getInventory().addItem(stack);extra.values().forEach(x->p.getWorld().dropItemNaturally(p.getLocation(),x));}}
        void complete(){if(finishing)return;finishing=true;List<ItemStack>a=collect(leftSlots),b=collect(rightSlots);give(left,b);give(right,a);message(left,"trade.complete");message(right,"trade.complete");activeTrades.remove(left.getUniqueId());activeTrades.remove(right.getUniqueId());left.closeInventory();right.closeInventory();}
        void cancel(boolean notify){if(finishing)return;finishing=true;give(left,collect(leftSlots));give(right,collect(rightSlots));activeTrades.remove(left.getUniqueId());activeTrades.remove(right.getUniqueId());if(notify){message(left,"trade.cancelled");message(right,"trade.cancelled");}if(left.getOpenInventory().getTopInventory().getHolder()==this)left.closeInventory();if(right.getOpenInventory().getTopInventory().getHolder()==this)right.closeInventory();}
    }
}

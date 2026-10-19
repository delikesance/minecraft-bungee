package win.vampyuyu.network;

import java.io.*;
import com.google.gson.Gson;
import java.nio.file.*;
import java.util.*;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;

final class PlayerInventoryStore {
    private final JavaPlugin plugin;
    private static final Gson JSON = new Gson();
    record Slot(int slot, String material, int amount, String name, String details, boolean custom) {}
    record View(String uuid, boolean online, String container, String revision, List<Slot> slots, Map<String,Object> player) {}
    record Edit(String container, String revision, int slot, String material, int amount) {}
    static class Conflict extends IllegalArgumentException { Conflict(String message) { super(message); } }
    PlayerInventoryStore(JavaPlugin plugin) { this.plugin = plugin; }
    private Path file(UUID uuid) { return plugin.getServer().getWorlds().get(0).getWorldFolder().toPath().resolve("playerdata").resolve(uuid + ".dat"); }
    private static int size(String container) { if (container.equals("inventory")) return 41; if (container.equals("ender")) return 27; throw new IllegalArgumentException("Choose inventory or ender chest"); }
    private static Inventory inventory(Player player, String container) { return container.equals("ender") ? player.getEnderChest() : player.getInventory(); }
    static int fromNbtSlot(int slot, boolean ender) { if (ender) return slot; if (slot == -106 || slot == 150) return 40; if (slot >= 100 && slot <= 103) return slot - 64; return slot; }
    static int toNbtSlot(int slot, boolean ender) { if (ender || slot < 36) return slot; return slot == 40 ? -106 : slot + 64; }
    private static Slot empty(int index) { return new Slot(index,"minecraft:air",0,"Empty","",false); }
    private static Slot onlineSlot(int index, ItemStack item) {
        if (item == null || item.getType().isAir()) return empty(index);
        String name = item.hasItemMeta() && item.getItemMeta().hasDisplayName()
                ? PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName()) : item.getType().getKey().toString();
        String detail = item.hasItemMeta() ? item.getItemMeta().getAsString() : "";
        return new Slot(index,item.getType().getKey().toString(),item.getAmount(),name,detail.length()>4000?detail.substring(0,4000):detail,item.hasItemMeta());
    }
    private static String onlineRevision(Inventory inventory) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        for (ItemStack item : inventory.getContents()) { if (item == null || item.getType().isAir()) { out.writeInt(0); continue; } byte[] data = item.serializeAsBytes(); out.writeInt(data.length); out.write(data); }
        return PlayerNbt.revision(bytes.toByteArray());
    }
    View read(UUID uuid, String container) throws IOException {
        int count = size(container); Player online = plugin.getServer().getPlayer(uuid);
        List<Slot> slots = new ArrayList<>(); Map<String,Object> info = new LinkedHashMap<>();
        if (online != null) {
            Inventory inventory = inventory(online,container);
            for (int i = 0; i < count; i++) slots.add(onlineSlot(i,inventory.getItem(i)));
            var location = online.getLocation();
            info.put("gamemode",online.getGameMode().name()); info.put("health",online.getHealth()); info.put("food",online.getFoodLevel());
            info.put("level",online.getLevel()); info.put("world",online.getWorld().getName()); info.put("x",location.getX()); info.put("y",location.getY()); info.put("z",location.getZ());
            info.put("op",online.isOp()); info.put("whitelisted",online.isWhitelisted()); info.put("flying",online.isFlying()); info.put("ping",online.getPing());
            return new View(uuid.toString(),true,container,onlineRevision(inventory),slots,info);
        }
        PlayerNbt.Document doc = PlayerNbt.read(file(uuid)); var root = PlayerNbt.compound(doc.root());
        PlayerNbt.Tags items = PlayerNbt.list(root,container.equals("ender")?"EnderItems":"Inventory");
        Map<Integer,PlayerNbt.Tag> indexed = index(items,container.equals("ender"),count);
        for (int i=0;i<count;i++) {
            if (!indexed.containsKey(i)) { slots.add(empty(i)); continue; }
            var item = PlayerNbt.compound(indexed.get(i)); String id = PlayerNbt.string(item,"id","minecraft:air"); int amount = PlayerNbt.number(item,"count",PlayerNbt.number(item,"Count",0));
            boolean custom = item.containsKey("components") || item.containsKey("tag");
            PlayerNbt.Tag properties = item.getOrDefault("components",item.get("tag"));
            String details = properties == null ? "" : JSON.toJson(properties.value());
            if(details.length()>4000)details=details.substring(0,4000)+"…";
            String name=id;
            if(properties!=null&&properties.type()==10){var fields=PlayerNbt.compound(properties);var customName=fields.get("minecraft:custom_name");if(customName!=null){if(customName.type()==8)name=(String)customName.value();else if(customName.type()==10)name=PlayerNbt.string(PlayerNbt.compound(customName),"text",id);}}
            slots.add(new Slot(i,id,amount,name,details,custom));
        }
        int mode = PlayerNbt.number(root,"playerGameType",0); info.put("gamemode",switch(mode){case 1->"CREATIVE";case 2->"ADVENTURE";case 3->"SPECTATOR";default->"SURVIVAL";});
        info.put("health",number(root,"Health",20)); info.put("food",PlayerNbt.number(root,"foodLevel",20)); info.put("level",PlayerNbt.number(root,"XpLevel",0));
        info.put("world",PlayerNbt.string(root,"Dimension","unknown"));
        PlayerNbt.Tag position = root.get("Pos"); if(position!=null&&position.type()==9){var values=((PlayerNbt.Tags)position.value()).values();if(values.size()==3){info.put("x",values.get(0).value());info.put("y",values.get(1).value());info.put("z",values.get(2).value());}}
        var offline = plugin.getServer().getOfflinePlayer(uuid);info.put("op",offline.isOp());info.put("whitelisted",offline.isWhitelisted());
        return new View(uuid.toString(),false,container,PlayerNbt.revision(Files.readAllBytes(file(uuid))),slots,info);
    }
    private static double number(Map<String,PlayerNbt.Tag> root,String key,double fallback){var tag=root.get(key);return tag!=null&&tag.value() instanceof Number value?value.doubleValue():fallback;}
    private static Map<Integer,PlayerNbt.Tag> index(PlayerNbt.Tags list,boolean ender,int count) {
        Map<Integer,PlayerNbt.Tag> result=new HashMap<>();for(var tag:list.values()){int slot=fromNbtSlot(PlayerNbt.number(PlayerNbt.compound(tag),"Slot",-1),ender);if(slot<0||slot>=count||result.put(slot,tag)!=null)throw new IllegalArgumentException("Invalid or duplicate saved inventory slot");}return result;
    }
    private void backup(UUID uuid) throws IOException {
        Path source=file(uuid);if(!Files.isRegularFile(source)||Files.isSymbolicLink(source))throw new IOException("Player data backup unavailable");
        Path folder=plugin.getDataFolder().toPath().resolve("player-backups").resolve(uuid.toString());Files.createDirectories(folder);
        Files.copy(source,folder.resolve(System.currentTimeMillis()+"-"+UUID.randomUUID()+".dat"));
        try(var entries=Files.list(folder)){var paths=entries.sorted(Comparator.comparing((Path p)->p.getFileName().toString()).reversed()).toList();for(int i=10;i<paths.size();i++)Files.delete(paths.get(i));}
    }
    void ensureOutsideArena(UUID uuid) throws IOException {
        if(java.nio.file.Files.exists(plugin.getDataFolder().toPath().getParent().resolve("NexusArenaEngine/snapshots").resolve(uuid+".yml")))throw new Conflict("Leave the arena and finish restoring the original player data before changing it.");
    }
    View edit(UUID uuid, Edit edit) throws IOException {
        ensureOutsideArena(uuid);
        int count=size(edit.container());if(edit.slot()<0||edit.slot()>=count)throw new IllegalArgumentException("Invalid inventory slot");
        Material material=Material.matchMaterial(edit.material());if(material==null||(!material.isItem()&&!material.isAir()))throw new IllegalArgumentException("Unknown item material");
        if(edit.amount()<0||edit.amount()>99)throw new IllegalArgumentException("Invalid item amount");
        View before=read(uuid,edit.container());if(edit.revision()==null||!edit.revision().equals(before.revision()))throw new Conflict("Inventory changed. Refresh before editing.");
        Player player=plugin.getServer().getPlayer(uuid);
        boolean clear=material.isAir()||edit.amount()==0;
        if(player!=null){
            Inventory inv=inventory(player,edit.container());ItemStack existing=inv.getItem(edit.slot());ItemStack replacement=null;
            if(!clear){replacement=existing!=null&&existing.getType()==material?existing.clone():new ItemStack(material);if(edit.amount()>replacement.getMaxStackSize())throw new IllegalArgumentException("Amount exceeds this item's stack size");replacement.setAmount(edit.amount());}
            player.saveData();backup(uuid);inv.setItem(edit.slot(),replacement);player.updateInventory();player.saveData();
        }else{
            Path file=file(uuid);PlayerNbt.Document doc=PlayerNbt.read(file);var root=PlayerNbt.compound(doc.root());if(PlayerNbt.number(root,"DataVersion",0)<3837)throw new IllegalArgumentException("Legacy player data: join this server once before offline inventory editing");String key=edit.container().equals("ender")?"EnderItems":"Inventory";
            PlayerNbt.Tags list=PlayerNbt.list(root,key);var indexed=index(list,edit.container().equals("ender"),count);var existing=indexed.get(edit.slot());
            var fields=existing==null?new LinkedHashMap<String,PlayerNbt.Tag>():new LinkedHashMap<>(PlayerNbt.compound(existing));
            boolean same=material.getKey().toString().equals(PlayerNbt.string(fields,"id",""));int max=material.getMaxStackSize();
            if(same&&fields.get("components")!=null){var components=PlayerNbt.compound(fields.get("components"));max=PlayerNbt.number(components,"minecraft:max_stack_size",max);}
            if(!clear&&edit.amount()>max)throw new IllegalArgumentException("Amount exceeds this item's stack size");
            if(!same)fields.clear();fields.put("id",new PlayerNbt.Tag(8,material.getKey().toString()));fields.put("count",new PlayerNbt.Tag(3,edit.amount()));fields.remove("Count");fields.put("Slot",new PlayerNbt.Tag(1,(byte)toNbtSlot(edit.slot(),edit.container().equals("ender"))));
            if(existing!=null)list.values().remove(existing);if(!clear)list.values().add(new PlayerNbt.Tag(10,fields));root.put(key,new PlayerNbt.Tag(9,new PlayerNbt.Tags(10,list.values())));
            // Recheck the complete file immediately before writing; never patch an online player.
            if(plugin.getServer().getPlayer(uuid)!=null||!PlayerNbt.revision(Files.readAllBytes(file)).equals(edit.revision()))throw new Conflict("Player data changed. Refresh before editing.");
            backup(uuid);PlayerNbt.write(file,doc);
        }
        return read(uuid,edit.container());
    }
    List<Map<String,Object>> backups(UUID uuid) throws IOException {
        Path folder=plugin.getDataFolder().toPath().resolve("player-backups").resolve(uuid.toString());
        if(!Files.isDirectory(folder)||Files.isSymbolicLink(folder))return List.of();
        try(var entries=Files.list(folder)){
            List<Map<String,Object>> result=new ArrayList<>();
            for(Path path:entries.filter(p->validBackup(p.getFileName().toString())&&!Files.isSymbolicLink(p)).sorted(Comparator.comparing((Path p)->p.getFileName().toString()).reversed()).limit(10).toList()){
                result.add(Map.of("file",path.getFileName().toString(),"created",Long.parseLong(path.getFileName().toString().split("-")[0])));
            }return result;
        }
    }
    private static boolean validBackup(String name){return name.matches("[0-9]{13}-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.dat");}
    void restore(UUID uuid,String container,String revision,String filename)throws IOException {
        ensureOutsideArena(uuid);
        size(container);if(plugin.getServer().getPlayer(uuid)!=null)throw new Conflict("Disconnect the player from this server before restoring an inventory backup.");
        if(!validBackup(filename))throw new IllegalArgumentException("Invalid backup filename");
        Path current=file(uuid);var currentDoc=PlayerNbt.read(current);String actual=PlayerNbt.revision(Files.readAllBytes(current));if(!actual.equals(revision))throw new Conflict("Player data changed. Refresh before restoring.");
        Path source=plugin.getDataFolder().toPath().resolve("player-backups").resolve(uuid.toString()).resolve(filename);
        var saved=PlayerNbt.read(source);
        if(PlayerNbt.number(PlayerNbt.compound(saved.root()),"DataVersion",0)!=PlayerNbt.number(PlayerNbt.compound(currentDoc.root()),"DataVersion",0))throw new IllegalArgumentException("Backup data version differs; a full compatibility review is required");
        String key=container.equals("ender")?"EnderItems":"Inventory";
        var savedItems=PlayerNbt.list(PlayerNbt.compound(saved.root()),key);index(savedItems,container.equals("ender"),size(container));
        PlayerNbt.compound(currentDoc.root()).put(key,new PlayerNbt.Tag(9,new PlayerNbt.Tags(10,savedItems.values())));
        backup(uuid);PlayerNbt.write(current,currentDoc);
    }
    void gamemode(UUID uuid,GameMode mode) throws IOException {
        ensureOutsideArena(uuid);
        Player player=plugin.getServer().getPlayer(uuid);if(player!=null){player.setGameMode(mode);player.saveData();return;}
        Path file=file(uuid);var doc=PlayerNbt.read(file);backup(uuid);PlayerNbt.compound(doc.root()).put("playerGameType",new PlayerNbt.Tag(3,mode.getValue()));PlayerNbt.write(file,doc);
    }
}

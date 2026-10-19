package win.vampyuyu.network;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Private, token-authenticated, fixed administration operations; no command/shell dispatch. */
final class ManagementApi implements AutoCloseable {
    private final JavaPlugin plugin;
    private final String token;
    private final PlayerInventoryStore inventories;
    private final Gson json = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private final HttpServer http;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r->{Thread t=new Thread(r,"vampyuyu-management");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private static final List<String> BOOLEAN_RULES=List.of("keepInventory","mobGriefing","doDaylightCycle","doWeatherCycle","doMobSpawning","doFireTick","showDeathMessages","doInsomnia","announceAdvancements");
    ManagementApi(JavaPlugin plugin,String token) throws IOException {
        this.plugin=plugin;this.token=token;inventories=new PlayerInventoryStore(plugin);
        for(World world:plugin.getServer().getWorlds()){String key="world-pvp."+world.getUID();if(plugin.getConfig().contains(key))world.setPVP(plugin.getConfig().getBoolean(key));}
        http=HttpServer.create(new InetSocketAddress("0.0.0.0",8084),16);http.setExecutor(executor);http.createContext("/",this::handle);http.start();
    }
    private void handle(HttpExchange request) throws IOException {
        try {
            if(!MessageDigest.isEqual(("Bearer "+token).getBytes(StandardCharsets.UTF_8),request.getRequestHeaders().getFirst("Authorization")==null?new byte[0]:request.getRequestHeaders().getFirst("Authorization").getBytes(StandardCharsets.UTF_8))){reply(request,401,Map.of("error","unauthorized"));return;}
            String path=request.getRequestURI().getPath(),method=request.getRequestMethod();
            JsonObject body=new JsonObject();
            if(method.equals("POST")||method.equals("PUT")){byte[] bytes=request.getRequestBody().readNBytes(65537);if(bytes.length>65536)throw new IllegalArgumentException("Request exceeds limit");body=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();}
            JsonObject input=body;
            Future<Object> pending=plugin.getServer().getScheduler().callSyncMethod(plugin,()->route(method,path,input));
            try{reply(request,200,pending.get(15,TimeUnit.SECONDS));}
            catch(TimeoutException e){pending.cancel(false);reply(request,503,Map.of("error","Minecraft main thread is busy. Refresh before retrying a change."));}
            catch(ExecutionException e){Throwable cause=e.getCause();int status=cause instanceof PlayerInventoryStore.Conflict?409:cause instanceof FileNotFoundException||cause instanceof java.nio.file.NoSuchFileException?404:cause instanceof IllegalArgumentException?400:503;if(status==503)plugin.getLogger().log(java.util.logging.Level.WARNING,"Management operation failed",cause);String message=status==503?"Minecraft operation failed; inspect the server console":cause.getMessage();reply(request,status,Map.of("error",message==null?"Invalid request":message));}
            catch(InterruptedException e){Thread.currentThread().interrupt();reply(request,503,Map.of("error","Server is stopping"));}
        }catch(IllegalArgumentException|IllegalStateException e){reply(request,400,Map.of("error","Invalid management request"));}
        finally{request.close();}
    }
    private void reply(HttpExchange request,int status,Object value)throws IOException{byte[] bytes=json.toJson(value).getBytes(StandardCharsets.UTF_8);request.getResponseHeaders().set("Content-Type","application/json");request.getResponseHeaders().set("Cache-Control","no-store");request.sendResponseHeaders(status,bytes.length);request.getResponseBody().write(bytes);}
    private static UUID uuid(String text){if(text==null||!text.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")||text.equals("00000000-0000-0000-0000-000000000000"))throw new IllegalArgumentException("Invalid player UUID");return UUID.fromString(text);}
    private static String text(JsonObject in,String key,int max){if(!in.has(key)||!in.get(key).isJsonPrimitive()||!in.get(key).getAsJsonPrimitive().isString())throw new IllegalArgumentException("Missing "+key);String value=in.get(key).getAsString();if(value.length()>max||value.contains("\u0000"))throw new IllegalArgumentException("Invalid "+key);return value;}
    private static int integer(JsonObject in,String key,int min,int max){try{int v=in.get(key).getAsBigDecimal().intValueExact();if(v<min||v>max)throw new IllegalArgumentException();return v;}catch(Exception e){throw new IllegalArgumentException("Invalid "+key);}}
    private static boolean bool(JsonObject in,String key){if(!in.has(key)||!in.get(key).isJsonPrimitive()||!in.get(key).getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException("Invalid "+key);return in.get(key).getAsBoolean();}
    private Player online(UUID uuid){Player p=plugin.getServer().getPlayer(uuid);if(p==null)throw new IllegalArgumentException("Player must be online on this server");return p;}
    private Object route(String method,String path,JsonObject body)throws Exception {
        if(method.equals("GET")&&path.equals("/state"))return state();
        if(method.equals("GET")&&path.equals("/materials"))return Arrays.stream(Material.values()).filter(m->m.isItem()&&!m.isAir()).map(m->m.getKey().toString()).sorted().toList();
        if(path.matches("/players/[0-9a-f-]+/(inventory|ender|backups)")){
            String[] parts=path.split("/");UUID id=uuid(parts[2]);String container=parts[3];
            if(container.equals("backups")){if(!method.equals("GET"))throw new IllegalArgumentException("Read-only history");return inventories.backups(id);}
            if(method.equals("GET"))return inventories.read(id,container);
            if(method.equals("PUT"))return inventories.edit(id,new PlayerInventoryStore.Edit(container,text(body,"revision",64),integer(body,"slot",0,40),text(body,"material",128),integer(body,"amount",0,99)));
        }
        if(method.equals("POST")&&path.equals("/action")){action(body);return Map.of("ok",true);}
        throw new FileNotFoundException("Unknown management endpoint");
    }
    private boolean lobbyBuild(Player player){try{var lobby=plugin.getServer().getPluginManager().getPlugin("VampyuyuLobby");return lobby!=null&&(Boolean)lobby.getClass().getMethod("isBuildMode",Player.class).invoke(lobby,player);}catch(ReflectiveOperationException e){return false;}}
    private void lobbyBuild(Player player,boolean enabled)throws Exception{var lobby=plugin.getServer().getPluginManager().getPlugin("VampyuyuLobby");if(lobby==null)throw new IllegalArgumentException("Lobby maintenance is available on the Lobby server only");try{lobby.getClass().getMethod("setBuildMode",Player.class,boolean.class).invoke(lobby,player,enabled);}catch(java.lang.reflect.InvocationTargetException e){if(e.getCause() instanceof IllegalArgumentException cause)throw cause;throw e;}}
    private Object state(){
        List<Map<String,Object>> worlds=new ArrayList<>();
        for(World world:plugin.getServer().getWorlds()){
            Map<String,Object> row=new LinkedHashMap<>();row.put("uuid",world.getUID().toString());row.put("name",world.getName());row.put("environment",world.getEnvironment().name());row.put("difficulty",world.getDifficulty().name());row.put("time",world.getTime());row.put("weather",world.isThundering()?"thunder":world.hasStorm()?"rain":"clear");row.put("pvp",world.getPVP());row.put("players",world.getPlayers().size());row.put("loadedChunks",world.getLoadedChunks().length);row.put("entities",world.getEntityCount());
            Map<String,Object> rules=new LinkedHashMap<>();for(String name:BOOLEAN_RULES)rules.put(name,world.getGameRuleValue(name));rules.put("randomTickSpeed",world.getGameRuleValue("randomTickSpeed"));row.put("rules",rules);
            var spawn=world.getSpawnLocation();row.put("spawn",Map.of("x",spawn.getX(),"y",spawn.getY(),"z",spawn.getZ()));worlds.add(row);
        }
        List<Map<String,Object>> whitelist=new ArrayList<>();for(OfflinePlayer p:plugin.getServer().getWhitelistedPlayers())whitelist.add(identity(p));
        List<Map<String,Object>> bans=new ArrayList<>();for(var entry:plugin.getServer().getBanList(BanList.Type.NAME).getBanEntries()){Map<String,Object> row=new LinkedHashMap<>();row.put("name",entry.getTarget());row.put("reason",entry.getReason());row.put("expires",entry.getExpiration()==null?null:entry.getExpiration().toInstant().toString());bans.add(row);}
        return Map.of("tps",plugin.getServer().getTPS(),"mspt",plugin.getServer().getAverageTickTime(),"version",plugin.getServer().getVersion(),"maxPlayers",plugin.getServer().getMaxPlayers(),"online",plugin.getServer().getOnlinePlayers().size(),"whitelistEnabled",plugin.getServer().hasWhitelist(),"whitelist",whitelist,"bans",bans,"worlds",worlds,"players",plugin.getServer().getOnlinePlayers().stream().map(p->{Map<String,Object> row=identity(p);row.put("gamemode",p.getGameMode().name());row.put("world",p.getWorld().getName());row.put("ping",p.getPing());row.put("lobbyBuild",lobbyBuild(p));return row;}).toList());
    }
    private static Map<String,Object> identity(OfflinePlayer p){Map<String,Object> row=new LinkedHashMap<>();row.put("uuid",p.getUniqueId().toString());row.put("name",p.getName()==null?p.getUniqueId().toString():p.getName());return row;}
    private void action(JsonObject in)throws Exception{
        String action=text(in,"action",32);
        if(action.equals("broadcast")){String message=text(in,"message",256);if(message.isBlank())throw new IllegalArgumentException("Message is empty");plugin.getServer().broadcast(Component.text("[Network] "+message));return;}
        if(action.equals("save")){plugin.getServer().savePlayers();for(World world:plugin.getServer().getWorlds())world.save();return;}
        if(action.equals("pardon-name")){String name=text(in,"name",16);if(!name.matches("[A-Za-z0-9_]{1,16}"))throw new IllegalArgumentException("Invalid player name");plugin.getServer().getBanList(BanList.Type.NAME).pardon(name);return;}
        if(action.equals("whitelist")){plugin.getServer().setWhitelist(bool(in,"enabled"));return;}
        if(List.of("difficulty","time","weather","pvp","gamerule").contains(action)){
            World world=plugin.getServer().getWorld(uuid(text(in,"world",36)));if(world==null)throw new IllegalArgumentException("World unavailable");
            switch(action){
                case "difficulty"->world.setDifficulty(Difficulty.valueOf(text(in,"value",16)));
                case "time"->world.setTime(integer(in,"value",0,23999));
                case "weather"->{String value=text(in,"value",16);if(!List.of("clear","rain","thunder").contains(value))throw new IllegalArgumentException("Invalid weather");world.setStorm(!value.equals("clear"));world.setThundering(value.equals("thunder"));}
                case "pvp"->{boolean enabled=bool(in,"enabled");world.setPVP(enabled);plugin.getConfig().set("world-pvp."+world.getUID(),enabled);plugin.saveConfig();}
                case "gamerule"->{String rule=text(in,"rule",64);String value;if(rule.equals("randomTickSpeed"))value=Integer.toString(integer(in,"value",0,20));else{if(!BOOLEAN_RULES.contains(rule))throw new IllegalArgumentException("Unsupported game rule");value=Boolean.toString(bool(in,"enabled"));}if(!world.setGameRuleValue(rule,value))throw new IllegalArgumentException("Game rule rejected");}
            }return;
        }
        UUID id=uuid(text(in,"uuid",36));OfflinePlayer offline=plugin.getServer().getOfflinePlayer(id);
        switch(action){
            case "restore-inventory"->inventories.restore(id,text(in,"container",16),text(in,"revision",64),text(in,"backup",96));
            case "lobby-build"->{inventories.ensureOutsideArena(id);lobbyBuild(online(id),bool(in,"enabled"));}
            case "gamemode"->inventories.gamemode(id,GameMode.valueOf(text(in,"value",16)));
            case "whitelist-add"->offline.setWhitelisted(true);
            case "whitelist-remove"->offline.setWhitelisted(false);
            case "kick"->online(id).kick(Component.text(text(in,"reason",160)));
            case "ban"->{String name=offline.getName();if(name==null)throw new IllegalArgumentException("Player profile is unknown on this server");int hours=integer(in,"hours",0,8760);plugin.getServer().getBanList(BanList.Type.NAME).addBan(name,text(in,"reason",160),hours==0?null:Date.from(Instant.now().plusSeconds(hours*3600L)),"Network panel");Player p=plugin.getServer().getPlayer(id);if(p!=null)p.kick(Component.text("You have been banned: "+text(in,"reason",160)));}
            case "pardon"->{String name=offline.getName();if(name==null)throw new IllegalArgumentException("Player profile is unknown");plugin.getServer().getBanList(BanList.Type.NAME).pardon(name);}
            case "heal"->{Player p=online(id);p.setHealth(Objects.requireNonNull(p.getAttribute(Objects.requireNonNull(Registry.ATTRIBUTE.get(NamespacedKey.minecraft("max_health"))))).getValue());p.setFireTicks(0);}
            case "feed"->{Player p=online(id);p.setFoodLevel(20);p.setSaturation(20);}
            case "clear-effects"->{Player p=online(id);for(var effect:p.getActivePotionEffects())p.removePotionEffect(effect.getType());}
            case "teleport-spawn"->{Player p=online(id);p.teleport(p.getWorld().getSpawnLocation());}
            case "set-spawn"->{Player p=online(id);p.getWorld().setSpawnLocation(p.getLocation());}
            default->throw new IllegalArgumentException("Unsupported management action");
        }
    }
    public void close(){http.stop(0);executor.shutdownNow();}
}

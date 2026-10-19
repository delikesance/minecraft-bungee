package win.vampyuyu.network;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.junit.Test;
import static org.junit.Assert.*;

public class PlayerNbtTest {
    @Test public void preservesUnknownTagsAndAllPrimitiveTypes() throws Exception {
        Map<String,PlayerNbt.Tag> fields=new LinkedHashMap<>();
        fields.put("byte",new PlayerNbt.Tag(1,(byte)-106));fields.put("short",new PlayerNbt.Tag(2,(short)32000));fields.put("int",new PlayerNbt.Tag(3,4554));fields.put("long",new PlayerNbt.Tag(4,1234567890123L));fields.put("float",new PlayerNbt.Tag(5,1.25f));fields.put("double",new PlayerNbt.Tag(6,-10.5));fields.put("bytes",new PlayerNbt.Tag(7,new byte[]{1,-1,0}));fields.put("custom",new PlayerNbt.Tag(8,"Custom ♥ item"));fields.put("ints",new PlayerNbt.Tag(11,new int[]{1,-100,300}));fields.put("longs",new PlayerNbt.Tag(12,new long[]{Long.MAX_VALUE,-1}));
        Map<String,PlayerNbt.Tag> components=new LinkedHashMap<>();components.put("minecraft:custom_data",new PlayerNbt.Tag(10,fields));
        var item=new PlayerNbt.Tag(10,components);var root=new PlayerNbt.Tag(10,new LinkedHashMap<>(Map.of("Inventory",new PlayerNbt.Tag(9,new PlayerNbt.Tags(10,new ArrayList<>(List.of(item)))))));
        Path path=Files.createTempFile("nbt-test-",".dat");
        try{PlayerNbt.write(path,new PlayerNbt.Document("Player",root));var read=PlayerNbt.read(path);assertEquals("Player",read.name());assertArrayEquals(PlayerNbt.encode(root),PlayerNbt.encode(read.root()));
            PlayerNbt.compound(read.root()).put("playerGameType",new PlayerNbt.Tag(3,1));PlayerNbt.write(path,read);var saved=PlayerNbt.read(path);assertArrayEquals(PlayerNbt.encode(root),PlayerNbt.encode(new PlayerNbt.Tag(10,new LinkedHashMap<>(Map.of("Inventory",PlayerNbt.compound(saved.root()).get("Inventory"))))));
        }finally{Files.deleteIfExists(path);}
    }
    @Test public void equipmentSlotsRoundTrip() {
        for(int slot=0;slot<41;slot++)assertEquals(slot,PlayerInventoryStore.fromNbtSlot(PlayerInventoryStore.toNbtSlot(slot,false),false));
        assertEquals(40,PlayerInventoryStore.fromNbtSlot(150,false));
        for(int slot=0;slot<27;slot++)assertEquals(slot,PlayerInventoryStore.fromNbtSlot(PlayerInventoryStore.toNbtSlot(slot,true),true));
    }
    @Test public void rejectsInvalidFilesAndLinks()throws Exception {
        Path path=Files.createTempFile("nbt-invalid-",".dat");
        try{Files.write(path,new byte[]{0,1,2});try{PlayerNbt.read(path);fail("invalid compression accepted");}catch(IOException expected){}
            try(DataOutputStream out=new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(path)))){out.writeByte(10);out.writeUTF("");out.writeByte(7);out.writeUTF("array");out.writeInt(Integer.MAX_VALUE);}
            try{PlayerNbt.read(path);fail("unbounded array accepted");}catch(IOException expected){}
        }finally{Files.deleteIfExists(path);}
    }
}

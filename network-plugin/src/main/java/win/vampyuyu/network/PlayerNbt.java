package win.vampyuyu.network;

import java.io.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Bounded lossless NBT codec: unknown player/item tags survive offline edits. */
final class PlayerNbt {
    static final int LIMIT = 16 * 1024 * 1024;
    record Tag(int type, Object value) {}
    record Tags(int type, List<Tag> values) {}
    record Document(String name, Tag root) {}
    @SuppressWarnings("unchecked") static Map<String, Tag> compound(Tag tag) {
        if (tag == null || tag.type() != 10) throw new IllegalArgumentException("Expected NBT compound");
        return (Map<String, Tag>) tag.value();
    }
    static String string(Map<String, Tag> map, String key, String fallback) {
        Tag tag = map.get(key); return tag != null && tag.type() == 8 ? (String) tag.value() : fallback;
    }
    static int number(Map<String, Tag> map, String key, int fallback) {
        Tag tag = map.get(key); return tag != null && tag.value() instanceof Number n ? n.intValue() : fallback;
    }
    static Tags list(Map<String, Tag> map, String key) {
        Tag tag = map.get(key); if (tag == null) return new Tags(10, new ArrayList<>());
        if (tag.type() != 9) throw new IllegalArgumentException("Invalid NBT inventory");
        Tags list = (Tags) tag.value(); if (!list.values().isEmpty() && list.type() != 10) throw new IllegalArgumentException("Invalid inventory entries");
        return list;
    }
    static Document read(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw new NoSuchFileException("No saved player data on this server. Join it once first.");
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path) || Files.size(path) > LIMIT) throw new IOException("Player data file unavailable");
        try (InputStream raw = Files.newInputStream(path); GZIPInputStream gzip = new GZIPInputStream(raw)) {
            byte[] bytes = gzip.readNBytes(LIMIT + 1); if (bytes.length > LIMIT) throw new IOException("Player data exceeds limit");
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            int type = in.readUnsignedByte(); if (type != 10) throw new IOException("Invalid player NBT root");
            String name = in.readUTF(); Tag tag = readTag(in, type, 0, new int[] {0});
            if (in.read() != -1) throw new IOException("Trailing player NBT data");
            return new Document(name, tag);
        }
    }
    private static int length(DataInputStream in) throws IOException {
        int value = in.readInt(); if (value < 0 || value > 1_000_000) throw new IOException("NBT length out of bounds"); return value;
    }
    private static Tag readTag(DataInputStream in, int type, int depth, int[] count) throws IOException {
        if (depth > 64 || ++count[0] > 1_000_000) throw new IOException("NBT nesting or tag limit exceeded");
        Object value = switch (type) {
            case 1 -> in.readByte(); case 2 -> in.readShort(); case 3 -> in.readInt(); case 4 -> in.readLong();
            case 5 -> in.readFloat(); case 6 -> in.readDouble();
            case 7 -> { int n = length(in); byte[] b = new byte[n]; in.readFully(b); yield b; }
            case 8 -> in.readUTF();
            case 9 -> { int child = in.readUnsignedByte(); int n = length(in); if (child == 0 && n != 0) throw new IOException("Invalid NBT list"); List<Tag> list = new ArrayList<>(); for (int i = 0; i < n; i++) list.add(readTag(in, child, depth + 1, count)); yield new Tags(child, list); }
            case 10 -> { Map<String, Tag> map = new LinkedHashMap<>(); for (;;) { int child = in.readUnsignedByte(); if (child == 0) break; String name = in.readUTF(); if (map.containsKey(name)) throw new IOException("Duplicate NBT key"); map.put(name, readTag(in, child, depth + 1, count)); } yield map; }
            case 11 -> { int[] values = new int[length(in)]; for (int i = 0; i < values.length; i++) values[i] = in.readInt(); yield values; }
            case 12 -> { long[] values = new long[length(in)]; for (int i = 0; i < values.length; i++) values[i] = in.readLong(); yield values; }
            default -> throw new IOException("Unknown NBT tag");
        }; return new Tag(type, value);
    }
    static byte[] encode(Tag tag) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        writeTag(out, tag); out.flush(); return bytes.toByteArray();
    }
    @SuppressWarnings("unchecked") private static void writeTag(DataOutputStream out, Tag tag) throws IOException {
        switch (tag.type()) {
            case 1 -> out.writeByte(((Number) tag.value()).byteValue()); case 2 -> out.writeShort(((Number) tag.value()).shortValue());
            case 3 -> out.writeInt(((Number) tag.value()).intValue()); case 4 -> out.writeLong(((Number) tag.value()).longValue());
            case 5 -> out.writeFloat(((Number) tag.value()).floatValue()); case 6 -> out.writeDouble(((Number) tag.value()).doubleValue());
            case 7 -> { byte[] b = (byte[]) tag.value(); out.writeInt(b.length); out.write(b); }
            case 8 -> out.writeUTF((String) tag.value());
            case 9 -> { Tags list = (Tags) tag.value(); out.writeByte(list.type()); out.writeInt(list.values().size()); for (Tag child : list.values()) writeTag(out, child); }
            case 10 -> { for (var entry : ((Map<String, Tag>) tag.value()).entrySet()) { out.writeByte(entry.getValue().type()); out.writeUTF(entry.getKey()); writeTag(out, entry.getValue()); } out.writeByte(0); }
            case 11 -> { int[] values = (int[]) tag.value(); out.writeInt(values.length); for (int v : values) out.writeInt(v); }
            case 12 -> { long[] values = (long[]) tag.value(); out.writeInt(values.length); for (long v : values) out.writeLong(v); }
            default -> throw new IOException("Unknown NBT tag");
        }
    }
    static void write(Path target, Document document) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), ".panel-player-", ".tmp");
        try {
            try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(temp)))) {
                out.writeByte(10); out.writeUTF(document.name()); writeTag(out, document.root());
            }
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
    static String revision(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}

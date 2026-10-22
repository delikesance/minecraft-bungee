package win.vampyuyu.arena;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Werewolf game journals on disk: one text file per game in plugins/NexusArenaEngine/logs/werewolf,
 * mirrored to the server console, listed and served to the panel for download.
 */
final class WerewolfLogs {
  /** Old journals are deleted beyond this many files. */
  static final int KEEP = 300;

  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,120}\\.log");
  private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
  private final Path directory;
  private final Logger console;
  private final boolean mirror;
  private final Set<String> live = ConcurrentHashMap.newKeySet();

  WerewolfLogs(Path directory, Logger console, boolean mirror) throws IOException {
    this.directory = directory;
    this.console = console;
    this.mirror = mirror;
    Files.createDirectories(directory);
  }

  /** Starts a game's journal file. When the file cannot be opened the game still runs, unlogged. */
  WerewolfJournal open(String arena, String eventId, Map<UUID, String> names) {
    String game = eventId.substring(0, Math.min(8, eventId.length()));
    String name =
        LocalDateTime.now().format(STAMP)
            + "_"
            + arena.replaceAll("[^A-Za-z0-9_-]", "-")
            + "_"
            + game
            + ".log";
    BufferedWriter file;
    try {
      prune();
      file =
          Files.newBufferedWriter(
              directory.resolve(name),
              StandardCharsets.UTF_8,
              StandardOpenOption.CREATE,
              StandardOpenOption.APPEND);
    } catch (IOException e) {
      console.warning("Werewolf journal unavailable for " + arena + ": " + e.getMessage());
      file = null;
    }
    BufferedWriter out = file;
    if (out != null) live.add(name);
    WerewolfJournal.Sink sink =
        new WerewolfJournal.Sink() {
          public void write(String line) {
            if (mirror) console.info("[Werewolf] " + line);
            if (out == null) return;
            try {
              out.write(line);
              out.newLine();
              out.flush();
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
          }

          public void close() {
            live.remove(name);
            if (out != null)
              try {
                out.close();
              } catch (IOException ignored) {
                // The lines already flushed stay on disk.
              }
          }
        };
    WerewolfJournal journal = new WerewolfJournal(game, names, sink);
    journal.log("FILE", "Journal of arena " + arena + ", game " + eventId + " → " + name);
    return journal;
  }

  /** Every journal on disk, newest first, with its size and whether its game is still running. */
  List<Map<String, Object>> list() throws IOException {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Path file : files()) {
      String name = file.getFileName().toString();
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("name", name);
      row.put("size", Files.size(file));
      row.put("modified", Files.getLastModifiedTime(file).toMillis());
      row.put("live", live.contains(name));
      out.add(row);
    }
    return out;
  }

  /** A journal's path, only for a plain file name of this directory. */
  Path file(String name) {
    if (name == null || !NAME.matcher(name).matches())
      throw new IllegalArgumentException("Invalid log name");
    Path file = directory.resolve(name).normalize();
    if (!file.getParent().equals(directory.normalize()) || !Files.isRegularFile(file))
      throw new NoSuchElementException("Log not found");
    return file;
  }

  private List<Path> files() throws IOException {
    try (var stream = Files.list(directory)) {
      return stream
          .filter(p -> NAME.matcher(p.getFileName().toString()).matches())
          .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
          .toList();
    }
  }

  private void prune() throws IOException {
    List<Path> all = files();
    for (Path old : all.subList(Math.min(all.size(), KEEP - 1), all.size()))
      if (!live.contains(old.getFileName().toString())) Files.deleteIfExists(old);
  }
}

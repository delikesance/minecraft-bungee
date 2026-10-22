package win.vampyuyu.arena;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * A werewolf game's play-by-play, for debugging: every phase, action, vote, death and timer, one
 * line each, tagged with the game id, the time since the start, the day or night and the phase.
 * Pure: the plugin decides where the lines go (a file per game, the console).
 */
public final class WerewolfJournal implements AutoCloseable {
  /** Where the lines go. */
  public interface Sink {
    void write(String line);

    default void close() {}
  }

  /** Writes nowhere: games built without a journal (tests, other integrations). */
  static final WerewolfJournal SILENT = new WerewolfJournal("-", Map.of(), line -> {});

  private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
  private final String game;
  private final Map<UUID, String> names;
  private final Sink sink;
  private final long started = System.currentTimeMillis();
  private volatile long phaseSince = started;
  private volatile String where = "SETUP";
  private boolean closed;
  private String lastRefusal;
  private int repeats;
  private String pending;

  /**
   * @param game the game's id, printed on every line
   * @param names every participant's display name, kept even after they leave
   */
  public WerewolfJournal(String game, Map<UUID, String> names, Sink sink) {
    this.game = game;
    this.names = Map.copyOf(names);
    this.sink = sink;
  }

  public String game() {
    return game;
  }

  /** A participant's name, or the start of their id when the journal never knew them. */
  public String name(UUID player) {
    if (player == null) return "nobody";
    String name = names.get(player);
    return name != null ? name : player.toString().substring(0, 8);
  }

  /** Replaces every participant id in a game message by the participant's name. */
  public String redact(String text) {
    if (text == null) return "";
    for (var entry : names.entrySet())
      text = text.replace(entry.getKey().toString(), entry.getValue());
    return text;
  }

  /**
   * The game moved to a new phase: the following lines carry it, and how long the last one lasted.
   */
  void phase(String from, String to, String when) {
    long now = System.currentTimeMillis();
    String lasted = seconds(now - phaseSince);
    phaseSince = now;
    where = when + " " + to;
    log("PHASE", from + " → " + to + " (" + from + " lasted " + lasted + ")");
  }

  /** Where the game stands, for lines written before the first phase change. */
  void at(String when) {
    where = when;
  }

  public void log(String tag, String message) {
    write(tag, message, null);
  }

  /**
   * A player's attempt, written only once something follows it: it is dropped when the game refuses
   * it, since the refusal names the attempt itself.
   */
  synchronized void attempt(String message) {
    pending = line("ACTION", message);
  }

  /**
   * A refused action. Further refusals of the same player in the same phase, one after the other (a
   * bot trying every head), are counted on one line instead of repeated.
   */
  void refused(String key, String message) {
    synchronized (this) {
      pending = null;
    }
    write("REFUSED", message, key);
  }

  private void write(String tag, String message, String refusal) {
    String line = line(tag, message);
    synchronized (this) {
      if (closed) return;
      if (refusal != null && refusal.equals(lastRefusal)) {
        repeats++;
        return;
      }
      flushRepeats();
      lastRefusal = refusal;
      if (pending != null) emit(pending);
      pending = null;
      emit(line);
    }
  }

  private String line(String tag, String message) {
    long now = System.currentTimeMillis();
    return LocalTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault()).format(CLOCK)
        + " +"
        + elapsed(now - started)
        + " ["
        + game
        + "] ["
        + where
        + "] "
        + String.format("%-8s", tag)
        + " "
        + redact(message);
  }

  private void flushRepeats() {
    if (repeats > 0)
      emit(line("REFUSED", "… and " + repeats + " more refused attempt(s) by the same player"));
    repeats = 0;
  }

  private void emit(String line) {
    try {
      sink.write(line);
    } catch (RuntimeException ignored) {
      // A full disk must never break the game itself.
    }
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    flushRepeats();
    closed = true;
    sink.close();
  }

  private static String elapsed(long millis) {
    long s = millis / 1000;
    return String.format("%02d:%02d.%d", s / 60, s % 60, millis % 1000 / 100);
  }

  static String seconds(long millis) {
    return String.format(Locale.ROOT, "%.1fs", millis / 1000.0);
  }
}

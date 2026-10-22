package win.vampyuyu.arena;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class WerewolfJournalTest {
  private static List<UUID> players(int n) {
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < n; i++) ids.add(new UUID(7, i + 1));
    return ids;
  }

  private static Map<UUID, String> names(List<UUID> ids) {
    Map<UUID, String> out = new HashMap<>();
    for (int i = 0; i < ids.size(); i++) out.put(ids.get(i), "P" + (i + 1));
    return out;
  }

  @Test
  public void aWholePracticeGameIsJournaled() {
    var ids = players(10);
    var lines = new ArrayList<String>();
    var game = new WerewolfGame(ids, 5, 5, 5, new Random(3), GameRules.Werewolf.defaults());
    game.journal(new WerewolfJournal("abcd1234", names(ids), lines::add));
    var bots = Set.copyOf(ids);
    var brain = new WerewolfBots(new Random(3));
    for (int turn = 0; turn < 200 && game.winner().isEmpty(); turn++) {
      String key = game.turnKey();
      brain.think(game, bots, 10_000, 10_000, true);
      if (key.equals(game.turnKey())) game.advance();
    }
    assertFalse(game.winner().isEmpty());
    String all = String.join("\n", lines);
    for (String tag :
        List.of("DEAL", "PHASE", "ACTION", "RESULT", "HUNT", "DAWN", "DEATH", "VERDICT", "GAME"))
      assertTrue("missing " + tag + " in\n" + all, all.contains("] " + tag));
    assertTrue(all.contains("Winner: " + game.winner()));
    for (String line : lines) {
      assertTrue(line, line.contains("[abcd1234]"));
      for (UUID id : ids) assertFalse("raw id leaked: " + line, line.contains(id.toString()));
    }
  }

  @Test
  public void refusalsTimeoutsAndPhaseLengthsAreJournaled() {
    var ids = players(4);
    var lines = new ArrayList<String>();
    var game = new WerewolfGame(ids, 5, 5, 5, new Random(2));
    game.journal(new WerewolfJournal("g", names(ids), lines::add));
    while (!game.phase().equals("DAY_VOTE")) game.advance();
    UUID voter = ids.getFirst();
    assertThrows(IllegalArgumentException.class, () -> game.select(voter, voter));
    assertTrue(lines.stream().anyMatch(l -> l.contains("REFUSED") && l.contains("another")));
    game.select(voter, ids.get(1));
    game.use(ids.get(2), "skip");
    game.advance();
    String all = String.join("\n", lines);
    assertTrue(all, all.contains("TIMEOUT  DAY_VOTE ended by the clock"));
    assertTrue(all, all.contains("P1→P2") && all.contains("skipped: P3"));
    assertTrue(all, all.matches("(?s).*PHASE +DISCUSSION → DAY_VOTE \\(DISCUSSION lasted .*"));
  }

  @Test
  public void aJournalWithoutAGameStaysQuietAfterClosing() {
    var lines = new ArrayList<String>();
    var journal = new WerewolfJournal("g", Map.of(), lines::add);
    journal.log("STOP", "done");
    journal.close();
    journal.log("LATE", "ignored");
    assertEquals(1, lines.size());
  }
}

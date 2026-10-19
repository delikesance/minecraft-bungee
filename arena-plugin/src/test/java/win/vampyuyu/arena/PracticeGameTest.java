package win.vampyuyu.arena;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class PracticeGameTest {
  @Test
  public void virtualCastProgressesWithoutHumanInputAndFinishes() {
    for (int seed = 0; seed < 30; seed++) {
      var ids = new ArrayList<UUID>();
      for (int i = 0; i < 8; i++) ids.add(new UUID(seed, i + 1));
      var game = new WerewolfGame(ids, 5, 5, 5, new Random(seed), GameRules.Werewolf.defaults());
      var bots = new HashSet<>(ids);
      bots.remove(ids.getFirst());
      for (int turn = 0; turn < 160 && game.winner().isEmpty(); turn++) {
        String phase = game.phase();
        game.simulateTurn(bots, new Random(seed * 1000L + turn));
        if (game.phase().equals(phase)) game.advance();
      }
      assertFalse(
          "Practice should finish with an idle human, seed " + seed, game.winner().isEmpty());
    }
  }

  @Test
  public void onlySpecifiedBotsActAndDiscussionDoesNotChange() {
    var ids = List.of(new UUID(0, 1), new UUID(0, 2), new UUID(0, 3), new UUID(0, 4));
    var game = new WerewolfGame(ids, 5, 5, 5, new Random(2));
    game.simulateTurn(Set.of(ids.get(1)), new Random(3));
    assertEquals("DISCUSSION", game.phase());
    game.advance();
    game.simulateTurn(Set.of(ids.get(1)), new Random(4));
    assertEquals(1, game.votes(ids.get(0)).values().stream().mapToInt(Integer::intValue).sum());
  }
}

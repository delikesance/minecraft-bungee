package win.vampyuyu.arena;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class WerewolfGameTest {
  private List<UUID> players() {
    return java.util.stream.IntStream.range(0, 8).mapToObj(i -> new UUID(0, i + 1)).toList();
  }

  private WerewolfGame game() {
    return new WerewolfGame(players(), 10, 10, 10, new Random(42));
  }

  @Test
  public void roleCountsAndPrivacy() {
    var g = game();
    assertEquals(2, players().stream().filter(p -> g.role(p).equals("WEREWOLF")).count());
    assertEquals(1, players().stream().filter(p -> g.role(p).equals("SEER")).count());
    UUID wolf =
        players().stream().filter(p -> g.role(p).equals("WEREWOLF")).findFirst().orElseThrow();
    UUID villager =
        players().stream().filter(p -> g.role(p).equals("VILLAGER")).findFirst().orElseThrow();
    g.advance();
    g.advance();
    g.select(wolf, villager);
    assertEquals(1, g.votes(wolf).get(villager).intValue());
    assertTrue(g.votes(villager).isEmpty());
  }

  @Test
  public void majorityEliminatesAndTieDoesNot() {
    var g = game();
    g.advance();
    g.select(players().get(0), players().get(2));
    g.select(players().get(1), players().get(2));
    g.select(players().get(3), players().get(4));
    g.advance();
    assertFalse(g.alive().contains(players().get(2)));
    var tie = game();
    tie.advance();
    tie.select(players().get(0), players().get(1));
    tie.select(players().get(1), players().get(0));
    tie.advance();
    assertEquals(8, tie.alive().size());
  }

  @Test
  public void oneSeerVisionPerNightAndNoRoleLeak() {
    var g = game();
    UUID seer = players().stream().filter(p -> g.role(p).equals("SEER")).findFirst().orElseThrow();
    UUID target = players().stream().filter(p -> !p.equals(seer)).findFirst().orElseThrow();
    g.advance();
    g.advance();
    assertTrue(g.select(seer, target).contains(g.role(target)));
    assertThrows(IllegalArgumentException.class, () -> g.select(seer, target));
    assertTrue(g.votes(seer).isEmpty());
  }

  @Test
  public void cannotVoteOutsidePhaseOrAsDeadPlayer() {
    var g = game();
    assertThrows(
        IllegalArgumentException.class, () -> g.select(players().get(0), players().get(1)));
    g.advance();
    g.remove(players().get(0));
    assertThrows(
        IllegalArgumentException.class, () -> g.select(players().get(0), players().get(1)));
    assertThrows(
        IllegalArgumentException.class, () -> g.select(players().get(1), players().get(1)));
  }

  @Test
  public void winnersAndAbstentions() {
    var g = game();
    for (UUID p : players()) if (g.role(p).equals("WEREWOLF")) g.remove(p);
    assertEquals("VILLAGE", g.winner());
    var h = game();
    h.advance();
    h.advance();
    assertEquals(8, h.alive().size());
  }

  @Test
  public void rejectDuplicateAndSmallGames() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new WerewolfGame(List.of(UUID.randomUUID()), 5, 5, 5, new Random()));
    var duplicate = new ArrayList<>(players());
    duplicate.set(1, duplicate.get(0));
    assertThrows(
        IllegalArgumentException.class, () -> new WerewolfGame(duplicate, 5, 5, 5, new Random()));
  }
}

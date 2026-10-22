package win.vampyuyu.arena;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class NightTurnsTest {
  private WerewolfGame game(Map<String, Integer> cast) {
    return new WerewolfGame(
        java.util.stream.IntStream.range(0, 10).mapToObj(i -> new UUID(0, i + 1)).toList(),
        10,
        10,
        10,
        new Random(7),
        new GameRules.Werewolf(cast, 5, 5, 5));
  }

  private List<UUID> role(WerewolfGame g, String role) {
    return g.alive().stream().filter(p -> g.role(p).equals(role)).sorted().toList();
  }

  private void to(WerewolfGame g, String phase) {
    for (int i = 0; i < 10 && !g.phase().equals(phase); i++) g.advance();
    assertEquals(phase, g.phase());
  }

  @Test
  public void rolesWakeInOrderAndWolvesFinishWhenUnanimous() {
    var g = game(Map.of("WEREWOLF", 2, "GUARD", 1, "SEER", 1, "WITCH", 1));
    var wolves = role(g, "WEREWOLF");
    var guard = role(g, "GUARD").getFirst();
    var seer = role(g, "SEER").getFirst();
    var victim = role(g, "VILLAGER").getFirst();
    to(g, "GUARD");
    assertFalse(g.sleeping(guard));
    assertTrue(g.sleeping(seer));
    assertTrue(g.sleeping(wolves.getFirst()));
    assertThrows(IllegalArgumentException.class, () -> g.select(seer, victim));
    g.select(guard, guard);
    assertEquals("SEER", g.phase());
    assertFalse(g.sleeping(seer));
    assertTrue(g.sleeping(guard));
    g.select(seer, victim);
    assertEquals("NIGHT", g.phase());
    g.select(wolves.get(0), victim);
    assertEquals("NIGHT", g.phase());
    g.select(wolves.get(1), seer);
    assertEquals("a split pack keeps hunting", "NIGHT", g.phase());
    g.select(wolves.get(0), seer);
    assertEquals("WITCH", g.phase());
    assertTrue(g.sleeping(wolves.get(0)));
    assertTrue(g.sleeping(wolves.get(1)));
    assertThrows(IllegalArgumentException.class, () -> g.select(wolves.get(0), victim));
  }

  @Test
  public void noVotesAndTiedVotesKillNobody() {
    for (boolean vote : List.of(false, true)) {
      var g = game(Map.of("WEREWOLF", 2));
      to(g, "NIGHT");
      var wolves = role(g, "WEREWOLF");
      var villagers = role(g, "VILLAGER");
      if (vote) {
        g.select(wolves.get(0), villagers.get(0));
        g.select(wolves.get(1), villagers.get(1));
        assertEquals("NIGHT", g.phase());
      }
      g.advance();
      assertEquals("DISCUSSION", g.phase());
      assertEquals(10, g.alive().size());
    }
  }

  @Test
  public void timeoutCountsOnlySubmittedVotes() {
    var g = game(Map.of("WEREWOLF", 2));
    to(g, "NIGHT");
    var victim = role(g, "VILLAGER").getFirst();
    g.select(role(g, "WEREWOLF").getFirst(), victim);
    g.advance();
    assertEquals("DISCUSSION", g.phase());
    assertFalse(g.alive().contains(victim));
  }

  @Test
  public void skipsAbsentOrDeadRoles() {
    var g = game(Map.of("WEREWOLF", 1, "GUARD", 1, "SEER", 1));
    g.remove(role(g, "GUARD").getFirst());
    g.remove(role(g, "SEER").getFirst());
    to(g, "NIGHT");
    g.advance();
    assertEquals("DISCUSSION", g.phase());
  }

  @Test
  public void spentWitchIsStillCalledSoHerTurnRevealsNothing() {
    var g = game(Map.of("WEREWOLF", 2, "WITCH", 1));
    var wolves = role(g, "WEREWOLF");
    var witch = role(g, "WITCH").getFirst();
    var villagers = role(g, "VILLAGER");
    to(g, "NIGHT");
    for (UUID wolf : wolves) g.select(wolf, villagers.get(0));
    assertEquals("WITCH", g.phase());
    g.use(witch, "heal");
    g.select(witch, villagers.get(1));
    g.select(witch, villagers.get(1));
    g.advance();
    assertTrue(g.alive().contains(villagers.get(0)));
    assertFalse(g.alive().contains(villagers.get(1)));
    to(g, "NIGHT");
    for (UUID wolf : wolves) g.select(wolf, villagers.get(2));
    assertEquals("WITCH", g.phase());
    assertTrue(g.abilities(witch).isEmpty());
    assertThrows(IllegalArgumentException.class, () -> g.select(witch, villagers.get(3)));
    g.advance();
    assertFalse(g.alive().contains(villagers.get(2)));
  }

  @Test
  public void deadVillageMembersStillBelongToWinningCamp() {
    var g = game(Map.of("WEREWOLF", 1));
    UUID villager = role(g, "VILLAGER").getFirst();
    g.remove(villager);
    g.remove(role(g, "WEREWOLF").getFirst());
    assertTrue(g.winners().contains(villager));
    assertEquals(9, g.winners().size());
  }
}

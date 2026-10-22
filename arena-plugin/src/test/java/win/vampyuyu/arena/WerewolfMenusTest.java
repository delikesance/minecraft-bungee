package win.vampyuyu.arena;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

/** The role menus of the design guide and the rules behind their buttons. */
public class WerewolfMenusTest {
  private List<UUID> ids() {
    return java.util.stream.IntStream.range(0, 10).mapToObj(i -> new UUID(3, i + 1)).toList();
  }

  private Map<UUID, String> names() {
    Map<UUID, String> names = new HashMap<>();
    for (UUID id : ids()) names.put(id, "P" + id.getLeastSignificantBits());
    return names;
  }

  private WerewolfGame game(Map<String, Integer> roles) {
    return new WerewolfGame(
        ids(), 10, 10, 10, new Random(11), new GameRules.Werewolf(roles, 5, 5, 5));
  }

  private UUID role(WerewolfGame g, String role) {
    return ids().stream().filter(p -> g.role(p).equals(role)).findFirst().orElseThrow();
  }

  private void phase(WerewolfGame g, String phase) {
    for (int i = 0; i < 20 && !g.phase().equals(phase); i++) g.advance();
    assertEquals(phase, g.phase());
  }

  private List<WerewolfMenus.Entry> menu(WerewolfGame g, UUID viewer) {
    return WerewolfMenus.menu(g, viewer, names(), 30, true);
  }

  private WerewolfMenus.Entry at(List<WerewolfMenus.Entry> menu, int raw) {
    return menu.stream().filter(e -> e.raw() == raw).findFirst().orElse(null);
  }

  @Test
  public void headsSpreadEvenlyOnTheRingAndNeverCoverTheEmblemUnderNineteen() {
    Set<Integer> ring = new HashSet<>();
    for (int slot : WerewolfMenus.RING) ring.add(slot);
    assertEquals(18, ring.size());
    for (int n = 1; n <= 18; n++) {
      int[] slots = WerewolfMenus.slots(n);
      assertEquals(n, Arrays.stream(slots).distinct().count());
      for (int slot : slots) assertTrue(ring.contains(slot));
    }
    int[] few = WerewolfMenus.slots(6);
    assertArrayEquals(new int[] {2, 5, 25, 51, 48, 28}, few);
    int[] crowd = WerewolfMenus.slots(45);
    assertEquals(45, Arrays.stream(crowd).distinct().count());
    for (int slot : crowd) assertTrue(slot >= 0 && slot < 54);
  }

  @Test
  public void eachPhaseOpensTheMenuOfTheRoleThatActs() {
    var g = game(Map.of("WEREWOLF", 2, "SEER", 1, "WITCH", 1, "GUARD", 1, "CUPID", 1));
    assertEquals(WerewolfMenus.Kind.LOVERS, WerewolfMenus.kind(g, role(g, "CUPID")));
    assertNull(WerewolfMenus.kind(g, role(g, "SEER")));
    phase(g, "GUARD");
    assertEquals(WerewolfMenus.Kind.GUARD, WerewolfMenus.kind(g, role(g, "GUARD")));
    phase(g, "SEER");
    assertEquals(WerewolfMenus.Kind.VISION, WerewolfMenus.kind(g, role(g, "SEER")));
    phase(g, "NIGHT");
    assertEquals(WerewolfMenus.Kind.HUNT, WerewolfMenus.kind(g, role(g, "WEREWOLF")));
    phase(g, "WITCH");
    assertEquals(WerewolfMenus.Kind.POTIONS, WerewolfMenus.kind(g, role(g, "WITCH")));
    phase(g, "DISCUSSION");
    assertNull(WerewolfMenus.kind(g, role(g, "VILLAGER")));
    assertTrue(menu(g, role(g, "VILLAGER")).isEmpty());
    phase(g, "DAY_VOTE");
    assertEquals(WerewolfMenus.Kind.VOTE, WerewolfMenus.kind(g, role(g, "VILLAGER")));
  }

  @Test
  public void everyMenuHasTheCardTimerAndLockOnTheHotbar() {
    var g = game(Map.of("WEREWOLF", 2, "SEER", 1));
    phase(g, "DAY_VOTE");
    UUID seer = role(g, "SEER");
    var menu = WerewolfMenus.menu(g, seer, names(), 42, true);
    assertEquals("card-seer", at(menu, WerewolfMenus.CARD).icon());
    assertEquals("Seer", at(menu, WerewolfMenus.CARD).lines().getFirst().text());
    assertEquals(42, at(menu, WerewolfMenus.TIMER).amount());
    assertNull("locked during the game", at(menu, WerewolfMenus.LOCK).click());
    assertEquals(
        "leave", at(WerewolfMenus.menu(g, seer, names(), 500, false), WerewolfMenus.LOCK).click());
    assertEquals(
        99, at(WerewolfMenus.menu(g, seer, names(), 500, false), WerewolfMenus.TIMER).amount());
    for (var e : menu) assertTrue(e.raw() >= 0 && e.raw() < 90);
  }

  @Test
  public void aSecondClickTakesTheVoteBackAndSkipMeansNoVote() {
    var g = game(Map.of("WEREWOLF", 2, "SEER", 1));
    phase(g, "DAY_VOTE");
    UUID a = ids().get(0), b = ids().get(1), c = ids().get(2);
    g.select(a, b);
    assertEquals(b, g.ballot(a));
    var head =
        menu(g, a).stream()
            .filter(e -> b.equals(e.head()) && e.raw() < 54)
            .findFirst()
            .orElseThrow();
    assertTrue("your vote shines", head.glint());
    assertEquals("Vote withdrawn.", g.select(a, b));
    assertNull(g.ballot(a));
    g.select(a, c);
    g.use(a, "skip");
    assertNull("skipping drops the ballot", g.ballot(a));
    assertTrue(g.abstained(a));
    assertTrue(at(menu(g, a), WerewolfMenus.box(7)).glint());
    g.select(a, b);
    assertFalse("voting again ends the abstention", g.abstained(a));
    g.use(a, "skip");
    g.use(a, "skip");
    assertFalse(g.abstained(a));
  }

  @Test
  public void everyoneMaySkipAndThenNobodyIsEliminated() {
    var g = game(Map.of("WEREWOLF", 2, "SEER", 1));
    phase(g, "DAY_VOTE");
    int before = g.alive().size();
    for (UUID p : g.alive()) g.use(p, "skip");
    g.advance();
    assertEquals(before, g.alive().size());
    assertTrue(g.night());
  }

  @Test
  public void wolvesSeeTheirPackAsLockedHeadsAndInTheirBox() {
    var g = game(Map.of("WEREWOLF", 2, "SEER", 1));
    phase(g, "NIGHT");
    List<UUID> wolves = ids().stream().filter(p -> g.role(p).equals("WEREWOLF")).toList();
    var menu = menu(g, wolves.get(0));
    var mate =
        menu.stream()
            .filter(e -> wolves.get(1).equals(e.head()) && e.raw() < 54)
            .findFirst()
            .orElseThrow();
    assertEquals("mark-wolf", mate.icon());
    assertNull(mate.click());
    assertEquals(wolves.get(1), at(menu, WerewolfMenus.box(6)).head());
    assertEquals(List.of(wolves.get(1)), g.pack(wolves.get(0)));
    assertEquals(List.of(), g.pack(role(g, "SEER")));
  }

  @Test
  public void theSeerKeepsHerVisionsAcrossNights() {
    var g = game(Map.of("WEREWOLF", 2, "SEER", 1));
    UUID seer = role(g, "SEER"), first = role(g, "VILLAGER");
    phase(g, "SEER");
    g.select(seer, first);
    phase(g, "DISCUSSION");
    phase(g, "SEER");
    var menu = menu(g, seer);
    var head =
        menu.stream().filter(e -> first.equals(e.head()) && e.raw() < 54).findFirst().orElseThrow();
    assertEquals("card-villager", head.icon());
    assertNull(head.click());
    assertEquals("card-villager", at(menu, WerewolfMenus.box(2)).icon());
    assertEquals(Map.of(first, "VILLAGER"), g.visions(seer));
  }

  @Test
  public void theWitchPicksAHeadThenPressesDeathAndSleepEndsHerTurn() {
    var g = game(Map.of("WEREWOLF", 1, "WITCH", 1));
    UUID wolf = role(g, "WEREWOLF"), witch = role(g, "WITCH");
    List<UUID> villagers = ids().stream().filter(p -> g.role(p).equals("VILLAGER")).toList();
    phase(g, "NIGHT");
    g.select(wolf, villagers.get(0));
    assertEquals("WITCH", g.phase());
    assertEquals(villagers.get(0), g.victim(witch));
    var menu = menu(g, witch);
    assertEquals("use:heal", at(menu, WerewolfMenus.box(1)).click());
    assertNull("DEATH waits for a pick", at(menu, WerewolfMenus.box(4)).click());
    assertEquals("use:sleep", at(menu, WerewolfMenus.box(7)).click());
    g.aim(witch, villagers.get(1));
    assertEquals("use:poison", at(menu(g, witch), WerewolfMenus.box(4)).click());
    assertEquals("Removed " + villagers.get(1) + ".", g.aim(witch, villagers.get(1)));
    g.aim(witch, villagers.get(1));
    g.use(witch, "poison");
    assertEquals("potion-death-off", at(menu(g, witch), WerewolfMenus.box(4)).icon());
    g.use(witch, "sleep");
    assertEquals("DISCUSSION", g.phase());
    assertFalse(g.alive().contains(villagers.get(0)));
    assertFalse(g.alive().contains(villagers.get(1)));
  }

  @Test
  public void cupidPicksTwoLoversThenBindsThemAndTimeBindsAFullPair() {
    var g = game(Map.of("WEREWOLF", 2, "CUPID", 1));
    UUID cupid = role(g, "CUPID");
    UUID a = ids().stream().filter(p -> !p.equals(cupid)).toList().get(0),
        b = ids().stream().filter(p -> !p.equals(cupid)).toList().get(1),
        c = ids().stream().filter(p -> !p.equals(cupid)).toList().get(2);
    assertThrows(IllegalArgumentException.class, () -> g.use(cupid, "bind"));
    g.aim(cupid, a);
    g.aim(cupid, b);
    assertThrows(IllegalArgumentException.class, () -> g.aim(cupid, c));
    g.aim(cupid, b); // Unpick, then pick another.
    g.aim(cupid, c);
    assertEquals(List.of(a, c), g.aims(cupid));
    assertEquals("use:bind", at(menu(g, cupid), WerewolfMenus.box(7)).click());
    g.advance(); // Time runs out with two picks: they are bound.
    assertEquals(c, g.partner(a));
    assertEquals(a, g.partner(c));
    assertNotEquals("CUPID", g.phase());
  }

  @Test
  public void theHunterAimsThenShootsAndTimeFiresAnAimedShot() {
    var g = game(Map.of("WEREWOLF", 2, "HUNTER", 1));
    UUID hunter = role(g, "HUNTER");
    phase(g, "DAY_VOTE");
    for (UUID p : g.alive()) if (!p.equals(hunter)) g.select(p, hunter);
    g.advance();
    assertEquals("HUNTER_SHOT", g.phase());
    UUID target = g.alive().iterator().next();
    assertNull(at(menu(g, hunter), WerewolfMenus.box(7)).click());
    g.aim(hunter, target);
    assertEquals("use:shoot", at(menu(g, hunter), WerewolfMenus.box(7)).click());
    g.advance();
    assertFalse(g.alive().contains(target));
  }

  @Test
  public void theGuardSeesLastNightsChoiceLockedAndMayProtectHimself() {
    var g = game(Map.of("WEREWOLF", 2, "GUARD", 1));
    UUID guard = role(g, "GUARD");
    phase(g, "GUARD");
    assertEquals("use:protect-self", at(menu(g, guard), WerewolfMenus.box(6)).click());
    g.use(guard, "protect-self");
    phase(g, "DISCUSSION");
    phase(g, "GUARD");
    assertEquals(guard, g.lastProtected(guard));
    assertNull("not two nights in a row", at(menu(g, guard), WerewolfMenus.box(6)).click());
    UUID other = role(g, "VILLAGER");
    g.select(guard, other);
    phase(g, "DISCUSSION");
    phase(g, "GUARD");
    var locked =
        menu(g, guard).stream()
            .filter(e -> other.equals(e.head()) && e.raw() < 54)
            .findFirst()
            .orElseThrow();
    assertEquals("mark-last", locked.icon());
    assertNull(locked.click());
  }

  @Test
  public void nightsAndDaysAreNumbered() {
    var g = game(Map.of("WEREWOLF", 2, "SEER", 1));
    assertEquals(1, g.round());
    phase(g, "DISCUSSION");
    assertEquals(1, g.round());
    phase(g, "SEER");
    assertEquals(2, g.round());
  }

  @Test
  public void bossbarSaysPhaseWhoActsAndTimeAndSleepersLearnNothing() {
    var g = game(Map.of("WEREWOLF", 2, "SEER", 1));
    phase(g, "SEER");
    var seer = WerewolfPresentation.boss(g, role(g, "SEER"), 12);
    assertEquals("☾ Night 1 · The Seer is awake · 12 s", seer.text());
    assertEquals(WerewolfPresentation.SEER, seer.color());
    var sleeper = WerewolfPresentation.boss(g, role(g, "VILLAGER"), 12);
    assertEquals("☾ Night 1 · You are asleep…", sleeper.text());
    assertFalse(sleeper.timer());
    phase(g, "DISCUSSION");
    assertEquals(
        "☀ Day 1 · Debate · 1 min 20",
        WerewolfPresentation.boss(g, role(g, "VILLAGER"), 80).text());
    assertEquals("2 min", WerewolfPresentation.clock(120));
  }

  @Test
  public void tooltipsWrapAndTheCompositionCountsTheDeal() {
    assertEquals(List.of("one two", "three"), WerewolfMenus.wrap("one two three", 8));
    var g = game(Map.of("WEREWOLF", 2, "SEER", 1));
    assertEquals(Map.of("VILLAGER", 7, "WEREWOLF", 2, "SEER", 1), g.composition());
  }

  @Test
  public void theVillageOverviewShowsTheLivingAndNeedsNoClick() {
    var g = game(Map.of("WEREWOLF", 2, "SEER", 1));
    phase(g, "DISCUSSION");
    UUID villager = role(g, "VILLAGER");
    g.remove(role(g, "SEER"));
    var menu = WerewolfMenus.village(g, villager, names(), "Hollow", 30, true);
    long heads = menu.stream().filter(e -> e.raw() < 54).count();
    assertEquals(g.alive().size(), heads);
    for (var e : menu) assertNull(e.raw() + " is not a button", e.click());
    assertEquals("card-villager", at(menu, WerewolfMenus.box(1)).icon());
    assertEquals(9, at(menu, WerewolfMenus.box(6)).amount());
    assertEquals(10, at(menu, WerewolfMenus.box(7)).amount());
    assertEquals("card-villager", at(menu, WerewolfMenus.CARD).icon());
    assertEquals(
        "leave",
        at(WerewolfMenus.village(g, villager, names(), "Hollow", 30, false), WerewolfMenus.LOCK)
            .click());
  }

  @Test
  public void wolvesAndLoversRecogniseEachOtherInTheOverviewOnly() {
    var g = game(Map.of("WEREWOLF", 2, "SEER", 1));
    phase(g, "DISCUSSION");
    List<UUID> wolves = ids().stream().filter(p -> g.role(p).equals("WEREWOLF")).toList();
    var wolfView = WerewolfMenus.village(g, wolves.get(0), names(), "Hollow", 30, true);
    var mate =
        wolfView.stream().filter(e -> wolves.get(1).equals(e.head())).findFirst().orElseThrow();
    assertEquals("A wolf of your pack", mate.lines().get(1).text());
    var villagerView = WerewolfMenus.village(g, role(g, "VILLAGER"), names(), "Hollow", 30, true);
    var same =
        villagerView.stream().filter(e -> wolves.get(1).equals(e.head())).findFirst().orElseThrow();
    assertEquals("Alive", same.lines().get(1).text());
  }

  @Test
  public void beforeTheDealTheOverviewListsEveryoneWithACardBackAndNoTimer() {
    var menu = WerewolfMenus.village(null, ids().get(0), names(), "Hollow", 0, false);
    assertEquals(ids().size(), menu.stream().filter(e -> e.raw() < 54).count());
    assertEquals("card-back", at(menu, WerewolfMenus.CARD).icon());
    assertNull(at(menu, WerewolfMenus.TIMER));
    assertEquals("leave", at(menu, WerewolfMenus.LOCK).click());
  }
}

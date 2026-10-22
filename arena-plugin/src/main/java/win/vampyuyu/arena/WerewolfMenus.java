package win.vampyuyu.arena;

import static win.vampyuyu.arena.WerewolfPresentation.*;

import java.util.*;

/**
 * Layout of the seven role menus (design guide "Les menus de rôle"). Pure: it says which item goes
 * in which raw slot of the 54-slot window and what a click does; CompassGUI turns it into items.
 *
 * <p>Raw slots: 0-53 chest (the painted scene, heads on a ring), 54-80 the player's inventory rows
 * (painted boxes and buttons), 81-89 the hotbar (card, timer, lock).
 */
final class WerewolfMenus {
  private WerewolfMenus() {}

  /** Painted background (resource-pack/layout.py), window title and colour of each menu. */
  enum Kind {
    VOTE("menu_vote", '\uE900', "Village vote", WerewolfPresentation.VOTE),
    HUNT("menu_hunt", '\uEA00', "The hunt", WEREWOLF),
    VISION("menu_vision", '\uEB00', "The vision", SEER),
    POTIONS("menu_potions", '\uEC00', "The potions", WITCH),
    GUARD("menu_guard", '\uED00', "The guard", WerewolfPresentation.GUARD),
    LOVERS("menu_lovers", '\uEE00', "The lovers", CUPID),
    SHOT("menu_shot", '\uEF00', "Last shot", HUNTER);

    final String background;
    final char tile;
    final String title;
    final int color;

    Kind(String background, char tile, String title, int color) {
      this.background = background;
      this.tile = tile;
      this.title = title;
      this.color = color;
    }
  }

  /** The 18 ring slots around the painted emblem, clockwise from the top left. */
  static final int[] RING = {2, 3, 4, 5, 6, 16, 25, 34, 43, 51, 50, 49, 48, 47, 37, 28, 19, 10};

  /** Raw slots of the hotbar: card, timer, lock. */
  static final int CARD = 81, TIMER = 85, LOCK = 89;

  /** First item row of the painted boxes (inventory row 1; the labels are painted on row 0). */
  static int box(int col) {
    return 63 + col;
  }

  record Line(String text, int color) {}

  /**
   * One item. {@code head} is a player whose skin to show, {@code icon} an item model under
   * vampyuyu:icon/ (used instead of the skin when both are set). {@code click} is "select:<uuid>",
   * "aim:<uuid>", "use:<ability>", "leave" or null.
   */
  record Entry(
      int raw, UUID head, String icon, int amount, boolean glint, List<Line> lines, String click) {}

  /** The menu the viewer must act in now, or null when no role menu applies. */
  static Kind kind(WerewolfGame game, UUID viewer) {
    synchronized (game) {
      if (!game.actors().contains(viewer)) return null;
      return switch (game.phase()) {
        case "DAY_VOTE" -> Kind.VOTE;
        case "NIGHT" -> Kind.HUNT;
        case "SEER" -> Kind.VISION;
        case "WITCH" -> Kind.POTIONS;
        case "GUARD" -> Kind.GUARD;
        case "CUPID" -> Kind.LOVERS;
        case "HUNTER_SHOT" -> Kind.SHOT;
        default -> null;
      };
    }
  }

  /**
   * Spreads n heads evenly on the ring (never all at the top with a hole at the bottom); past 18
   * players the rest fill the free slots left to right, top to bottom.
   */
  static int[] slots(int n) {
    int[] out = new int[n];
    for (int i = 0; i < Math.min(n, RING.length); i++)
      out[i] = RING[(int) Math.floor((double) i * RING.length / Math.min(n, RING.length))];
    Set<Integer> ring = new HashSet<>();
    for (int r : RING) ring.add(r);
    int next = 0;
    for (int i = RING.length; i < n; i++) {
      while (ring.contains(next)) next++;
      out[i] = next++;
    }
    return out;
  }

  private static Line name(String name) {
    return new Line(name, 0xffffff);
  }

  private static final Line BLANK = new Line("", 0xffffff);

  private static List<Line> lines(Line... lines) {
    return List.of(lines);
  }

  private static String who(Map<UUID, String> names, UUID id) {
    return names.getOrDefault(id, "Player");
  }

  /** Every item of the viewer's role menu, or an empty list when no role menu applies. */
  static List<Entry> menu(
      WerewolfGame game,
      UUID viewer,
      Map<UUID, String> names,
      long secondsLeft,
      boolean leaveLocked) {
    synchronized (game) {
      Kind kind = kind(game, viewer);
      if (kind == null) return List.of();
      List<Entry> out = new ArrayList<>();
      List<UUID> targets =
          game.alive().stream()
              .filter(p -> !p.equals(viewer))
              .sorted(Comparator.comparing((UUID p) -> who(names, p).toLowerCase(Locale.ROOT)))
              .toList();
      int[] ring = slots(targets.size());
      for (int i = 0; i < targets.size() && i < 54; i++)
        out.add(head(game, kind, viewer, targets.get(i), ring[i], names));
      bottom(game, kind, viewer, names, out);
      hotbar(game, viewer, names, secondsLeft, leaveLocked, out);
      return out;
    }
  }

  private static Entry head(
      WerewolfGame game, Kind kind, UUID viewer, UUID target, int slot, Map<UUID, String> names) {
    String n = who(names, target);
    int c = kind.color;
    switch (kind) {
      case VOTE -> {
        int votes = game.votes(viewer).getOrDefault(target, 0);
        boolean mine = target.equals(game.ballot(viewer));
        return new Entry(
            slot,
            target,
            null,
            Math.max(1, votes),
            mine,
            lines(
                name(n),
                new Line(
                    votes == 0
                        ? "No votes yet"
                        : votes + (votes == 1 ? " vote" : " votes") + " against them",
                    c),
                BLANK,
                mine
                    ? new Line("Your vote · Click: take it back", OK)
                    : new Line("Click: vote against " + n, OK)),
            "select:" + target);
      }
      case HUNT -> {
        if (game.role(target).equals("WEREWOLF"))
          return new Entry(
              slot,
              target,
              "mark-wolf",
              1,
              false,
              lines(
                  name(n),
                  new Line("A wolf of your pack", c),
                  BLANK,
                  new Line("Wolves never hunt a wolf", NO)),
              null);
        int votes = game.votes(viewer).getOrDefault(target, 0);
        boolean mine = target.equals(game.ballot(viewer));
        int pack = game.pack(viewer).size() + 1;
        return new Entry(
            slot,
            target,
            null,
            Math.max(1, votes),
            mine,
            lines(
                name(n),
                new Line("Pack: " + votes + " / " + pack + (pack == 1 ? " wolf" : " wolves"), c),
                BLANK,
                mine
                    ? new Line("Your prey · Click: change your mind", VOTE)
                    : new Line("Click: hunt " + n, OK)),
            "select:" + target);
      }
      case VISION -> {
        String seen = game.visions(viewer).get(target);
        if (seen != null)
          return new Entry(
              slot,
              target,
              "card-" + seen.toLowerCase(Locale.ROOT),
              1,
              false,
              lines(
                  name(n),
                  new Line("✦ Seen: " + roleName(seen), c),
                  BLANK,
                  new Line("Already seen", INK_DIM)),
              null);
        return new Entry(
            slot,
            target,
            null,
            1,
            false,
            lines(
                name(n),
                new Line("Unknown role", c),
                BLANK,
                new Line("Click: see " + n + "'s role", OK)),
            "select:" + target);
      }
      case POTIONS -> {
        boolean victim = target.equals(game.victim(viewer));
        boolean picked = game.aims(viewer).contains(target);
        boolean death = game.deathPotion(viewer);
        List<Line> l = new ArrayList<>();
        l.add(name(n));
        if (victim) l.add(new Line("☠ The wolves' victim tonight", WEREWOLF));
        else l.add(new Line(picked ? "Picked for the death potion" : "Asleep", c));
        l.add(BLANK);
        if (victim) l.add(new Line("LIFE: save them", VOTE));
        if (!death) l.add(new Line("Your death potion is used", NO));
        else
          l.add(
              picked
                  ? new Line("DEATH: poison " + n + " · Click: unpick", OK)
                  : new Line("Click: pick for the death potion", OK));
        return new Entry(
            slot, target, null, 1, picked, List.copyOf(l), death ? "aim:" + target : null);
      }
      case GUARD -> {
        if (target.equals(game.lastProtected(viewer)))
          return new Entry(
              slot,
              target,
              "mark-last",
              1,
              false,
              lines(
                  name(n),
                  new Line("Protected last night", c),
                  BLANK,
                  new Line("Not available tonight", NO)),
              null);
        return new Entry(
            slot,
            target,
            null,
            1,
            false,
            lines(
                name(n),
                new Line("Sleeping", c),
                BLANK,
                new Line("Click: protect " + n + " (final)", OK)),
            "select:" + target);
      }
      case LOVERS -> {
        List<UUID> picks = game.aims(viewer);
        int at = picks.indexOf(target);
        return new Entry(
            slot,
            target,
            null,
            1,
            at >= 0,
            at >= 0
                ? lines(
                    name(n),
                    new Line("Lover #" + (at + 1), c),
                    BLANK,
                    new Line("Click: unpick " + n, INK_DIM))
                : lines(
                    name(n),
                    new Line("Not in love", c),
                    BLANK,
                    picks.size() < 2
                        ? new Line("Click: pick " + n + " as a lover", OK)
                        : new Line("Two lovers picked: unpick one first", NO)),
            "aim:" + target);
      }
      default -> { // SHOT
        boolean picked = game.aims(viewer).contains(target);
        return new Entry(
            slot,
            target,
            null,
            1,
            picked,
            picked
                ? lines(
                    name(n), new Line("Your target", c), BLANK, new Line("Then press SHOOT", VOTE))
                : lines(name(n), new Line("Alive", c), BLANK, new Line("Click: aim at " + n, OK)),
            "aim:" + target);
      }
    }
  }

  private static void bottom(
      WerewolfGame game, Kind kind, UUID viewer, Map<UUID, String> names, List<Entry> out) {
    Map<String, String> can = game.abilities(viewer);
    switch (kind) {
      case VOTE -> {
        UUID mine = game.ballot(viewer);
        if (mine != null)
          out.add(
              new Entry(
                  box(1),
                  mine,
                  null,
                  1,
                  false,
                  lines(name(who(names, mine)), new Line("Your vote", VOTE)),
                  "select:" + mine));
        Map<UUID, Integer> votes = game.votes(viewer);
        int top = votes.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<UUID> leaders =
            votes.entrySet().stream()
                .filter(e -> e.getValue() == top)
                .map(Map.Entry::getKey)
                .toList();
        if (top > 0 && leaders.size() == 1)
          out.add(
              new Entry(
                  box(4),
                  leaders.getFirst(),
                  null,
                  top,
                  false,
                  lines(
                      name(who(names, leaders.getFirst())),
                      new Line(top + (top == 1 ? " vote" : " votes") + " · leading", VOTE)),
                  null));
        else if (top > 0)
          out.add(
              new Entry(
                  box(4),
                  null,
                  "skip",
                  1,
                  false,
                  lines(new Line("Tie", 0xffffff), new Line("A tie eliminates nobody", INK_MUTED)),
                  null));
        boolean skipped = game.abstained(viewer);
        out.add(
            new Entry(
                box(7),
                null,
                "skip",
                1,
                skipped,
                skipped
                    ? lines(
                        new Line("You skip this vote", 0xffffff),
                        BLANK,
                        new Line("Click: vote after all", OK))
                    : lines(
                        new Line("Skip the vote", 0xffffff),
                        new Line("Vote for nobody this time", INK_MUTED),
                        BLANK,
                        new Line("Click: skip", OK)),
                "use:skip"));
      }
      case HUNT -> {
        UUID prey = game.ballot(viewer);
        if (prey != null)
          out.add(
              new Entry(
                  box(1),
                  prey,
                  null,
                  1,
                  false,
                  lines(name(who(names, prey)), new Line("Your prey", WEREWOLF)),
                  "select:" + prey));
        List<UUID> pack = game.pack(viewer);
        for (int i = 0; i < pack.size() && i < 3; i++) {
          UUID wolf = pack.get(i), choice = game.ballot(wolf);
          out.add(
              new Entry(
                  box(6 + i),
                  wolf,
                  null,
                  1,
                  false,
                  lines(
                      name(who(names, wolf)),
                      new Line(
                          choice == null ? "Has not chosen yet" : "Hunts " + who(names, choice),
                          WEREWOLF)),
                  null));
        }
        if (pack.isEmpty())
          out.add(
              new Entry(
                  box(6),
                  null,
                  "mark-wolf",
                  1,
                  false,
                  lines(new Line("You hunt alone", 0xffffff)),
                  null));
      }
      case VISION -> {
        List<Map.Entry<UUID, String>> seen = new ArrayList<>(game.visions(viewer).entrySet());
        List<Map.Entry<UUID, String>> last =
            seen.subList(Math.max(0, seen.size() - 3), seen.size());
        for (int i = 0; i < last.size(); i++) {
          var v = last.get(i);
          String n = who(names, v.getKey()), role = roleName(v.getValue());
          out.add(
              new Entry(
                  box(1 + 3 * i),
                  v.getKey(),
                  null,
                  1,
                  false,
                  lines(name(n), new Line("✦ " + role, SEER)),
                  null));
          out.add(
              new Entry(
                  box(2 + 3 * i),
                  null,
                  "card-" + v.getValue().toLowerCase(Locale.ROOT),
                  1,
                  false,
                  lines(
                      new Line(role, WerewolfPresentation.color(v.getValue())),
                      new Line(n + " is " + article(v.getValue()) + role, INK_MUTED)),
                  null));
        }
      }
      case POTIONS -> {
        UUID victim = game.victim(viewer);
        boolean life = can.containsKey("heal");
        out.add(
            new Entry(
                box(1),
                null,
                game.lifePotion(viewer) ? "potion-life" : "potion-life-off",
                1,
                false,
                life
                    ? lines(
                        new Line("LIFE", VOTE),
                        new Line("Save " + who(names, victim) + ", the wolves' victim", INK_MUTED),
                        BLANK,
                        new Line("Click: drink the life potion", OK))
                    : lines(
                        new Line("LIFE", VOTE),
                        new Line(
                            game.lifePotion(viewer) ? "Nobody to save tonight" : "Already used",
                            NO)),
                life ? "use:heal" : null));
        boolean death = can.containsKey("poison");
        List<UUID> aim = game.aims(viewer);
        out.add(
            new Entry(
                box(4),
                null,
                game.deathPotion(viewer) ? "potion-death" : "potion-death-off",
                1,
                false,
                death
                    ? lines(
                        new Line("DEATH", WITCH),
                        new Line("Poison " + who(names, aim.getFirst()), INK_MUTED),
                        BLANK,
                        new Line("Click: pour the death potion", OK))
                    : lines(
                        new Line("DEATH", WITCH),
                        new Line(
                            game.deathPotion(viewer) ? "Pick a head first" : "Already used", NO)),
                death ? "use:poison" : null));
        out.add(
            new Entry(
                box(7),
                null,
                "moon",
                1,
                false,
                lines(
                    new Line("SLEEP", INK_DIM),
                    new Line("End your turn", INK_MUTED),
                    BLANK,
                    new Line("Click: go back to sleep", OK)),
                "use:sleep"));
      }
      case GUARD -> {
        UUID last = game.lastProtected(viewer);
        if (last != null)
          out.add(
              new Entry(
                  box(1),
                  last,
                  last.equals(viewer) ? "shield" : null,
                  1,
                  false,
                  lines(
                      name(last.equals(viewer) ? "You" : who(names, last)),
                      new Line("Protected last night", WerewolfPresentation.GUARD)),
                  null));
        boolean self = can.containsKey("protect-self");
        out.add(
            new Entry(
                box(6),
                null,
                "shield",
                1,
                false,
                self
                    ? lines(
                        new Line("PROTECT ME", WerewolfPresentation.GUARD),
                        new Line("Guard yourself tonight", INK_MUTED),
                        BLANK,
                        new Line("Click: protect yourself (final)", OK))
                    : lines(
                        new Line("PROTECT ME", WerewolfPresentation.GUARD),
                        new Line("Not two nights in a row", NO)),
                self ? "use:protect-self" : null));
      }
      case LOVERS -> {
        List<UUID> picks = game.aims(viewer);
        for (int i = 0; i < picks.size(); i++)
          out.add(
              new Entry(
                  box(1 + 2 * i),
                  picks.get(i),
                  null,
                  1,
                  false,
                  lines(name(who(names, picks.get(i))), new Line("Lover #" + (i + 1), CUPID)),
                  "aim:" + picks.get(i)));
        out.add(new Entry(box(2), null, "heart", 1, false, lines(new Line("♥", CUPID)), null));
        boolean bind = can.containsKey("bind");
        out.add(
            new Entry(
                box(7),
                null,
                "heart",
                1,
                bind,
                bind
                    ? lines(
                        new Line("BIND", CUPID),
                        new Line(
                            who(names, picks.get(0)) + " ♥ " + who(names, picks.get(1)), INK_MUTED),
                        BLANK,
                        new Line("Click: unite them (final)", OK))
                    : lines(new Line("BIND", CUPID), new Line("Pick two lovers first", NO)),
                bind ? "use:bind" : null));
      }
      case SHOT -> {
        List<UUID> aim = game.aims(viewer);
        if (!aim.isEmpty())
          out.add(
              new Entry(
                  box(1),
                  aim.getFirst(),
                  null,
                  1,
                  false,
                  lines(name(who(names, aim.getFirst())), new Line("Your target", HUNTER)),
                  "aim:" + aim.getFirst()));
        boolean shoot = can.containsKey("shoot");
        out.add(
            new Entry(
                box(7),
                null,
                "target",
                1,
                shoot,
                shoot
                    ? lines(
                        new Line("SHOOT", HUNTER),
                        new Line("Shoot " + who(names, aim.getFirst()), INK_MUTED),
                        BLANK,
                        new Line("Click: fire (final)", OK))
                    : lines(new Line("SHOOT", HUNTER), new Line("Pick your target first", NO)),
                shoot ? "use:shoot" : null));
      }
    }
  }

  static String article(String role) {
    return role.equals("WEREWOLF") || role.equals("VILLAGER") ? "a " : "the ";
  }

  /** Card, timer (stack size = seconds left) and lock: the same hotbar in every menu. */
  private static void hotbar(
      WerewolfGame game,
      UUID viewer,
      Map<UUID, String> names,
      long secondsLeft,
      boolean leaveLocked,
      List<Entry> out) {
    out.add(card(game, viewer, names));
    out.add(
        new Entry(
            TIMER,
            null,
            "timer",
            (int) Math.max(1, Math.min(99, secondsLeft)),
            false,
            lines(
                new Line(clock(secondsLeft) + " left", VOTE),
                new Line("The turn ends when the timer runs out", INK_MUTED)),
            null));
    out.add(
        leaveLocked
            ? new Entry(
                LOCK,
                null,
                "lock",
                1,
                false,
                lines(
                    new Line("Leave locked", 0xffffff),
                    new Line("You cannot leave while the game is running", INK_MUTED)),
                null)
            : new Entry(
                LOCK,
                null,
                "lock",
                1,
                false,
                lines(
                    new Line("Leave the arena", 0xffffff),
                    new Line("Get your items, stats and position back", INK_MUTED),
                    BLANK,
                    new Line("Click: leave", OK)),
                "leave"));
  }

  /** The viewer's role card: its name in the role colour, the team, then how to play it. */
  static Entry card(WerewolfGame game, UUID viewer, Map<UUID, String> names) {
    String role = game.role(viewer);
    String help = game.help(viewer);
    for (var entry : names.entrySet())
      help = help.replace(entry.getKey().toString(), entry.getValue());
    List<Line> l = new ArrayList<>();
    l.add(new Line(roleName(role), WerewolfPresentation.color(role)));
    l.add(new Line(team(role), INK));
    l.add(BLANK);
    for (String part : wrap(help, 40)) l.add(new Line(part, INK_MUTED));
    return new Entry(
        CARD, null, "card-" + role.toLowerCase(Locale.ROOT), 1, false, List.copyOf(l), null);
  }

  /**
   * Splits text on spaces into lines of at most {@code width} characters (tooltips do not wrap).
   */
  static List<String> wrap(String text, int width) {
    List<String> out = new ArrayList<>();
    StringBuilder line = new StringBuilder();
    for (String word : text.split(" ")) {
      if (line.length() > 0 && line.length() + 1 + word.length() > width) {
        out.add(line.toString());
        line.setLength(0);
      }
      if (line.length() > 0) line.append(' ');
      line.append(word);
    }
    if (line.length() > 0) out.add(line.toString());
    return out;
  }
}

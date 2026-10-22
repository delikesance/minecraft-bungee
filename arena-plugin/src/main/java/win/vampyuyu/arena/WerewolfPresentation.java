package win.vampyuyu.arena;

import java.util.*;

/** Viewer-specific text policy, shared by announcements, HUD and compass. No Bukkit access. */
final class WerewolfPresentation {
  record View(String phase, String instruction, boolean actor, boolean timerVisible) {}

  record Cue(String title, String subtitle) {}

  /** Palette of the art direction (resource-pack design guide, tokens role-* / ink-* / state-*). */
  static final int WEREWOLF = 0xff5a5a,
      VILLAGER = 0xe9d99b,
      SEER = 0xb892ff,
      WITCH = 0x6fdc8c,
      HUNTER = 0xf0a050,
      CUPID = 0xff8fc0,
      GUARD = 0x6cc6ff,
      VOTE = 0xffd36e,
      INK = 0xf6ead0,
      INK_MUTED = 0xcdbfe3,
      INK_DIM = 0x8f84b3,
      OK = 0x9cf09c,
      NO = 0xff6b6b;

  /** One colour per role, used by its menu, card, bossbar and chat lines. */
  static int color(String role) {
    return switch (role == null ? "" : role) {
      case "WEREWOLF" -> WEREWOLF;
      case "SEER" -> SEER;
      case "WITCH" -> WITCH;
      case "HUNTER" -> HUNTER;
      case "CUPID" -> CUPID;
      case "GUARD" -> GUARD;
      default -> VILLAGER;
    };
  }

  /** Singular display name: "Werewolf", "Seer", "Guard"... */
  static String roleName(String role) {
    return role.charAt(0) + role.substring(1).toLowerCase(Locale.ROOT);
  }

  static String plural(String role, int count) {
    if (count == 1) return roleName(role);
    return role.equals("WEREWOLF") ? "Werewolves" : roleName(role) + "s";
  }

  static String team(String role) {
    return role.equals("WEREWOLF") ? "Wolf pack" : "Village team";
  }

  /** Bossbar countdown: "12 s" under a minute, "1 min 20" above. */
  static String clock(long seconds) {
    seconds = Math.max(0, seconds);
    return seconds < 60
        ? seconds + " s"
        : seconds / 60 + " min" + (seconds % 60 == 0 ? "" : String.format(" %02d", seconds % 60));
  }

  record Boss(String text, int color, boolean timer) {}

  /**
   * Bossbar for this viewer: phase · who acts · time left. Sleepers learn nothing about the turn.
   */
  static Boss boss(WerewolfGame game, UUID viewer, long seconds) {
    synchronized (game) {
      String phase = game.phase();
      int round = game.round();
      if (phase.equals("FINISHED"))
        return new Boss("Game over · " + result(game.winner()), VOTE, false);
      boolean actor = game.actors().contains(viewer);
      if (game.night()) {
        String head = "☾ Night " + round + " · ";
        if (!actor) return new Boss(head + "You are asleep…", INK_DIM, false);
        String role = turnRole(phase);
        String turn =
            switch (phase) {
              case "NIGHT" -> "The Werewolves hunt";
              case "HUNTER_SHOT" -> "The Hunter takes a last shot";
              default -> "The " + roleName(role) + " is awake";
            };
        return new Boss(head + turn + " · " + clock(seconds), color(role), true);
      }
      String head = "☀ Day " + round + " · ";
      return switch (phase) {
        case "DISCUSSION" -> new Boss(head + "Debate · " + clock(seconds), VILLAGER, true);
        case "DAY_VOTE" -> new Boss(head + "Village vote · " + clock(seconds), VOTE, true);
        default ->
            new Boss(head + "The Hunter takes a last shot · " + clock(seconds), HUNTER, true);
      };
    }
  }

  /** True when this player must act now: their menu opens by itself and cannot be closed. */
  static boolean menuRequired(WerewolfGame game, UUID id) {
    synchronized (game) {
      String phase = game.phase();
      return !phase.equals("DISCUSSION") && !phase.equals("FINISHED") && game.actors().contains(id);
    }
  }

  /** Role that acts during a night phase, or null outside the night. */
  static String turnRole(String phase) {
    return switch (phase) {
      case "CUPID" -> "CUPID";
      case "GUARD" -> "GUARD";
      case "SEER" -> "SEER";
      case "NIGHT" -> "WEREWOLF";
      case "WITCH" -> "WITCH";
      case "HUNTER_SHOT" -> "HUNTER";
      default -> null;
    };
  }

  static String roleLabel(String role) {
    return role.equals("WEREWOLF")
        ? "Werewolves"
        : role.charAt(0) + role.substring(1).toLowerCase(Locale.ROOT);
  }

  static String deathLine(String name, String role) {
    return name
        + " has died — they were "
        + (role.equals("WEREWOLF") ? "a Werewolf" : "the " + roleLabel(role))
            .replace("the Villager", "a Villager");
  }

  static String wakeLine(String phase) {
    String role = turnRole(phase);
    return role == null
        ? null
        : "The " + roleLabel(role) + " wake" + (role.equals("WEREWOLF") ? "" : "s") + " up";
  }

  static String sleepLine(String phase) {
    String role = turnRole(phase);
    return role == null
        ? null
        : "The "
            + roleLabel(role)
            + " go"
            + (role.equals("WEREWOLF") ? "" : "es")
            + " back to sleep";
  }

  static boolean dusk(boolean wasNight, boolean night) {
    return !wasNight && night;
  }

  static boolean dawn(boolean wasNight, boolean night, String phase) {
    return wasNight && !night && !phase.equals("FINISHED");
  }

  static View view(WerewolfGame game, UUID viewer) {
    synchronized (game) {
      String phase = game.phase();
      boolean actor = game.actors().contains(viewer);
      if (game.night() && !actor)
        return new View("The village sleeps", "Zzz… You sleep until dawn", false, false);
      String label =
          switch (phase) {
            case "DISCUSSION" -> "Day · Discussion";
            case "DAY_VOTE" -> "Day · Voting";
            case "CUPID" -> "Cupid's choice";
            case "GUARD" -> "Guard's turn";
            case "SEER" -> "Seer's turn";
            case "NIGHT" -> "Wolf hunt";
            case "WITCH" -> "Potions";
            case "HUNTER_SHOT" -> "Hunter's final shot";
            default -> "Game over";
          };
      String instruction =
          actor
              ? switch (phase) {
                case "DISCUSSION" -> "Talk it over: who are the wolves?";
                case "DAY_VOTE" -> "Click a head to vote, or SKIP";
                case "CUPID" -> "Pick two lovers, then press BIND";
                case "GUARD" -> "Click a head to protect it tonight";
                case "SEER" -> "Click a head to see its role";
                case "NIGHT" -> "Click a head to choose your prey";
                case "WITCH" -> "LIFE saves, DEATH poisons, SLEEP ends your turn";
                case "HUNTER_SHOT" -> "Pick a target, then press SHOOT";
                default -> "The game has ended";
              }
              : phase.equals("FINISHED") ? "The game has ended" : "Watch the village";
      return new View(label, instruction, actor, !phase.equals("FINISHED"));
    }
  }

  static Cue awakening(String phase) {
    return switch (phase) {
      case "CUPID" -> new Cue("Cupid, bind two hearts", "Choose two lovers in your menu");
      case "GUARD" -> new Cue("Guard, awaken", "Choose someone to protect");
      case "SEER" -> new Cue("Seer, awaken", "Discover one player's secret");
      case "NIGHT" -> new Cue("Wolves, awaken", "Vote for your victim in the menu");
      case "WITCH" -> new Cue("Witch, awaken", "Your potions may change their fate");
      case "HUNTER_SHOT" -> new Cue("Hunter, take your final shot", "Choose your last target");
      default -> null;
    };
  }

  static String result(String winner) {
    return switch (winner) {
      case "VILLAGE" -> "The village wins";
      case "WEREWOLVES" -> "The wolves win";
      case "LOVERS" -> "Love conquers all";
      case "DRAW" -> "The story ends in a draw";
      default -> "Time is up";
    };
  }

  static boolean current(String event, long epoch, String expectedEvent, long expectedEpoch) {
    return Objects.equals(event, expectedEvent) && epoch == expectedEpoch;
  }
}

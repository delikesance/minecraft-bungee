package win.vampyuyu.arena;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;

/**
 * The screen outside the menus (design guide "L'écran de jeu"): the bossbar says the phase, who
 * acts and the time left; the sidebar carries the role card, the night or day, the living and the
 * roles in play. Must run on the player's thread.
 */
final class WerewolfHud {
  /** Role cards as font glyphs (resource-pack/art/render.py, font vampyuyu:hud). */
  static final Key FONT = Key.key("vampyuyu", "hud");

  static final List<String> CARDS =
      List.of("back", "villager", "werewolf", "seer", "cupid", "hunter", "witch", "guard");

  /** Glyph of a card: the back, a role card, or its grey version once the player is dead. */
  static char glyph(String role, boolean dead) {
    int index = Math.max(0, CARDS.indexOf(role.toLowerCase(Locale.ROOT)));
    return (char) ('\uE000' + index + (dead && index > 0 ? 0x10 : 0));
  }

  private final Map<UUID, BossBar> bars = new ConcurrentHashMap<>();
  private final Map<UUID, Scoreboard> boards = new ConcurrentHashMap<>();

  void show(ArenaSession s, Player p, WerewolfGame game, boolean cardFaceUp) {
    UUID id = p.getUniqueId();
    long seconds = Math.max(0, (s.phaseEndsAt - System.currentTimeMillis() + 999) / 1000);
    var boss = WerewolfPresentation.boss(game, id, seconds);
    float progress =
        boss.timer() && game.seconds() > 0
            ? Math.max(0f, Math.min(1f, seconds / (float) game.seconds()))
            : 1f;
    Component title = Component.text(boss.text(), TextColor.color(boss.color()));
    BossBar bar =
        bars.computeIfAbsent(
            id,
            k -> {
              BossBar created =
                  BossBar.bossBar(
                      title, progress, barColor(boss.color()), BossBar.Overlay.PROGRESS);
              p.showBossBar(created);
              return created;
            });
    p.showBossBar(bar); // No-op while shown; brings it back after a reconnect.
    bar.name(title);
    bar.progress(progress);
    bar.color(barColor(boss.color()));
    sidebar(s, p, game, cardFaceUp);
  }

  /** Nearest of the six bossbar colours to the role colour. */
  static BossBar.Color barColor(int color) {
    return switch (color) {
      case WerewolfPresentation.WEREWOLF -> BossBar.Color.RED;
      case WerewolfPresentation.SEER -> BossBar.Color.PURPLE;
      case WerewolfPresentation.WITCH -> BossBar.Color.GREEN;
      case WerewolfPresentation.CUPID -> BossBar.Color.PINK;
      case WerewolfPresentation.GUARD -> BossBar.Color.BLUE;
      case WerewolfPresentation.HUNTER, WerewolfPresentation.VOTE -> BossBar.Color.YELLOW;
      default -> BossBar.Color.WHITE;
    };
  }

  private void sidebar(ArenaSession s, Player p, WerewolfGame game, boolean cardFaceUp) {
    Scoreboard board = boards.get(p.getUniqueId());
    if (board == null) {
      // Folia has no scoreboard API: the bossbar and the menus still say everything.
      ScoreboardManager manager;
      try {
        manager = Bukkit.getScoreboardManager();
      } catch (UnsupportedOperationException folia) {
        return;
      }
      if (manager == null) return;
      board = manager.getNewScoreboard();
      Objective objective =
          board.registerNewObjective("werewolf", Criteria.DUMMY, Component.empty());
      objective.setDisplaySlot(DisplaySlot.SIDEBAR);
      objective.numberFormat(NumberFormat.blank());
      boards.put(p.getUniqueId(), board);
      p.setScoreboard(board);
    }
    if (p.getScoreboard() != board) p.setScoreboard(board);
    voteTags(board, s, game, p.getUniqueId());
    Objective objective = board.getObjective("werewolf");
    if (objective == null) return;
    UUID id = p.getUniqueId();
    String role = game.role(id);
    boolean dead = !game.alive().contains(id);
    int roleColor = dead ? WerewolfPresentation.INK_DIM : WerewolfPresentation.color(role);
    // The card hangs above the sidebar title, at the right edge of the screen.
    objective.displayName(
        Component.text()
            .append(
                Component.text(String.valueOf(glyph(cardFaceUp ? role : "back", dead))).font(FONT))
            .append(
                Component.text(
                    " " + (cardFaceUp ? WerewolfPresentation.roleName(role) : "Your card…"),
                    TextColor.color(roleColor)))
            .build());
    List<Component> lines = new ArrayList<>();
    lines.add(
        Component.text(
            cardFaceUp ? WerewolfPresentation.team(role) + (dead ? " · dead" : "") : "",
            TextColor.color(WerewolfPresentation.INK_MUTED)));
    lines.add(Component.empty());
    lines.add(
        Component.text(
            game.night() ? "☾ Night " + game.round() : "☀ Day " + game.round(),
            TextColor.color(
                game.night() ? WerewolfPresentation.SEER : WerewolfPresentation.VILLAGER)));
    lines.add(
        Component.text("Alive ", TextColor.color(WerewolfPresentation.INK_MUTED))
            .append(
                Component.text(
                    game.alive().size() + " / " + s.players.size(),
                    TextColor.color(WerewolfPresentation.INK))));
    lines.add(Component.empty());
    lines.add(Component.text("Roles in play", TextColor.color(WerewolfPresentation.INK_MUTED)));
    // Dead players' roles are announced, so what is left in play is public knowledge.
    Map<String, Integer> left = new LinkedHashMap<>(game.composition());
    for (UUID other : s.players.keySet())
      if (!game.alive().contains(other) && s.revealed.contains(other))
        left.computeIfPresent(game.role(other), (k, n) -> n - 1);
    for (var entry : left.entrySet())
      if (entry.getValue() > 0)
        lines.add(
            Component.text(
                " "
                    + entry.getValue()
                    + " "
                    + WerewolfPresentation.plural(entry.getKey(), entry.getValue()),
                TextColor.color(WerewolfPresentation.color(entry.getKey()))));
    UUID partner = game.partner(id);
    if (partner != null) {
      lines.add(Component.empty());
      lines.add(
          Component.text(
              "♥ " + s.players.getOrDefault(partner, "Your lover"),
              TextColor.color(WerewolfPresentation.CUPID)));
    }
    for (int i = 0; i < 15; i++) {
      String entry = "line" + i;
      if (i < lines.size()) {
        Score score = objective.getScore(entry);
        score.setScore(lines.size() - i);
        score.customName(lines.get(i));
      } else board.resetScores(entry);
    }
  }

  /**
   * During the day vote, everyone's ballots hang under their name tag ("✖ 3 votes"), the same
   * public totals the vote menu shows. Outside the vote the line disappears.
   */
  private static void voteTags(Scoreboard board, ArenaSession s, WerewolfGame game, UUID viewer) {
    Objective tags = board.getObjective("votes");
    if (tags == null) tags = board.registerNewObjective("votes", Criteria.DUMMY, Component.empty());
    if (!game.phase().equals("DAY_VOTE")) {
      if (tags.getDisplaySlot() != null) tags.setDisplaySlot(null);
      return;
    }
    if (tags.getDisplaySlot() != DisplaySlot.BELOW_NAME) tags.setDisplaySlot(DisplaySlot.BELOW_NAME);
    Map<UUID, Integer> totals = game.votes(viewer);
    for (var entry : s.players.entrySet()) {
      int votes = totals.getOrDefault(entry.getKey(), 0);
      Score score = tags.getScore(entry.getValue());
      score.setScore(votes);
      score.numberFormat(
          votes == 0
              ? NumberFormat.blank()
              : NumberFormat.fixed(
                  Component.text(
                      "✖ " + votes + (votes == 1 ? " vote" : " votes"),
                      TextColor.color(WerewolfPresentation.VOTE))));
    }
  }

  /** Back to the server's scoreboard and no bossbar, when the player leaves or the game ends. */
  void clear(Player p) {
    BossBar bar = bars.remove(p.getUniqueId());
    if (bar != null) p.hideBossBar(bar);
    if (boards.remove(p.getUniqueId()) != null)
      try {
        p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
      } catch (UnsupportedOperationException ignored) {
      }
  }
}

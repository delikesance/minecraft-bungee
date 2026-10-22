package win.vampyuyu.arena;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.*;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.entity.Player;

/** Entity-local one-shot sounds and guarded presentation tasks. Never broadcasts world sounds. */
final class WerewolfAmbience {
  private final NexusArenaEngine plugin;
  private final WerewolfHud screen = new WerewolfHud();
  private volatile long lastWarning;
  private static final TextColor INK = TextColor.color(WerewolfPresentation.INK),
      INK_MUTED = TextColor.color(WerewolfPresentation.INK_MUTED),
      VOTE = TextColor.color(WerewolfPresentation.VOTE);

  WerewolfAmbience(NexusArenaEngine plugin) {
    this.plugin = plugin;
  }

  private boolean valid(ArenaSession s, Player p, String event, long epoch, boolean ending) {
    return p.isOnline()
        && plugin.arenas.session(p.getUniqueId()) == s
        && !plugin.arenas.transitioning(p.getUniqueId())
        && WerewolfPresentation.current(s.eventId, s.ambienceEpoch, event, epoch)
        && !s.stopping
        && (ending || (!s.finishing && s.game != null && s.game.phase().equals(s.observedPhase)));
  }

  private CompletableFuture<Void> dispatch(
      ArenaSession s, Player p, String event, long epoch, boolean ending, Consumer<Player> action) {
    return plugin
        .onPlayer(
            p,
            () -> {
              if (valid(s, p, event, epoch, ending)) action.accept(p);
              return (Void) null;
            })
        .exceptionally(
            e -> {
              // Disconnects belong to recovery; report actual presentation failures without
              // flooding logs.
              long now = System.currentTimeMillis();
              if (p.isOnline() && valid(s, p, event, epoch, ending) && now - lastWarning > 60000) {
                lastWarning = now;
                plugin.getLogger().warning("Werewolf presentation: " + NexusArenaEngine.reason(e));
              }
              return null;
            });
  }

  private void delayed(
      ArenaSession s, Player p, String event, long epoch, long ticks, Consumer<Player> action) {
    p.getScheduler()
        .runDelayed(
            plugin,
            task -> {
              if (valid(s, p, event, epoch, false)) action.accept(p);
            },
            null,
            ticks);
  }

  private void sound(Player p, Sound sound, float volume, float pitch) {
    p.playSound(p.getLocation(), sound, SoundCategory.PLAYERS, volume, pitch);
  }

  private void title(Player p, String heading, String subtitle, TextColor color) {
    p.showTitle(
        Title.title(
            Component.text(heading, color),
            Component.text(subtitle, INK),
            Title.Times.times(
                Duration.ofMillis(250), Duration.ofMillis(1250), Duration.ofMillis(500))));
  }

  /** The turn's instruction stays on screen for the whole turn. */
  private void instruct(
      Player p, String heading, String subtitle, TextColor color, long stayMillis) {
    p.showTitle(
        Title.title(
            Component.text(heading, color),
            Component.text(subtitle, INK),
            Title.Times.times(
                Duration.ofMillis(250), Duration.ofMillis(stayMillis), Duration.ofMillis(500))));
  }

  private void heart(ArenaSession s, Player p, String event, long epoch) {
    if (!(s.game instanceof WerewolfGame game)
        || !game.phase().equals("NIGHT")
        || !game.actors().contains(p.getUniqueId())) return;
    sound(p, Sound.ENTITY_WARDEN_HEARTBEAT, .22f, 1.1f);
    delayed(
        s,
        p,
        event,
        epoch,
        5,
        player -> {
          if (s.game == game
              && game.phase().equals("NIGHT")
              && game.actors().contains(player.getUniqueId()))
            sound(player, Sound.ENTITY_WARDEN_HEARTBEAT, .16f, .95f);
        });
  }

  CompletableFuture<Void> enter(ArenaSession s, boolean opening) {
    if (!(s.game instanceof WerewolfGame game) || game.phase().equals("FINISHED"))
      return CompletableFuture.completedFuture(null);
    String phase = game.phase(), event = s.eventId;
    long epoch = s.ambienceEpoch;
    // A departure during start-up can skip the opening turn; tick() then presents the new one, so
    // this call only reveals the roles and leaves the night/dawn bookkeeping untouched.
    boolean current = phase.equals(s.observedPhase);
    boolean night = current ? game.night() : s.ambienceNight;
    boolean dusk = WerewolfPresentation.dusk(s.ambienceNight, night);
    boolean dawn = WerewolfPresentation.dawn(s.ambienceNight, night, phase);
    s.ambienceNight = night;
    Set<UUID> fallen = new HashSet<>();
    for (UUID id : s.players.keySet())
      if (current && !game.alive().contains(id) && !s.revealed.contains(id)) fallen.add(id);
    List<Component> news = current ? news(s, game, phase) : List.of();
    s.nextHeartbeatAt = System.currentTimeMillis() + 4000;
    boolean enabled = s.config.werewolf().ambienceEnabled();
    if (opening) s.cardsTurnAt = System.currentTimeMillis() + 2000;
    List<CompletableFuture<?>> tasks = new ArrayList<>();
    for (UUID id : s.players.keySet()) {
      Player p = Bukkit.getPlayer(id);
      if (p == null) continue;
      String role = game.role(id);
      TextColor roleColor = TextColor.color(WerewolfPresentation.color(role));
      if (opening)
        tasks.add(
            dispatch(
                s,
                p,
                event,
                epoch,
                true,
                player -> {
                  player.clearTitle();
                  plugin.gui.disarm(player);
                  player.sendMessage(Component.text("Your story begins", INK));
                  if (enabled) {
                    sound(player, Sound.BLOCK_NOTE_BLOCK_HARP, .35f, 1.1f);
                    title(player, "Your story begins", "Your card is being dealt…", INK);
                  }
                  // The card shows its back for two seconds, then turns over.
                  delayed(
                      s,
                      player,
                      event,
                      epoch,
                      40,
                      target -> {
                        target.sendMessage(
                            CompassGUI.whisper(
                                "Your role: "
                                    + WerewolfPresentation.roleName(role)
                                    + " · "
                                    + WerewolfPresentation.team(role),
                                WerewolfPresentation.color(role)));
                        if (enabled) sound(target, Sound.ITEM_BOOK_PAGE_TURN, .6f, 1f);
                        hud(s, target);
                      });
                }));
      if (!current) continue;
      tasks.add(
          dispatch(
              s,
              p,
              event,
              epoch,
              false,
              player -> {
                if (!opening) player.clearTitle(); // Keep the opening title just shown.
                news.forEach(player::sendMessage);
                var view = WerewolfPresentation.view(game, id);
                if (dusk) {
                  player.sendMessage(
                      separator("☾ Night " + game.round(), WerewolfPresentation.SEER));
                  player.sendMessage(
                      Component.text("Night falls. The village falls asleep.", INK_MUTED));
                  if (enabled) {
                    sound(player, Sound.BLOCK_BELL_USE, .35f, .7f);
                    Consumer<Player> cue =
                        target -> {
                          title(
                              target,
                              "Night falls",
                              "The village falls asleep",
                              TextColor.color(WerewolfPresentation.SEER));
                        };
                    if (opening) delayed(s, player, event, epoch, 40, cue);
                    else cue.accept(player);
                  }
                } else if (dawn) {
                  player.sendMessage(
                      separator("☀ Day " + game.round(), WerewolfPresentation.VILLAGER));
                  player.sendMessage(
                      Component.text("Dawn breaks. The village wakes up.", INK_MUTED));
                  if (enabled) {
                    sound(player, Sound.BLOCK_NOTE_BLOCK_CHIME, .35f, 1.2f);
                    title(
                        player,
                        "Dawn breaks",
                        "The village awakens",
                        TextColor.color(WerewolfPresentation.VILLAGER));
                  }
                } else if (phase.equals("DAY_VOTE")) {
                  player.sendMessage(
                      Component.text(
                          "The village vote opens: vote in your menu, or skip.",
                          TextColor.color(WerewolfPresentation.VOTE)));
                  if (enabled) {
                    sound(player, Sound.BLOCK_NOTE_BLOCK_HAT, .2f, 1f);
                    title(player, "Village vote", "Pick a head, or skip", VOTE);
                  }
                }
                if (fallen.contains(id) && enabled) {
                  // Your own death is one of the big moments that earn a title.
                  title(
                      player,
                      "☠ You died",
                      "You were "
                          + WerewolfMenus.article(role)
                          + WerewolfPresentation.roleName(role),
                      TextColor.color(WerewolfPresentation.WEREWOLF));
                  sound(player, Sound.ENTITY_WITHER_SPAWN, .15f, 1.4f);
                }
                WerewolfPresentation.Cue awakening = WerewolfPresentation.awakening(phase);
                if (view.actor() && awakening != null) {
                  player.sendMessage(
                      CompassGUI.whisper(
                          awakening.title() + ". " + awakening.subtitle() + ".",
                          WerewolfPresentation.color(WerewolfPresentation.turnRole(phase))));
                  {
                    // A dead hunter first reads their death, then the call to shoot.
                    long delay =
                        (opening ? dusk ? 80 : 40 : dusk ? 40 : 0) + (fallen.contains(id) ? 50 : 0);
                    // A short cue only: the bossbar and the action bar carry the turn after it.
                    long stay = 2500;
                    Consumer<Player> cue =
                        target -> {
                          instruct(
                              target, awakening.title(), awakening.subtitle(), roleColor, stay);
                          if (enabled)
                            switch (phase) {
                              case "CUPID" -> sound(target, Sound.BLOCK_NOTE_BLOCK_HARP, .3f, 1.5f);
                              case "GUARD" ->
                                  sound(target, Sound.BLOCK_ENCHANTMENT_TABLE_USE, .25f, 1f);
                              case "SEER" ->
                                  sound(target, Sound.BLOCK_AMETHYST_BLOCK_CHIME, .3f, 1.2f);
                              case "WITCH" -> sound(target, Sound.ITEM_BOTTLE_FILL, .25f, .8f);
                              case "HUNTER_SHOT" ->
                                  sound(target, Sound.ENTITY_ARROW_SHOOT, .2f, .8f);
                              default -> {} // Wolves receive the heartbeat pair immediately after
                                // gathering.
                            }
                        };
                    if (delay > 0) delayed(s, player, event, epoch, delay, cue);
                    else cue.accept(player);
                  }
                }
                if (enabled && phase.equals("NIGHT") && view.actor())
                  heart(s, player, event, epoch);
                if (!phase.equals("DISCUSSION") && view.actor())
                  delayed(
                      s,
                      player,
                      event,
                      epoch,
                      dusk || opening ? opening ? 100 : 60 : 20,
                      plugin.gui::open);
                else if (player.getOpenInventory().getTopInventory().getHolder()
                    instanceof CompassMenuHolder) player.closeInventory();
                hud(s, player);
              }));
    }
    return CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new));
  }

  /** How long the village sees its verdict in daylight before night falls. */
  static final long VERDICT_SECONDS = 4;

  /**
   * The village's verdict, still in daylight: whoever the day vote eliminated dies in front of
   * everyone before night falls, instead of being found missing at dawn.
   */
  CompletableFuture<Void> verdict(ArenaSession s, WerewolfGame.Verdict verdict) {
    if (verdict == null || !(s.game instanceof WerewolfGame game))
      return CompletableFuture.completedFuture(null);
    String event = s.eventId;
    long epoch = s.ambienceEpoch;
    UUID lynched = verdict.lynched();
    // Only the vote's dead: the lynched and a lover who follows them. Any other death (e.g. a
    // departure's lover) keeps its own announcement at dusk.
    List<UUID> fallen = new ArrayList<>();
    if (lynched != null) fallen.add(lynched);
    UUID grieving = lynched == null ? null : game.partner(lynched);
    if (grieving != null) fallen.add(grieving);
    fallen.removeIf(id -> game.alive().contains(id) || s.revealed.contains(id));
    List<Component> news = deaths(s, game, fallen); // Announced now, so dusk does not repeat them.
    String heading = lynched == null ? "Nobody is eliminated" : "☠ " + s.players.get(lynched);
    String subtitle =
        lynched == null ? "No majority: the village spares everyone" : "The village has spoken";
    boolean enabled = s.config.werewolf().ambienceEnabled();
    for (UUID id : s.players.keySet()) {
      Player p = Bukkit.getPlayer(id);
      if (p == null) continue;
      String role = game.role(id);
      String cause = id.equals(lynched) ? "The village voted you out" : "You died of grief";
      dispatch(
          s,
          p,
          event,
          epoch,
          true,
          player -> {
            screen.hideVotes(player);
            // The menus come back with the night; until then nothing hides the verdict.
            if (player.getOpenInventory().getTopInventory().getHolder()
                instanceof CompassMenuHolder) player.closeInventory();
            player.sendMessage(separator("Verdict", WerewolfPresentation.VOTE));
            news.forEach(player::sendMessage);
            if (lynched == null) player.sendMessage(Component.text(subtitle + ".", INK_MUTED));
            if (!enabled) return;
            if (fallen.contains(id)) {
              title(
                  player,
                  "☠ You died",
                  cause
                      + ": you were "
                      + WerewolfMenus.article(role)
                      + WerewolfPresentation.roleName(role),
                  TextColor.color(WerewolfPresentation.WEREWOLF));
              sound(player, Sound.ENTITY_WITHER_SPAWN, .15f, 1.4f);
            } else {
              title(player, heading, subtitle, VOTE);
              sound(player, Sound.BLOCK_BELL_RESONATE, .3f, lynched == null ? 1.2f : .8f);
            }
          });
    }
    return CompletableFuture.runAsync(
        () -> {}, CompletableFuture.delayedExecutor(VERDICT_SECONDS, TimeUnit.SECONDS));
  }

  /** Public story beats: who just died (with their role) and which role wakes or sleeps. */
  private List<Component> news(ArenaSession s, WerewolfGame game, String phase) {
    List<Component> lines = new ArrayList<>(deaths(s, game));
    String previous = s.ambiencePhase;
    s.ambiencePhase = phase;
    if (!previous.equals(phase)) {
      String sleep = WerewolfPresentation.sleepLine(previous);
      if (sleep != null)
        lines.add(Component.text(sleep, TextColor.color(WerewolfPresentation.INK_DIM)));
      String wake = WerewolfPresentation.wakeLine(phase);
      if (wake != null)
        lines.add(
            Component.text(
                wake,
                TextColor.color(WerewolfPresentation.color(WerewolfPresentation.turnRole(phase)))));
    }
    return lines;
  }

  private List<Component> deaths(ArenaSession s, WerewolfGame game) {
    return deaths(s, game, s.players.keySet());
  }

  /** The same, for these players only. */
  private List<Component> deaths(ArenaSession s, WerewolfGame game, Collection<UUID> among) {
    List<Component> lines = new ArrayList<>();
    for (var entry : s.players.entrySet())
      if (among.contains(entry.getKey())
          && !game.alive().contains(entry.getKey())
          && s.revealed.add(entry.getKey()))
        lines.add(
            Component.text(
                "☠ " + WerewolfPresentation.deathLine(entry.getValue(), game.role(entry.getKey())),
                TextColor.color(WerewolfPresentation.WEREWOLF)));
    return lines;
  }

  void tick(ArenaSession s) {
    if (!(s.game instanceof WerewolfGame) || s.stopping || s.finishing || !s.presentation.isDone())
      return;
    boolean pulse = System.currentTimeMillis() >= s.nextHeartbeatAt;
    if (pulse) s.nextHeartbeatAt = System.currentTimeMillis() + 4000;
    String event = s.eventId;
    long epoch = s.ambienceEpoch;
    for (UUID id : s.players.keySet()) {
      Player p = Bukkit.getPlayer(id);
      if (p == null) continue;
      dispatch(
          s,
          p,
          event,
          epoch,
          false,
          player -> {
            hud(s, player);
            if (pulse && s.config.werewolf().ambienceEnabled()) heart(s, player, event, epoch);
          });
    }
  }

  /** Action bar: what this player must do now. The bossbar carries the phase and the time. */
  private void hud(ArenaSession s, Player p) {
    if (!(s.game instanceof WerewolfGame game)) return;
    var view = WerewolfPresentation.view(game, p.getUniqueId());
    int color =
        view.actor()
            ? WerewolfPresentation.color(game.role(p.getUniqueId()))
            : WerewolfPresentation.INK_DIM;
    p.sendActionBar(Component.text(view.instruction(), TextColor.color(color)));
    screen.show(s, p, game, System.currentTimeMillis() >= s.cardsTurnAt);
  }

  /** Chat journal separator at the start of each night and day. */
  private static Component separator(String label, int color) {
    return Component.text("━━━━━━  " + label + "  ━━━━━━", TextColor.color(color));
  }

  CompletableFuture<Void> conclude(ArenaSession s, String reason) {
    if (!(s.game instanceof WerewolfGame game) || !reason.equals("COMPLETED"))
      return CompletableFuture.completedFuture(null);
    s.ambienceFinale = true;
    String result = WerewolfPresentation.result(game.winner()), event = s.eventId;
    long epoch = s.ambienceEpoch;
    List<Component> news = deaths(s, game);
    List<CompletableFuture<?>> tasks = new ArrayList<>();
    for (UUID id : s.players.keySet()) {
      Player p = Bukkit.getPlayer(id);
      if (p == null) continue;
      tasks.add(
          dispatch(
              s,
              p,
              event,
              epoch,
              true,
              player -> {
                news.forEach(player::sendMessage);
                player.sendMessage(separator("Game over", WerewolfPresentation.VOTE));
                player.sendMessage(Component.text(result, VOTE));
                if (s.config.werewolf().ambienceEnabled()) {
                  sound(player, Sound.ENTITY_PLAYER_LEVELUP, .3f, .85f);
                  title(player, "Game over", result, VOTE);
                }
              }));
    }
    return CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new));
  }

  void clearNow(Player p, ArenaSession s) {
    if (s == null || !(s.game instanceof WerewolfGame)) return;
    p.sendActionBar(Component.empty());
    screen.clear(p);
    plugin.village.unconceal(p, s);
    if (p.getOpenInventory().getTopInventory().getHolder() instanceof CompassMenuHolder)
      p.closeInventory();
    if (!s.ambienceFinale) p.clearTitle();
  }
}

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
  private volatile long lastWarning;
  private static final NamedTextColor GOLD = NamedTextColor.GOLD;

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

  private void title(Player p, String heading, String subtitle, NamedTextColor color) {
    p.showTitle(
        Title.title(
            Component.text(heading, color),
            Component.text(subtitle, NamedTextColor.WHITE),
            Title.Times.times(
                Duration.ofMillis(250), Duration.ofMillis(1250), Duration.ofMillis(500))));
  }

  /** The turn's instruction stays on screen for the whole turn. */
  private void instruct(
      Player p, String heading, String subtitle, NamedTextColor color, long stayMillis) {
    p.showTitle(
        Title.title(
            Component.text(heading, color),
            Component.text(subtitle, NamedTextColor.WHITE),
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
    boolean night = WerewolfPresentation.secret(phase);
    boolean dusk = WerewolfPresentation.dusk(s.ambienceNight, phase);
    boolean dawn = WerewolfPresentation.dawn(s.ambienceNight, phase);
    s.ambienceNight = night;
    List<Component> news = news(s, game, phase);
    s.nextHeartbeatAt = System.currentTimeMillis() + 4000;
    boolean enabled = s.config.werewolf().ambienceEnabled();
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
              false,
              player -> {
                player.clearTitle();
                news.forEach(player::sendMessage);
                if (opening) plugin.gui.arm(player, game.role(id));
                var view = WerewolfPresentation.view(game, id);
                NamedTextColor roleColor = CompassGUI.roleColor(game.role(id));
                if (opening) {
                  player.sendMessage(Component.text("Your story begins", GOLD));
                  player.sendMessage(
                      Component.text("Your secret role: " + game.role(id), roleColor));
                  if (enabled) {
                    sound(player, Sound.BLOCK_NOTE_BLOCK_HARP, .35f, 1.1f);
                    title(
                        player,
                        "Your story begins",
                        "Open your compass to see your secret role",
                        GOLD);
                  }
                }
                if (dusk) {
                  player.sendMessage(
                      Component.text(
                          "Night falls — The village falls asleep", NamedTextColor.BLUE));
                  if (enabled) {
                    sound(player, Sound.BLOCK_BELL_USE, .35f, .7f);
                    Consumer<Player> cue =
                        target -> {
                          title(
                              target,
                              "Night falls",
                              "The village falls asleep",
                              NamedTextColor.BLUE);
                        };
                    if (opening) delayed(s, player, event, epoch, 40, cue);
                    else cue.accept(player);
                  }
                } else if (dawn) {
                  player.sendMessage(Component.text("Dawn breaks — The village awakens", GOLD));
                  if (enabled) {
                    sound(player, Sound.BLOCK_NOTE_BLOCK_CHIME, .35f, 1.2f);
                    title(player, "Dawn breaks", "The village awakens", GOLD);
                  }
                } else if (phase.equals("DAY_VOTE")) {
                  player.sendMessage(
                      Component.text("Choose wisely — Vote with your compass", GOLD));
                  if (enabled) {
                    sound(player, Sound.BLOCK_NOTE_BLOCK_HAT, .2f, 1f);
                    title(player, "Choose wisely", "The village must decide", GOLD);
                  }
                }
                WerewolfPresentation.Cue awakening = WerewolfPresentation.awakening(phase);
                if (view.actor() && awakening != null) {
                  player.sendMessage(
                      Component.text(awakening.title() + " — " + awakening.subtitle(), roleColor));
                  {
                    long delay = opening ? dusk ? 80 : 40 : dusk ? 40 : 0;
                    long stay = Math.max(2000, game.seconds() * 1000L - delay * 50);
                    Consumer<Player> cue =
                        target -> {
                          instruct(target, awakening.title(), awakening.subtitle(), roleColor, stay);
                          if (enabled)
                            switch (phase) {
                            case "CUPID" -> sound(target, Sound.BLOCK_NOTE_BLOCK_HARP, .3f, 1.5f);
                            case "GUARD" ->
                                sound(target, Sound.BLOCK_ENCHANTMENT_TABLE_USE, .25f, 1f);
                            case "SEER" ->
                                sound(target, Sound.BLOCK_AMETHYST_BLOCK_CHIME, .3f, 1.2f);
                            case "WITCH" -> sound(target, Sound.ITEM_BOTTLE_FILL, .25f, .8f);
                            case "HUNTER_SHOT" -> sound(target, Sound.ENTITY_ARROW_SHOOT, .2f, .8f);
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

  /** Public story beats: who just died (with their role) and which role wakes or sleeps. */
  private List<Component> news(ArenaSession s, WerewolfGame game, String phase) {
    List<Component> lines = new ArrayList<>(deaths(s, game));
    String previous = s.ambiencePhase;
    s.ambiencePhase = phase;
    if (!previous.equals(phase)) {
      String sleep = WerewolfPresentation.sleepLine(previous);
      if (sleep != null) lines.add(Component.text(sleep, NamedTextColor.GRAY));
      String wake = WerewolfPresentation.wakeLine(phase);
      if (wake != null)
        lines.add(
            Component.text(
                wake, CompassGUI.roleColor(WerewolfPresentation.turnRole(phase))));
    }
    return lines;
  }

  private List<Component> deaths(ArenaSession s, WerewolfGame game) {
    List<Component> lines = new ArrayList<>();
    for (var entry : s.players.entrySet())
      if (!game.alive().contains(entry.getKey()) && s.revealed.add(entry.getKey()))
        lines.add(
            Component.text(
                WerewolfPresentation.deathLine(entry.getValue(), game.role(entry.getKey())),
                NamedTextColor.DARK_RED));
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

  private void hud(ArenaSession s, Player p) {
    if (!(s.game instanceof WerewolfGame game)) return;
    var view = WerewolfPresentation.view(game, p.getUniqueId());
    long seconds = Math.max(0, (s.phaseEndsAt - System.currentTimeMillis() + 999) / 1000);
    NamedTextColor color =
        view.actor() ? CompassGUI.roleColor(game.role(p.getUniqueId())) : NamedTextColor.GRAY;
    Component line = Component.text(view.instruction(), color);
    if (view.timerVisible())
      line =
          line.append(
              Component.text(" · " + seconds + "s", seconds <= 5 ? NamedTextColor.RED : GOLD));
    p.sendActionBar(line);
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
                player.sendMessage(Component.text("Game over — " + result, GOLD));
                if (s.config.werewolf().ambienceEnabled()) {
                  sound(player, Sound.ENTITY_PLAYER_LEVELUP, .3f, .85f);
                  title(player, "Game over", result, GOLD);
                }
              }));
    }
    return CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new));
  }

  void clearNow(Player p, ArenaSession s) {
    if (s == null || !(s.game instanceof WerewolfGame)) return;
    p.sendActionBar(Component.empty());
    plugin.village.unconceal(p, s);
    if (p.getOpenInventory().getTopInventory().getHolder() instanceof CompassMenuHolder)
      p.closeInventory();
    if (!s.ambienceFinale) p.clearTitle();
  }
}

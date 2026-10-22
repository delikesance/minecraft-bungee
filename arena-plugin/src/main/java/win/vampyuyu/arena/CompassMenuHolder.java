package win.vampyuyu.arena;

import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.inventory.*;

public final class CompassMenuHolder implements InventoryHolder {
  final UUID viewer;
  final String arenaId;
  final boolean skinned;
  int leaveSlot = 53;
  /** Phase this menu was drawn for; clicks are ignored once the game has moved on. */
  String phase = "";
  final Map<Integer, UUID> targets = new HashMap<>();
  final Map<Integer, String> abilities = new HashMap<>();

  /** Role menu this window was opened as (its painted title), or null for the plain compass. */
  WerewolfMenus.Kind kind;

  /** True for the werewolf village overview (WerewolfMenus.village). */
  boolean village;

  /** Role-menu actions by raw slot: select:/aim:<uuid>, use:<ability> or leave. */
  final Map<Integer, String> clicks = new HashMap<>();

  private final Inventory inventory;

  CompassMenuHolder(UUID viewer, String arenaId, boolean skinned, Component title) {
    this.viewer = viewer;
    this.arenaId = arenaId;
    this.skinned = skinned;
    inventory = Bukkit.createInventory(this, 54, title);
  }

  @Override
  public Inventory getInventory() {
    return inventory;
  }
}

package win.vampyuyu.arena;

import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.inventory.*;

public final class CompassMenuHolder implements InventoryHolder {
  final UUID viewer;
  final String arenaId;
  final boolean skinned;
  final Map<Integer, UUID> targets = new HashMap<>();
  final Map<Integer, String> abilities = new HashMap<>();
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

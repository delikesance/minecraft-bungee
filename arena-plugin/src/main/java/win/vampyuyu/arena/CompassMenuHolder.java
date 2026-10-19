package win.vampyuyu.arena;

import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.inventory.*;

public final class CompassMenuHolder implements InventoryHolder {
  final UUID viewer;
  final String arenaId;
  final Map<Integer, UUID> targets = new HashMap<>();
  final Map<Integer, String> abilities = new HashMap<>();
  private final Inventory inventory;

  CompassMenuHolder(UUID viewer, String arenaId) {
    this.viewer = viewer;
    this.arenaId = arenaId;
    inventory = Bukkit.createInventory(this, 54, Component.text("Vampyuyu · Arena compass"));
  }

  @Override
  public Inventory getInventory() {
    return inventory;
  }
}

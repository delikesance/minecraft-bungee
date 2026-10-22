package win.vampyuyu.network;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Item;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** The vault lends a player's inventory to a menu and must always hand it back intact. */
public class MenuVaultMockTest {
  private ServerMock server;
  private JavaPlugin plugin;
  private MenuVault vault;

  private static final class TestMenu implements MenuVault.Menu {
    private final Inventory inventory;

    TestMenu(int size) {
      inventory = Bukkit.createInventory(this, size);
    }

    @Override
    public Inventory getInventory() {
      return inventory;
    }
  }

  @Before
  public void boot() {
    server = MockBukkit.mock();
    plugin = MockBukkit.createMockPlugin();
    vault = new MenuVault(plugin);
  }

  @After
  public void stop() {
    MockBukkit.unmock();
  }

  private Path file(PlayerMock p) {
    return plugin.getDataFolder().toPath().resolve("vault").resolve(p.getUniqueId() + ".bin");
  }

  private PlayerMock playerWithItems() {
    PlayerMock p = server.addPlayer();
    p.getInventory().setItem(0, new ItemStack(Material.DIAMOND));
    p.getInventory().setItem(20, new ItemStack(Material.APPLE, 5));
    p.getInventory().setItem(35, new ItemStack(Material.COBBLESTONE, 64));
    return p;
  }

  private void assertOriginal(PlayerMock p) {
    assertEquals(Material.DIAMOND, p.getInventory().getItem(0).getType());
    assertEquals(5, p.getInventory().getItem(20).getAmount());
    assertEquals(64, p.getInventory().getItem(35).getAmount());
    for (int i = 0; i < 36; i++) {
      ItemStack item = p.getInventory().getItem(i);
      if (item != null) assertNotEquals("menu button left behind in slot " + i, Material.ARROW, item.getType());
    }
  }

  private void close(PlayerMock p, TestMenu menu, InventoryCloseEvent.Reason reason) {
    p.openInventory(menu.getInventory());
    server.getPluginManager().callEvent(new InventoryCloseEvent(p.getOpenInventory(), reason));
  }

  @Test
  public void showSavesTheInventoryClearsItAndFillsTheLowerHalf() {
    PlayerMock p = playerWithItems();
    TestMenu menu = new TestMenu(27);
    Map<Integer, ItemStack> lower = new HashMap<>();
    lower.put(MenuVault.hotbar(27, 6), new ItemStack(Material.ARROW));
    vault.show(p, menu.getInventory(), lower);
    assertTrue("the real inventory is saved on disk first", Files.exists(file(p)));
    assertNull(p.getInventory().getItem(0));
    assertNull(p.getInventory().getItem(20));
    assertEquals(Material.ARROW, p.getInventory().getItem(6).getType());
  }

  @Test
  public void closingTheMenuGivesEverythingBackAndRemovesTheFile() {
    PlayerMock p = playerWithItems();
    TestMenu menu = new TestMenu(27);
    vault.show(p, menu.getInventory(), Map.of(MenuVault.hotbar(27, 6), new ItemStack(Material.ARROW)));
    close(p, menu, InventoryCloseEvent.Reason.PLAYER);
    assertOriginal(p);
    assertFalse(Files.exists(file(p)));
  }

  @Test
  public void movingBetweenMenusNeverSavesTheButtonsAsRealItems() {
    PlayerMock p = playerWithItems();
    TestMenu first = new TestMenu(54), second = new TestMenu(27);
    vault.show(p, first.getInventory(), Map.of(MenuVault.hotbar(54, 6), new ItemStack(Material.ARROW)));
    // Opening the next menu closes the previous one with OPEN_NEW: nothing is handed back yet.
    server.getPluginManager().callEvent(new InventoryCloseEvent(p.getOpenInventory(), InventoryCloseEvent.Reason.OPEN_NEW));
    assertNull("still held between menus", p.getInventory().getItem(0));
    vault.show(p, second.getInventory(), Map.of(MenuVault.hotbar(27, 0), new ItemStack(Material.ARROW)));
    close(p, second, InventoryCloseEvent.Reason.PLUGIN);
    assertOriginal(p);
  }

  @Test
  public void quittingWithAMenuOpenRestoresTheInventory() {
    PlayerMock p = playerWithItems();
    vault.show(p, new TestMenu(27).getInventory(), Map.of(MenuVault.hotbar(27, 1), new ItemStack(Material.ARROW)));
    server.getPluginManager().callEvent(new PlayerQuitEvent(p, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
    assertOriginal(p);
    assertFalse(Files.exists(file(p)));
  }

  @Test
  public void giveIsSafeWhenNothingIsHeld() {
    PlayerMock p = playerWithItems();
    vault.give(p);
    assertOriginal(p);
  }

  @Test
  public void aCrashLeavesAFileThatIsRestoredOnTheNextGive() {
    PlayerMock p = playerWithItems();
    vault.show(p, new TestMenu(27).getInventory(), Map.of(MenuVault.hotbar(27, 2), new ItemStack(Material.ARROW)));
    // Simulate a restart: a fresh vault has no memory, only the file on disk.
    MenuVault restarted = new MenuVault(plugin);
    restarted.give(p);
    assertOriginal(p);
    assertFalse(Files.exists(file(p)));
  }

  @Test
  public void buttonsNeverDropAsLootButTheRealItemsDoWhenKeepInventoryIsOff() {
    PlayerMock p = playerWithItems();
    vault.show(p, new TestMenu(27).getInventory(), Map.of(MenuVault.hotbar(27, 3), new ItemStack(Material.ARROW)));
    List<ItemStack> drops = new ArrayList<>();
    for (ItemStack item : p.getInventory().getContents()) if (item != null) drops.add(item);
    PlayerDeathEvent death =
        new PlayerDeathEvent(p, DamageSource.builder(DamageType.GENERIC).build(), drops, 0, Component.empty(), false);
    death.setKeepInventory(false);
    server.getPluginManager().callEvent(death);
    assertTrue(death.getDrops().stream().noneMatch(i -> i.getType() == Material.ARROW));
    assertTrue(death.getDrops().stream().anyMatch(i -> i.getType() == Material.DIAMOND));
    assertTrue(death.getDrops().stream().anyMatch(i -> i.getType() == Material.COBBLESTONE && i.getAmount() == 64));
    assertFalse("nothing left to restore after death", Files.exists(file(p)));
  }

  @Test
  public void withKeepInventoryTheRealItemsComeBackImmediately() {
    PlayerMock p = playerWithItems();
    vault.show(p, new TestMenu(27).getInventory(), Map.of(MenuVault.hotbar(27, 3), new ItemStack(Material.ARROW)));
    PlayerDeathEvent death =
        new PlayerDeathEvent(p, DamageSource.builder(DamageType.GENERIC).build(), new ArrayList<>(), 0, Component.empty(), false);
    death.setKeepInventory(true);
    server.getPluginManager().callEvent(death);
    assertOriginal(p);
  }

  @Test
  public void pickingUpItemsIsBlockedWhileTheMenuHoldsTheInventory() {
    PlayerMock p = playerWithItems();
    vault.show(p, new TestMenu(27).getInventory(), Map.of(MenuVault.hotbar(27, 3), new ItemStack(Material.ARROW)));
    Item item = p.getWorld().dropItem(p.getLocation(), new ItemStack(Material.STONE));
    PlayerAttemptPickupItemEvent pickup = new PlayerAttemptPickupItemEvent(p, item);
    server.getPluginManager().callEvent(pickup);
    assertTrue(pickup.isCancelled());
    close(p, new TestMenu(27), InventoryCloseEvent.Reason.PLAYER);
    PlayerAttemptPickupItemEvent later = new PlayerAttemptPickupItemEvent(p, item);
    server.getPluginManager().callEvent(later);
    assertFalse(later.isCancelled());
  }
}

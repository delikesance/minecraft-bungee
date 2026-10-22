package win.vampyuyu.network;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class MenuVaultTest {
  @Test
  public void rawSlotsBelowA54SlotWindowMapToInventorySlots() {
    assertEquals(9, MenuVault.inventoryIndex(54, 54));
    assertEquals(35, MenuVault.inventoryIndex(54, 80));
    assertEquals(0, MenuVault.inventoryIndex(54, 81));
    assertEquals(8, MenuVault.inventoryIndex(54, 89));
  }

  @Test
  public void rawSlotsBelowA27SlotWindowMapToInventorySlots() {
    assertEquals(9, MenuVault.inventoryIndex(27, 27));
    assertEquals(35, MenuVault.inventoryIndex(27, 53));
    assertEquals(0, MenuVault.inventoryIndex(27, 54));
    assertEquals(8, MenuVault.inventoryIndex(27, 62));
  }

  @Test
  public void hotbarButtonsLandOnTheHotbarRow() {
    for (int top : new int[] {27, 54})
      for (int i = 0; i < 9; i++)
        assertEquals(i, MenuVault.inventoryIndex(top, MenuVault.hotbar(top, i)));
  }
}

package win.vampyuyu.arena;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CompassSlotsTest {
  @Test
  public void rawSlotsBelowTheChestMapToTheRightInventorySlots() {
    assertEquals(9, CompassGUI.inventoryIndex(54));
    assertEquals(35, CompassGUI.inventoryIndex(80));
    assertEquals(0, CompassGUI.inventoryIndex(81));
    assertEquals(7, CompassGUI.inventoryIndex(88));
    assertEquals(8, CompassGUI.inventoryIndex(89));
  }

  @Test
  public void roleMenuHotbarHoldsCardTimerAndLockInSlotsOneFiveAndNine() {
    assertEquals(0, CompassGUI.inventoryIndex(WerewolfMenus.CARD));
    assertEquals(4, CompassGUI.inventoryIndex(WerewolfMenus.TIMER));
    assertEquals(8, CompassGUI.inventoryIndex(WerewolfMenus.LOCK));
    assertEquals("middle inventory row", 19, CompassGUI.inventoryIndex(WerewolfMenus.box(1)));
  }

  @Test
  public void menuSlotsNeverTouchTheClawSlot() {
    for (int raw : new int[] {66, 67, 68, 81, 83, 84, 85, 88})
      org.junit.Assert.assertNotEquals(8, CompassGUI.inventoryIndex(raw));
  }
}

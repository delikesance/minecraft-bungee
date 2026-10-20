"""Shared geometry of the painted menu backgrounds (used by the renderer and the tests).

A background covers a whole container window (176 GUI px wide). Minecraft packs bitmap-font glyphs
into 256x256 atlas pages, so each background is cut into COLS x rows tiles of at most 176x222
texture pixels (4 texture px per GUI px) and drawn as one title string of tile characters.
"""
GUI_W = 176
COLS = 4
PX_PER_GUI = 4
CSS_PER_GUI = 3          # the art is authored at 3 CSS px per GUI px
ASCENT = 13              # baseline of the vanilla title: the window top sits 13 px above it

# name -> (first tile codepoint, window height in GUI px, tile rows)
BACKGROUNDS = {
    "menu_lobby":   (0xE100, 168, 4),   # 27-slot window
    "menu_setup":   (0xE200, 222, 6),   # 54-slot window
    "menu_list":    (0xE300, 222, 6),
    "menu_detail":  (0xE400, 168, 4),
    "menu_confirm": (0xE500, 168, 4),
}

TILE_W = GUI_W // COLS   # 44 GUI px
SHIFT_BACK = 0xE000      # -8: from the title origin to the window's left edge
SHIFT_FORWARD = 0xE001   # back to x=44 where the dynamic title text starts
ROW_BACK = 0xE002        # -(176 + 1): return to the left edge after a tile row
SEAM = 0xE003            # -1: cancels the +1 advance every bitmap glyph adds
ADVANCES = {SHIFT_BACK: -8, SHIFT_FORWARD: -(GUI_W + 1 - 44), ROW_BACK: -(GUI_W + 1), SEAM: -1}

def tile_height(gui_h, rows):
    assert gui_h % rows == 0
    return gui_h // rows

def tile_file(name, r, c):
    return f"{name}_{r}{c}"

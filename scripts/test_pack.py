#!/usr/bin/env python3
"""Structural checks for resource-pack/pack that do not need a Minecraft client."""
import hashlib, json, pathlib, struct, sys, tempfile, unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent
PACK = ROOT / "resource-pack" / "pack"
sys.path.insert(0, str(ROOT / "scripts"))
sys.path.insert(0, str(ROOT / "resource-pack"))
import build_pack  # noqa: E402
import layout  # noqa: E402

def png_size(path):
    data = path.read_bytes()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", f"{path} is not a PNG"
    return struct.unpack(">II", data[16:24])

class PackTest(unittest.TestCase):
    def test_mcmeta_supports_1_21_9_and_newer(self):
        meta = json.loads((PACK / "pack.mcmeta").read_text())["pack"]
        self.assertEqual(meta["min_format"], 69)
        self.assertGreater(meta["max_format"], 84, "must cover the 26.x releases")
        self.assertTrue(meta["description"])

    def test_font_tiles_fit_the_glyph_atlas_and_tile_each_window_exactly(self):
        font = json.loads((PACK / "assets/vampyuyu/font/menus.json").read_text())
        advances = next(p for p in font["providers"] if p["type"] == "space")["advances"]
        self.assertEqual({ord(k): v for k, v in advances.items()}, layout.ADVANCES)
        self.assertEqual(layout.ADVANCES[layout.SHIFT_BACK], -8, "title origin is x=8")
        self.assertEqual(layout.ADVANCES[layout.SHIFT_FORWARD], -(176 + 1 - 44), "dynamic text starts at x=44")
        # each bitmap glyph advances width+1; SEAM cancels it between tiles and ROW_BACK rewinds a row
        row_width = layout.COLS * (layout.TILE_W + 1) + (layout.COLS - 1) * layout.ADVANCES[layout.SEAM]
        self.assertEqual(row_width, -layout.ADVANCES[layout.ROW_BACK])
        tiles = {}
        for provider in font["providers"]:
            if provider["type"] != "bitmap":
                continue
            namespace, _, rel = provider["file"].partition(":")
            texture = PACK / "assets" / namespace / "textures" / rel
            self.assertTrue(texture.is_file(), texture)
            width, height = png_size(texture)
            # Minecraft stitches font glyphs into 256x256 pages: a larger glyph renders as missing.
            self.assertLessEqual(max(width, height), 256, texture.name)
            self.assertEqual(width, layout.TILE_W * layout.PX_PER_GUI, "tile is exactly 44 GUI px wide")
            self.assertEqual(height, provider["height"] * layout.PX_PER_GUI, "texture px match the GUI scale")
            self.assertEqual(len(provider["chars"]), 1)
            tiles[ord(provider["chars"][0])] = provider
        for name, (base, gui_h, rows) in layout.BACKGROUNDS.items():
            th = layout.tile_height(gui_h, rows)
            for r in range(rows):
                for c in range(layout.COLS):
                    provider = tiles[base + r * layout.COLS + c]
                    self.assertEqual(provider["height"], th)
                    self.assertEqual(provider["ascent"], layout.ASCENT - r * th, "row r sits r tile-heights below the top")
                    self.assertEqual(provider["file"], f"vampyuyu:font/{layout.tile_file(name, r, c)}.png")
            self.assertEqual(rows * th, gui_h)
        self.assertEqual(len(tiles), sum(r * layout.COLS for _, _, r in layout.BACKGROUNDS.values()))

    def test_inventory_label_is_blank_in_every_shipped_language(self):
        langs = sorted((PACK / "assets/minecraft/lang").glob("*.json"))
        self.assertIn("en_us", [p.stem for p in langs])
        for lang in langs:
            self.assertEqual(json.loads(lang.read_text()), {"container.inventory": ""}, lang.name)

    def test_every_icon_has_texture_model_and_definition(self):
        icons = sorted(p.stem for p in (PACK / "assets/vampyuyu/textures/item/icon").glob("*.png"))
        self.assertGreaterEqual(len(icons), 20)
        for name in icons:
            definition = json.loads((PACK / f"assets/vampyuyu/items/icon/{name}.json").read_text())
            self.assertEqual(definition["model"]["model"], f"vampyuyu:item/icon/{name}")
            model = json.loads((PACK / f"assets/vampyuyu/models/item/icon/{name}.json").read_text())
            self.assertEqual(model["textures"]["layer0"], f"vampyuyu:item/icon/{name}")

    def test_invisible_item_model_chain(self):
        item = json.loads((PACK / "assets/vampyuyu/items/blank.json").read_text())
        self.assertEqual(item["model"]["model"], "vampyuyu:item/blank")
        model = json.loads((PACK / "assets/vampyuyu/models/item/blank.json").read_text())
        self.assertEqual(model["textures"]["layer0"], "vampyuyu:item/blank")
        self.assertTrue((PACK / "assets/vampyuyu/textures/item/blank.png").is_file())

    def test_build_is_reproducible_and_complete(self):
        with tempfile.TemporaryDirectory() as a, tempfile.TemporaryDirectory() as b:
            first, digest = build_pack.build(a)
            second, again = build_pack.build(b)
            self.assertEqual(digest, again)
            self.assertEqual(hashlib.sha1(first.read_bytes()).hexdigest(), digest)
            self.assertEqual(first.read_bytes(), second.read_bytes())
            self.assertRegex((pathlib.Path(a) / "vampyuyu-menus.sha1").read_text().strip(), r"^[0-9a-f]{40}$")
            self.assertLess(first.stat().st_size, 8 << 20, "keep the optional pack small")

if __name__ == "__main__":
    unittest.main()

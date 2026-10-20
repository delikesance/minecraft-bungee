#!/usr/bin/env python3
"""Structural checks for resource-pack/pack that do not need a Minecraft client."""
import hashlib, json, pathlib, struct, sys, tempfile, unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent
PACK = ROOT / "resource-pack" / "pack"
sys.path.insert(0, str(ROOT / "scripts"))
import build_pack  # noqa: E402

def png_size(path):
    data = path.read_bytes()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", f"{path} is not a PNG"
    return struct.unpack(">II", data[16:24])

class PackTest(unittest.TestCase):
    def test_mcmeta_targets_1_21_9(self):
        meta = json.loads((PACK / "pack.mcmeta").read_text())["pack"]
        self.assertEqual((meta["min_format"], meta["max_format"]), (69, 69))
        self.assertTrue(meta["description"])

    def test_font_providers_reference_real_textures(self):
        font = json.loads((PACK / "assets/vampyuyu/font/menus.json").read_text())
        glyphs = set()
        for provider in font["providers"]:
            if provider["type"] == "space":
                self.assertEqual(provider["advances"]["\ue000"], -8, "title origin is x=8")
                continue
            self.assertEqual(provider["type"], "bitmap")
            namespace, _, rel = provider["file"].partition(":")
            texture = PACK / "assets" / namespace / "textures" / rel
            self.assertTrue(texture.is_file(), texture)
            width, height = png_size(texture)
            self.assertIn(provider["height"], (168, 222), "27- or 54-slot window")
            self.assertEqual(len(provider["chars"]), 1, "one glyph per background")
            self.assertEqual(provider["ascent"], 13, "texture top must sit on the window top")
            # The glyph is scaled so its height becomes `height`; the width must then be exactly 176 GUI px.
            self.assertAlmostEqual(width * provider["height"] / height, 176, delta=0.6)
            glyphs.update(provider["chars"])
        advances = next(p for p in font["providers"] if p["type"] == "space")["advances"]
        self.assertEqual(advances["\ue001"], -(176 + 1 - 44), "dynamic title text starts at x=44, after the painted brand")
        for glyph in "\ue100\ue101\ue102\ue103\ue104":
            self.assertIn(glyph, glyphs)

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

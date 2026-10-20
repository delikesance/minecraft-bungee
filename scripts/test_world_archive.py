import gzip
import json
from pathlib import Path
import stat
import struct
import tempfile
import unittest
from unittest.mock import patch
import zipfile
import world_archive as a


def string(text):
    b = text.encode()
    return struct.pack(">H", len(b)) + b


def level(version="1.21.9"):
    return gzip.compress(
        b"\x0a\0\0\x0a"
        + string("Data")
        + b"\x0a"
        + string("Version")
        + b"\x08"
        + string("Name")
        + string(version)
        + b"\0\0\0"
    )


class Archives(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.data = self.root / "data"
        self.data.mkdir()
        (self.data / "world/playerdata").mkdir(parents=True)
        (self.data / "world/level.dat").write_bytes(level())
        (self.data / "world/playerdata/user.dat").write_bytes(b"inventory")
        (self.data / "plugins/Claims").mkdir(parents=True)
        (self.data / "plugins/Claims/claims.db").write_bytes(b"claim records")
        (self.data / "plugins/network-display.jar").write_bytes(b"managed")
        (self.data / "plugins/claim.jar").write_bytes(b"custom plugin")
        (self.data / "server.properties").write_text("rcon.password=target-secret")
        (self.data / "config").mkdir()
        (self.data / "config/paper-global.yml").write_text("target forwarding")
        self.zip = self.root / "creative.zip"

    def export(self):
        return a.write_zip(self.data, "creative", "1.21.9", self.zip)

    def test_roundtrip_preserves_world_claims_and_target_network_settings(self):
        m = self.export()
        self.assertNotIn("server.properties", m["files"])
        self.assertNotIn("plugins/network-display.jar", m["files"])
        self.assertIn("plugins/claim.jar", m["files"])
        self.assertEqual(stat.S_IMODE(self.zip.stat().st_mode), 0o600)
        plan = a.inspect_zip(self.zip, "creative", "1.21.9")
        stage = self.data / ".archive-staging-test"
        stage.mkdir()
        a.stage_zip(self.zip, plan, stage)
        (self.data / "world/playerdata/user.dat").write_bytes(b"new state")
        a.swap_roots(self.data, stage, plan["roots"])
        self.assertEqual(
            (self.data / "world/playerdata/user.dat").read_bytes(), b"inventory"
        )
        self.assertEqual(
            (self.data / "plugins/Claims/claims.db").read_bytes(), b"claim records"
        )
        self.assertEqual(
            (self.data / "config/paper-global.yml").read_text(), "target forwarding"
        )
        self.assertTrue(self.zip.exists())

    def test_refuse_existing_output_internal_output_wrong_server_and_version(self):
        self.export()
        with self.assertRaises(ValueError):
            self.export()
        with self.assertRaises(ValueError):
            a.write_zip(self.data, "creative", "1.21.9", self.data / "world/bad.zip")
        for server, version in [("lobby", "1.21.9"), ("creative", "1.21.4")]:
            with self.assertRaises(ValueError):
                a.inspect_zip(self.zip, server, version)

    def test_traversal_links_duplicate_and_special_files_rejected(self):
        for i, name in enumerate(
            ["../escape", "/tmp/escape", "world/../escape", "a\\escape", "a//escape"]
        ):
            p = self.root / f"bad{i}.zip"
            with zipfile.ZipFile(p, "w") as z:
                z.writestr(name, b"bad")
            with self.assertRaises(ValueError):
                a.inspect_zip(p, "creative", "1.21.9")
        for kind in [stat.S_IFLNK, stat.S_IFIFO]:
            p = self.root / f"{kind}.zip"
            info = zipfile.ZipInfo("world/link")
            info.external_attr = (kind | 0o777) << 16
            with zipfile.ZipFile(p, "w") as z:
                z.writestr(info, b"../escape")
            with self.assertRaises(ValueError):
                a.inspect_zip(p, "creative", "1.21.9")
        p = self.root / "duplicate.zip"
        with zipfile.ZipFile(p, "w") as z:
            z.writestr("world/a", b"a")
            z.writestr("world/A", b"b")
        with self.assertRaises(ValueError):
            a.inspect_zip(p, "creative", "1.21.9")

    def test_raw_world_zip_reads_real_version_and_changes_only_selected_world(self):
        p = self.root / "raw.zip"
        with zipfile.ZipFile(p, "w") as z:
            z.writestr("My World/level.dat", level())
            z.writestr("My World/region/chunk.mca", b"world")
        plan = a.inspect_zip(p, "creative", "1.21.9", "world")
        self.assertEqual(plan["kind"], "world")
        with self.assertRaises(ValueError):
            a.inspect_zip(p, "creative", "1.21.4")
        with self.assertRaises(ValueError):
            a.inspect_zip(p, "creative", "1.21.9", "config")
        with self.assertRaises(ValueError):
            a.inspect_zip(p, "creative", "1.21.9", "server.properties")

    def test_checksum_corruption_rejected_before_swap(self):
        self.export()
        corrupt = self.root / "corrupt.zip"
        with zipfile.ZipFile(self.zip) as old, zipfile.ZipFile(corrupt, "w") as z:
            for f in old.infolist():
                raw = old.read(f)
                if f.filename.endswith("user.dat"):
                    raw = b"corrupted"
                z.writestr(f, raw)
        plan = a.inspect_zip(corrupt, "creative", "1.21.9")
        stage = self.root / "stage"
        stage.mkdir()
        with self.assertRaises(ValueError):
            a.stage_zip(corrupt, plan, stage)
        self.assertEqual(
            (self.data / "world/playerdata/user.dat").read_bytes(), b"inventory"
        )

    def test_failed_install_rolls_back_original_world(self):
        self.export()
        plan = a.inspect_zip(self.zip, "creative", "1.21.9")
        stage = self.data / ".archive-test"
        stage.mkdir()
        a.stage_zip(self.zip, plan, stage)
        real = a.os.rename

        def fail(src, dest):
            if src == stage / "world":
                raise OSError("injected rename failure")
            return real(src, dest)

        with patch.object(a.os, "rename", side_effect=fail):
            with self.assertRaises(OSError):
                a.swap_roots(self.data, stage, plan["roots"])
        self.assertEqual(
            (self.data / "world/playerdata/user.dat").read_bytes(), b"inventory"
        )
        self.assertEqual(
            (self.data / "plugins/Claims/claims.db").read_bytes(), b"claim records"
        )

    def test_export_resumes_only_a_previously_running_selected_service(self):
        import os

        original_cwd = Path.cwd()
        self.addCleanup(os.chdir, original_cwd)
        for running in (True, False):
            folder = self.root / ("case" + str(running))
            folder.mkdir()
            data = folder / "creative"
            data.mkdir()
            (data / "world").mkdir()
            (data / "world/level.dat").write_bytes(level())
            (data / ".network-version").write_text("1.21.9")
            cfg = {
                "services": {
                    "tools": {"volumes": [{"source": str(folder), "target": "/state"}]},
                    "creative": {"environment": {"VERSION": "1.21.9"}},
                }
            }
            calls = []
            stopped = False

            def fake(*args, capture=True):
                nonlocal stopped
                calls.append(args)
                if "config" in args:
                    return json.dumps(cfg)
                if "ps" in args:
                    return "lobby" + ("\ncreative" if running and not stopped else "")
                if "stop" in args:
                    stopped = True
                return ""

            with patch.object(a, "ROOT", folder), patch.object(
                a, "command", side_effect=fake
            ), patch.dict(
                os.environ,
                {"COMPOSE": "compose.local.yaml", "SERVER": "creative", "OUT": ""},
            ):
                a.main("export")
            starts = [c for c in calls if "start" in c]
            self.assertEqual(len(starts), int(running))
            if starts:
                self.assertEqual(starts[0][-1], "creative")
            self.assertTrue(list((folder / "backups").glob("*.zip")))

    def test_cleanup_permission_error_does_not_prevent_resume_after_restore(self):
        import os

        original_cwd = Path.cwd()
        self.addCleanup(os.chdir, original_cwd)
        self.export()
        self.data.rename(self.root / "creative")
        self.data = self.root / "creative"
        (self.data / ".network-version").write_text("1.21.9")
        cfg = {
            "services": {
                "tools": {"volumes": [{"source": str(self.root), "target": "/state"}]},
                "creative": {"environment": {"VERSION": "1.21.9"}},
            }
        }
        calls = []
        stopped = False

        def fake(*args, capture=True):
            nonlocal stopped
            calls.append(args)
            if "config" in args:
                return json.dumps(cfg)
            if "ps" in args:
                return "" if stopped else "creative"
            if "stop" in args:
                stopped = True
            return ""

        with patch.object(a, "ROOT", self.root), patch.object(
            a, "command", side_effect=fake
        ), patch.object(
            a.shutil, "rmtree", side_effect=PermissionError("root-owned old directory")
        ), patch.dict(
            os.environ,
            {
                "COMPOSE": "compose.local.yaml",
                "SERVER": "creative",
                "ZIP": str(self.zip),
                "OUT": "",
            },
        ):
            a.main("import")
        self.assertTrue(any("start" in c for c in calls))
        self.assertEqual(
            (self.data / "world/playerdata/user.dat").read_bytes(), b"inventory"
        )


if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
"""Portable Minecraft ZIPs. Standard library only; never source .env as shell code."""

import datetime
import contextlib
import gzip
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import sqlite3
import stat
import struct
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SERVERS = {"lobby", "creative", "survival", "panel"}
ROOT_FILES = {
    "ops.json",
    "whitelist.json",
    "banned-players.json",
    "banned-ips.json",
    "usercache.json",
    "permissions.yml",
    ".vampyuyu-palace",
}
MANAGED_JARS = {"lobby-selector.jar", "network-display.jar", "nexus-arena-engine.jar"}
LIMIT, MAX_FILES = 40 << 30, 200000


def require(ok, message):
    if not ok:
        raise ValueError(message)


def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def world_name(name):
    require(
        bool(re.fullmatch(r"[A-Za-z0-9_-][A-Za-z0-9_.-]{0,63}", name))
        and name
        not in {
            "plugins",
            "config",
            "logs",
            "cache",
            "versions",
            "libraries",
            "server.properties",
            "bukkit.yml",
            "spigot.yml",
            "server.jar",
            "paper.jar",
        },
        "Invalid world folder name",
    )
    return name


def roots_for(data, server):
    if server == "panel":
        return sorted(
            p.name for p in data.iterdir() if not p.name.startswith(".archive-")
        )
    worlds = [
        p.name
        for p in data.iterdir()
        if p.is_dir() and not p.is_symlink() and (p / "level.dat").is_file()
    ]
    return sorted(
        set(worlds)
        | ({"plugins"} if (data / "plugins").is_dir() else set())
        | {n for n in ROOT_FILES if (data / n).exists()}
    )


def write_zip(data, server, version, output):
    """Caller must stop the writer first. Private, exclusive output with atomic completion."""
    require(not output.exists(), "Output already exists")
    require(
        not output.resolve().is_relative_to(data.resolve()),
        "Export ZIP must be outside the server data directory",
    )
    require(server in SERVERS, "Invalid server")
    selected = roots_for(data, server)
    require(bool(selected), "No saved data to export")
    if server == "panel":
        require(
            (data / "panel.db").is_file(), "Panel database has not been initialized"
        )
    else:
        worlds = [name for name in selected if (data / name / "level.dat").is_file()]
        require(worlds, "No saved worlds to export")
        for name in worlds:
            require(
                level_version((data / name / "level.dat").read_bytes()) == version,
                "Saved world version differs from configured version",
            )
    output.parent.mkdir(parents=True, exist_ok=True)
    partial = output.with_name(output.name + ".partial")
    files = {}
    expanded = 0
    try:
        with partial.open("xb") as target:
            os.chmod(partial, 0o600)
            with zipfile.ZipFile(
                target,
                "w",
                compression=zipfile.ZIP_DEFLATED,
                compresslevel=1,
                allowZip64=True,
            ) as z:
                for root in selected:
                    base = data / root
                    entries = (
                        [base, *sorted(base.rglob("*"))] if base.is_dir() else [base]
                    )
                    for p in entries:
                        require(
                            not p.is_symlink(),
                            "Symlinks cannot be exported: " + str(p.relative_to(data)),
                        )
                        if p.is_dir():
                            continue
                        require(p.is_file(), "Only regular files can be exported")
                        rel = p.relative_to(data).as_posix()
                        if server != "panel" and (
                            rel.startswith("plugins/.paper-remapped/")
                            or rel.startswith("plugins/")
                            and p.name in MANAGED_JARS
                        ):
                            continue
                        h = hashlib.sha256()
                        size = 0
                        with p.open("rb") as source, z.open(
                            "payload/" + rel, "w", force_zip64=True
                        ) as dest:
                            for chunk in iter(lambda: source.read(1 << 20), b""):
                                dest.write(chunk)
                                h.update(chunk)
                                size += len(chunk)
                        expanded += size
                        require(
                            expanded <= LIMIT and len(files) < MAX_FILES - 1,
                            "Export exceeds supported ZIP limits",
                        )
                        files[rel] = {"sha256": h.hexdigest(), "size": size}
                manifest = {
                    "format": "vampyuyu-server-zip-v1",
                    "server": server,
                    "minecraft": version,
                    "createdAt": datetime.datetime.now(
                        datetime.timezone.utc
                    ).isoformat(),
                    "roots": selected,
                    "files": files,
                }
                z.writestr("manifest.json", json.dumps(manifest, indent=2) + "\n")
            target.flush()
            os.fsync(target.fileno())
        os.link(partial, output)
        partial.unlink()
        checksum = output.with_name(output.name + ".sha256")
        with checksum.open("x") as f:
            os.chmod(checksum, 0o600)
            f.write(digest(output) + "  " + output.name + "\n")
        return manifest
    finally:
        if partial.exists():
            partial.unlink()


def level_version(raw):
    """Read the world's own version; never infer compatibility from the ZIP filename."""
    import io

    with gzip.GzipFile(fileobj=io.BytesIO(raw)) as g:
        data = g.read((4 << 20) + 1)
    require(len(data) <= 4 << 20, "level.dat metadata too large")
    pos = 0

    def take(n):
        nonlocal pos
        require(0 <= n <= len(data) - pos, "Invalid level.dat")
        b = data[pos : pos + n]
        pos += n
        return b

    def number(fmt):
        return struct.unpack(fmt, take(struct.calcsize(fmt)))[0]

    def text():
        return take(number(">H")).decode("utf8")

    def value(kind, depth=0):
        require(depth < 64, "NBT nesting limit exceeded")
        if kind in {1, 2, 3, 4, 5, 6}:
            return number({1: ">b", 2: ">h", 3: ">i", 4: ">q", 5: ">f", 6: ">d"}[kind])
        if kind == 8:
            return text()
        if kind in {7, 11, 12}:
            n = number(">i")
            take(n * {7: 1, 11: 4, 12: 8}[kind])
            return None
        if kind == 9:
            sub = number(">B")
            n = number(">i")
            require(0 <= n <= 1000000, "Invalid NBT list")
            return [value(sub, depth + 1) for _ in range(n)]
        if kind == 10:
            out = {}
            while True:
                sub = number(">B")
                if not sub:
                    return out
                name = text()
                out[name] = value(sub, depth + 1)
        raise ValueError("Unsupported NBT tag")

    require(number(">B") == 10, "level.dat must be an NBT compound")
    text()
    tag = value(10)
    require(
        isinstance(tag.get("Data"), dict)
        and isinstance(tag["Data"].get("Version"), dict),
        "Missing world version metadata",
    )
    version = tag["Data"]["Version"].get("Name")
    require(
        isinstance(version, str)
        and re.fullmatch(r"1\.\d+(?:\.\d+)?|26\.\d+(?:\.\d+)?", version),
        "World version cannot be confirmed; inspect this archive manually",
    )
    return version


def inspect_zip(source, server, version, world="world"):
    require(server in SERVERS, "Invalid server")
    with zipfile.ZipFile(source) as z:
        seen = set()
        total = 0
        entries = {}
        require(len(z.infolist()) <= MAX_FILES, "Too many ZIP entries")
        for f in z.infolist():
            n = f.filename
            name = n.rstrip("/")
            path = PurePosixPath(name)
            require(
                name
                and not any(c in n for c in "\\\x00:")
                and not path.is_absolute()
                and str(path) == name
                and all(p not in {".", ".."} for p in path.parts),
                "Unsafe ZIP path: " + repr(n),
            )
            mode = f.external_attr >> 16
            require(
                not stat.S_ISLNK(mode)
                and (stat.S_IFMT(mode) in {0, stat.S_IFREG, stat.S_IFDIR}),
                "ZIP links and special files are forbidden",
            )
            require(name.casefold() not in seen, "Duplicate ZIP entry")
            seen.add(name.casefold())
            require(not f.flag_bits & 1, "Encrypted ZIPs are unsupported")
            total += f.file_size
            require(total <= LIMIT, "ZIP expansion limit exceeded")
            if not f.is_dir():
                entries[name] = f
        if "manifest.json" in entries:
            require(
                entries["manifest.json"].file_size <= 32 << 20, "Manifest too large"
            )
            m = json.loads(z.read("manifest.json"))
            require(isinstance(m, dict), "Invalid manifest")
            require(
                m.get("format") == "vampyuyu-server-zip-v1"
                and m.get("server") == server,
                "Wrong ZIP format or destination server",
            )
            require(
                m.get("minecraft") == version,
                "Minecraft version mismatch; upgrades/downgrades require a reviewed migration",
            )
            files = m.get("files")
            roots = m.get("roots")
            require(
                isinstance(files, dict)
                and isinstance(roots, list)
                and roots
                and all(isinstance(r, str) for r in roots)
                and len(set(roots)) == len(roots),
                "Invalid manifest",
            )
            for root in roots:
                require(
                    isinstance(root, str)
                    and "/" not in root
                    and root not in {".", ".."},
                    "Invalid restore root",
                )
                if server != "panel" and root not in ROOT_FILES and root != "plugins":
                    world_name(root)
                if server == "panel":
                    require(
                        bool(re.fullmatch(r"[A-Za-z0-9_.-]+", root))
                        and root not in {".", ".."},
                        "Invalid panel path",
                    )
            mapping = {}
            for rel, meta in files.items():
                require(
                    isinstance(rel, str) and rel.split("/")[0] in roots,
                    "Payload outside declared roots",
                )
                require(
                    isinstance(meta, dict)
                    and isinstance(meta.get("size"), int)
                    and meta["size"] >= 0
                    and bool(re.fullmatch("[0-9a-f]{64}", str(meta.get("sha256", "")))),
                    "Invalid file metadata",
                )
                key = "payload/" + rel
                require(
                    key in entries and entries[key].file_size == meta["size"],
                    "Missing or truncated payload",
                )
                mapping[key] = (rel, meta["sha256"])
            require(
                set(entries) == set(mapping) | {"manifest.json"}, "Unexpected ZIP files"
            )
            if server != "panel":
                worlds = [r for r in roots if r not in ROOT_FILES and r != "plugins"]
                require(worlds, "Server ZIP has no world")
                for root in ROOT_FILES & set(roots):
                    require(
                        root in files
                        and not any(n.startswith(root + "/") for n in files),
                        "Expected a root file",
                    )
                for name in worlds:
                    key = "payload/" + name + "/level.dat"
                    require(key in entries, "World is missing level.dat")
                    require(
                        entries[key].file_size <= 4 << 20
                        and level_version(z.read(key)) == version,
                        "Saved world version mismatch",
                    )
            else:
                require("payload/panel.db" in mapping, "Panel database missing")
            return {
                "kind": "server",
                "roots": roots,
                "files": mapping,
                "bytes": total,
                "version": version,
            }
        require(server != "panel", "Panel imports require a panel export ZIP")
        levels = [n for n in entries if PurePosixPath(n).name == "level.dat"]
        require(
            len(levels) == 1,
            "Use an exported server ZIP, or a plain ZIP containing exactly one Minecraft world",
        )
        level = levels[0]
        prefix = level[: -len("level.dat")]
        world_name(world)
        require(
            entries[level].file_size <= 4 << 20
            and level_version(z.read(level)) == version,
            "World version mismatch",
        )
        mapping = {}
        for n in entries:
            require(
                n.startswith(prefix),
                "Files outside the world folder; repack the archive",
            )
            mapping[n] = (world + "/" + n[len(prefix) :], None)
        return {
            "kind": "world",
            "roots": [world],
            "files": mapping,
            "bytes": total,
            "version": version,
        }


def stage_zip(source, plan, stage):
    require(
        shutil.disk_usage(stage).free > plan["bytes"] + (64 << 20),
        "Insufficient free space for staging",
    )
    written = 0
    with zipfile.ZipFile(source) as z:
        for key, (rel, expected) in plan["files"].items():
            path = stage / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            h = hashlib.sha256()
            with z.open(key) as src, path.open("xb") as dest:
                for chunk in iter(lambda: src.read(1 << 20), b""):
                    written += len(chunk)
                    require(written <= plan["bytes"], "ZIP changed after inspection")
                    dest.write(chunk)
                    h.update(chunk)
            os.chmod(
                path,
                (
                    0o600
                    if rel.split("/")[0]
                    not in {"world", "world_nether", "world_the_end"}
                    else 0o640
                ),
            )
            require(
                expected is None or h.hexdigest() == expected,
                "ZIP checksum mismatch: " + rel,
            )
    for root in plan["roots"]:
        if not (stage / root).exists():
            (stage / root).mkdir()
        level = stage / root / "level.dat"
        if level.is_file():
            require(
                level_version(level.read_bytes()) == plan["version"],
                "Staged world version mismatch",
            )


def swap_roots(data, stage, roots):
    """Same-filesystem rename with rollback; keep the bind-mounted data root stable."""
    old = stage / ".rollback"
    old.mkdir()
    moved = []
    installed = []
    try:
        for name in roots:
            require(
                not (data / name).is_symlink(), "Refusing symlink restore destination"
            )
            if (data / name).exists():
                os.rename(data / name, old / name)
                moved.append(name)
            if (stage / name).exists():
                os.rename(stage / name, data / name)
                installed.append(name)
    except BaseException:
        for name in reversed(installed):
            os.rename(data / name, stage / name)
        for name in reversed(moved):
            os.rename(old / name, data / name)
        raise


def command(*args, capture=True):
    return (
        subprocess.check_output(args, text=True).strip()
        if capture
        else subprocess.run(args, check=True)
    )


def main(action):
    with contextlib.ExitStack() as resources:
        return operate(action, resources)


def operate(action, resources):
    os.chdir(ROOT)
    compose = os.environ.get("COMPOSE", "compose.local.yaml")
    require(
        compose in {"compose.local.yaml", "compose.prod.yaml"},
        "Choose compose.local.yaml or compose.prod.yaml",
    )
    dc = ["docker", "compose", "-f", compose]
    if action == "help":
        print(
            "Vampyuyu Network · operator commands\n\n  make configure                         Initialize missing secrets/forwarding\n  make up                                Start the stack\n  make status                            Show services\n  make export SERVER=creative             Create a private portable ZIP\n  make zip SERVER=lobby                   Same as export\n  make zip-all                            Export Lobby, Creative, Survival and panel\n  make inspect SERVER=creative ZIP=...     Validate ZIP/version without changes\n  make import SERVER=creative ZIP=...      Restore, with automatic prior-state ZIP\n  make import SERVER=survival ZIP=... WORLD=world  Also accepts a plain world ZIP\n  make export-panel                       Save accounts, roles and arena admin audit\n  make import-panel ZIP=...               Restore panel database\n  make backup                             Full backup including secrets (stays stopped)\n\nUse COMPOSE=compose.prod.yaml in production. OUT=... selects export filename.\nWorld ZIPs keep worlds, dimensions, player saves, plugin data/JARs, claims, bans and OPs.\nManaged plugin JARs come from the selected image. Network secrets/settings remain on the target.\nImports briefly stop only the selected service and preserve its previous running/stopped state.\nZIPs are private data: transfer over SSH. Full migration also needs panel data and network secrets."
        )
        return
    if action == "export-all":
        require(
            not os.environ.get("OUT"),
            "OUT is for a single export; omit it for export-all",
        )
        previous = os.environ.get("SERVER")
        try:
            for server in ("lobby", "creative", "survival", "panel"):
                os.environ["SERVER"] = server
                main("export")
        finally:
            if previous is None:
                os.environ.pop("SERVER", None)
            else:
                os.environ["SERVER"] = previous
        return
    if action == "status":
        command(*dc, "ps", capture=False)
        return
    if action == "up":
        command("bash", "scripts/up.sh", compose, capture=False)
        return
    if action == "backup":
        command("bash", "scripts/backup.sh", compose, capture=False)
        return
    config = json.loads(
        command(*dc, "--profile", "tools", "config", "--format", "json")
    )
    if action == "configure":
        uid = int(config["services"]["creative"]["environment"]["UID"])
        gid = int(config["services"]["creative"]["environment"]["GID"])
        require(
            os.geteuid() == 0 or os.geteuid() == uid,
            "Configure as the configured data owner or root",
        )
        for volume in config["services"]["tools"]["volumes"]:
            if volume["target"] not in {"/state", "/secrets", "/archives"}:
                continue
            folder = Path(volume["source"])
            if not folder.exists():
                folder.mkdir(
                    parents=True,
                    mode=0o700 if volume["target"] == "/secrets" else 0o750,
                )
                if os.geteuid() == 0:
                    os.chown(folder, uid, gid)
        if compose == "compose.local.yaml":
            command(*dc, "build", "tools", capture=False)
        else:
            command(*dc, "pull", "tools", capture=False)
        command(*dc, "run", "--rm", "--no-deps", "tools", "secrets", capture=False)
        version = config["services"]["creative"]["environment"]["VERSION"]
        command(
            *dc,
            "run",
            "--rm",
            "--no-deps",
            "tools",
            "configure",
            "--version",
            version,
            capture=False
        )
        return
    server = (
        "panel" if action.endswith("-panel") else os.environ.get("SERVER", "creative")
    )
    require(server in SERVERS, "SERVER must be lobby, creative, survival or panel")
    action = action.removesuffix("-panel")
    if action == "stop":
        command(*dc, "stop", server, capture=False)
        return
    require(action in {"export", "import", "inspect"}, "Unknown operation")
    tools = config["services"]["tools"]
    state = Path(next(v["source"] for v in tools["volumes"] if v["target"] == "/state"))
    data = state / server
    version = config["services"]["creative"]["environment"]["VERSION"]
    if action in {"export", "import"}:
        require(
            data.is_dir() and not data.is_symlink(),
            "Initialize data first with make configure",
        )
        if server != "panel":
            require(
                (data / ".network-version").read_text().strip() == version,
                "Configured world version mismatch",
            )
    backups = ROOT / "backups"
    backups.mkdir(exist_ok=True)
    os.chmod(backups, 0o700)
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")

    def output(label):
        return backups / (server + "-" + label + "-" + stamp + ".zip")

    import fcntl

    lock = (
        resources.enter_context((data / ".archive-operation.lock").open("a"))
        if action in {"export", "import"}
        else None
    )
    if lock is not None:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ValueError("Another archive operation is active for this server")
    source = None
    plan = None
    if action in {"import", "inspect"}:
        require(bool(os.environ.get("ZIP")), "Provide ZIP=/path/to/archive.zip")
        source = Path(os.environ["ZIP"]).resolve()
        require(source.is_file(), "ZIP not found")
        require(
            not source.is_relative_to(data.resolve()),
            "Import ZIP must be outside the target server data directory",
        )
        checksum = source.with_name(source.name + ".sha256")
        if checksum.exists():
            require(
                checksum.read_text().split()[0] == digest(source),
                "Archive SHA-256 mismatch",
            )
        plan = inspect_zip(source, server, version, os.environ.get("WORLD", "world"))
        print(
            "Validated:",
            server,
            plan["kind"],
            version,
            ", ".join(plan["roots"]),
            len(plan["files"]),
            "files",
            flush=True,
        )
        if action == "inspect":
            return
    running = (
        server in command(*dc, "ps", "--services", "--status", "running").splitlines()
    )
    stage = None
    stopped = False
    success = False
    resume_safe = True
    try:
        if action == "import":
            stage = Path(tempfile.mkdtemp(prefix=".archive-staging-", dir=data))
            stage_zip(source, plan, stage)
            uid, gid = data.stat().st_uid, data.stat().st_gid
            require(
                os.geteuid() == 0 or uid == os.geteuid(),
                "Run as the data owner (or root) to preserve ownership",
            )
            if os.geteuid() == 0:
                for path in stage.rglob("*"):
                    os.chown(path, uid, gid)
            if server == "panel":
                db = sqlite3.connect(
                    "file:" + str(stage / "panel.db") + "?mode=ro", uri=True
                )
                try:
                    require(
                        db.execute("pragma integrity_check").fetchone()[0] == "ok",
                        "Panel database integrity check failed",
                    )
                finally:
                    db.close()
        # Stop even a non-running service: an unhealthy/restarting writer must not run during backup.
        stopped = True
        command(*dc, "stop", server, capture=False)
        require(
            not command(*dc, "ps", "--services", "--status", "running")
            .splitlines()
            .count(server),
            "Service is still running",
        )
        if action == "export":
            destination = (
                Path(os.environ["OUT"]).resolve()
                if os.environ.get("OUT")
                else output("export")
            )
            write_zip(data, server, version, destination)
            print("ZIP saved:", destination, flush=True)
        else:
            previous = roots_for(data, server)
            if previous:
                destination = output("before-import")
                write_zip(data, server, version, destination)
                print("Previous state saved:", destination, flush=True)
            roots = (
                sorted(set(previous) | set(plan["roots"]))
                if plan["kind"] == "server"
                else plan["roots"]
            )
            swap_roots(data, stage, roots)
            print(
                "Restored:",
                server,
                "— original ZIP preserved; target forwarding/secrets unchanged.",
                flush=True,
            )
        success = True
    finally:
        if stage is not None:
            rollback = stage / ".rollback"
            if not success and rollback.is_dir() and any(rollback.iterdir()):
                resume_safe = False
                print(
                    "Rollback files preserved at "
                    + str(stage)
                    + "; service remains stopped for recovery.",
                    file=sys.stderr,
                )
            else:
                try:
                    shutil.rmtree(stage)
                except OSError:
                    print(
                        "Staging cleanup needs the data owner/root: "
                        + str(stage)
                        + ". Backup retained; service will still resume.",
                        file=sys.stderr,
                    )
        if stopped and running and resume_safe:
            command(*dc, "start", server, capture=False)
        if not running and stopped:
            print("Service remains stopped, matching its prior state.", flush=True)
    require(success, "Operation failed")


if __name__ == "__main__":
    try:
        main(sys.argv[1] if len(sys.argv) > 1 else "help")
    except (
        ValueError,
        OSError,
        zipfile.BadZipFile,
        sqlite3.DatabaseError,
        subprocess.CalledProcessError,
    ) as e:
        print("Archive operation failed: " + str(e), file=sys.stderr)
        sys.exit(1)

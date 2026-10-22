# Vampyuyu Minecraft network

Velocity → Lobby → Creative / Survival, with a Go + React administration panel.

- **Minecraft:** `play.vampyuyu.win:25565`, Java Edition, official accounts.
- **Production panel:** `https://panel.vampyuyu.win`, administrator login.
- **Local panel:** `http://localhost:5173`.

The first milestone provides server status, start/stop/restart, a live console, and console commands for the three Paper servers. The lobby includes a compass and `/menu` selector: a three-row native Minecraft menu with a red/dark glass frame, ivory/gold accents, English world descriptions, and a return-to-lobby button. Cosmetic player roles are editable from the dashboard. The panel also provides CPU/RAM/storage/process monitoring, plugin installation/removal, per-server OP controls, and limited role command grants. File editing, multi-admin roles, and proxy command submission are deferred. Console commands already have Minecraft console privileges.

## Panel visual direction

Keep all panel interface copy in English. Use the Vampyuyu reference palette: deep burgundy, wine red, warm ivory, and pale gold accents. Preserve the existing dashboard layout and keep green reserved for operational status indicators. Shared palette tokens live at the top of `web/src/style.css`.

## Architecture

| Service | Responsibility | Published ports |
| --- | --- | --- |
| Velocity | Account authentication, modern forwarding, routing | TCP 25565 |
| Lobby | Protected gothic palace hub and clickable selector | None |
| Creative / Survival | Persistent Paper worlds | None |
| Panel | Go API, SQLite sessions/audit/player roles, embedded React production UI, private role bridge | Local development: loopback 8080; bridge 8083 is never published |
| Frontend | Vite development server, API proxy | Local development: loopback 5173 |
| Controller | Fixed Docker lifecycle/log operations and private RCON | None |
| Caddy | Cloudflare origin TLS and panel reverse proxy | Production: TCP 443 |
| Tools | Offline secrets, ZIP inspection/import, forwarding configuration | None |

Only the controller mounts the Docker socket. Its API requires a secret and accepts only the four fixed server IDs, fixed actions, and containers bearing this stack's two labels. Docker socket access still grants host-level authority if the controller is compromised; run this on a dedicated trusted host. The panel cannot create containers, mount host paths, or execute host shell commands.

The game network is a private Docker bridge with outbound connectivity for server downloads and authentication. No backend game or RCON port is published. The panel/controller control network is internal. Never publish RCON or backend ports. Docker administrators and installed Minecraft plugins are trusted operators/code.

## First local setup

Requirements: Linux, Docker Engine with Compose, Bash, Python 3. Go, Node, and Maven can all run through Docker. The host was confirmed as x86_64. The sample memory settings target the production 8-vCPU / 24-GB host; reduce heaps and container limits for a smaller development machine.

```bash
cp .env.example .env
mkdir -p state/panel secrets archives backups
chmod 700 secrets backups
id -u
id -g
stat -c '%g' /var/run/docker.sock
```

Set `PUID`, `PGID`, and `DOCKER_GID` in `.env` to those three values. All state directories and secret files must be owned by `PUID:PGID`; Compose file secrets retain their host ownership. The sample socket group `131` is specific to the original development host. Keep `STACK_ID` unique per installation. Use separate state, secret, and archive directories for local and production.

Build tooling and initialize random secrets; existing secret files are preserved:

```bash
docker compose -f compose.local.yaml build tools
docker compose -f compose.local.yaml run --rm --no-deps tools secrets
```

The sample `MC_VERSION=1.21.9`, `PAPER_BUILD=59`, Velocity `3.4.0-SNAPSHOT` build `563`, and Java 21 image are a pinned **disposable development baseline**, not a decision to upgrade the Creative server. Before using the real archive, follow the next section. For a new development world, configure that baseline:

```bash
docker compose -f compose.local.yaml run --rm --no-deps tools configure --version 1.21.9
```

Read the [Minecraft EULA](https://www.minecraft.net/eula). Set `EULA=TRUE` in `.env` only if you accept it. It defaults to `FALSE`; the setup does not accept it on your behalf.

```bash
./scripts/up.sh compose.local.yaml
```

The wrapper validates EULA acceptance, version markers, and forwarding files, then builds and starts the stack. Server entrypoint guards also reject missing/mismatched markers when Compose is invoked directly. Initial world generation and downloads may take several minutes.

Create the one administrator with a 14–72-byte password. No account or password ships by default. Use the containerized Go command for the development service:

```bash
read -rs -p 'New administrator password: ' admin_password; echo
printf '%s' "$admin_password" | docker compose -f compose.local.yaml run --rm -T --no-deps panel go run ./cmd/panel admin admin
unset admin_password
```

If the disposable demo was initialized by Codex, its state is under `state/smoke/`, the username is `admin`, and its generated password is in `secrets/local/admin_password`. Those files are ignored by Git.

Open `http://localhost:5173` and connect Minecraft to `localhost:25565`. The lobby compass or `/menu` lets players select Creative or Survival. `/menu` works in all three worlds and includes a Lobby button. An unavailable target leaves the player in their current world with a proxy error. Manual `/server creative` and `/server survival` also work.

## Import the existing Creative ZIP

**Do this before booting Creative.** If you already created a disposable Creative world, stop the stack and move its entire directory aside explicitly. Import will never replace it.

1. Place the original ZIP in `archives/creative.zip`. Keep an independent copy elsewhere.
2. Stop all game services before importing or configuring them.
3. Inspect without running Java or modifying server data:

```bash
docker compose -f compose.local.yaml stop velocity lobby creative survival
docker compose -f compose.local.yaml run --rm --no-deps tools inspect --zip /archives/creative.zip
```

The JSON report includes the server root, detected Minecraft version and Java requirement, worlds, plugin filenames, expanded size, and compatibility problems. Inspection uses runtime version metadata and Paper artifact names, not ZIP filenames. Ambiguous versions, missing worlds, non-Paper servers, and unsupported configuration layouts require manual review. Automatic import supports Paper 1.19+ with `config/paper-global.yml`; older modern-forwarding servers need an explicit compatibility adaptation first. This tool does not scan plugin code for safety or guarantee that every plugin works behind Velocity.

4. Set `MC_VERSION` to the inspected version and `PAPER_BUILD` to the reviewed matching Paper build. Pin `MC_IMAGE` to the corresponding Java image digest. The report recommends Java 17, 21, or 25 according to the version; review actual plugin/runtime requirements too. For production, update the Dockerfile's `MINECRAFT_IMAGE` default and publish matching server and lobby images when changing Java. Its selector bytecode targets Java 17.
5. Import with the exact detected version, for example:

```bash
docker compose -f compose.local.yaml run --rm --no-deps tools import --zip /archives/creative.zip --version 1.21.9
```

Extraction is staged next to the destination, bounded to 40 GiB and 200,000 entries. Absolute paths, traversal, backslashes, links, duplicate entries, and multiple server roots are rejected. Files outside a nested server root require repacking. The ZIP remains intact. Worlds, UUID files, plugins, and settings are copied without rewriting them. A pre-existing destination is rejected. Adjust the reviewed expansion limit in source if the actual server is larger.

6. Review the planned network changes: enable Velocity modern forwarding and its secret in `config/paper-global.yml`, disable legacy Bungee forwarding in `spigot.yml`, and set offline backend authentication plus private RCON through Compose. The proxy continues authenticating official accounts. The Creative gamemode is Creative; other existing properties remain unless explicitly managed by Compose. Review existing player UUIDs if the old server used offline accounts; this setup never rewrites player identities.
7. Apply configuration offline, using the selected version:

```bash
docker compose -f compose.local.yaml run --rm --no-deps tools configure --version 1.21.9
```

Existing YAML settings are preserved and original YAML is saved once as `*.before-network`; comments may be normalized. Version markers prevent accidental changes on later starts. Version history mismatches abort configuration. This command must run while servers are stopped. It writes the matching secret for Velocity and all three backends. Rotating it requires configuring and restarting all four services together.

8. Back up, accept the EULA if appropriate, start the stack, and complete the gameplay checklist below. Do not change Minecraft versions or replace production worlds to resolve an import failure.

### Imported local Creative server

The supplied `yuyu-mc.zip` was imported locally as **Paper 1.21.9 build 59 / Java 21**, matching all three saved dimensions. The active local `.env` uses `STATE_DIR=./state/yuyu-local`, `MC_VERSION=1.21.9`, and `PAPER_BUILD=59`. Connect using a Java Edition **1.21.9** client at `localhost:25565`; the panel remains at `http://localhost:5173`.

The original ZIP and a SHA-256 manifest are preserved under ignored `archives/`; the reviewed import excludes only the outer deployment Compose and obsolete 1.21.8 runtime artifacts. Worlds, player data, whitelist, OP entries, and claims are retained. Unused imported plugins and their configuration were discarded at the operator’s request; the original ZIP retains their source copies. Creative uses Creative mode as configured by the network. Its original whitelist remains enabled.

Creative intentionally runs only **SimpleClaimSystem 1.13.1** and **VampyuyuNetwork 0.4.1**. The claim plugin was updated from the archive’s 1.12.3.2 after observing an obsolete Paper API call during respawn; its stopped database/configuration snapshot is recorded in the ignored import report under `archives/`. Database record counts were verified after the update. The network plugin supplies `/menu`, cosmetic roles, chat names, and TAB formatting. EssentialsX, EssentialsChat, LuckPerms, Vault, WorldEdit, TAB, and voice-chat plugins were removed, including the set-aside copies. The original ZIP remains intact. SimpleClaimSystem has no mandatory plugin dependency; its economy integration is disabled so claims work without Vault or an economy provider. Existing claims and worlds remain in place. Regular players use the claim plugin's native default permissions and configured default limits; former LuckPerms group overrides are inactive. Gameplay and claim protection still require verification with an authenticated Minecraft client.

Lobby and Survival were aligned from 1.21.4 to 1.21.9 in copies of their existing data. The former complete state remains in `state/smoke`, and the stopped pre-import backup is `backups/network-20261019T213304Z.tar.gz`. To roll back locally, stop the entire stack, restore `.env` to `STATE_DIR=./state/smoke`, `MC_VERSION=1.21.4`, `PAPER_BUILD=232`, and restart. This switches back to the preserved old data; **never start the imported Creative or upgraded world copies with 1.21.4**. New play progress after the switch belongs to its respective state directory.

## Production deployment

GitHub Actions checks Go, the frontend, dependency advisories, Compose, and Docker builds on PRs. A passing push to `main` publishes five GHCR images:

```text
ghcr.io/garder500/minecraft-bungee-panel:<40-character-commit-sha>
ghcr.io/garder500/minecraft-bungee-controller:<40-character-commit-sha>
ghcr.io/garder500/minecraft-bungee-server:<40-character-commit-sha>
ghcr.io/garder500/minecraft-bungee-lobby:<40-character-commit-sha>
ghcr.io/garder500/minecraft-bungee-tools:<40-character-commit-sha>
```

Production Compose never builds source. Tags select exact commits; no `latest` tag is used. For immutable deployment enforcement beyond tag convention, record registry digests in release records. Third-party runtime/build images are pinned by digest; Minecraft and Velocity versions **and builds** are separately pinned. Builds currently publish `linux/amd64`; verify `uname -m` is `x86_64`. Maven's Paper API snapshot is build-time-only; runtime server versions do not follow it.

On the production host, clone this repository **using SSH**, check out the release commit, copy `.env.example` to `.env`, and set:

- `STACK_ID=vampyuyu-prod`, actual UID/GID/socket group, and production data paths.
- Reviewed Minecraft/Java images and versions matching the Creative archive.
- `IMAGE_REPOSITORY=ghcr.io/garder500/minecraft-bungee` and the published full commit SHA as `IMAGE_TAG`.
- `PUBLIC_ORIGIN=https://panel.vampyuyu.win` and `MC_BIND=0.0.0.0`.
- Heap sizes/container limits suitable for the host; defaults reserve roughly 4 GB outside container limits, plus unused heap headroom. `MAX_PLAYERS=40` is a configurable admission cap, not a performance guarantee.
- `EULA=TRUE` only after explicit acceptance.

If GHCR packages are private, use `docker login ghcr.io` with a read-packages credential. Grant the repository Actions workflow package publishing permissions. No deployment or DNS changes happen automatically.

```bash
docker compose -f compose.prod.yaml pull
mkdir -p state/panel secrets/tls archives backups
chmod 700 secrets backups
docker compose -f compose.prod.yaml run --rm --no-deps tools secrets
# Inspect/import the archive and configure as above, substituting compose.prod.yaml.
```

Generate a Cloudflare Origin CA certificate covering `panel.vampyuyu.win`. Save its PEM certificate to `secrets/tls/origin.pem` and private key to `secrets/tls/origin-key.pem` (or the configured `TLS_DIR`). Keep the private key mode `600`. Caddy mounts these files; they never enter an image. Then configure:

- Cloudflare `panel` A record → host IP, **proxied**; SSL/TLS mode **Full (strict)**.
- Cloudflare `play` A record → host IP, **DNS-only**. Ordinary Cloudflare HTTP proxying does not carry Minecraft Java TCP.
- Add AAAA records only if IPv6 routing and firewall rules are configured.
- Host/provider firewall: allow TCP 25565 for players; allow TCP 443 from current Cloudflare IP ranges; restrict SSH to trusted sources. Account for Docker's firewall chains: ordinary UFW rules alone may not restrict published Docker ports. Do not expose 8080, 8081, 8083, 25575, or backend ports. No port 80 is required for the Origin CA setup.
- Disable Cloudflare caching for `/api/*`; preserve streaming with SSE. Caddy flushes streamed responses immediately. Origin CA certificates are for Cloudflare-to-origin traffic, so directly browsing the origin is not expected to show a publicly trusted certificate.

Create the administrator before first login:

```bash
read -rs -p 'New administrator password: ' admin_password; echo
printf '%s' "$admin_password" | docker compose -f compose.prod.yaml run --rm -T --no-deps panel admin admin
unset admin_password
./scripts/up.sh compose.prod.yaml
```

If the administrator password is lost, stop the panel, back up SQLite, and perform an explicitly reviewed offline reset. The bootstrap command refuses to replace an existing administrator.

## Player roles, command access, TAB, and server icon

Open **Players & roles** in the English dashboard to search known players, filter roles, and save a new role for any online or previously discovered player. Identities use authenticated Minecraft UUIDs; usernames are display/search fields, so name changes retain the assigned role. The roles are `owner`, `catgirlfriend_`, `catgundisc_`, `streamer`, `Mod`, and the default **Yuyu's Bat**. Role colors follow the supplied pink, lavender, violet, and raspberry reference; labels/colors are defined in `internal/players/players.go` and matched by the dashboard badge palette.

Roles begin with **no command grants**. Administrators can explicitly enable `ban`, `kick`, `pardon`, and `fly` for each role in Players & roles. Assigning `owner` or `Mod` never grants OP, gamemode control, arbitrary permissions, or dashboard access. OP remains a separate, full-privilege per-server operation. Dashboard administrators remain separate from Minecraft player roles. Role prefixes are bold in chat and TAB; usernames retain normal weight. Each player has one network role; new players receive Yuyu's Bat automatically.

The `VampyuyuNetwork` Paper plugin is bundled into the shared server image and the lobby image. It applies colored prefixes/names in chat and TAB, with a styled Vampyuyu header, `play.vampyuyu.win`, the current world, and local/network player counts. The TAB roster remains the current world's player list; the footer counts unique players across worlds. Set `MC_CONNECT_ADDRESS=localhost:25565` locally and `play.vampyuyu.win` in production; local TAB footers include the connection hint. No client resource pack is required.

The plugin sends authenticated snapshots every five seconds to the panel's private port 8083. Role changes persist immediately in SQLite and apply in-game within a few seconds. Known offline players are discovered from each world's existing player cache in batches after startup; players who have never visited the network must join once. Stopped or disconnected worlds expire from presence after 25 seconds. During a panel outage, running plugins retain their last received role cosmetics; after a simultaneous game restart, default cosmetics remain until synchronization returns. Other chat/TAB plugins may override these displays and need integration review.

The separate `role_bridge_token` secret authenticates this private display API. It is **not** the Docker controller token. The public authenticated endpoints are `GET /api/v1/roles`, `GET /api/v1/players?search=&role=&page=`, and `PUT /api/v1/players/{uuid}/role` with JSON `{"role":"mod"}`. Role updates require the administrator session, Origin, and CSRF token, and are committed atomically with an audit record. Players cannot assign their own role.

For an existing installation, rebuild/pull the tools image and rerun `tools secrets` to create the new bridge secret without replacing existing files, then restart the panel and three Paper services with the new images. The first panel startup adds the player directory tables without changing administrator accounts or existing data. Include the panel database and bridge secret in backups.

The supplied artwork is stored as a 64 × 64 RGBA PNG at `infra/server-icon.png`, synchronized into Velocity as `server-icon.png`. Restart Velocity after replacing it; refresh Minecraft's Multiplayer list to fetch the icon. Velocity re-encodes PNGs for its status response, so compressed bytes may differ while pixels remain identical.

## Backups, updates, and rollback

Run `./scripts/backup.sh compose.prod.yaml` (or `compose.local.yaml`). It stops the entire stack, verifies nothing remains running, and streams state, secrets, `.env`, Compose, and infrastructure configuration into a mode-600 archive under `backups/`. Services remain stopped so deliberate stops are preserved. Store encrypted off-host copies; these archives contain secrets. The script requires enough space for the compressed backup, and failed archives must not be treated as valid backups. Provider snapshots complement, but do not replace, an application-consistent backup.

To restore: stop the stack; verify and extract a trusted backup into an **empty staging directory**; inspect its `state/`, `secrets/`, and `config/`; move existing data aside; restore into the configured paths with original ownership; check out the recorded code/image release and verify versions; then use `scripts/up.sh`. Restore SQLite and all server data while services are stopped. Never extract a backup over a running world. Keep a copy of the original Creative ZIP separately.

For an application update: make a backup, select a passing published commit in `IMAGE_TAG`, check out its infrastructure files, pull, and start only intended services. Use a previous application image tag to roll back only when its schema/configuration remains compatible. This initial version uses additive SQLite initialization; future migrations must document their rollback requirements. **Do not downgrade Minecraft binaries against newer worlds.** World/runtime upgrades require separate compatibility review and a full restore point; startup version markers intentionally refuse silent changes.

Game containers use `restart: "no"`, so panel stops stay stopped and game services also remain stopped after a host reboot. Start intended services manually. The panel/controller/Caddy restart automatically. Stop/restart requests allow 60 seconds for Docker shutdown; Compose allows 75 seconds. Monitor service health, disk space, Java memory, server logs, and failed audit actions. Do not automatically restart a server into repeated world corruption errors.

## Player inventories and administration workspaces

The English panel has bookmarkable pages: `/#overview`, `/#players`, `/#plugins`, `/#settings`, and `/#activity`. Plugin management is on its own page. Select a Paper server on Plugins or Server settings; Velocity retains its status, lifecycle, metrics, and console workspace in Overview.

Click a player name in **Players & roles** to open the player workspace. Choose Lobby, Creative, or Survival to inspect that server’s inventory, armor, offhand, and ender chest. Dimensions on the same server share inventory normally. Profiles show the live or saved game mode, health, food, XP level, position, OP and whitelist status. The directory also shows live game modes. Profiles refresh every ten seconds without overwriting a pending slot edit. A player who has no save on a server must join it once before saved inventory/game-mode editing. The target game server must be running even when the player is offline.

The slot editor changes material/amount or clears one slot. Amount-only changes preserve custom components, enchantments, names, and other metadata. Changing material creates a fresh item and requires confirmation; metadata is displayed read-only, bounded to 4,000 characters. Stack-size limits are enforced by Minecraft, with support for saved custom maximum stack sizes. A SHA-256 revision rejects stale edits with HTTP 409 rather than overwriting newer changes. Online operations run on the Minecraft main thread and save player data before/after edits. Offline editing preserves unknown NBT tags, bounds decompression to 16 MiB, limits tag nesting/array sizes, and atomically replaces only the validated player UUID’s data file. Pre-component inventory saves (DataVersion below 3837) require the player to join once before offline editing; the editor does not silently upgrade legacy inventory data.

Before inventory edits and offline game-mode changes, the plugin snapshots the current player data under `plugins/VampyuyuNetwork/player-backups/<uuid>/`, retaining the latest ten files. **Inventory backups** in the player workspace can restore only the selected inventory or ender chest while that player is offline on the target server. Restoring preserves current position, game mode, XP, and the other container; it checks the current revision and requires matching backup/current data versions. The current inventory is backed up before restoration. For a full player-data restore outside the panel, stop the server and preserve the current file before copying a reviewed snapshot to the matching primary world’s `playerdata/<uuid>.dat`; a full restore also replaces other saved player progress.

The player workspace provides administrator game-mode changes, heal/feed, clear effects, return to spawn, whitelist membership, kick, temporary/permanent ban, and unban. Actions are scoped to the chosen backend server; bans and whitelists are not automatically shared by Velocity. These controls belong to panel administrators and do not expand Minecraft role command grants.

**Server settings** shows Minecraft version, online players and game modes, TPS (1/5/15-minute averages), average tick duration, dimensions, loaded chunk/entity counts, and spawn positions. Administrators can save players/worlds, broadcast a server announcement, manage whitelist/bans, set per-dimension difficulty, time and weather, toggle PvP, and edit a finite set of common game rules. Random tick speed is limited to 0–20. World state/rules use Minecraft persistence; per-world PvP overrides are stored in the network plugin configuration. **Activity** shows the latest 100 audited changes, filters by server, and paginates older events. Inventory contents, credentials, uploaded JAR contents and console command text are excluded from audit records.

VampyuyuNetwork’s managed build now requires Java 21 and Paper 1.21.4+; the imported local network remains pinned to Minecraft 1.21.9. Its authenticated management listener uses backend TCP 8084 on the private game network, never a published host port. The controller resolves only fixed labeled game containers, forwards only allowlisted operations, does not follow redirects, and never exposes arbitrary Docker/shell/file execution. The public API retains administrator sessions, same-origin checks and CSRF protection. A complete stopped pre-upgrade network backup is `backups/network-20261019T220824Z.tar.gz`.

## Resources, plugins, and operators

Select a server in Overview. **Resources** samples real Docker CPU and container RAM every five seconds, keeps the latest 36 samples in the open browser view, and shows uptime, automatic restart count, tasks/threads, and a bounded process list. CPU 100% means one full core; values can exceed 100%. RAM excludes inactive filesystem cache and is measured against the container limit, not just Java heap. Process CPU is a lifetime average and process RAM is relative to host memory. Server storage sums regular files without following symlinks and is cached for 30 seconds; free disk space is shared host filesystem capacity. Stopped or unavailable services display unavailable live values rather than fabricated zeroes. History is not stored across page reloads.

**Plugins** lists installed JARs for Lobby, Creative, and Survival. Upload a Paper/Bukkit JAR up to 64 MiB, then restart that server to load it. Uploads are staged, validate bounded plugin metadata, reject duplicate filenames/plugin identities, and cannot replace the managed VampyuyuNetwork or Lobby plugins. To update/remove a plugin, stop the server first, remove its JAR, upload the replacement if applicable, and start again. Removing a JAR keeps its configuration/data. Plugins are executable code selected by the administrator; no hot reload or arbitrary file/shell endpoint is provided. The controller alone has Docker access and mounts only each game server’s data, with Velocity read-only.

In **Players & roles**, choose the target world above the directory, then Grant OP or Remove OP for a known player. An explicit UI confirmation identifies the player and world. The controller calls a console-only VampyuyuNetwork command using the player's authenticated UUID, avoiding username changes or command injection. OP status is refreshed by the private bridge. This affects only that world. OP includes full Minecraft privileges such as gamemode; the role command system never grants OP.

The **Role access** cards persist each role’s selected commands in SQLite and synchronize to all three worlds. The plugin attaches only the exact permissions for `ban`, `kick`, `pardon`, and self-only `fly`. Wildcards, namespaces, `execute`, `op`, `deop`, `gamemode`, and arbitrary commands/permission strings are rejected. Role changes revoke former grants; a bridge outage revokes role grants after 25 seconds while preserving cached name cosmetics. `/fly` toggles self flight in Survival/Adventure and does not change game mode; Creative/Spectator retain their native flight. Revoking a plugin-issued flight grant disables that flight. Vanilla OP or other installed plugins' independent grants remain outside this role policy. Grant and revoke operations, role policy changes, plugin mutations, and lifecycle actions are audited without credentials or file contents.

## API and behavior

Authenticated same-origin API under `/api/v1`:

| Method / route | Behavior |
| --- | --- |
| `POST /login` | JSON username/password → HttpOnly session cookie and CSRF token |
| `GET /session` | Current username and CSRF token |
| `POST /logout` | Revoke the persisted session |
| `GET /servers` | Four fixed service IDs, Docker state/health, command capability |
| `POST /servers/{id}/{start,stop,restart}` | Allowlisted lifecycle operation |
| `POST /servers/{id}/command` | JSON `command`, private RCON; Paper servers only |
| `GET /servers/{id}/game/state` | Live world/player/access/performance summary |
| `GET /servers/{id}/game/materials` | Valid item materials from the running server |
| `GET /servers/{id}/game/players/{uuid}/{inventory,ender}` | Live or saved inventory, revision and player profile |
| `PUT /servers/{id}/game/players/{uuid}/{inventory,ender}` | Validated single-slot edit with expected revision |
| `GET /servers/{id}/game/players/{uuid}/backups` | Bounded player snapshot history |
| `POST /servers/{id}/game/action` | Fixed administrative player/world/access operations, including offline inventory restore |
| `GET /activity?server=&before=` | Filtered, cursor-paginated administrative audit |
| `GET /servers/{id}/metrics` | CPU, RAM, server storage, uptime, restarts, bounded processes |
| `GET /servers/{id}/plugins` | Installed Paper plugin JAR metadata |
| `POST /servers/{id}/plugins/{filename}` | Raw JAR body, up to 64 MiB; requires restart |
| `DELETE /servers/{id}/plugins/{filename}` | Remove non-managed JAR while stopped; keep config |
| `GET /role-commands` | Allowed command catalog and current role policies |
| `PUT /roles/{role}/commands` | JSON `commands` list from the finite allowlist |
| `POST /players/{uuid}/operator` | JSON `server`, `enabled`; known UUID, Paper only |
| `GET /servers/{id}/console` | SSE `snapshot`, `unavailable`, or `expired` events |

All mutations require the configured `Origin`; authenticated mutations also require `X-CSRF-Token`. Sessions expire after 24 hours and survive panel restarts. Login permits eight attempts per five minutes per direct peer; behind Caddy this is intentionally a shared administrator limit. The backend does not trust spoofable client IP headers. Audit records live in SQLite; command text and credentials are never audited. Operational Docker logs rotate at 3 × 10 MB per game container.

Console SSE refreshes a bounded snapshot every two seconds, containing up to 200 lines with at most 8 KiB per line. Heartbeats keep idle streams alive. Reconnection replaces the snapshot to avoid duplicate appended lines. This is an operational tail, not a durable lossless log feed; bursts exceeding 200 lines require the server's persistent logs. Console output is rendered as React text, never HTML. The controller redacts its known secrets from returned logs and command responses. Other plugin-specific secrets may still be emitted by plugins; access is administrator-only.

## World ZIP toolbox

Use `make help` for the operator commands. `make zip SERVER=creative` exports a portable ZIP; `make restore SERVER=creative ZIP=/path/to/export.zip` validates, backs up the target and restores it. `make zip-all` creates Lobby, Creative, Survival and panel ZIPs. Set `COMPOSE=compose.prod.yaml` on the production host. Conventional single-world Minecraft ZIPs are supported too. Each service retains its previous running/stopped state.

See [portable worlds and deployment](docs/world-archives.md) for exactly what is included, raw world ZIPs, claims, the separate panel database, secret handling, rollback and first production installation. For a complete network snapshot including Redis and secrets, `make backup` retains the existing full-network backup workflow and leaves the stack stopped.

## Validation

```bash
docker run --rm -v "$PWD:/src" -w /src golang:1.26-alpine sh -c 'go test ./... && go vet ./...'
# Or use installed Go; no generated UI is needed for backend tests.
cd web && npm ci && npm run build && npm audit --audit-level=high
# From the repository root:
docker compose --env-file .env.example -f compose.local.yaml config --quiet
docker compose --env-file .env.example -f compose.prod.yaml config --quiet
docker build --target panel -t minecraft-bungee-panel:test .
docker build --target controller -t minecraft-bungee-controller:test .
docker build --target server -t minecraft-bungee-server:test .
docker build --target lobby -t minecraft-bungee-lobby:test .
python3 scripts/smoke.py
# Against the running disposable 1.21.9 network:
python3 scripts/check-forwarding.py
```

The smoke test uses isolated temporary state, credentials, and a labeled Alpine container, exercises real Docker lifecycle/logs and panel sessions, and removes its containers afterward. It also verifies authenticated cosmetic role changes, cross-world synchronization, and persistence after panel restart. It does not accept the Minecraft EULA or claim to test gameplay. `python3 scripts/smoke-management.py` additionally checks the already-running localhost Minecraft management bridge with a synthetic offline UUID and temporary player data. It exercises inventory/equipment/ender edits, metadata preservation, stale revisions, stack limits, saved game modes, partial inventory restore, backup retention and audit redaction, and removes its fixture afterward. It does not change real users or send Minecraft chat messages.

Before declaring production ready, verify with the actual archive and an authenticated Java client:

- Join `play.vampyuyu.win`, arrive in Lobby, and select both Creative and Survival with the menu. Run `/menu` in each world, switch directly between Creative and Survival, and use its Lobby button to return. Check that normal building and inventory use still work outside the Lobby.
- Confirm Creative worlds/plugins/player UUIDs survived, correct gamemodes apply, lobby damage/building is blocked, and unavailable destinations are handled.
- Confirm official account authentication, reject invalid forwarding secrets from a temporary private test backend, and confirm only intended ports are reachable externally.
- Exercise real server console commands, stop/restart, intentional-stop persistence, and panel restart/session persistence.
- Change a player role in Players & roles; verify its bold prefix/color and normal-weight username in chat and TAB in all three worlds, default Yuyu's Bat for new players, persistence offline, and that OP remains unchanged while configured limited command grants follow the role. Verify revocation, denied OP/gamemode access for non-operators, and `/fly` on Survival. Confirm the branded TAB header/footer and server-list icon.
- Restore a stopped-server backup into an isolated test stack and verify worlds, users, panel login, and secrets before relying on backups.

References: [Velocity forwarding](https://docs.papermc.io/velocity/player-information-forwarding/), [Velocity security](https://docs.papermc.io/velocity/security/), [itzg server configuration](https://docker-minecraft-server.readthedocs.io/), [Cloudflare Full (strict)](https://developers.cloudflare.com/ssl/origin-configuration/ssl-modes/full-strict/).

## Arenas and mini-games

Open [Arenas](http://localhost:5173/#arenas) to configure play zones in existing worlds, manage Werewolf/free-play sessions, follow participants, stop and restore players, send clickable network invitations, and issue network kick/ban/unban actions. For a solo Werewolf test, use **Solo practice → Start practice**: select your connected Minecraft player and add a virtual cast (seven by default). Practice bots vote and act automatically, phases are capped at 15 seconds, and the cast is removed on stop or the last real player leaving. Bots are virtual menu participants, not NPCs or Minecraft accounts.

The engine stores persistent player snapshots before joining and protects inventory/gamemode edits while a snapshot is outstanding. No arena or world is automatically created on installation.

The new private Redis service requires `arena_redis_password`; regenerate missing secrets with the existing `network secrets` command before updating an older stack. Include Redis data and `plugins/NexusArenaEngine` in stopped-server backups. Empty-server pausing is disabled so management operations and timers keep responding.

See [NexusArenaEngine operations and architecture](arena-plugin/README.md) for setup, protection boundaries, restore/upgrade rules, API routes, Redis behavior, tests and Folia validation requirements.


### Vampyuyu Sanctum lobby

![Vampyuyu Sanctum block preview](docs/lobby-preview.png)

The Lobby is an original burgundy, ivory and gold floating palace: four spires, a bat courtyard, cherry gardens, reflecting pools and Creative/Survival gates. It uses only blocks; there are no NPCs, armor stands, item frames or decorative entities. English gate signs and the compass lead to the same network selector. The sky stays at early night (13500 ticks), with low lanterns, palace chandeliers, lit candles and concealed shroomlights beneath gardens and carpet. All lighting uses blocks, preserving the entity-free lobby. Inside, facing burgundy sofas, low tables, bookcases, a reading lectern, an ivory reception counter, guest seating and potted plants furnish the hall around a central seated cat statue, with clear passages on both sides. The block-only furniture contains no loot or decorative entities.

For an existing Sanctum, `vampyuyu:warm_lights` is a guarded lighting-only upgrade: it changes a block only when the original expected material is still present. Back up and load the footprint before applying it; never rerun `build_once` to update lighting. `vampyuyu:furniture` applies the same guarded approach to the interior furniture; it only replaces the expected original blocks. `vampyuyu:cat_statue` replaces the former bat centerpiece with a seated blackstone cat: pointed ears, glowing amber eyes, ivory paws and muzzle, a burgundy collar with a gold bell, and a curled tail. The guarded migration removes the former wings and preserves the ivory/gold plinth and both side aisles. Fresh worlds build the cat directly. `vampyuyu:night_sky` adds five static bat silhouettes above the courtyard at staggered heights. Warm shroomlight cores illuminate their dark wings and burgundy membranes, keeping them visible without shaders, spawned bats or other entities. It also fixes clear weather and time at 13500 ticks. All upgrade functions are operator-run; normal restarts never reapply them.

A native 100-block world border and invisible perimeter prevent escapes. Falling returns players to spawn. All non-player entity spawning, item pickup, item dropping, mob loot, explosions, griefing and weather changes are blocked, including for OPs. Visitors cannot build or change inventory. On joining the Lobby, inventory, ender chest and XP are cleared and only the selector compass is given. These rules apply only to the Lobby, never Creative or Survival.

For maintenance, an OP explicitly enables `/lobbybuild on` (or **Server settings → Lobby → Build & inventory access** in the panel). This permits block editing and inventory management; it still forbids item drops, pickup and entity spawning. Finish with `/lobbybuild off`. Build access resets on disconnect/restart and cannot be enabled during an arena session.

`python3 scripts/generate-lobby.py` regenerates the reviewed vanilla datapack in `infra/lobby-palace`. The Lobby image includes it; `infra/start.sh` installs it only when creating a fresh Lobby world, with Minecraft **1.21.9** and an empty flat generator. Its load function builds once, tracked by `vampyuyu:lobby` storage. Normal restarts preserve edits. Existing worlds are never replaced automatically; on any other version `infra/start.sh` only removes the palace datapack it installed earlier (its 1.21.9 `gamerule` syntax no longer parses), keeping the built world. To replace an old Lobby, stop it, archive its complete data, move its world folders aside, then start a new Lobby. Never apply the build function to an inhabited world. The local pre-palace world is preserved under `backups/lobby-before-palace-*`.

### Creative warps and global profile (VampyuyuNetwork 0.5)

Creative players can publish one public warp by default. `/warp` asks for a name and captures position/orientation when issued; `/warpadd <name>` creates directly. Names use 1–32 ASCII letters, digits, `_` or `-`, unique ignoring case. `/warps` opens a paged burgundy/ivory/gold menu with descriptions and owners. Select a warp to visit, or use `/warptp <name>`. Owners can edit descriptions (240 characters) and confirm deletion in the menu. Cancel chat input with `cancel` (or `annuler`).

`/warpadmin limit <player|uuid> <count>` persists a personal quota, including zero; lowering it preserves existing warps. OPs have `vampyuyu.warp.admin` and `vampyuyu.warp.unlimited`; these are separately delegatable permissions, independent of cosmetic roles. Data lives in `plugins/VampyuyuNetwork/warps.json`. Warps are available only on Creative outside arena participation/recovery. Destinations are checked for world availability, collisions, supporting floor, hazards and world border.

`/profil` on Lobby, Creative and Survival shows the central **Shards** balance and event history outside an arena. Rewards, `/moneyadmin`, village setup and recovery are documented in [the arena guide](arena-plugin/README.md#village-immersion-and-in-game-administration-13). Deploy the panel and both plugins together after emptying arenas. Existing arena JSON remains compatible. No map construction or live deployment is automatic.

## Menu resource pack (optional)

`resource-pack/pack` holds an optional resource pack that paints the plugin menus (full-window backgrounds drawn as 4×N font-glyph tiles of at most 176×222 px, because Minecraft stitches glyphs into 256×256 atlas pages; invisible or themed item models). Vanilla chests are untouched: only titles that begin with the pack's glyph change.

* Every backend offers it on join (`MENU_PACK_BASE_URL` + the SHA-1 baked into the image at `/opt/vampyuyu-menus.sha1`). Players who decline or fail to load it keep the plain glass-pane menus: the layout is chosen per player after the client reports `SUCCESSFULLY_LOADED`.
* `MENU_PACK_REQUIRED=true` forces the download: the client must accept the pack or is disconnected from the server. Leave it off until `MENU_PACK_BASE_URL` is reachable by players, otherwise nobody can join. When forced, nobody ever sees the plain fallback menus.
* The panel serves the ZIP publicly, and only by content hash: `GET /pack/<sha1>.zip` (immutable, cacheable). `make pack` builds the same reproducible ZIP locally; `make art` repaints the PNGs (needs headless Chrome); `make test-pack` validates the structure.
* Painted: `/menu`, the arena setup/list/houses/doors/detail/confirm menus (`/arena admin`) and the warp list/detail/confirmation. The arena compass (heads on a council ring) and `/profil` are painted too.
* Rendering inside the game client is not covered by automated tests. After changing art or fonts, open each menu once on a 1.21.9 client.

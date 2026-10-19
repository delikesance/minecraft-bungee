/* Local Linux integration smoke. Requires minecraft-protocol@1.68.0 through NODE_PATH.
 * Uses correctly signed PRIVATE forwarding with disposable UUIDs. Never disables auth.
 * Refuses to run while any real players are connected. Restores/removes its own arenas.
 */
const mc = require("minecraft-protocol");
const crypto = require("node:crypto"),
  fs = require("node:fs"),
  cp = require("node:child_process"),
  path = require("node:path");
const origin = "http://localhost:5173",
  root = process.cwd();
const config = JSON.parse(
  cp.execFileSync(
    "docker",
    ["compose", "-f", "compose.local.yaml", "config", "--format", "json"],
    { encoding: "utf8" },
  ),
);
const secretsDir = path.dirname(config.secrets.role_bridge_token.file),
  key = fs
    .readFileSync(path.join(secretsDir, "forwarding_secret"), "utf8")
    .trim();
const arenaId = "smoke-" + crypto.randomBytes(5).toString("hex"),
  clients = [],
  ids = [],
  servers = ["lobby", "creative", "survival"];
let cookie = "",
  csrf = "",
  created = false,
  fixtureConfig;
const pause = (ms) => new Promise((r) => setTimeout(r, ms));
function assert(value, message) {
  if (!value) throw new Error(message);
}
async function api(route, method = "GET", body, expected = 200) {
  const response = await fetch("http://127.0.0.1:8080/api/v1" + route, {
    method,
    headers: {
      Origin: origin,
      Cookie: cookie,
      "Content-Type": "application/json",
      "X-CSRF-Token": csrf,
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  assert(
    response.status === expected,
    `${method} ${route}: expected ${expected}, got ${response.status} ${text.slice(0, 200)}`,
  );
  return { data: JSON.parse(text), response };
}
async function until(fn, message, limit = 40) {
  for (let i = 0; i < limit; i++) {
    const result = await fn();
    if (result) return result;
    await pause(300);
  }
  throw new Error(message);
}
async function state(server = "survival") {
  return (await api(`/servers/${server}/game/arenas`)).data;
}
async function action(action, extra = {}, expected = 200) {
  return api(
    "/servers/survival/game/action",
    "POST",
    { action, arenaId, ...extra },
    expected,
  );
}
function vi(value) {
  const out = [];
  do {
    let b = value & 127;
    value >>>= 7;
    if (value) b |= 128;
    out.push(b);
  } while (value);
  return Buffer.from(out);
}
function str(text) {
  const bytes = Buffer.from(text);
  return Buffer.concat([vi(bytes.length), bytes]);
}
async function connect(id, name, server = "survival") {
  const info = JSON.parse(
    cp.execFileSync("docker", ["inspect", `${config.name}-${server}-1`], {
      encoding: "utf8",
    }),
  )[0];
  const client = mc.createClient({
    host: Object.values(info.NetworkSettings.Networks)[0].IPAddress,
    port: 25565,
    version: "1.21.9",
    username: name,
    auth: "offline",
    clientSettings: { viewDistance: 2, chatFlags: 0 },
  });
  clients.push(client);
  client.fixture = {
    id,
    name,
    server,
    borders: [],
    windows: [],
    messages: [],
    positions: [],
  };
  client.removeAllListeners("login_plugin_request");
  client.on("login_plugin_request", (packet) => {
    if (packet.channel !== "velocity:player_info") {
      client.write("login_plugin_response", { messageId: packet.messageId });
      return;
    }
    const payload = Buffer.concat([
      vi(1),
      str("127.0.0.1"),
      Buffer.from(id.replaceAll("-", ""), "hex"),
      str(name),
      vi(0),
    ]);
    client.write("login_plugin_response", {
      messageId: packet.messageId,
      data: Buffer.concat([
        crypto.createHmac("sha256", key).update(payload).digest(),
        payload,
      ]),
    });
  });
  client.on("position", (packet) => {
    client.fixture.positions.push(packet);
    if (packet.teleportId !== undefined)
      client.write("teleport_confirm", { teleportId: packet.teleportId });
  });
  client.on("packet", (packet, meta) => {
    if (meta.name.includes("world_border"))
      client.fixture.borders.push({ type: meta.name, ...packet });
  });
  client.on("window_items", (packet) => client.fixture.windows.push(packet));
  client.on("system_chat", (packet) =>
    client.fixture.messages.push(JSON.stringify(packet)),
  );
  client.on("error", () => {});
  await new Promise((resolve, reject) => {
    const timeout = setTimeout(
      () => reject(new Error("Minecraft client login timeout")),
      15000,
    );
    client.once("login", () => {
      clearTimeout(timeout);
      resolve();
    });
    client.once("disconnect", (packet) => {
      clearTimeout(timeout);
      reject(new Error("Login rejected: " + JSON.stringify(packet)));
    });
  });
  await pause(500);
  return client;
}
function command(client, text) {
  client.write("chat_command", { command: text });
}
async function inventory(id) {
  return (await api(`/servers/survival/game/players/${id}/inventory`)).data;
}
async function check() {
  try {
    const login = await api("/login", "POST", {
      username: "admin",
      password: fs
        .readFileSync(path.join(secretsDir, "admin_password"), "utf8")
        .trim(),
    });
    cookie = login.response.headers.get("set-cookie").split(";")[0];
    csrf = login.data.csrf;
    for (const server of servers) {
      const status = await until(
        async () => {
          try {
            const value = await state(server);
            return value.redisConnected ? value : false;
          } catch {
            return false;
          }
        },
        "Arena service did not become ready: " + server,
        120,
      );
      assert(status.redisConnected, "Redis not connected on " + server);
      assert(
        status.onlinePlayers.length === 0,
        "Refusing smoke test: real players are online",
      );
    }
    // Recover journals left by an interrupted earlier smoke, using only recorded test UUIDs.
    if (fs.existsSync("/tmp/nexus-arena-smoke-fixtures.json"))
      for (const old of JSON.parse(
        fs.readFileSync("/tmp/nexus-arena-smoke-fixtures.json", "utf8"),
      )) {
        if (!servers.includes(old.server) || !/^[a-f0-9-]{36}$/.test(old.id))
          continue;
        const data = config.services[old.server].volumes.find(
          (v) => v.target === "/data",
        ).source;
        const journal = path.join(
          data,
          "plugins/NexusArenaEngine/snapshots",
          old.id + ".yml",
        );
        if (fs.existsSync(journal)) {
          const recovery = await connect(old.id, "ArenaRecover", old.server);
          await until(
            () => !fs.existsSync(journal),
            "Interrupted smoke journal did not recover",
          );
          recovery.end("Smoke journal recovered");
          await pause(500);
        }
      }
    for (const old of (await state()).arenas)
      if (
        old.config.name === "Disposable arena smoke" &&
        old.config.id.startsWith("smoke-") &&
        old.players.length === 0
      )
        await action("arena-delete", { arenaId: old.config.id });
    const world = (await state()).worlds.find((w) => w.name === "world");
    assert(world, "Missing world");
    const x = Math.floor(world.spawn.x),
      z = Math.floor(world.spawn.z);
    fixtureConfig = {
      id: arenaId,
      name: "Disposable arena smoke",
      worldName: "world",
      preset: "werewolf",
      werewolf: {
        roles: { WEREWOLF: 1, SEER: 1 },
        cupidSeconds: 5,
        witchSeconds: 5,
        hunterSeconds: 5,
      },
      bounds: {
        minX: x - 20,
        minY: world.minY,
        minZ: z - 20,
        maxX: x + 20,
        maxY: world.maxY,
        maxZ: z + 20,
      },
      spawn: { ...world.spawn, yaw: 0, pitch: 0 },
      allowedBreakBlocks: [],
      allowedPlaceBlocks: [],
      allowPvP: false,
      allowChests: false,
      allowDoors: true,
      gameMode: "ADVENTURE",
      durationSeconds: 1800,
      minPlayers: 4,
      maxPlayers: 8,
      discussionSeconds: 5,
      voteSeconds: 8,
      nightSeconds: 8,
    };
    await action("arena-save", { config: fixtureConfig });
    created = true;
    await action(
      "arena-save",
      { config: { ...fixtureConfig, id: "../escape" } },
      400,
    );
    await action(
      "arena-save",
      { config: { ...fixtureConfig, id: arenaId + "-overlap" } },
      400,
    );
    await action(
      "arena-save",
      {
        config: {
          ...fixtureConfig,
          bounds: {
            ...fixtureConfig.bounds,
            minX: fixtureConfig.bounds.maxX + 1,
          },
        },
      },
      400,
    );
    const bots = [];
    for (let i = 0; i < 4; i++) {
      const id = crypto.randomUUID();
      ids.push({ id, server: "survival" });
      bots.push(await connect(id, "ArenaTest" + i));
      await api("/servers/survival/command", "POST", {
        command: "minecraft:gamemode creative ArenaTest" + i,
      });
    }
    const outsideId = crypto.randomUUID();
    ids.push({ id: outsideId, server: "survival" });
    const outside = await connect(outsideId, "ArenaOutside");
    await api("/servers/survival/command", "POST", {
      command: "minecraft:gamemode creative ArenaOutside",
    });
    const observerId = crypto.randomUUID();
    ids.push({ id: observerId, server: "lobby" });
    const observer = await connect(observerId, "ArenaObserver", "lobby");
    await until(async () => {
      const directory = (await api("/players?search=ArenaTest")).data;
      return (
        directory.players.filter((p) => ids.some((x) => x.id === p.uuid))
          .length === 4
      );
    }, "Player directory did not synchronize");
    const first = ids[0].id;
    await api("/servers/survival/game/action", "POST", {
      action: "gamemode",
      uuid: first,
      value: "CREATIVE",
    });
    let view = await inventory(first);
    await api(`/servers/survival/game/players/${first}/inventory`, "PUT", {
      revision: view.revision,
      slot: 0,
      material: "minecraft:diamond_sword",
      amount: 1,
    });
    const before = await inventory(first),
      borders = bots[0].fixture.borders.length,
      observerBorders = observer.fixture.borders.length,
      outsideBorders = outside.fixture.borders.length;
    await action("arena-join", { uuid: first });
    view = await inventory(first);
    assert(view.player.gamemode === "ADVENTURE", "Arena mode was not applied");
    assert(
      view.slots[0].amount === 0 &&
        view.slots[8].material === "minecraft:compass",
      "Arena inventory not isolated",
    );
    await until(
      () => bots[0].fixture.borders.length > borders,
      "No per-player border packet",
    );
    assert(
      observer.fixture.borders.length === observerBorders,
      "Unrelated player border changed",
    );
    assert(
      outside.fixture.borders.length === outsideBorders,
      "Nonparticipant in the same world received arena border packets",
    );
    await api(
      `/servers/survival/game/players/${first}/inventory`,
      "PUT",
      {
        revision: view.revision,
        slot: 8,
        material: "minecraft:air",
        amount: 0,
      },
      409,
    );
    await api(
      "/servers/survival/game/action",
      "POST",
      { action: "gamemode", uuid: first, value: "SURVIVAL" },
      409,
    );
    command(bots[0], "arena");
    await until(
      () => bots[0].fixture.windows.some((w) => w.items.length === 90),
      "Compass GUI did not open",
    );
    await action("arena-save", { config: fixtureConfig }, 400);
    await action("arena-leave", { uuid: first });
    view = await inventory(first);
    assert(
      view.player.gamemode === before.player.gamemode &&
        view.slots[0].material === "minecraft:diamond_sword",
      "Original inventory/mode not restored",
    );
    assert(
      Math.abs(view.player.x - before.player.x) < 0.1 &&
        Math.abs(view.player.z - before.player.z) < 0.1,
      "Original position not restored",
    );
    await action("arena-join", { uuid: first });
    bots[0].end("Reconnect recovery smoke");
    await pause(600);
    bots[0] = await connect(first, "ArenaTest0");
    await until(async () => {
      const v = await inventory(first);
      return (
        v.player.gamemode === "CREATIVE" &&
        v.slots[0].material === "minecraft:diamond_sword"
      );
    }, "Disconnect snapshot did not recover");
    for (const { id } of ids.slice(0, 4))
      await action("arena-join", { uuid: id });
    // Only fixture players are connected. Exercise real Redis delivery and click components.
    for (const server of servers)
      assert(
        (await state(server)).onlinePlayers.every((p) =>
          ids.some((x) => x.id === p.uuid),
        ),
        "Real player joined; skip broadcast",
      );
    await action("arena-announce", {
      message: "Disposable arena invitation smoke",
    });
    await until(
      () =>
        observer.fixture.messages.some(
          (m) =>
            m.includes("Disposable arena invitation smoke") &&
            m.includes("run_command") &&
            m.includes(`survival:${arenaId}`),
        ),
      "Cross-server clickable invitation missing",
    );
    await action("arena-start");
    await until(
      async () =>
        (await state()).arenas.find((a) => a.config.id === arenaId)?.phase ===
        "DAY_VOTE",
      "Day vote phase missing",
    );
    assert(
      (await state()).arenas.find((a) => a.config.id === arenaId).players
        .length === 4,
      "Participant status missing",
    );
    for (const bot of bots) command(bot, "arena");
    await pause(700);
    const roles = bots.map((bot) => {
      const window = bot.fixture.windows
        .filter((w) => w.items.length === 90)
        .at(-1);
      assert(window, "Missing role GUI");
      const match = JSON.stringify(window.items[45]).match(
        /Your secret role: ([A-Z]+)/,
      );
      assert(match, "Missing private role reminder");
      return match[1];
    });
    const victim = roles.findIndex((role) => role === "VILLAGER");
    assert(victim >= 0, "No villager assigned");
    for (let i = 0; i < bots.length; i++)
      if (i !== victim) {
        const window = bots[i].fixture.windows
          .filter((w) => w.items.length === 90)
          .at(-1);
        const slot = window.items.findIndex(
          (item, index) =>
            index < 45 && JSON.stringify(item).includes("ArenaTest" + victim),
        );
        assert(slot >= 0, "Missing living player head");
        bots[i].write("window_click", {
          windowId: window.windowId,
          stateId: window.stateId,
          slot,
          mouseButton: 0,
          mode: 0,
          changedSlots: [],
          cursorItem: null,
        });
      }
    await until(async () => {
      const arena = (await state()).arenas.find((a) => a.config.id === arenaId);
      return (
        arena.phase === "NIGHT" &&
        arena.players.filter((p) => p.alive).length === 3
      );
    }, "GUI majority vote did not eliminate its target");
    await action("arena-stop");
    await until(async () => {
      const arena = (await state()).arenas.find((a) => a.config.id === arenaId);
      return arena.phase === "WAITING" && arena.players.length === 0;
    }, "Stop did not restore all players");
    assert(
      (await inventory(first)).slots[0].material === "minecraft:diamond_sword",
      "Stop lost original items",
    );
    // BAN and UNBAN are real Redis events affecting only this temporary UUID.
    await action("arena-ban", {
      uuid: first,
      reason: "Disposable network ban smoke",
    });
    await until(() => bots[0].ended, "Network ban did not kick test client");
    await until(
      async () =>
        (await state("creative")).bans[first] ===
        "Disposable network ban smoke",
      "BAN did not reach Creative",
    );
    let rejected = false;
    try {
      await connect(first, "ArenaTest0", "lobby");
    } catch (e) {
      rejected = String(e).includes("Network ban");
    }
    assert(rejected, "Network ban did not reject backend login");
    await action("arena-unban", { uuid: first, reason: "Smoke cleanup" });
    await until(
      async () => !(await state("creative")).bans[first],
      "UNBAN did not synchronize",
    );
    console.log(
      "PASS: panel auth/arena CRUD and bounds validation; real clients; private borders; isolated inventory + locked admin edits; compass GUI; leave/reconnect restoration; Redis clickable cross-server invite; Werewolf phases and real compass-GUI ballots; stop/restore; network ban/login rejection/unban.",
    );
  } finally {
    if (created) {
      try {
        await action("arena-stop");
      } catch {}
      try {
        await action("arena-delete");
      } catch {}
    }
    for (const client of clients)
      try {
        client.end("Arena smoke cleanup");
      } catch {}
    await pause(1000);
    // Save fixture identity for optional stopped-server cleanup, never touch real UUIDs.
    const oldFixtures = fs.existsSync("/tmp/nexus-arena-smoke-fixtures.json")
      ? JSON.parse(
          fs.readFileSync("/tmp/nexus-arena-smoke-fixtures.json", "utf8"),
        )
      : [];
    fs.writeFileSync(
      "/tmp/nexus-arena-smoke-fixtures.json",
      JSON.stringify([...oldFixtures, ...ids]),
    );
    if (cookie)
      try {
        await api("/logout", "POST");
      } catch {}
  }
}
check().then(
  () => process.exit(0),
  (error) => {
    console.error(error);
    process.exit(1);
  },
);

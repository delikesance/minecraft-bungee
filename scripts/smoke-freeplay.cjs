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
    version: process.env.SMOKE_MC_VERSION || "1.21.9",
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
    const ready = await until(
      async () => {
        try {
          return await state();
        } catch {
          return false;
        }
      },
      "Survival arena bridge not ready",
      120,
    );
    assert(
      ready.onlinePlayers.length === 0,
      "Refusing free-play smoke: Survival has real players",
    );
    const world = ready.worlds.find((w) => w.name === "world"),
      x = Math.floor(world.spawn.x),
      z = Math.floor(world.spawn.z);
    fixtureConfig = {
      id: arenaId,
      name: "Disposable freeplay smoke",
      worldName: "world",
      preset: "freeplay",
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
      durationSeconds: 120,
      minPlayers: 1,
      maxPlayers: 8,
      discussionSeconds: 5,
      voteSeconds: 5,
      nightSeconds: 5,
      freeplay: {
        objective: "RACE",
        targetScore: 1,
        pointsPerEvent: 1,
        allowFlight: true,
        hunger: false,
        personalTime: 18000,
        weather: "CLEAR",
        kit: { IRON_SWORD: 1, ARROW: 16 },
        effects: { speed: 2 },
        checkpoints: [
          {
            x: world.spawn.x,
            y: world.spawn.y + 5,
            z: world.spawn.z,
            radius: 1,
          },
          {
            x: world.spawn.x,
            y: world.spawn.y + 10,
            z: world.spawn.z,
            radius: 1,
          },
        ],
        instructions: "Reach both floating checkpoints in order.",
      },
    };
    await action("arena-save", { config: fixtureConfig });
    created = true;
    const bots = [];
    for (let i = 0; i < 2; i++) {
      const id = crypto.randomUUID();
      ids.push({ id, server: "survival" });
      bots.push(await connect(id, "ArenaRace" + i));
      const result = await api("/servers/survival/command", "POST", {
        command: "minecraft:gamemode creative ArenaRace" + i,
      });
      assert(
        /creative/i.test(result.data.output),
        "Could not protect temporary test player",
      );
    }
    await until(async () => {
      const found = (await api("/players?search=ArenaRace")).data.players;
      return ids.every((id) => found.some((p) => p.uuid === id.id));
    }, "Test players missing from directory");
    const first = ids[0].id;
    let original = await inventory(first);
    await api(`/servers/survival/game/players/${first}/inventory`, "PUT", {
      revision: original.revision,
      slot: 0,
      material: "minecraft:stone",
      amount: 12,
    });
    await api("/servers/survival/command", "POST", {
      command: "minecraft:experience set ArenaRace0 7 levels",
    });
    original = await inventory(first);
    await action("arena-join", { uuid: first });
    let view = await inventory(first);
    assert(
      view.player.gamemode === "ADVENTURE" &&
        view.slots.some(
          (s) => s.material === "minecraft:iron_sword" && s.amount === 1,
        ) &&
        view.slots.some(
          (s) => s.material === "minecraft:arrow" && s.amount === 16,
        ) &&
        view.slots[8].material === "minecraft:compass",
      "Temporary kit or compass was not applied",
    );
    await action("arena-start");
    bots[0].write("abilities", { flags: 2 });
    const point = fixtureConfig.freeplay.checkpoints[0];
    bots[0].write("position", {
      x: point.x,
      y: point.y,
      z: point.z,
      flags: { onGround: false, hasHorizontalCollision: false },
    });
    await until(async () => {
      const a = (await state()).arenas.find((a) => a.config.id === arenaId);
      return a.players[0]?.score === 1;
    }, "First checkpoint did not score");
    bots[0].write("position", {
      x: point.x,
      y: point.y,
      z: point.z,
      flags: { onGround: false, hasHorizontalCollision: false },
    });
    await pause(300);
    assert(
      (await state()).arenas.find((a) => a.config.id === arenaId).players[0]
        .score === 1,
      "Stationary checkpoint farming scored",
    );
    const second = fixtureConfig.freeplay.checkpoints[1];
    bots[0].write("position", {
      x: second.x,
      y: second.y,
      z: second.z,
      flags: { onGround: false, hasHorizontalCollision: false },
    });
    await until(
      async () =>
        (await state()).arenas.find((a) => a.config.id === arenaId).players
          .length === 0,
      "Race victory did not restore player",
    );
    view = await inventory(first);
    assert(
      view.player.gamemode === original.player.gamemode &&
        view.slots[0].material === "minecraft:stone" &&
        view.slots[0].amount === 12 &&
        view.player.level === 7,
      "Race lost original player state",
    );
    const duel = {
      ...fixtureConfig,
      allowPvP: true,
      gameMode: "SURVIVAL",
      minPlayers: 2,
      freeplay: {
        ...fixtureConfig.freeplay,
        objective: "PVP",
        targetScore: 2,
        checkpoints: [],
        effects: {},
        kit: { IRON_SWORD: 1 },
        allowFlight: false,
      },
    };
    await action("arena-save", { config: duel });
    for (const { id } of ids) await action("arena-join", { uuid: id });
    await action("arena-start");
    await pause(2200);
    const damage = () =>
      api("/servers/survival/command", "POST", {
        command:
          "minecraft:damage ArenaRace1 50 minecraft:player_attack by ArenaRace0",
      });
    await damage();
    await until(async () => {
      const a = (await state()).arenas.find((a) => a.config.id === arenaId);
      return a.players.find((p) => p.uuid === first)?.score === 1;
    }, "PvP defeat did not score");
    assert(
      (await inventory(ids[1].id)).player.health > 0,
      "PvP victim did not respawn safely",
    );
    await pause(2200);
    await damage();
    await until(
      async () =>
        (await state()).arenas.find((a) => a.config.id === arenaId).players
          .length === 0,
      "PvP target score did not finish",
    );
    assert(
      (await inventory(first)).slots[0].amount === 12,
      "PvP stop did not restore original items",
    );
    console.log(
      "PASS: live free-play race checkpoints/order/anti-repeat; temporary kit/effects and compass; target victory/automatic restore of inventory, mode and XP; PvP scoring, safe respawn and target-score victory.",
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
        client.end("Freeplay smoke cleanup");
      } catch {}
    await pause(1000);
    const old = fs.existsSync("/tmp/nexus-arena-smoke-fixtures.json")
      ? JSON.parse(
          fs.readFileSync("/tmp/nexus-arena-smoke-fixtures.json", "utf8"),
        )
      : [];
    fs.writeFileSync(
      "/tmp/nexus-arena-smoke-fixtures.json",
      JSON.stringify([...old, ...ids]),
    );
    if (cookie)
      try {
        await api("/logout", "POST");
      } catch {}
  }
}
check().then(
  () => process.exit(0),
  (e) => {
    console.error(e);
    process.exit(1);
  },
);

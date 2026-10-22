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
      "Survival not ready",
      160,
    );
    assert(ready.onlinePlayers.length === 0, "Survival has real players");
    const world = ready.worlds.find((w) => w.name === "world");
    fixtureConfig = JSON.parse(
      fs.readFileSync("arena-plugin/src/main/resources/presets/werewolf.json"),
    );
    Object.assign(fixtureConfig, {
      id: arenaId,
      name: "Disposable solo practice",
      worldName: "world",
      maxPlayers: 8,
      discussionSeconds: 5,
      voteSeconds: 5,
      nightSeconds: 5,
      durationSeconds: 180,
      bounds: {
        minX: world.spawn.x - 20,
        minY: world.minY,
        minZ: world.spawn.z - 20,
        maxX: world.spawn.x + 20,
        maxY: world.maxY,
        maxZ: world.spawn.z + 20,
      },
      spawn: { ...world.spawn, yaw: 0, pitch: 0 },
      werewolf: {
        roles: {},
        cupidSeconds: 5,
        witchSeconds: 5,
        hunterSeconds: 5,
      },
    });
    await action("arena-save", { config: fixtureConfig });
    created = true;
    await action("arena-practice-start", { botCount: 7 }, 400);
    const id = crypto.randomUUID();
    ids.push({ id, server: "survival" });
    let client = await connect(id, "PracticeSmoke");
    await until(async () => {
      try {
        return await inventory(id);
      } catch {
        return false;
      }
    }, "Inventory unavailable");
    await api("/servers/survival/command", "POST", {
      command: "minecraft:gamemode creative PracticeSmoke",
    });
    let before = await inventory(id);
    await api(`/servers/survival/game/players/${id}/inventory`, "PUT", {
      revision: before.revision,
      slot: 0,
      material: "minecraft:stone",
      amount: 12,
    });
    for (const count of [0, 45, 1.5, 8])
      await action("arena-practice-start", { botCount: count, uuid: id }, 400);
    assert(
      (await inventory(id)).slots[0].amount === 12,
      "Rejected practice changed inventory",
    );
    await action("arena-practice-start", { botCount: 7, uuid: id });
    let a = (await state()).arenas.find((a) => a.config.id === arenaId);
    assert(
      a.practice &&
        a.players.length === 8 &&
        a.players.filter((p) => p.bot).length === 7,
      "Incorrect virtual cast",
    );
    const bots = a.players.filter((p) => p.bot).map((p) => p.uuid);
    const data = config.services.survival.volumes.find(
      (v) => v.target === "/data",
    ).source;
    assert(
      fs.existsSync(
        path.join(data, "plugins/NexusArenaEngine/snapshots", id + ".yml"),
      ),
      "Human snapshot missing",
    );
    for (const bot of bots) {
      assert(
        !fs.existsSync(path.join(data, "world/playerdata", bot + ".dat")),
        "Bot created playerdata",
      );
      assert(
        !fs.existsSync(
          path.join(data, "plugins/NexusArenaEngine/snapshots", bot + ".yml"),
        ),
        "Bot created snapshot",
      );
    }
    const phases = new Set([a.phase]);
    await until(
      async () => {
        const a = (await state()).arenas.find((a) => a.config.id === arenaId);
        phases.add(a.phase);
        return phases.size >= 3;
      },
      "Practice phases did not progress automatically",
      140,
    );
    command(client, "menu");
    await pause(300);
    command(client, "arena");
    await pause(300);
    await action("arena-stop");
    a = (await state()).arenas.find((a) => a.config.id === arenaId);
    assert(
      !a.practice && a.phase === "WAITING" && a.players.length === 0,
      "Virtual cast not cleared on stop",
    );
    assert(
      (await inventory(id)).slots[0].amount === 12,
      "Practice failed to restore inventory",
    );
    await action("arena-practice-start", { botCount: 7, uuid: id });
    client.end("Practice disconnect cleanup");
    await until(async () => {
      const a = (await state()).arenas.find((a) => a.config.id === arenaId);
      return !a.practice && a.phase === "WAITING" && a.players.length === 0;
    }, "Last real disconnect left virtual players running");
    await pause(600);
    client = await connect(id, "PracticeSmoke");
    await until(async () => {
      const v = await inventory(id);
      return (
        v.slots[0].amount === 12 &&
        !fs.existsSync(
          path.join(data, "plugins/NexusArenaEngine/snapshots", id + ".yml"),
        )
      );
    }, "Reconnect did not restore the real player");
    console.log(
      "PASS: authenticated solo practice starts with one human + seven virtual players; invalid counts/no-human rejected; automatic phases; no bot accounts/snapshots; stop and last-human disconnect clear bots; inventory restored on stop/reconnect.",
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
    for (const c of clients)
      try {
        c.end("Practice smoke cleanup");
      } catch {}
    await pause(1000);
    const p = "/tmp/nexus-arena-smoke-fixtures.json";
    const old = fs.existsSync(p) ? JSON.parse(fs.readFileSync(p)) : [];
    fs.writeFileSync(p, JSON.stringify([...old, ...ids]));
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

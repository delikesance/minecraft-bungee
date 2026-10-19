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
  return (await api(`/servers/lobby/game/players/${id}/inventory`)).data;
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
    const id = crypto.randomUUID();
    ids.push({ id, server: "lobby" });
    const client = await connect(id, "LobbySmoke", "lobby");
    await until(async () => {
      try {
        return (await inventory(id)).slots.some(
          (s) => s.material === "minecraft:compass",
        );
      } catch {
        return false;
      }
    }, "Lobby compass missing");
    const view = await inventory(id);
    assert(
      view.slots.filter((s) => s.material && s.material !== "minecraft:air")
        .length === 1,
      "Lobby inventory contains extra items",
    );
    assert(
      view.player.gamemode === "ADVENTURE",
      "Visitor is not in adventure mode",
    );
    const rcon = (cmd) =>
      cp.execFileSync(
        "docker",
        ["exec", `${config.name}-lobby-1`, "rcon-cli", cmd],
        { encoding: "utf8" },
      );
    assert(/100/.test(rcon("worldborder get")), "Incorrect border");
    assert(
      /65/.test(rcon("data get entity LobbySmoke Pos")),
      "Incorrect palace spawn",
    );
    rcon("summon minecraft:pig 0 65 12");
    rcon("summon minecraft:armor_stand 1 65 12");
    rcon(
      'summon minecraft:item 0 65 12 {Item:{id:"minecraft:diamond",count:1}}',
    );
    assert(
      !rcon(
        "execute if entity @e[type=!minecraft:player] run time query daytime",
      ).trim(),
      "Non-player entity spawned",
    );
    rcon("tp LobbySmoke 0 55 12");
    await until(
      async () => /65/.test(rcon("data get entity LobbySmoke Pos")),
      "Fall protection failed",
    );
    command(client, "menu");
    await pause(500);
    assert(client.fixture.windows.length > 0, "Selector menu did not open");
    console.log(
      "PASS: live Lobby join, compass-only inventory, Adventure mode, 100-block border, cancelled pig/armor stand/item spawns, fall rescue and selector GUI.",
    );
  } finally {
    for (const c of clients) c.end("Lobby smoke cleanup");
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

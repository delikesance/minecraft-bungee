/* Isolated Paper 1.21.9 feature smoke. No existing servers/worlds are started or modified.
 * Requires minecraft-protocol@1.68.0, built plugin JARs, and the Go bridge test binary.
 * Usage: NODE_PATH=/tmp/community-smoke-node/node_modules node scripts/smoke-community.cjs
 * BRIDGE_FIXTURE_BIN defaults to /tmp/community-smoke-bin/bridge.test.
 * SMOKE_PAPER_DIR is a read-only source of cached Paper runtime files and accepted eula.txt.
 */
const fs = require("node:fs"),
  path = require("node:path"),
  os = require("node:os"),
  cp = require("node:child_process"),
  crypto = require("node:crypto"),
  mc = require("minecraft-protocol");
const ambienceSmoke = process.env.AMBIENCE_SMOKE_ONLY === "1";
const soundNames = require("minecraft-data")("1.21.9").sounds;
const root = process.cwd(),
  temp = fs.mkdtempSync(path.join(os.tmpdir(), "community-smoke-"));
const name = "community-smoke-" + crypto.randomBytes(4).toString("hex");
const token = crypto.randomBytes(32).toString("hex"),
  key = crypto.randomBytes(32).toString("hex");
const clients = [],
  ids = Array.from({ length: 6 }, () => crypto.randomUUID());
let java,
  logs = "",
  bridgePort,
  apiPort,
  gamePort;
const pause = (ms) => new Promise((r) => setTimeout(r, ms));
const assert = (value, msg) => {
  if (!value) throw new Error(msg);
};
const docker = (...args) =>
  cp
    .execFileSync("docker", args, {
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
    })
    .trim();
async function until(fn, label, attempts = 120) {
  for (let i = 0; i < attempts; i++) {
    const v = await fn();
    if (v) return v;
    await pause(250);
  }
  throw new Error(label);
}
function port(container, p) {
  return Number(docker("port", container, p).split(":").at(-1));
}
async function api(action, extra = {}, expected = 200) {
  const response = await fetch(
    "http://127.0.0.1:" + apiPort + (action ? "/arenas/action" : "/arenas"),
    {
      method: action ? "POST" : "GET",
      headers: {
        Authorization: "Bearer " + token,
        "Content-Type": "application/json",
      },
      body: action
        ? JSON.stringify({ action, arenaId: "smoke", ...extra })
        : undefined,
    },
  );
  const data = await response.json();
  assert(response.status === expected, JSON.stringify(data));
  return data;
}
async function profile(id) {
  const r = await fetch("http://127.0.0.1:" + bridgePort + "/bridge/profile", {
    method: "POST",
    headers: {
      Authorization: "Bearer " + token,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ uuid: id, page: 0 }),
  });
  assert(r.status === 200, "profile unavailable");
  return r.json();
}
function vi(value) {
  const b = [];
  do {
    let x = value & 127;
    value >>>= 7;
    if (value) x |= 128;
    b.push(x);
  } while (value);
  return Buffer.from(b);
}
function str(s) {
  const b = Buffer.from(s);
  return Buffer.concat([vi(b.length), b]);
}
async function connect(i) {
  const client = mc.createClient({
    host: "127.0.0.1",
    port: gamePort,
    version: process.env.SMOKE_MC_VERSION || "1.21.9",
    username: "FeatureTest" + i,
    auth: "offline",
  });
  client.fixture = {
    id: ids[i],
    messages: [],
    windows: [],
    positions: [],
    effects: [],
    sounds: [],
    titles: [],
    hud: [],
    open: [],
  };
  clients.push(client);
  client.removeAllListeners("login_plugin_request");
  client.on("login_plugin_request", (p) => {
    if (p.channel !== "velocity:player_info") {
      client.write("login_plugin_response", { messageId: p.messageId });
      return;
    }
    const payload = Buffer.concat([
      vi(1),
      str("127.0.0.1"),
      Buffer.from(ids[i].replaceAll("-", ""), "hex"),
      str("FeatureTest" + i),
      vi(0),
    ]);
    client.write("login_plugin_response", {
      messageId: p.messageId,
      data: Buffer.concat([
        crypto.createHmac("sha256", key).update(payload).digest(),
        payload,
      ]),
    });
  });
  client.on("position", (p) => {
    client.fixture.positions.push(p);
    client.write("teleport_confirm", { teleportId: p.teleportId });
  });
  client.on("window_items", (p) => client.fixture.windows.push(p));
  client.on("open_window", (p) => client.fixture.open.push(p));
  client.on("system_chat", (p) =>
    client.fixture.messages.push(JSON.stringify(p)),
  );
  client.on("packet", (p, m) => {
    if (m.name === "sound_effect")
      client.fixture.sounds.push({
        at: Date.now(),
        name: p.sound.data?.soundName || soundNames[p.sound.soundId + 1]?.name,
        packet: p,
      });
    if (m.name === "set_title_text" || m.name === "set_title_subtitle")
      client.fixture.titles.push(JSON.stringify(p));
    if (m.name === "action_bar") client.fixture.hud.push(JSON.stringify(p));
    if (m.name === "entity_effect" || m.name === "remove_entity_effect")
      client.fixture.effects.push({ type: m.name, ...p });
  });
  client.on("error", () => {});
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("Login timed out")), 20000);
    client.once("login", () => {
      clearTimeout(timer);
      resolve();
    });
    client.once("disconnect", (p) => {
      clearTimeout(timer);
      reject(new Error("Login rejected " + JSON.stringify(p)));
    });
  });
  await pause(400);
  return client;
}
function command(client, text) {
  client.write("chat_command", { command: text });
}
async function window(client, cmd) {
  const count = client.fixture.windows.length;
  command(client, cmd);
  return until(
    () => client.fixture.windows.slice(count).find((w) => w.items.length >= 54),
    "Menu " + cmd + " missing",
  );
}
function click(client, w, slot) {
  client.write("window_click", {
    windowId: w.windowId,
    stateId: w.stateId,
    slot,
    mouseButton: 0,
    mode: 0,
    changedSlots: [],
    cursorItem: null,
  });
}
function close(client) {
  const w = client.fixture.windows.at(-1);
  if (w) client.write("close_window", { windowId: w.windowId });
}
function consoleCommand(text) {
  java.stdin.write(text + "\n");
}
async function consoleUntil(text, label) {
  const before = logs.length;
  consoleCommand(text);
  return until(
    () => logs.slice(before).includes(label),
    "Console check failed: " + text,
  );
}
function position(client) {
  return client.fixture.positions.at(-1);
}
async function phase(target, attempts = 240) {
  return until(
    async () => {
      const data = await api();
      return (
        data.arenas?.find((a) => a.config.id === "smoke")?.phase === target
      );
    },
    "Phase " + target,
    attempts,
  );
}
async function startPaper() {
  const before = logs.length;
  java = cp.spawn(
    "docker",
    [
      "run",
      "--rm",
      "-i",
      "--name",
      name + "-paper",
      "--network",
      name,
      "--user",
      process.getuid() + ":" + process.getgid(),
      "-p",
      "127.0.0.1::25565",
      "-p",
      "127.0.0.1::8085",
      "-e",
      "SERVER_ID=creative",
      "-e",
      "ROLE_BRIDGE_URL=http://bridge:8083",
      "-v",
      temp + ":/data",
      "-v",
      path.join(temp, "token") + ":/run/secrets/role_bridge_token:ro",
      "-w",
      "/data",
      "--entrypoint",
      "java",
      "vampyuyu-arena-build:latest",
      "-Xms256M",
      "-Xmx1200M",
      "-jar",
      "paper.jar",
      "nogui",
    ],
    { stdio: ["pipe", "pipe", "pipe"] },
  );
  java.stdout.on("data", (b) => {
    logs += b.toString();
  });
  java.stderr.on("data", (b) => {
    logs += b.toString();
  });
  await until(
    () => logs.slice(before).includes("Done ("),
    "Paper startup",
    480,
  );
  assert(
    !logs.slice(before).includes("startup failed") &&
      !logs.slice(before).includes("Error occurred while enabling"),
    "Plugin startup failed",
  );
  apiPort = port(name + "-paper", "8085");
  gamePort = port(name + "-paper", "25565");
}
async function main() {
  const source = path.resolve(
    process.env.SMOKE_PAPER_DIR || "state/yuyu-local/survival",
  );
  assert(
    fs
      .readFileSync(path.join(source, "eula.txt"), "utf8")
      .includes("eula=true"),
    "An already accepted EULA is required",
  );
  for (const dir of ["libraries", "cache", "versions"])
    cp.execFileSync("cp", [
      "-a",
      "--reflink=auto",
      path.join(source, dir),
      temp,
    ]);
  fs.copyFileSync(
    path.join(source, "paper-1.21.9-59.jar"),
    path.join(temp, "paper.jar"),
  );
  fs.copyFileSync(path.join(source, "eula.txt"), path.join(temp, "eula.txt"));
  fs.mkdirSync(path.join(temp, "plugins"));
  fs.mkdirSync(path.join(temp, "config"));
  fs.copyFileSync(
    path.join(root, "arena-plugin/target/nexus-arena-engine.jar"),
    path.join(temp, "plugins/arena.jar"),
  );
  fs.copyFileSync(
    path.join(root, "network-plugin/target/network-display.jar"),
    path.join(temp, "plugins/network.jar"),
  );
  fs.writeFileSync(path.join(temp, "token"), token);
  fs.writeFileSync(
    path.join(temp, "ops.json"),
    JSON.stringify([
      {
        uuid: ids[0],
        name: "FeatureTest0",
        level: 4,
        bypassesPlayerLimit: true,
      },
    ]),
  );
  fs.writeFileSync(
    path.join(temp, "config/paper-global.yml"),
    "_version: 30\nproxies:\n  velocity:\n    enabled: true\n    online-mode: true\n    secret: '" +
      key +
      "'\n",
  );
  fs.writeFileSync(
    path.join(temp, "server.properties"),
    'online-mode=false\nserver-port=25565\nlevel-type=minecraft:flat\ngenerator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:stone","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}\ngenerate-structures=false\nspawn-protection=0\nview-distance=3\nsimulation-distance=3\nmax-players=10\ndifficulty=peaceful\nenforce-secure-profile=false\ngamemode=creative\n',
  );
  docker("network", "create", name);
  docker(
    "run",
    "-d",
    "--name",
    name + "-bridge",
    "--network",
    name,
    "--network-alias",
    "bridge",
    "-p",
    "127.0.0.1::8083",
    "-e",
    "COMMUNITY_BRIDGE_FIXTURE=1",
    "-e",
    "COMMUNITY_BRIDGE_TOKEN=" + token,
    "-v",
    path.resolve(
      process.env.BRIDGE_FIXTURE_BIN || "/tmp/community-smoke-bin/bridge.test",
    ) + ":/bridge:ro",
    "alpine:3.22",
    "/bridge",
    "-test.run=TestCommunityBridgeFixture",
    "-test.timeout=15m",
  );
  bridgePort = port(name + "-bridge", "8083");
  await startPaper();
  consoleCommand("forceload add -32 -32 32 32");
  await pause(1500);
  await consoleUntil("fill -20 64 -20 20 64 20 stone", "Successfully filled");
  consoleCommand("setworldspawn 0 65 0");
  consoleCommand("setblock 2 65 2 oak_door[half=lower,open=true] strict");
  consoleCommand("setblock 2 66 2 oak_door[half=upper,open=true] strict");
  await pause(1000);
  for (let i = 0; i < 6; i++) {
    await connect(i);
    consoleCommand("tp FeatureTest" + i + " 0.5 65 0.5");
  }
  await pause(500);
  console.log("Paper ready; checking warps and native menus");
  // Native menus, creative warp creation, owner quota, and global profile.
  command(clients[1], "warpadd Castle");
  await until(
    () => fs.existsSync(path.join(temp, "plugins/VampyuyuNetwork/warps.json")),
    "Warp persistence",
  );
  await window(clients[1], "warps");
  close(clients[1]);
  command(clients[1], "warpadd Extra");
  await until(
    () => clients[1].fixture.messages.some((x) => x.includes("quota")),
    "Default quota",
  );
  command(clients[0], "warpadmin limit FeatureTest1 2");
  await pause(500);
  command(clients[1], "warpadd Garden");
  await until(
    () =>
      Object.keys(
        JSON.parse(
          fs.readFileSync(
            path.join(temp, "plugins/VampyuyuNetwork/warps.json"),
          ),
        ).warps,
      ).length === 2,
    "Personal quota override",
  );
  close(clients[1]);
  command(clients[1], "warptp Castle");
  await pause(500);
  await window(clients[1], "profil");
  close(clients[1]);
  await window(clients[0], "arena admin");
  close(clients[0]);
  console.log("Warps and menus passed; checking Werewolf turns");
  // Controlled doors, houses and role turns with six actual connected clients.
  const config = JSON.parse(
    fs.readFileSync(
      path.join(root, "arena-plugin/src/main/resources/presets/werewolf.json"),
    ),
  );
  Object.assign(config, {
    id: "smoke",
    minPlayers: 6,
    maxPlayers: 6,
    discussionSeconds: 5,
    voteSeconds: 5,
    nightSeconds: 15,
    werewolf: {
      roles: { WEREWOLF: 2, GUARD: 1, SEER: 1, WITCH: 1 },
      cupidSeconds: 5,
      witchSeconds: 15,
      hunterSeconds: 5,
    },
    village: {
      enabled: true,
      houses: Object.fromEntries(
        ids.map((_, i) => [
          "house" + i,
          { x: -10 + i * 3, y: 65, z: -10, yaw: 0, pitch: 0 },
        ]),
      ),
      meeting: { x: 10, y: 65, z: 10, yaw: 0, pitch: 0 },
      daypoint: { x: 0.5, y: 65, z: 0.5, yaw: 0, pitch: 0 },
      doors: [{ x: 2, y: 65, z: 2 }],
    },
    rewards: { participation: 10, victory: 5 },
  });
  await api("arena-save", { config });
  for (const id of ids) await api("arena-join", { uuid: id });
  await api("arena-start");
  await until(
    () =>
      fs.existsSync(
        path.join(temp, "plugins/NexusArenaEngine/doors/smoke.json"),
      ),
    "Door journal",
  );
  const roles = [];
  for (const client of clients) {
    const w = await window(client, "arena");
    const role = JSON.stringify(w.items[45]).match(
      /Your secret role: ([A-Z_]+)/,
    )?.[1];
    assert(role, "Private role card missing");
    roles.push(role);
  }
  await phase("GUARD");
  await consoleUntil(
    "execute if block 2 65 2 oak_door[open=false] run say DOOR_CLOSED",
    "DOOR_CLOSED",
  );
  await phase("SEER");
  if (ambienceSmoke) {
    clients.forEach((client, i) =>
      assert(
        client.fixture.titles.some((t) => t.includes("Guard, awaken")) ===
          (roles[i] === "GUARD"),
        "Guard title leaked or missing",
      ),
    );
    const sleeping = roles.findIndex((r) => r === "VILLAGER");
    const sleepingMenu = await window(clients[sleeping], "arena");
    assert(
      JSON.stringify(sleepingMenu.items[50]).includes("The village sleeps") &&
        !JSON.stringify(sleepingMenu.items[50]).includes("seconds remaining"),
      "Secret timer leaked",
    );
    assert(
      !JSON.stringify(sleepingMenu.items[48]).includes("Seer's turn"),
      "Secret phase leaked",
    );
  }
  await phase("NIGHT");
  const wolves = roles.flatMap((r, i) => (r === "WEREWOLF" ? [i] : []));
  assert(wolves.length === 2, "Wrong wolf cast");
  await until(
    () =>
      wolves.every(
        (i) =>
          Math.abs(position(clients[i]).x - 10) < 0.01 &&
          Math.abs(position(clients[i]).z - 10) < 0.01,
      ),
    "Wolf gathering",
  );
  if (ambienceSmoke) {
    await until(
      () =>
        wolves.every(
          (i) =>
            clients[i].fixture.sounds.filter(
              (s) => s.name === "entity.warden.heartbeat",
            ).length >= 4,
        ),
      "Heartbeat pairs missing",
    );
    clients.forEach((client, i) => {
      assert(
        client.fixture.titles.some((t) => t.includes("Wolves, awaken")) ===
          wolves.includes(i),
        "Wolf title leaked or missing",
      );
      assert(
        client.fixture.sounds.some(
          (s) => s.name === "entity.warden.heartbeat",
        ) === wolves.includes(i),
        "Heartbeat leaked",
      );
      assert(
        client.fixture.sounds.filter((s) => s.name === "block.bell.use")
          .length === 1,
        "Dusk bell duplicated or missing",
      );
    });
  }
  const wolfMarker = "wolf-private-" + crypto.randomBytes(4).toString("hex");
  clients[wolves[0]].chat(wolfMarker);
  await until(
    () =>
      clients[wolves[1]].fixture.messages.some((m) => m.includes(wolfMarker)),
    "Wolf private chat missing",
  );
  await pause(250);
  assert(
    clients.every(
      (client, i) =>
        wolves.includes(i) ||
        !client.fixture.messages.some((m) => m.includes(wolfMarker)),
    ),
    "Wolf chat leaked",
  );
  const sleeper = roles.findIndex((r) => r === "VILLAGER");
  const sleepMarker = "sleeping-chat-" + crypto.randomBytes(4).toString("hex");
  clients[sleeper].chat(sleepMarker);
  await pause(500);
  assert(
    clients.every(
      (client) => !client.fixture.messages.some((m) => m.includes(sleepMarker)),
    ),
    "Sleeping chat leaked",
  );
  const home = wolves.map((i) => ({
    i,
    at: clients[i].fixture.positions
      .filter((p) => Math.abs(p.z + 10) < 0.01)
      .at(-1),
  }));
  assert(
    home.every((h) => h.at),
    "No assigned home teleport",
  );
  const target = roles.findIndex((r) => r === "VILLAGER"),
    targetName = "FeatureTest" + target;
  for (const i of wolves) {
    const client = clients[i],
      w = await window(client, "arena");
    const slot = w.items
      .slice(0, 45)
      .findIndex((item) => JSON.stringify(item).includes(targetName));
    assert(slot >= 0, "Target head missing");
    click(client, w, slot);
    await pause(250);
  }
  await phase("WITCH");
  if (ambienceSmoke) {
    const beats = clients.map(
      (c) =>
        c.fixture.sounds.filter((s) => s.name === "entity.warden.heartbeat")
          .length,
    );
    await pause(1200);
    assert(
      clients.every(
        (c, i) =>
          c.fixture.sounds.filter((s) => s.name === "entity.warden.heartbeat")
            .length === beats[i],
      ),
      "Heartbeat continued after final vote",
    );
  }
  await until(
    () =>
      home.every(
        (h) =>
          Math.abs(position(clients[h.i]).x - h.at.x) < 0.01 &&
          Math.abs(position(clients[h.i]).z - h.at.z) < 0.01,
      ),
    "Wolves did not return after last vote",
  );
  assert(
    clients.some((c) =>
      c.fixture.effects.some((e) => e.type === "entity_effect"),
    ),
    "No night effects received",
  );
  console.log("Wolf return passed; checking restore and rewards");
  // Admin stop restores the actual original door state and rewards nobody.
  await api("arena-stop");
  await phase("WAITING");
  await consoleUntil(
    "execute if block 2 65 2 oak_door[open=true] run say DOOR_RESTORED",
    "DOOR_RESTORED",
  );
  await until(
    async () =>
      (await profile(ids[0])).history.some((e) => e.result === "STOPPED"),
    "Stopped history missing",
  );
  assert((await profile(ids[0])).balance === 0, "Admin stop awarded currency");
  // With no witch, timeout returns wolves home before dawn.
  const timeoutConfig = {
    ...config,
    nightSeconds: 5,
    werewolf: {
      roles: { WEREWOLF: 2 },
      cupidSeconds: 5,
      witchSeconds: 5,
      hunterSeconds: 5,
    },
  };
  await api("arena-save", { config: timeoutConfig });
  for (const id of ids) await api("arena-join", { uuid: id });
  await api("arena-start");
  await phase("NIGHT");
  const counts = clients.map((c) => c.fixture.positions.length);
  await phase("DISCUSSION");
  await until(
    () =>
      clients.some((c, i) =>
        c.fixture.positions
          .slice(counts[i])
          .some((p) => Math.abs(p.z + 10) < 0.01),
      ),
    "No home return at vote timeout",
  );
  if (ambienceSmoke)
    await until(
      () =>
        clients.every((c) =>
          c.fixture.titles.some((t) => t.includes("Dawn breaks")),
        ),
      "Dawn presentation missing after teleports",
    );
  await api("arena-stop");
  await phase("WAITING");
  if (ambienceSmoke) {
    assert(
      clients.every((c) =>
        c.fixture.titles.some((t) => t.includes("Dawn breaks")),
      ),
      "Dawn announcement missing",
    );
    const before = clients.map((c) => ({
      sounds: c.fixture.sounds.length,
      titles: c.fixture.titles.length,
    }));
    const muted = {
      ...timeoutConfig,
      nightSeconds: 15,
      werewolf: { ...timeoutConfig.werewolf, ambienceEnabled: false },
    };
    await api("arena-save", { config: muted });
    for (const id of ids) await api("arena-join", { uuid: id });
    await api("arena-start");
    await phase("NIGHT");
    await pause(1000);
    assert(
      clients.every((c, i) => c.fixture.titles.length === before[i].titles),
      "Muted game sent titles",
    );
    const cues = new Set([
      "block.bell.use",
      "entity.warden.heartbeat",
      "block.note_block.harp",
      "block.note_block.chime",
      "block.note_block.hat",
      "block.enchantment_table.use",
      "block.amethyst_block.chime",
      "item.bottle.fill",
      "entity.arrow.shoot",
      "entity.player.levelup",
    ]);
    assert(
      clients.every(
        (c, i) =>
          !c.fixture.sounds
            .slice(before[i].sounds)
            .some((s) => cues.has(s.name)),
      ),
      "Muted game sent ambience sounds",
    );
    const livingRoles = [];
    for (const client of clients) {
      const w = await window(client, "arena");
      livingRoles.push(
        JSON.stringify(w.items[45]).match(/Your secret role: ([A-Z_]+)/)?.[1],
      );
    }
    const sleeper = livingRoles.findIndex((r) => r !== "WEREWOLF");
    const menu = await window(clients[sleeper], "arena");
    assert(
      JSON.stringify(menu.items[50]).includes("The village sleeps"),
      "Muted game leaked secret timer",
    );
    await api("arena-stop");
    const stopped = clients.map((c) => ({
      sounds: c.fixture.sounds.length,
      titles: c.fixture.titles.length,
      hud: c.fixture.hud.length,
    }));
    await pause(4500);
    assert(
      clients.every(
        (c, i) =>
          c.fixture.sounds.length === stopped[i].sounds &&
          c.fixture.titles.length === stopped[i].titles &&
          c.fixture.hud.length === stopped[i].hud,
      ),
      "Late presentation after restoration",
    );
    // Stop at dusk while the delayed wolf wakeup/second heartbeat may still be pending.
    const cancelled = {
      ...timeoutConfig,
      nightSeconds: 15,
      werewolf: { ...timeoutConfig.werewolf, ambienceEnabled: true },
    };
    await api("arena-save", { config: cancelled });
    for (const id of ids) await api("arena-join", { uuid: id });
    await api("arena-start");
    await phase("NIGHT");
    await api("arena-stop");
    const afterCancel = clients.map((c) => ({
      sounds: c.fixture.sounds.length,
      titles: c.fixture.titles.length,
      hud: c.fixture.hud.length,
    }));
    await pause(4500);
    assert(
      clients.every(
        (c, i) =>
          c.fixture.sounds.length === afterCancel[i].sounds &&
          c.fixture.titles.length === afterCancel[i].titles &&
          c.fixture.hud.length === afterCancel[i].hud,
      ),
      "Enabled presentation continued after stop",
    );
    // A real village win must show the conclusion before restoration.
    const finale = {
      ...timeoutConfig,
      nightSeconds: 5,
      werewolf: {
        ...timeoutConfig.werewolf,
        roles: { WEREWOLF: 1 },
        ambienceEnabled: true,
      },
    };
    await api("arena-save", { config: finale });
    for (const id of ids) await api("arena-join", { uuid: id });
    await api("arena-start");
    const finaleRoles = [];
    for (const client of clients) {
      const w = await window(client, "arena");
      finaleRoles.push(
        JSON.stringify(w.items[45]).match(/Your secret role: ([A-Z_]+)/)?.[1],
      );
    }
    const wolf = finaleRoles.findIndex((r) => r === "WEREWOLF");
    await phase("DAY_VOTE");
    for (let i = 0; i < clients.length; i++) {
      if (i === wolf) continue;
      const w = await window(clients[i], "arena");
      const target = w.items
        .slice(0, 45)
        .findIndex((item) =>
          JSON.stringify(item).includes("FeatureTest" + wolf),
        );
      assert(target >= 0, "Finale voting target missing");
      click(clients[i], w, target);
    }
    await phase("WAITING");
    await until(
      () =>
        clients.every(
          (c) =>
            c.fixture.titles.some((t) => t.includes("Game over")) &&
            c.fixture.titles.some((t) => t.includes("The village wins")),
        ),
      "Final result title missing",
    );
    console.log(
      "PASS: private wakeups and timers, heartbeat pairs, single dusk bell, wolf vote/timeout return, muted ambience, victory conclusion and cancellation after restore.",
    );
    return;
  }
  // A short, normal event credits every real participant once.
  const free = {
    ...config,
    preset: "freeplay",
    village: { enabled: false, houses: {}, doors: [] },
    durationSeconds: 60,
    minPlayers: 1,
    freeplay: {
      objective: "SANDBOX",
      targetScore: 10,
      pointsPerEvent: 1,
      allowFlight: false,
      hunger: false,
      personalTime: -1,
      weather: "WORLD",
      kit: {},
      effects: {},
      checkpoints: [],
      instructions: "Smoke",
    },
  };
  await api("arena-save", { config: free });
  await api("arena-join", { uuid: ids[0] });
  await api("arena-start");
  docker("pause", name + "-bridge");
  await phase("WAITING", 400);
  assert(
    fs
      .readdirSync(path.join(temp, "plugins/NexusArenaEngine/results"))
      .some((f) => f.endsWith(".json")),
    "Offline result was not queued",
  );
  const beforeUnavailable = clients[0].fixture.messages.length;
  command(clients[0], "profil");
  await until(
    () =>
      clients[0].fixture.messages
        .slice(beforeUnavailable)
        .some((m) => m.includes("Profile unavailable")),
    "Profile did not report service outage",
    40,
  );
  docker("unpause", name + "-bridge");
  await until(
    async () => (await profile(ids[0])).balance === 10,
    "Completed event reward",
    160,
  );
  assert((await profile(ids[0])).currency === "Shards", "Wrong currency name");
  console.log("Rewards passed; checking crash recovery");
  await api("arena-save", { config });
  for (const id of ids) await api("arena-join", { uuid: id });
  await api("arena-start");
  await phase("GUARD");
  await consoleUntil(
    "execute if block 2 65 2 oak_door[open=false] run say CRASH_DOOR_CLOSED",
    "CRASH_DOOR_CLOSED",
  );
  await consoleUntil("save-all flush", "Saved the game");
  const previousJava = java;
  docker("kill", "--signal=KILL", name + "-paper");
  await new Promise((resolve) => {
    if (previousJava.exitCode !== null) resolve();
    else previousJava.once("exit", resolve);
  });
  for (const client of clients)
    try {
      client.end();
    } catch {}
  await startPaper();
  consoleCommand("forceload add -32 -32 32 32");
  await pause(1500);
  await consoleUntil(
    "execute if block 2 65 2 oak_door[open=true] run say CRASH_DOOR_RESTORED",
    "CRASH_DOOR_RESTORED",
  );
  await connect(0);
  await until(
    () =>
      !fs.existsSync(
        path.join(temp, "plugins/NexusArenaEngine/snapshots", ids[0] + ".yml"),
      ),
    "Login snapshot recovery",
  );
  await until(
    async () =>
      (await profile(ids[0])).history.some((e) => e.result === "INTERRUPTED"),
    "Interrupted history missing",
    160,
  );
  assert(
    (await profile(ids[0])).balance === 10,
    "Interrupted game awarded currency",
  );
  assert(
    !logs.includes("Could not pass event") &&
      !logs.includes("Village transition failed"),
    "Runtime error",
  );
  console.log(
    "PASS: native menus, quota, warps, sequential roles, wolf return on final vote, effects, doors, timeout, crash recovery, history and Shards rewards.",
  );
}
main()
  .catch((e) => {
    console.error(e.stack);
    process.exitCode = 1;
  })
  .finally(async () => {
    for (const client of clients)
      try {
        client.end();
      } catch {}
    if (java)
      try {
        consoleCommand("stop");
        await pause(1000);
      } catch {}
    try {
      docker("unpause", name + "-bridge");
    } catch {}
    for (const suffix of ["-paper", "-bridge"])
      try {
        docker("rm", "-f", name + suffix);
      } catch {}
    try {
      docker("network", "rm", name);
    } catch {}
    fs.writeFileSync(path.join(temp, "smoke.log"), logs);
    if (ambienceSmoke)
      fs.writeFileSync(
        path.join(temp, "ambience-packets.json"),
        JSON.stringify(
          clients.map((c) => ({
            id: c.fixture.id,
            sounds: c.fixture.sounds,
            titles: c.fixture.titles,
            hud: c.fixture.hud,
          })),
          null,
          2,
        ),
      );
    console.log("Disposable fixture and logs: " + temp);
  });

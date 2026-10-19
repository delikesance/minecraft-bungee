import { useConfirm } from "./ConfirmDialog";
import { useEffect, useState } from "react";
import { api } from "./api";
type World = {
  uuid: string;
  name: string;
  environment: string;
  difficulty: string;
  time: number;
  weather: string;
  pvp: boolean;
  players: number;
  loadedChunks: number;
  entities: number;
  rules: Record<string, string>;
  spawn: { x: number; y: number; z: number };
};
type State = {
  tps?: number[];
  mspt?: number;
  version: string;
  maxPlayers: number;
  online: number;
  whitelistEnabled: boolean;
  whitelist: { uuid: string; name: string }[];
  bans: { name: string; reason: string; expires: string | null }[];
  worlds: World[];
  players: {
    uuid: string;
    name: string;
    gamemode: string;
    world: string;
    ping: number;
    lobbyBuild?: boolean;
  }[];
};
export function Operations({
  server,
  csrf,
  onError,
}: {
  server: string;
  csrf: string;
  onError: (error: unknown) => void;
}) {
  const confirm = useConfirm();
  const [state, setState] = useState<State | null>(null),
    [worldId, setWorldId] = useState(""),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [notice, setNotice] = useState("");
  const [message, setMessage] = useState(""),
    [search, setSearch] = useState(""),
    [known, setKnown] = useState<{ uuid: string; name: string }[]>([]),
    [target, setTarget] = useState(""),
    [randomTicks, setRandomTicks] = useState(3);
  async function load() {
    try {
      const data = await api<State>(`/servers/${server}/game/state`);
      setState(data);
      setError("");
      setWorldId((previous) =>
        data.worlds.some((w) => w.uuid === previous)
          ? previous
          : data.worlds[0]?.uuid || "",
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : "Server unavailable");
    }
  }
  useEffect(() => {
    void load();
  }, [server]);
  useEffect(() => {
    let active = true;
    const timer = setTimeout(() => {
      api<{ players: { uuid: string; name: string }[] }>(
        `/players?search=${encodeURIComponent(search)}`,
      )
        .then((r) => {
          if (active) setKnown(r.players);
        })
        .catch(onError);
    }, 250);
    return () => {
      active = false;
      clearTimeout(timer);
    };
  }, [search]);
  const world = state?.worlds.find((w) => w.uuid === worldId);
  async function action(name: string, extra: Record<string, unknown> = {}) {
    if (
      !(await confirm(
        `Apply ${name.replaceAll("-", " ")} on ${server}${extra.world ? " / " + world?.name : ""}?`,
      ))
    )
      return;
    setBusy(true);
    setNotice("");
    setError("");
    try {
      await api(`/servers/${server}/game/action`, "POST", csrf, {
        action: name,
        ...extra,
      });
      setNotice("Server settings updated.");
      await load();
    } catch (e) {
      onError(e);
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <div className="management-heading">
        <p className="console-note">
          Live controls for {server}. Player inventories and game modes are in
          Players & roles.
        </p>
        <button onClick={load} disabled={busy}>
          Refresh status
        </button>
      </div>
      {server === "lobby" && state && (
        <section className="arena-zone">
          <span className="eyebrow">LOBBY MAINTENANCE</span>
          <h3>Build & inventory access</h3>
          <p>
            Lobby protections also apply to operators. Enable Build mode
            explicitly to repair blocks and move, discard or clear inventory
            items. The player needs OP or the lobby build permission. In game:
            /lobbybuild on · /lobbybuild off.
          </p>
          {state.players.length === 0 ? (
            <small>Connect to the Lobby to manage your build mode.</small>
          ) : (
            state.players.map((p) => (
              <div key={p.uuid} className="arena-toolbar">
                <strong>{p.name}</strong>
                <span className={`status ${p.lobbyBuild ? "on" : ""}`}>
                  {p.lobbyBuild ? "Build mode" : "Protected visitor"}
                </span>
                <button
                  disabled={busy}
                  onClick={() =>
                    action("lobby-build", {
                      uuid: p.uuid,
                      enabled: !p.lobbyBuild,
                    })
                  }
                >
                  {p.lobbyBuild ? "Disable build mode" : "Enable build mode"}
                </button>
              </div>
            ))
          )}
        </section>
      )}
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
      {notice && (
        <p className="management-notice" role="status">
          {notice}
        </p>
      )}
      {state && (
        <>
          <section className="management-section">
            <div className="management-heading">
              <div>
                <span className="eyebrow">LIVE SERVER</span>
                <h2>
                  {state.online} / {state.maxPlayers} players
                </h2>
                <small>{state.version}</small>
                <p className="console-note">
                  TPS (1m / 5m / 15m):{" "}
                  {state.tps
                    ?.map((v) => Math.min(v, 20).toFixed(2))
                    .join(" / ") || "—"}{" "}
                  · Average tick: {state.mspt?.toFixed(2) ?? "—"} ms
                </p>
              </div>
              <button disabled={busy} onClick={() => action("save")}>
                Save players & worlds
              </button>
            </div>
            <div className="player-table-wrap">
              <table className="player-table">
                <thead>
                  <tr>
                    <th>Player</th>
                    <th>Game mode</th>
                    <th>Dimension</th>
                    <th>Ping</th>
                  </tr>
                </thead>
                <tbody>
                  {state.players.map((p) => (
                    <tr key={p.uuid}>
                      <td>{p.name}</td>
                      <td>{p.gamemode}</td>
                      <td>{p.world}</td>
                      <td>{p.ping} ms</td>
                    </tr>
                  ))}
                  {!state.players.length && (
                    <tr>
                      <td colSpan={4}>No players online on this server.</td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          </section>
          <section className="management-section">
            <div className="management-heading">
              <div>
                <span className="eyebrow">DIMENSION CONTROLS</span>
                <h2>Shape the world.</h2>
              </div>
              <select
                aria-label="Dimension"
                value={worldId}
                onChange={(e) => setWorldId(e.target.value)}
              >
                {state.worlds.map((w) => (
                  <option key={w.uuid} value={w.uuid}>
                    {w.name} · {w.environment}
                  </option>
                ))}
              </select>
            </div>
            {world && (
              <>
                <div className="process-summary">
                  <span>{world.loadedChunks} loaded chunks</span>
                  <span>{world.entities} entities</span>
                  <span>Time: {world.time}</span>
                  <span>
                    Spawn: {world.spawn.x.toFixed(0)} /{" "}
                    {world.spawn.y.toFixed(0)} / {world.spawn.z.toFixed(0)}
                  </span>
                </div>
                <div className="settings-grid">
                  <label>
                    Difficulty
                    <select
                      value={world.difficulty}
                      disabled={busy}
                      onChange={(e) =>
                        action("difficulty", {
                          world: worldId,
                          value: e.target.value,
                        })
                      }
                    >
                      {["PEACEFUL", "EASY", "NORMAL", "HARD"].map((v) => (
                        <option key={v}>{v}</option>
                      ))}
                    </select>
                  </label>
                  <label>
                    Weather
                    <select
                      value={world.weather}
                      disabled={busy}
                      onChange={(e) =>
                        action("weather", {
                          world: worldId,
                          value: e.target.value,
                        })
                      }
                    >
                      {["clear", "rain", "thunder"].map((v) => (
                        <option key={v}>{v}</option>
                      ))}
                    </select>
                  </label>
                  <label className="setting-check">
                    <input
                      type="checkbox"
                      checked={world.pvp}
                      disabled={busy}
                      onChange={(e) =>
                        action("pvp", {
                          world: worldId,
                          enabled: e.target.checked,
                        })
                      }
                    />
                    Player versus player
                  </label>
                </div>
                <div className="player-action-row">
                  {[
                    ["Day", 1000],
                    ["Noon", 6000],
                    ["Night", 13000],
                    ["Midnight", 18000],
                  ].map(([name, ticks]) => (
                    <button
                      key={name}
                      disabled={busy}
                      onClick={() =>
                        action("time", { world: worldId, value: ticks })
                      }
                    >
                      {name}
                    </button>
                  ))}
                </div>
                <div className="settings-grid rules-grid">
                  {Object.entries(world.rules)
                    .filter(([name]) => name !== "randomTickSpeed")
                    .map(([name, value]) => (
                      <label className="setting-check" key={name}>
                        <input
                          type="checkbox"
                          disabled={busy}
                          checked={value === "true"}
                          onChange={(e) =>
                            action("gamerule", {
                              world: worldId,
                              rule: name,
                              enabled: e.target.checked,
                            })
                          }
                        />
                        {name.replace(/([A-Z])/g, " $1")}
                      </label>
                    ))}
                </div>
                <form
                  className="inline-settings"
                  onSubmit={(e) => {
                    e.preventDefault();
                    void action("gamerule", {
                      world: worldId,
                      rule: "randomTickSpeed",
                      value: randomTicks,
                    });
                  }}
                >
                  <label>
                    Random tick speed (current: {world.rules.randomTickSpeed})
                    <input
                      type="number"
                      min={0}
                      max={20}
                      value={randomTicks}
                      onChange={(e) => setRandomTicks(Number(e.target.value))}
                    />
                  </label>
                  <button disabled={busy}>Set tick speed</button>
                </form>
              </>
            )}
          </section>
          <section className="management-section">
            <span className="eyebrow">COMMUNITY ACCESS</span>
            <h2>Whitelist & bans.</h2>
            <label className="setting-check">
              <input
                type="checkbox"
                disabled={busy}
                checked={state.whitelistEnabled}
                onChange={(e) =>
                  action("whitelist", { enabled: e.target.checked })
                }
              />
              Whitelist enabled
            </label>
            <p className="console-note">
              When enabled, only listed players and operators may join. Toggling
              it does not kick players already connected.
            </p>
            <div className="inline-settings">
              <label>
                Find a known player
                <input
                  placeholder="Search name or UUID"
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                />
              </label>
              <label>
                Player
                <select
                  value={target}
                  onChange={(e) => setTarget(e.target.value)}
                >
                  <option value="">Choose player</option>
                  {known.map((p) => (
                    <option key={p.uuid} value={p.uuid}>
                      {p.name}
                    </option>
                  ))}
                </select>
              </label>
              <button
                disabled={busy || !target}
                onClick={() => action("whitelist-add", { uuid: target })}
              >
                Add to whitelist
              </button>
            </div>
            <div className="whitelist-pills">
              {state.whitelist.map((p) => (
                <span key={p.uuid}>
                  {p.name}
                  <button
                    aria-label={`Remove ${p.name} from whitelist`}
                    disabled={busy}
                    onClick={() => action("whitelist-remove", { uuid: p.uuid })}
                  >
                    ×
                  </button>
                </span>
              ))}
            </div>
            <h3>Active bans</h3>
            <div className="player-table-wrap">
              <table className="player-table">
                <thead>
                  <tr>
                    <th>Player</th>
                    <th>Reason</th>
                    <th>Expires</th>
                    <th>Manage</th>
                  </tr>
                </thead>
                <tbody>
                  {state.bans.map((b) => (
                    <tr key={b.name}>
                      <td>{b.name}</td>
                      <td>{b.reason}</td>
                      <td>
                        {b.expires
                          ? new Date(b.expires).toLocaleString()
                          : "Permanent"}
                      </td>
                      <td>
                        <button
                          disabled={busy}
                          onClick={() =>
                            action("pardon-name", { name: b.name })
                          }
                        >
                          Remove ban
                        </button>
                      </td>
                    </tr>
                  ))}
                  {!state.bans.length && (
                    <tr>
                      <td colSpan={4}>No active bans.</td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          </section>
          <section className="management-section">
            <span className="eyebrow">SERVER ANNOUNCEMENT</span>
            <h2>Say something to your world.</h2>
            <form
              className="inline-settings"
              onSubmit={(e) => {
                e.preventDefault();
                void action("broadcast", { message });
              }}
            >
              <label>
                Message
                <input
                  value={message}
                  maxLength={256}
                  onChange={(e) => setMessage(e.target.value)}
                  placeholder="A message for everyone on this server…"
                />
              </label>
              <button disabled={busy || !message.trim()}>Broadcast</button>
            </form>
          </section>
        </>
      )}
    </>
  );
}
export function Activity({ onError }: { onError: (error: unknown) => void }) {
  type Entry = {
    id: number;
    at: string;
    actor: string;
    server: string;
    action: string;
    result: number;
  };
  const [rows, setRows] = useState<Entry[]>([]),
    [filter, setFilter] = useState(""),
    [busy, setBusy] = useState(false),
    [cursor, setCursor] = useState(0);
  async function load(before = 0) {
    setBusy(true);
    try {
      setRows(
        await api<Entry[]>(`/activity?server=${filter}&before=${before}`),
      );
      setCursor(before);
    } catch (e) {
      onError(e);
    } finally {
      setBusy(false);
    }
  }
  useEffect(() => {
    void load();
  }, [filter]);
  return (
    <section className="management-section">
      <div className="management-heading">
        <div>
          <span className="eyebrow">ADMINISTRATION HISTORY</span>
          <h2>Every action, accounted for.</h2>
        </div>
        <select
          aria-label="Activity server"
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
        >
          {["", "network", "velocity", "lobby", "creative", "survival"].map(
            (v) => (
              <option value={v} key={v}>
                {v || "All servers"}
              </option>
            ),
          )}
        </select>
        <button disabled={busy} onClick={() => load()}>
          Refresh latest
        </button>
      </div>
      <p className="console-note">
        Player data, uploaded file contents, passwords and console command text
        are excluded from this log.
      </p>
      <div className="player-table-wrap">
        <table className="player-table">
          <thead>
            <tr>
              <th>Time</th>
              <th>Administrator</th>
              <th>Server</th>
              <th>Action</th>
              <th>Result</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.id}>
                <td>{new Date(row.at).toLocaleString()}</td>
                <td>{row.actor}</td>
                <td>{row.server}</td>
                <td className="audit-action">{row.action}</td>
                <td>
                  {row.result === 0
                    ? "Pending / interrupted"
                    : row.result < 300
                      ? "Success"
                      : `Failed (${row.result})`}
                </td>
              </tr>
            ))}
            {!rows.length && (
              <tr>
                <td colSpan={5}>No recorded actions for this filter.</td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      <div className="player-pagination">
        <button disabled={busy || cursor === 0} onClick={() => load()}>
          Latest
        </button>
        <button
          disabled={busy || rows.length < 100}
          onClick={() => load(rows[rows.length - 1].id)}
        >
          Older actions →
        </button>
      </div>
    </section>
  );
}

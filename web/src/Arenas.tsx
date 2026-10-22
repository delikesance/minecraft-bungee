import { useEffect, useRef, useState, type FormEvent } from "react";
import { api } from "./api";
import {
  ArenaRulesEditor,
  defaultWerewolf,
  defaultFreeplay,
  type WerewolfRules,
  type FreeplayRules,
} from "./ArenaRulesEditor";
import { useConfirm } from "./ConfirmDialog";
import { GameLogs } from "./GameLogs";

export type Config = {
  id: string;
  name: string;
  worldName: string;
  preset: string;
  bounds: {
    minX: number;
    minY: number;
    minZ: number;
    maxX: number;
    maxY: number;
    maxZ: number;
  };
  spawn: { x: number; y: number; z: number; yaw: number; pitch: number };
  allowedBreakBlocks: string[];
  allowedPlaceBlocks: string[];
  allowPvP: boolean;
  allowChests: boolean;
  allowDoors: boolean;
  gameMode: string;
  durationSeconds: number;
  minPlayers: number;
  maxPlayers: number;
  discussionSeconds: number;
  voteSeconds: number;
  nightSeconds: number;
  village?: {
    enabled: boolean;
    houses: Record<string, Config["spawn"]>;
    meeting?: Config["spawn"];
    daypoint?: Config["spawn"];
    doors: { x: number; y: number; z: number }[];
  };
  rewards?: { participation: number; victory: number };
  werewolf?: WerewolfRules;
  freeplay?: FreeplayRules;
};
type Arena = {
  config: Config;
  phase: string;
  startedAt: number;
  phaseEndsAt: number;
  practice?: boolean;
  players: {
    uuid: string;
    name: string;
    alive: boolean;
    bot?: boolean;
    score?: number;
  }[];
};
type World = {
  name: string;
  minY: number;
  maxY: number;
  spawn: { x: number; y: number; z: number };
};
type State = {
  arenas: Arena[];
  redisConnected: boolean;
  worlds: World[];
  bans: Record<string, string>;
  onlinePlayers: { uuid: string; name: string }[];
};
const servers = { creative: "Creative", survival: "Survival", lobby: "Lobby" };
const titles: Record<string, string> = {
  WAITING: "Waiting for players",
  DISCUSSION: "Day · Discussion",
  DAY_VOTE: "Day · Voting",
  GUARD: "Night · Guard",
  SEER: "Night · Seer",
  NIGHT: "Night · Wolves",
  FINISHED: "Game finished",
  CUPID: "Cupid · Choose the lovers",
  WITCH: "Night · Potions",
  HUNTER_SHOT: "Hunter · Final shot",
  FREEPLAY: "Free play",
  RESTORING: "Restoring players",
};
function normalizeCorners(config: Config): Config {
  const b = config.bounds;
  return {
    ...config,
    bounds: {
      minX: Math.min(b.minX, b.maxX),
      minY: Math.min(b.minY, b.maxY),
      minZ: Math.min(b.minZ, b.maxZ),
      maxX: Math.max(b.minX, b.maxX),
      maxY: Math.max(b.minY, b.maxY),
      maxZ: Math.max(b.minZ, b.maxZ),
    },
  };
}
function fresh(world: World): Config {
  const x = Math.floor(world.spawn.x),
    z = Math.floor(world.spawn.z);
  return {
    id: "",
    name: "",
    worldName: world.name,
    preset: "werewolf",
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
    maxPlayers: 20,
    discussionSeconds: 60,
    voteSeconds: 30,
    nightSeconds: 30,
    werewolf: structuredClone(defaultWerewolf),
    freeplay: structuredClone(defaultFreeplay),
  };
}
export function Arenas({
  csrf,
  onError,
}: {
  csrf: string;
  onError: (error: unknown) => void;
}) {
  const confirm = useConfirm();
  const [practiceBots, setPracticeBots] = useState(7);
  const [practicePlayer, setPracticePlayer] = useState("");
  const [server, setServer] = useState("creative"),
    [state, setState] = useState<State | null>(null),
    [selected, setSelected] = useState(""),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false),
    [notice, setNotice] = useState("");
  const [draft, setDraft] = useState<Config | null>(null),
    [editing, setEditing] = useState(false),
    [breaks, setBreaks] = useState(""),
    [places, setPlaces] = useState("");
  const [invite, setInvite] = useState(
      "A moonlit village is waiting. Join the next game!",
    ),
    [target, setTarget] = useState(""),
    [reason, setReason] = useState("Managed by network administration");
  const [playerSearch, setPlayerSearch] = useState("");
  const [knownPlayers, setKnownPlayers] = useState<
    { uuid: string; name: string }[]
  >([]);
  useEffect(() => {
    let live = true;
    const timer = setTimeout(() => {
      api<{ players: { uuid: string; name: string }[] }>(
        `/players?search=${encodeURIComponent(playerSearch)}`,
      )
        .then((data) => {
          if (live) setKnownPlayers(data.players);
        })
        .catch(() => {});
    }, 250);
    return () => {
      live = false;
      clearTimeout(timer);
    };
  }, [playerSearch]);
  const generation = useRef(0);
  const editor = useRef<HTMLFormElement>(null);
  useEffect(() => {
    if (draft)
      editor.current?.scrollIntoView({ behavior: "smooth", block: "start" });
  }, [draft !== null]);
  async function load() {
    const version = generation.current;
    try {
      const data = await api<State>(`/servers/${server}/game/arenas`);
      if (version !== generation.current) return;
      setState(data);
      setError("");
      setSelected((id) =>
        data.arenas.some((a) => a.config.id === id)
          ? id
          : data.arenas[0]?.config.id || "",
      );
    } catch (e) {
      if (version !== generation.current) return;
      setError(e instanceof Error ? e.message : "Arena engine unavailable");
    }
  }
  useEffect(() => {
    generation.current++;
    setState(null);
    setDraft(null);
    setSelected("");
    setNotice("");
    setTarget("");
    setPracticePlayer("");
    void load();
    const timer = setInterval(load, 3000);
    return () => {
      generation.current++;
      clearInterval(timer);
    };
  }, [server]);
  const current = state?.arenas.find((a) => a.config.id === selected);
  const practiceHumans = current?.players.filter((p) => !p.bot).length || 0;
  const practiceCandidates =
    state?.onlinePlayers.filter(
      (p) =>
        !state.arenas.some((a) =>
          a.players.some((member) => member.uuid === p.uuid),
        ),
    ) || [];
  const practiceTarget =
    practicePlayer ||
    (practiceCandidates.length === 1 ? practiceCandidates[0].uuid : "");
  const practiceCount = current
    ? Math.min(
        current.config.maxPlayers - Math.max(1, practiceHumans),
        Math.max(
          current.config.minPlayers - Math.max(1, practiceHumans),
          practiceBots,
        ),
      )
    : practiceBots;
  async function action(
    action: string,
    extra: Record<string, unknown> = {},
    question?: string,
  ) {
    if (question && !(await confirm(question))) return;
    setBusy(true);
    setNotice("");
    try {
      await api(`/servers/${server}/game/action`, "POST", csrf, {
        action,
        arenaId: selected,
        ...extra,
      });
      setNotice("Arena operation completed.");
      await load();
    } catch (e) {
      onError(e);
    } finally {
      setBusy(false);
    }
  }
  async function closeDraft() {
    if (
      draft &&
      !(await confirm("Discard the unsaved arena configuration?", {
        title: "Discard changes?",
        confirmLabel: "Discard changes",
      }))
    )
      return false;
    setDraft(null);
    return true;
  }
  async function edit(config?: Config) {
    if (draft && !(await closeDraft())) return;
    if (!state?.worlds.length) return;
    const next = config ? structuredClone(config) : fresh(state.worlds[0]);
    setDraft(next);
    setEditing(!!config);
    setBreaks(next.allowedBreakBlocks.join(", "));
    setPlaces(next.allowedPlaceBlocks.join(", "));
  }
  async function save(e: FormEvent) {
    e.preventDefault();
    if (!draft) return;
    const normalized = normalizeCorners(draft);
    setDraft(normalized);
    if (!editing && state?.arenas.some((a) => a.config.id === draft.id)) {
      setError(
        "This ID already exists. Edit the existing arena or choose a different ID.",
      );
      return;
    }
    if (
      !(await confirm(
        "Save these arena bounds and rules? Check that the spawn has safe ground and the zone is ready for players.",
        { title: "Save arena configuration?", confirmLabel: "Save arena" },
      ))
    )
      return;
    const materials = (raw: string) =>
      raw
        .split(",")
        .map((v) =>
          v
            .trim()
            .replace(/^minecraft:/i, "")
            .toUpperCase(),
        )
        .filter(Boolean);
    setBusy(true);
    try {
      await api(`/servers/${server}/game/action`, "POST", csrf, {
        action: "arena-save",
        config: {
          ...normalized,
          allowedBreakBlocks: materials(breaks),
          allowedPlaceBlocks: materials(places),
        },
      });
      setSelected(draft.id);
      setDraft(null);
      setNotice(
        "Arena saved. Players can now join; the game starts when you choose Start game.",
      );
      await load();
    } catch (e) {
      onError(e);
    } finally {
      setBusy(false);
    }
  }
  function number(key: keyof Config, label: string, min: number, max: number) {
    return (
      <label key={key}>
        {label}
        <input
          type="number"
          min={min}
          max={max}
          required
          value={Number(draft![key])}
          onChange={(e) =>
            setDraft({ ...draft!, [key]: Number(e.target.value) })
          }
        />
      </label>
    );
  }
  return (
    <section className="arena-page">
      <div className="arena-toolbar">
        <label>
          Host server
          <select
            value={server}
            disabled={busy}
            onChange={async (e) => {
              const next = e.target.value;
              if (await closeDraft()) setServer(next);
            }}
          >
            {Object.entries(servers).map(([id, name]) => (
              <option key={id} value={id}>
                {name}
              </option>
            ))}
          </select>
        </label>
        <span className={`status ${state?.redisConnected ? "on" : ""}`}>
          {state?.redisConnected
            ? "Cross-server connected"
            : "Cross-server unavailable"}
        </span>
        <button onClick={load} disabled={busy}>
          Refresh
        </button>
        <button
          className="primary"
          disabled={busy || !state}
          onClick={() => edit()}
        >
          + Create arena
        </button>
      </div>
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
      {!state && !error && <p>Connecting to the arena engine…</p>}
      {state && (
        <>
          <div className="arena-summary">
            <div>
              <span className="eyebrow">YOUR PLAYGROUNDS</span>
              <strong>{state.arenas.length}</strong>
              <small>Configured arenas</small>
            </div>
            <div>
              <span className="eyebrow">TOGETHER IN GAME</span>
              <strong>
                {state.arenas.reduce((n, a) => n + a.players.length, 0)}
              </strong>
              <small>
                Participants on {servers[server as keyof typeof servers]}
              </small>
            </div>
            <div>
              <span className="eyebrow">SAFE RETURNS</span>
              <strong>Inventory snapshots</strong>
              <small>
                Original items, stats and position restored on leave
              </small>
            </div>
          </div>
          <div className="arena-layout">
            <div className="arena-list">
              <span className="eyebrow">CHOOSE AN ARENA</span>
              {state.arenas.length === 0 && (
                <p>
                  No arenas yet. Choose an existing world and define your first
                  play area.
                </p>
              )}
              {state.arenas.map((a) => (
                <button
                  className={`arena-card ${selected === a.config.id ? "arena-selected" : ""}`}
                  key={a.config.id}
                  disabled={busy}
                  aria-pressed={selected === a.config.id}
                  onClick={() => setSelected(a.config.id)}
                >
                  <span className="eyebrow">
                    {a.config.preset === "werewolf"
                      ? "☾ WEREWOLF"
                      : "✦ FREE PLAY"}
                  </span>
                  <strong>{a.config.name}</strong>
                  <small>{titles[a.phase] || a.phase}</small>
                  <span>
                    {a.players.length} / {a.config.maxPlayers} players{" "}
                    <small>· {a.config.worldName}</small>
                  </span>
                </button>
              ))}
            </div>
            <div className="arena-detail">
              {current ? (
                <>
                  <div className="management-heading">
                    <div>
                      <span className="eyebrow">
                        {current.config.id} ·{" "}
                        {servers[server as keyof typeof servers]}
                      </span>
                      <h2>
                        {current.config.name}
                        <span>.</span>
                      </h2>
                    </div>
                    <span
                      className={`status ${current.phase !== "WAITING" ? "on" : ""}`}
                    >
                      {titles[current.phase] || current.phase}
                    </span>
                  </div>
                  <div className="arena-facts">
                    <span>
                      {current.config.preset === "werewolf"
                        ? "Werewolf"
                        : "Free play"}{" "}
                      · {current.config.gameMode.toLowerCase()}
                    </span>
                    <span>
                      {current.config.durationSeconds / 60} min maximum
                    </span>
                    <span>{current.config.minPlayers} players to start</span>
                    {current.phaseEndsAt > 0 && current.phase !== "WAITING" && (
                      <span>
                        Phase ends in{" "}
                        {Math.max(
                          0,
                          Math.ceil((current.phaseEndsAt - Date.now()) / 1000),
                        )}
                        s
                      </span>
                    )}
                  </div>
                  <div className="arena-buttons">
                    <button
                      className="primary"
                      disabled={
                        busy ||
                        current.phase !== "WAITING" ||
                        current.players.length < current.config.minPlayers
                      }
                      onClick={() =>
                        action(
                          "arena-start",
                          {},
                          `Start ${current.config.name} with ${current.players.length} participants?`,
                        )
                      }
                    >
                      Start game →
                    </button>
                    <button
                      disabled={
                        busy ||
                        (current.players.length === 0 &&
                          current.phase === "WAITING")
                      }
                      onClick={() =>
                        action(
                          "arena-stop",
                          {},
                          `Stop ${current.config.name} and restore every participant's original state?`,
                        )
                      }
                    >
                      Stop & restore
                    </button>
                    <button
                      disabled={
                        busy ||
                        current.players.length > 0 ||
                        current.phase !== "WAITING"
                      }
                      onClick={() => edit(current.config)}
                    >
                      Edit rules
                    </button>
                    <button
                      disabled={
                        busy ||
                        current.players.length > 0 ||
                        current.phase !== "WAITING"
                      }
                      onClick={() =>
                        action(
                          "arena-delete",
                          {},
                          `Delete the configuration for ${current.config.name}? The world itself is kept.`,
                        )
                      }
                    >
                      Delete arena
                    </button>
                  </div>
                  <p className="console-note">
                    Borders and gameplay rules apply to participants only. Build
                    the arena in the existing world before inviting players.
                    Allowed block changes are real and stay in the world.
                  </p>
                  <div className="arena-zone">
                    <strong>Play area · {current.config.worldName}</strong>
                    <span>
                      X {current.config.bounds.minX} →{" "}
                      {current.config.bounds.maxX} · Y{" "}
                      {current.config.bounds.minY} →{" "}
                      {current.config.bounds.maxY} · Z{" "}
                      {current.config.bounds.minZ} →{" "}
                      {current.config.bounds.maxZ}
                    </span>
                    <small>
                      Spawn {current.config.spawn.x} / {current.config.spawn.y}{" "}
                      / {current.config.spawn.z}
                    </small>
                  </div>
                  {current.config.preset === "werewolf" &&
                    current.phase === "WAITING" && (
                      <section
                        className="arena-invite"
                        aria-label="Solo practice"
                      >
                        <span className="eyebrow">SOLO PRACTICE</span>
                        <h3>Try the village with a virtual cast.</h3>
                        <p>
                          Practice players receive secret roles and act
                          automatically at the end of each phase. Phases last at
                          most 15 seconds. They are virtual participants, with
                          no Minecraft accounts, NPCs or inventory.
                        </p>
                        {practiceHumans === 0 && (
                          <label>
                            Player to join
                            <select
                              value={practiceTarget}
                              onChange={(e) =>
                                setPracticePlayer(e.target.value)
                              }
                              disabled={busy}
                            >
                              <option value="">
                                Choose a connected player…
                              </option>
                              {practiceCandidates.map((p) => (
                                <option key={p.uuid} value={p.uuid}>
                                  {p.name}
                                </option>
                              ))}
                            </select>
                          </label>
                        )}
                        <label>
                          Practice players
                          <input
                            type="number"
                            min={Math.max(
                              1,
                              current.config.minPlayers -
                                Math.max(1, practiceHumans),
                            )}
                            max={
                              current.config.maxPlayers -
                              Math.max(1, practiceHumans)
                            }
                            value={practiceCount}
                            disabled={busy}
                            onChange={(e) =>
                              setPracticeBots(
                                Math.max(1, Number(e.target.value)),
                              )
                            }
                          />
                        </label>
                        <button
                          className="primary"
                          disabled={
                            busy ||
                            practiceCount < 1 ||
                            (practiceHumans === 0 && !practiceTarget)
                          }
                          onClick={() =>
                            action(
                              "arena-practice-start",
                              {
                                botCount: practiceCount,
                                ...(practiceHumans === 0
                                  ? { uuid: practiceTarget }
                                  : {}),
                              },
                              `Start practice with ${Math.max(1, practiceHumans)} real player(s) and ${practiceCount} virtual players? Real inventories and positions will be saved and restored normally.`,
                            )
                          }
                        >
                          Start practice →
                        </button>
                      </section>
                    )}
                  {current.practice && (
                    <p className="console-note">
                      Practice game · virtual players act automatically. Stop
                      &amp; restore removes the entire virtual cast.
                    </p>
                  )}
                  <h3>
                    Participants <small>· {current.players.length}</small>
                  </h3>
                  {current.players.length === 0 ? (
                    <p className="console-note">
                      Players can use{" "}
                      <code>
                        /arena join {server}:{current.config.id}
                      </code>{" "}
                      or click an invitation.
                    </p>
                  ) : (
                    <div className="arena-participants">
                      {current.players.map((p) => (
                        <div key={p.uuid}>
                          <strong>
                            {p.name}
                            {p.bot ? " · Practice" : ""}
                          </strong>
                          {current.config.preset === "freeplay" && (
                            <span>{p.score || 0} pts</span>
                          )}
                          <span className={`status ${p.alive ? "on" : ""}`}>
                            {p.alive ? "In play" : "Eliminated"}
                          </span>
                          <button
                            disabled={busy || p.bot}
                            onClick={() =>
                              action(
                                "arena-leave",
                                { uuid: p.uuid },
                                `Return ${p.name} to their original inventory and position?`,
                              )
                            }
                          >
                            Return player
                          </button>
                        </div>
                      ))}
                    </div>
                  )}
                  {current.phase === "WAITING" && (
                    <label className="arena-player-add">
                      Add a connected player
                      <select
                        value=""
                        disabled={busy}
                        onChange={(e) => {
                          const uuid = e.target.value;
                          void action(
                            "arena-join",
                            { uuid },
                            `Move this player into ${current.config.name}? Their current state will be saved first.`,
                          );
                        }}
                      >
                        <option value="">Choose a player…</option>
                        {state.onlinePlayers
                          ?.filter(
                            (p) =>
                              !state.arenas.some((a) =>
                                a.players.some(
                                  (member) => member.uuid === p.uuid,
                                ),
                              ),
                          )
                          .map((p) => (
                            <option key={p.uuid} value={p.uuid}>
                              {p.name}
                            </option>
                          ))}
                      </select>
                    </label>
                  )}
                  <div className="arena-invite">
                    <span className="eyebrow">A LITTLE INVITATION</span>
                    <h3>Bring the network together.</h3>
                    <p>
                      Send a clickable invitation to Lobby, Creative and
                      Survival. Players on another server transfer
                      automatically.
                    </p>
                    <label>
                      Invitation message
                      <input
                        maxLength={300}
                        value={invite}
                        onChange={(e) => setInvite(e.target.value)}
                      />
                    </label>
                    <button
                      disabled={
                        busy ||
                        !state.redisConnected ||
                        current.phase !== "WAITING" ||
                        !invite.trim()
                      }
                      onClick={() =>
                        action(
                          "arena-announce",
                          { message: invite },
                          `Send this invitation to every player on the network?`,
                        )
                      }
                    >
                      Send network invitation ↗
                    </button>
                  </div>
                </>
              ) : (
                <div className="arena-empty">
                  <span aria-hidden="true">☾</span>
                  <h2>Your next story starts here.</h2>
                  <p>
                    Create a Werewolf village or a free-play area within your
                    existing Minecraft world.
                  </p>
                  <p>Your world's global border stays unchanged.</p>
                </div>
              )}
            </div>
          </div>
          {draft && (
            <form ref={editor} className="arena-editor" onSubmit={save}>
              <div className="management-heading">
                <div>
                  <span className="eyebrow">ARENA WORKSHOP</span>
                  <h2>
                    {editing ? "Refine your arena" : "Create something to play"}
                    <span>.</span>
                  </h2>
                </div>
                <button type="button" disabled={busy} onClick={closeDraft}>
                  Close editor ×
                </button>
              </div>
              <fieldset disabled={busy}>
                <div className="arena-form-grid">
                  <label>
                    Arena ID
                    <input
                      value={draft.id}
                      required
                      readOnly={editing}
                      pattern="[a-z0-9][a-z0-9_-]{0,39}"
                      maxLength={40}
                      placeholder="moonlit-village"
                      onChange={(e) =>
                        setDraft({ ...draft, id: e.target.value })
                      }
                    />
                  </label>
                  <label>
                    Display name
                    <input
                      required
                      maxLength={80}
                      value={draft.name}
                      placeholder="Moonlit village"
                      onChange={(e) =>
                        setDraft({ ...draft, name: e.target.value })
                      }
                    />
                  </label>
                  <label>
                    Game preset
                    <select
                      value={draft.preset}
                      onChange={(e) =>
                        setDraft({
                          ...draft,
                          preset: e.target.value,
                          allowPvP: false,
                          minPlayers:
                            e.target.value === "werewolf"
                              ? Math.max(4, draft.minPlayers)
                              : 1,
                        })
                      }
                    >
                      <option value="werewolf">
                        Werewolf · Secret roles & votes
                      </option>
                      <option value="freeplay">
                        Free play · Generic protected arena
                      </option>
                    </select>
                  </label>
                  <label>
                    Existing world
                    <select
                      value={draft.worldName}
                      onChange={(e) => {
                        const w = state.worlds.find(
                          (w) => w.name === e.target.value,
                        )!;
                        setDraft({
                          ...draft,
                          worldName: w.name,
                          bounds: { ...fresh(w).bounds },
                          spawn: { ...w.spawn, yaw: 0, pitch: 0 },
                        });
                      }}
                    >
                      {state.worlds.map((w) => (
                        <option key={w.name}>{w.name}</option>
                      ))}
                    </select>
                  </label>
                </div>
                <h3>Exact play area</h3>
                <p className="console-note">
                  Enter either corner first — coordinates are sorted
                  automatically when saved. The spawn must be inside, with safe
                  ground and enough headroom. No blocks are generated.
                </p>
                <div className="arena-coordinate-grid">
                  {(
                    ["minX", "minY", "minZ", "maxX", "maxY", "maxZ"] as const
                  ).map((key) => (
                    <label key={key}>
                      {key.replace(/^(min|max)/, "$1 ")}
                      <input
                        type="number"
                        step="any"
                        required
                        value={draft.bounds[key as keyof Config["bounds"]]}
                        onChange={(e) =>
                          setDraft({
                            ...draft,
                            bounds: {
                              ...draft.bounds,
                              [key]: Number(e.target.value),
                            },
                          })
                        }
                      />
                    </label>
                  ))}
                </div>
                <h3>Arrival position</h3>
                <div className="arena-coordinate-grid">
                  {(["x", "y", "z", "yaw", "pitch"] as const).map((key) => (
                    <label key={key}>
                      Spawn {key}
                      <input
                        type="number"
                        step="any"
                        required
                        value={draft.spawn[key as keyof Config["spawn"]]}
                        onChange={(e) =>
                          setDraft({
                            ...draft,
                            spawn: {
                              ...draft.spawn,
                              [key]: Number(e.target.value),
                            },
                          })
                        }
                      />
                    </label>
                  ))}
                </div>
                <ArenaRulesEditor
                  config={draft}
                  onPatch={(patch) => {
                    setDraft({ ...draft, ...patch });
                    if (patch.allowedBreakBlocks)
                      setBreaks(patch.allowedBreakBlocks.join(", "));
                    if (patch.allowedPlaceBlocks)
                      setPlaces(patch.allowedPlaceBlocks.join(", "));
                  }}
                />
                <h3>Game rules</h3>
                <div className="arena-form-grid">
                  <label>
                    Player game mode
                    <select
                      value={draft.gameMode}
                      onChange={(e) =>
                        setDraft({ ...draft, gameMode: e.target.value })
                      }
                    >
                      {["ADVENTURE", "SURVIVAL", "CREATIVE"].map((mode) => (
                        <option key={mode}>{mode}</option>
                      ))}
                    </select>
                  </label>
                  {number(
                    "minPlayers",
                    "Minimum players",
                    draft.preset === "werewolf" ? 4 : 1,
                    45,
                  )}
                  {number("maxPlayers", "Maximum players", 1, 45)}
                  {number(
                    "durationSeconds",
                    "Whole game time limit (seconds)",
                    60,
                    14400,
                  )}
                </div>
                {draft.preset === "werewolf" && (
                  <>
                    <h3>Werewolf timers</h3>
                    <p className="console-note">
                      Each round is a day then a night. Day: players talk, then
                      vote to eliminate someone. Night: roles wake one after
                      another (Guard, Seer, Werewolves, Witch). The wolves'
                      vote ends early once every wolf has voted. Eliminated
                      players become spectators and their role is revealed.
                    </p>
                    <div className="arena-form-grid">
                      {number(
                        "discussionSeconds",
                        "Day · discussion time (seconds)",
                        5,
                        600,
                      )}
                      {number(
                        "voteSeconds",
                        "Day · village vote time (seconds)",
                        5,
                        300,
                      )}
                      {number(
                        "nightSeconds",
                        "Night · time per role: Guard, Seer, Werewolf vote (seconds)",
                        5,
                        300,
                      )}
                    </div>
                  </>
                )}
                <div className="arena-flags">
                  {(
                    [
                      ["allowPvP", "Allow player combat"],
                      ["allowChests", "Allow containers"],
                      ["allowDoors", "Allow doors & trapdoors"],
                    ] as const
                  ).map(([key, label]) => (
                    <label key={key}>
                      <input
                        type="checkbox"
                        checked={draft[key]}
                        disabled={
                          key === "allowPvP" && draft.preset === "werewolf"
                        }
                        onChange={(e) =>
                          setDraft({ ...draft, [key]: e.target.checked })
                        }
                      />
                      {label}
                    </label>
                  ))}
                </div>
                <div className="arena-form-grid">
                  <label>
                    Blocks players may break
                    <input
                      value={breaks}
                      maxLength={3000}
                      placeholder="Empty = no breaking"
                      onChange={(e) => setBreaks(e.target.value)}
                    />
                  </label>
                  <label>
                    Blocks players may place
                    <input
                      value={places}
                      maxLength={3000}
                      placeholder="Empty = no placing"
                      onChange={(e) => setPlaces(e.target.value)}
                    />
                  </label>
                </div>
                <p className="console-note">
                  Comma-separated Minecraft material names, e.g. STONE,
                  OAK_PLANKS. Existing claim protections still apply. These
                  changes are not rolled back.
                </p>
                <button className="primary" type="submit">
                  Save arena →
                </button>
              </fieldset>
            </form>
          )}
          <GameLogs server={server} />
          <section className="arena-moderation">
            <div>
              <span className="eyebrow">ACROSS ALL YOUR WORLDS</span>
              <h2>
                Network moderation<span>.</span>
              </h2>
              <p className="console-note">
                Network bans persist in Redis and are checked on every backend.
                A kick disconnects the player wherever they are.
              </p>
            </div>
            <div className="arena-form-grid">
              <label>
                Find a player
                <input
                  type="search"
                  placeholder="Search by name…"
                  value={playerSearch}
                  onChange={(e) => setPlayerSearch(e.target.value)}
                />
              </label>
              <label>
                Player
                <select
                  value={target}
                  onChange={(e) => setTarget(e.target.value)}
                >
                  <option value="">Choose a player…</option>
                  {target && !knownPlayers.some((p) => p.uuid === target) && (
                    <option value={target}>
                      Selected player · {target.slice(0, 8)}
                    </option>
                  )}
                  {knownPlayers.map((p) => (
                    <option key={p.uuid} value={p.uuid}>
                      {p.name}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                Reason
                <input
                  maxLength={300}
                  value={reason}
                  onChange={(e) => setReason(e.target.value)}
                />
              </label>
            </div>
            <div className="arena-buttons">
              {(
                [
                  ["arena-kick", "Kick from network"],
                  ["arena-ban", "Ban from network"],
                ] as const
              ).map(([actionName, label]) => (
                <button
                  key={actionName}
                  disabled={
                    busy ||
                    !state.redisConnected ||
                    !reason.trim() ||
                    !/^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(target)
                  }
                  onClick={() =>
                    action(
                      actionName,
                      { uuid: target, reason },
                      `${label} for ${knownPlayers.find((p) => p.uuid === target)?.name || target}? This applies across all three servers.`,
                    )
                  }
                >
                  {label}
                </button>
              ))}
            </div>
            {Object.entries(state.bans || {}).map(([uuid, reason]) => (
              <div className="arena-ban" key={uuid}>
                <code>{uuid}</code>
                <span>{reason}</span>
                <button
                  disabled={busy || !state.redisConnected}
                  onClick={() =>
                    action(
                      "arena-unban",
                      { uuid, reason: "Network ban removed by administrator" },
                      `Remove the network ban for ${uuid}? Backend-specific bans are kept.`,
                    )
                  }
                >
                  Remove network ban
                </button>
              </div>
            ))}
          </section>
        </>
      )}
    </section>
  );
}

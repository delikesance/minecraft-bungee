import { useConfirm } from "./ConfirmDialog";
import { FormEvent, useEffect, useRef, useState } from "react";
import { api } from "./api";
import { RoleCommands } from "./RoleCommands";
import { PlayerDetails } from "./PlayerDetails";

type Role = {
  id: string;
  name: string;
  color: string;
  prefix: string;
  priority: number;
};
type Player = {
  gamemode: string;
  operators: string[];
  uuid: string;
  name: string;
  role: string;
  firstSeen: number;
  lastSeen: number;
  server: string;
  online: boolean;
};
type Directory = {
  players: Player[];
  total: number;
  online: number;
  page: number;
  pageSize: number;
  bridges: { server: string; lastSeen: number; connected: boolean }[];
};
const worlds: Record<string, string> = {
  lobby: "Lobby",
  creative: "Creative",
  survival: "Survival",
};
function seen(timestamp: number) {
  return timestamp
    ? new Date(timestamp * 1000).toLocaleString(undefined, {
        dateStyle: "medium",
        timeStyle: "short",
      })
    : "Not yet seen";
}

export function Players({
  csrf,
  onError,
}: {
  csrf: string;
  onError: (error: unknown) => void;
}) {
  const confirm = useConfirm();
  const [roles, setRoles] = useState<Role[]>([]),
    [directory, setDirectory] = useState<Directory | null>(null);
  const [search, setSearch] = useState(""),
    [query, setQuery] = useState(""),
    [filter, setFilter] = useState(""),
    [page, setPage] = useState(1);
  const [pending, setPending] = useState(""),
    [selection, setSelection] = useState<Record<string, string>>({}),
    [notice, setNotice] = useState("");
  const [inspected, setInspected] = useState<Player | null>(null);
  const [opServer, setOpServer] = useState("creative");
  async function operator(player: Player) {
    const enabled = !player.operators?.includes(opServer);
    if (
      !(await confirm(
        `${enabled ? "Grant full OP privileges to" : "Remove OP from"} ${player.name} on ${worlds[opServer]}? This is separate from roles.`,
      ))
    )
      return;
    setPending(player.uuid);
    setNotice("");
    try {
      await api(`/players/${player.uuid}/operator`, "POST", csrf, {
        server: opServer,
        enabled,
      });
      setNotice(
        `OP ${enabled ? "granted" : "removed"} for ${player.name} on ${worlds[opServer]}. Status refreshes after synchronization.`,
      );
      setDirectory(await api<Directory>(endpoint));
    } catch (e) {
      onError(e);
    } finally {
      setPending("");
    }
  }
  const revision = useRef(0);
  const report = useRef(onError);
  report.current = onError;
  const endpoint = `/players?search=${encodeURIComponent(query)}&role=${encodeURIComponent(filter)}&page=${page}`;
  useEffect(() => {
    const timer = setTimeout(() => {
      setQuery(search.trim());
      setPage(1);
    }, 300);
    return () => clearTimeout(timer);
  }, [search]);
  useEffect(() => {
    let active = true;
    api<Role[]>("/roles")
      .then((data) => {
        if (active) setRoles(data);
      })
      .catch((e) => {
        if (active) report.current(e);
      });
    return () => {
      active = false;
    };
  }, []);
  useEffect(() => {
    let active = true;
    const refresh = () => {
      const version = revision.current;
      api<Directory>(endpoint)
        .then((data) => {
          if (active && version === revision.current) setDirectory(data);
        })
        .catch((e) => {
          if (active) report.current(e);
        });
    };
    refresh();
    const timer = setInterval(refresh, 5000);
    return () => {
      active = false;
      clearInterval(timer);
    };
  }, [endpoint]);
  async function save(event: FormEvent, player: Player) {
    event.preventDefault();
    const role = selection[player.uuid] ?? player.role;
    if (role === player.role) return;
    setPending(player.uuid);
    setNotice("");
    revision.current++;
    try {
      await api(`/players/${player.uuid}/role`, "PUT", csrf, { role });
      setDirectory((previous) =>
        previous
          ? {
              ...previous,
              players: previous.players.map((p) =>
                p.uuid === player.uuid ? { ...p, role } : p,
              ),
            }
          : previous,
      );
      setSelection((previous) => {
        const next = { ...previous };
        delete next[player.uuid];
        return next;
      });
      setNotice(
        `${player.name} is now ${roles.find((r) => r.id === role)?.name ?? role}. In-game display updates within a few seconds.`,
      );
      revision.current++;
    } catch (e) {
      report.current(e);
    } finally {
      setPending("");
    }
  }
  const connected = directory?.bridges.filter((b) => b.connected).length ?? 0;
  return (
    <section className="players-page" aria-label="Players and roles">
      <div className="community-stats">
        <div>
          <span className="eyebrow">KNOWN PLAYERS</span>
          <strong>{directory?.total ?? "—"}</strong>
          <p>
            {filter || query
              ? "Matching your filters"
              : "Across your Minecraft worlds"}
          </p>
        </div>
        <div>
          <span className="eyebrow">ONLINE NOW</span>
          <strong>
            {directory?.online ?? "—"}
            <span className="dot" />
          </strong>
          <p>Connected across the network</p>
        </div>
        <div>
          <span className="eyebrow">NETWORK ROLES</span>
          <strong>{roles.length || "—"}</strong>
          <p>Names, prefixes and selected commands</p>
        </div>
      </div>
      <div className="role-palette">
        <div>
          <span className="eyebrow">THE VAMPYUYU FAMILY</span>
          <h2>A little color. A place for everyone.</h2>
        </div>
        <div className="role-pills">
          {roles.map((role) => (
            <span className={`role-badge role-${role.id}`} key={role.id}>
              <span className="role-swatch" />
              {role.name}
            </span>
          ))}
        </div>
        <p>
          Roles appear in chat and the Minecraft TAB list. Everyone starts as{" "}
          <strong>Yuyu’s Bat</strong>. Configure role commands below; OP stays
          separate.
        </p>
      </div>
      <div className="directory-heading">
        <div>
          <span className="eyebrow">PLAYER DIRECTORY</span>
          <h2>Your community</h2>
        </div>
        <span className={`bridge-status ${connected === 3 ? "connected" : ""}`}>
          <span className="dot" />
          {connected}/3 worlds synchronized
        </span>
      </div>
      {notice && (
        <div className="role-notice" role="status">
          {notice}
        </div>
      )}
      {directory && connected < 3 && (
        <p className="bridge-note">
          Some worlds are offline or waiting for the display plugin. Role
          changes are saved and apply when they reconnect.
        </p>
      )}
      <div className="player-filters">
        <label>
          <span>Search players</span>
          <input
            type="search"
            placeholder="Search by name or UUID…"
            value={search}
            maxLength={64}
            onChange={(event) => setSearch(event.target.value)}
          />
        </label>
        <label>
          <span>Role</span>
          <select
            value={filter}
            onChange={(event) => {
              setFilter(event.target.value);
              setPage(1);
            }}
          >
            <option value="">All roles</option>
            {roles.map((role) => (
              <option value={role.id} key={role.id}>
                {role.name}
              </option>
            ))}
          </select>
        </label>
      </div>
      <div className="operator-toolbar">
        <label>
          Manage OP on{" "}
          <select
            value={opServer}
            onChange={(e) => setOpServer(e.target.value)}
          >
            {Object.entries(worlds).map(([id, name]) => (
              <option key={id} value={id}>
                {name}
              </option>
            ))}
          </select>
        </label>
        <span>OP grants full server privileges, including /gamemode.</span>
      </div>
      <div className="player-table-wrap">
        <table className="player-table">
          <thead>
            <tr>
              <th>Player</th>
              <th>Role</th>
              <th>World / Last seen</th>
              <th>Change role</th>
              <th>Operator</th>
            </tr>
          </thead>
          <tbody>
            {!directory && (
              <tr>
                <td colSpan={5} className="empty-players">
                  Loading your community…
                </td>
              </tr>
            )}
            {directory?.players.length === 0 && (
              <tr>
                <td colSpan={5} className="empty-players">
                  {search || filter
                    ? "No players match these filters."
                    : "No players discovered yet. Join a world to appear here; existing players are imported when each world starts."}
                </td>
              </tr>
            )}
            {directory?.players.map((player) => {
              const role = roles.find((r) => r.id === player.role);
              const selected = selection[player.uuid] ?? player.role;
              return (
                <tr key={player.uuid}>
                  <td>
                    <div className="player-identity">
                      <span className={`player-avatar role-${player.role}`}>
                        {player.name.slice(0, 1).toUpperCase()}
                      </span>
                      <div>
                        <button
                          className="player-open"
                          onClick={() => setInspected(player)}
                          aria-label={`Manage ${player.name}`}
                        >
                          {player.name} ↗
                        </button>
                        <small title={player.uuid}>{player.uuid}</small>
                      </div>
                    </div>
                  </td>
                  <td>
                    <span className={`role-badge role-${player.role}`}>
                      <span className="role-swatch" />
                      {role?.name ?? player.role}
                    </span>
                  </td>
                  <td>
                    <span
                      className={`player-presence ${player.online ? "online" : ""}`}
                    >
                      {player.online && <span className="dot" />}
                      {player.online
                        ? (worlds[player.server] ?? player.server)
                        : "Offline"}
                    </span>
                    <small className="last-seen">
                      {player.online
                        ? `${player.gamemode || "Online"} · Playing now`
                        : seen(player.lastSeen)}
                    </small>
                  </td>
                  <td>
                    <form
                      className="role-form"
                      onSubmit={(event) => save(event, player)}
                    >
                      <select
                        aria-label={`Role for ${player.name}`}
                        value={selected}
                        disabled={pending !== ""}
                        onChange={(event) =>
                          setSelection((previous) => ({
                            ...previous,
                            [player.uuid]: event.target.value,
                          }))
                        }
                      >
                        {roles.map((r) => (
                          <option key={r.id} value={r.id}>
                            {r.name}
                          </option>
                        ))}
                      </select>
                      <button
                        disabled={pending !== "" || selected === player.role}
                      >
                        {pending === player.uuid ? "Saving…" : "Save"}
                      </button>
                    </form>
                  </td>
                  <td>
                    <button
                      aria-label={`${player.operators?.includes(opServer) ? "Remove OP from" : "Grant OP to"} ${player.name} on ${worlds[opServer]}`}
                      disabled={
                        pending !== "" ||
                        !directory?.bridges.some(
                          (b) => b.server === opServer && b.connected,
                        )
                      }
                      onClick={() => operator(player)}
                    >
                      {player.operators?.includes(opServer)
                        ? "Remove OP"
                        : "Grant OP"}
                    </button>
                    <small className="last-seen">{worlds[opServer]}</small>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      {directory && directory.total > directory.pageSize && (
        <div className="player-pagination">
          <button disabled={page === 1} onClick={() => setPage((p) => p - 1)}>
            ← Previous
          </button>
          <span>
            Page {page} of {Math.ceil(directory.total / directory.pageSize)}
          </span>
          <button
            disabled={page * directory.pageSize >= directory.total}
            onClick={() => setPage((p) => p + 1)}
          >
            Next →
          </button>
        </div>
      )}
      <p className="console-note">
        ⓘ Roles apply across all three worlds. Dashboard access and OP are
        managed separately.
      </p>
      <RoleCommands csrf={csrf} onError={onError} />
      {inspected && (
        <PlayerDetails
          player={inspected}
          initialServer={inspected.server || opServer}
          csrf={csrf}
          onClose={() => setInspected(null)}
          onError={onError}
        />
      )}
    </section>
  );
}

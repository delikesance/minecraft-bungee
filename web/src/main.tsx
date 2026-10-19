import { useConfirm, ConfirmProvider } from "./ConfirmDialog";
import React, { FormEvent, useEffect, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import "./style.css";
import { api, APIError } from "./api";
import { Players } from "./Players";
import { ServerTools } from "./ServerTools";
import { Arenas } from "./Arenas";
import { Operations, Activity } from "./Operations";

type Session = { username: string; csrf: string };
type Server = {
  id: string;
  state: string;
  health: string;
  startedAt?: string;
  commands: boolean;
};
const names: Record<string, string> = {
  velocity: "Velocity",
  lobby: "Lobby",
  creative: "Creative",
  survival: "Survival",
};
const descriptions: Record<string, string> = {
  velocity: "The front door to your network",
  lobby: "A place to meet. A world to choose.",
  creative: "Room for every idea",
  survival: "Your next adventure starts here",
};
function App() {
  const confirm = useConfirm();
  type View =
    | "overview"
    | "players"
    | "plugins"
    | "settings"
    | "activity"
    | "arenas";
  const readView = (): View => {
    const value = window.location.hash.slice(1);
    return [
      "overview",
      "players",
      "plugins",
      "settings",
      "activity",
      "arenas",
    ].includes(value)
      ? (value as View)
      : "overview";
  };
  const [view, storeView] = useState<View>(readView);
  function setView(value: View) {
    window.location.hash = value;
    storeView(value);
  }
  useEffect(() => {
    const changed = () => storeView(readView());
    window.addEventListener("hashchange", changed);
    return () => window.removeEventListener("hashchange", changed);
  }, []);

  const [session, setSession] = useState<Session | null>(null),
    [loading, setLoading] = useState(true),
    [error, setError] = useState("");
  const [servers, setServers] = useState<Server[]>([]),
    [selected, setSelected] = useState("creative"),
    [lines, setLines] = useState<string[]>([]),
    [stream, setStream] = useState("Connecting"),
    [busy, setBusy] = useState(""),
    [command, setCommand] = useState(""),
    [output, setOutput] = useState(""),
    [follow, setFollow] = useState(true);
  const logWindow = useRef<HTMLDivElement>(null);
  function report(e: unknown) {
    if (e instanceof APIError && e.status === 401) setSession(null);
    setError(e instanceof Error ? e.message : "Something went wrong");
  }
  useEffect(() => {
    api<Session>("/session")
      .then(setSession)
      .catch((e) => {
        if (!(e instanceof APIError && e.status === 401)) report(e);
      })
      .finally(() => setLoading(false));
  }, []);
  useEffect(() => {
    if (!session) return;
    let active = true;
    const poll = () =>
      api<Server[]>("/servers")
        .then((v) => {
          if (active) setServers(v);
        })
        .catch((e) => {
          if (active) report(e);
        });
    poll();
    const id = setInterval(poll, 5000);
    return () => {
      active = false;
      clearInterval(id);
    };
  }, [session]);
  useEffect(() => {
    if (!session || view !== "overview") return;
    setLines([]);
    setOutput("");
    setStream("Connecting");
    const events = new EventSource(`/api/v1/servers/${selected}/console`);
    events.addEventListener("snapshot", (e) => {
      try {
        const data = JSON.parse((e as MessageEvent).data);
        if (Array.isArray(data)) {
          setLines(data.slice(-200));
          setStream("Live");
        }
      } catch {
        setStream("Invalid console response");
      }
    });
    events.addEventListener("unavailable", () =>
      setStream("Server unavailable · retrying"),
    );
    events.addEventListener("expired", () => {
      events.close();
      setSession(null);
    });
    events.onerror = () => {
      setStream("Reconnecting");
      api<Session>("/session").catch((e) => {
        if (e instanceof APIError && e.status === 401) setSession(null);
      });
    };
    return () => events.close();
  }, [session, selected, view]);
  useEffect(() => {
    if (follow && logWindow.current)
      logWindow.current.scrollTop = logWindow.current.scrollHeight;
  }, [lines, follow]);
  async function login(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setError("");
    const data = new FormData(e.currentTarget);
    setBusy("login");
    try {
      setSession(
        await api<Session>("/login", "POST", "", {
          username: data.get("username"),
          password: data.get("password"),
        }),
      );
    } catch (e) {
      report(e);
    } finally {
      setBusy("");
    }
  }
  async function action(action: string) {
    if (!session) return;
    if (
      (action === "stop" || action === "restart") &&
      !(await confirm(
        `${action === "stop" ? "Stop" : "Restart"} ${names[selected]}? Connected players will be disconnected.`,
      ))
    )
      return;
    setBusy(action);
    setError("");
    try {
      await api(`/servers/${selected}/${action}`, "POST", session.csrf);
      setServers(await api<Server[]>("/servers"));
    } catch (e) {
      report(e);
    } finally {
      setBusy("");
    }
  }
  async function send(e: FormEvent) {
    e.preventDefault();
    if (!session || !command.trim()) return;
    setBusy("command");
    setError("");
    try {
      const v = await api<{ output: string }>(
        `/servers/${selected}/command`,
        "POST",
        session.csrf,
        { command },
      );
      setOutput(v.output || "Command sent.");
      setCommand("");
    } catch (e) {
      report(e);
    } finally {
      setBusy("");
    }
  }
  if (loading)
    return (
      <main className="login">
        <p>Connecting to your network…</p>
      </main>
    );
  if (!session)
    return (
      <main className="login">
        <div className="login-brand">
          <Logo />
          <span>
            VAMPYUYU <small>NETWORK CONTROL</small>
          </span>
        </div>
        <form onSubmit={login} className="login-card">
          <span className="eyebrow">ADMINISTRATOR ACCESS</span>
          <h1>
            Your worlds.
            <br />
            One place.
          </h1>
          <p>Sign in to manage your Minecraft network.</p>
          <label>
            Username
            <input
              name="username"
              autoComplete="username"
              required
              maxLength={64}
            />
          </label>
          <label>
            Password
            <input
              name="password"
              type="password"
              autoComplete="current-password"
              required
              maxLength={72}
            />
          </label>
          {error && (
            <p role="alert" className="error">
              {error}
            </p>
          )}
          <button className="primary" disabled={!!busy}>
            Sign in <span>→</span>
          </button>
        </form>
        <p className="login-foot">play.vampyuyu.win · Java Edition</p>
      </main>
    );
  const current = servers.find((s) => s.id === selected),
    running = current?.state === "running",
    online = servers.filter((s) => s.state === "running").length;
  return (
    <div className="shell">
      <aside>
        <a className="brand" href="/">
          <Logo />
          <span>
            VAMPYUYU<small>NETWORK CONTROL</small>
          </span>
        </a>
        <span className="eyebrow nav-label">WORKSPACE</span>
        <nav className="workspace-nav" aria-label="Workspace">
          <button
            className={`nav-item ${view === "overview" ? "nav-current" : ""}`}
            onClick={() => {
              setView("overview");
              setError("");
            }}
          >
            <span>▦</span> Overview{" "}
            {view === "overview" && <span className="nav-dot" />}
          </button>
          <button
            className={`nav-item ${view === "players" ? "nav-current" : ""}`}
            onClick={() => {
              setView("players");
              setError("");
            }}
          >
            <span>♙</span> Players &amp; roles{" "}
            {view === "players" && <span className="nav-dot" />}
          </button>
          {(
            [
              ["arenas", "☾", "Arenas"],
              ["plugins", "♧", "Plugins"],
              ["settings", "⚙", "Server settings"],
              ["activity", "≡", "Activity"],
            ] as const
          ).map(([id, icon, label]) => (
            <button
              key={id}
              className={`nav-item ${view === id ? "nav-current" : ""}`}
              onClick={() => {
                setView(id);
                setError("");
                if (selected === "velocity" && id !== "activity")
                  setSelected("creative");
              }}
            >
              <span>{icon}</span>
              {label}
              {view === id && <span className="nav-dot" />}
            </button>
          ))}
        </nav>
        <div className="side-network">
          <span className="eyebrow">YOUR NETWORK</span>
          <strong>play.vampyuyu.win</strong>
          <p>
            Java Edition <span>↗</span>
          </p>
        </div>
        <div className="side-bottom">
          <div className="avatar">
            {session.username.slice(0, 1).toUpperCase()}
          </div>
          <div>
            <strong>{session.username}</strong>
            <small>Administrator</small>
          </div>
          <button
            className="signout"
            title="Sign out"
            aria-label="Sign out"
            onClick={async () => {
              try {
                await api("/logout", "POST", session.csrf);
                setSession(null);
                setError("");
              } catch (e) {
                report(e);
              }
            }}
          >
            ↪
          </button>
        </div>
      </aside>
      <main className="workspace">
        <header>
          <div className="breadcrumb">
            Workspace <span>/</span>{" "}
            {
              {
                arenas: "Arenas",
                overview: "Overview",
                players: "Players & roles",
                plugins: "Plugins",
                settings: "Server settings",
                activity: "Activity",
              }[view]
            }
          </div>
          <span className="tag">JAVA EDITION</span>
        </header>
        <section className="page-heading">
          <div>
            <span className="eyebrow">
              {view === "overview"
                ? "A LITTLE CONTROL. ENDLESS POSSIBILITIES."
                : "YOUR COMMUNITY. BEAUTIFULLY CONNECTED."}
            </span>
            <h1>
              {
                {
                  arenas: "Arenas & mini-games",
                  overview: "Network overview",
                  players: "Players & roles",
                  plugins: "Server plugins",
                  settings: "Server settings",
                  activity: "Administration activity",
                }[view]
              }
              <span>.</span>
            </h1>
            <p>
              {view === "overview"
                ? "Keep your worlds running and your community connected."
                : {
                    arenas:
                      "Create a playground. Gather your players. Tell a new story.",
                    players:
                      "Inventories, game modes, roles and care for your community.",
                    plugins: "Install and manage the plugins for each world.",
                    settings:
                      "World rules, access and everyday server controls.",
                    activity: "Follow the changes made across your network.",
                  }[view]}
            </p>
          </div>
          <div className="network-state">
            <span className="dot" />
            {online} of 4 services running
          </div>
        </section>
        {error && (
          <div className="error banner" role="alert">
            {error}
            <button aria-label="Dismiss error" onClick={() => setError("")}>
              ×
            </button>
          </div>
        )}
        {view === "arenas" ? (
          <Arenas csrf={session.csrf} onError={report} />
        ) : view === "players" ? (
          <Players csrf={session.csrf} onError={report} />
        ) : view === "activity" ? (
          <Activity onError={report} />
        ) : view === "plugins" || view === "settings" ? (
          <>
            <div className="page-server-selector">
              <label>
                Server{" "}
                <select
                  value={selected}
                  onChange={(e) => setSelected(e.target.value)}
                >
                  {["lobby", "creative", "survival"].map((id) => (
                    <option key={id} value={id}>
                      {names[id]}
                    </option>
                  ))}
                </select>
              </label>
              <span className={`status ${running ? "on" : ""}`}>
                {current?.state || "Unavailable"}
              </span>
              <div className="actions">
                <button
                  disabled={!!busy || running}
                  onClick={() => action("start")}
                >
                  Start
                </button>
                <button
                  disabled={!!busy || !running}
                  onClick={() => action("restart")}
                >
                  Restart
                </button>
                <button
                  disabled={!!busy || !running}
                  onClick={() => action("stop")}
                >
                  Stop
                </button>
              </div>
            </div>
            {view === "plugins" ? (
              <ServerTools
                key={selected}
                mode="plugins"
                server={selected}
                running={running}
                csrf={session.csrf}
                onError={report}
                onRestart={() => action("restart")}
              />
            ) : (
              <Operations
                key={selected}
                server={selected}
                csrf={session.csrf}
                onError={report}
              />
            )}
          </>
        ) : (
          <>
            <section className="cards" aria-label="Servers">
              {["velocity", "lobby", "creative", "survival"].map(
                (id, index) => {
                  const s = servers.find((s) => s.id === id);
                  return (
                    <button
                      key={id}
                      onClick={() => {
                        setSelected(id);
                        setError("");
                      }}
                      className={`server-card ${selected === id ? "selected" : ""}`}
                      aria-pressed={selected === id}
                    >
                      <div className="card-top">
                        <span className={`server-icon icon-${id}`}>
                          {["⇄", "⌂", "▧", "♧"][index]}
                        </span>
                        <span
                          className={`status ${s?.state === "running" ? "on" : ""}`}
                        >
                          <span />
                          {s?.state || "loading"}
                        </span>
                      </div>
                      <h2>{names[id]}</h2>
                      <p>{descriptions[id]}</p>
                      <div className="card-foot">
                        <span>
                          {id === "velocity"
                            ? "PROXY"
                            : id === "lobby"
                              ? "HUB"
                              : "PAPER SERVER"}
                        </span>
                        <span>
                          {selected === id ? "Selected" : "Open console"} ↗
                        </span>
                      </div>
                    </button>
                  );
                },
              )}
            </section>
            <ServerTools
              key={selected}
              server={selected}
              running={running}
              csrf={session.csrf}
              onError={report}
              onRestart={() => action("restart")}
            />
            <section className="console-section">
              <div className="console-heading">
                <div>
                  <span className="eyebrow">SERVER WORKSPACE</span>
                  <h2>{names[selected]} console</h2>
                </div>
                <div className="actions">
                  <button
                    disabled={!!busy || running || !current}
                    onClick={() => action("start")}
                  >
                    ▷ Start
                  </button>
                  <button
                    disabled={!!busy || !running}
                    onClick={() => action("restart")}
                  >
                    ↻ Restart
                  </button>
                  <button
                    className="stop"
                    disabled={!!busy || !running}
                    onClick={() => action("stop")}
                  >
                    □ Stop
                  </button>
                </div>
              </div>
              <div className="console">
                <div className="console-toolbar">
                  <div>
                    <span className={stream === "Live" ? "dot" : ""} />
                    <span>{stream}</span>
                  </div>
                  <label>
                    <input
                      type="checkbox"
                      checked={follow}
                      onChange={(e) => setFollow(e.target.checked)}
                    />{" "}
                    Auto-scroll
                  </label>
                  <span>Last 200 lines</span>
                </div>
                <div
                  className="log-window"
                  ref={logWindow}
                  role="log"
                  aria-label={`${names[selected]} console`}
                  tabIndex={0}
                >
                  {lines.length ? (
                    lines.map((line, i) => (
                      <div className="log-line" key={i}>
                        <span className="line-number">{i + 1}</span>
                        <span>{line}</span>
                      </div>
                    ))
                  ) : (
                    <div className="empty-log">Waiting for server output…</div>
                  )}
                </div>
                <form className="command-bar" onSubmit={send}>
                  <span>❯</span>
                  <input
                    aria-label="Server command"
                    placeholder={
                      selected === "velocity"
                        ? "Proxy command submission is not available"
                        : "Enter a Minecraft command…"
                    }
                    value={command}
                    maxLength={2048}
                    onChange={(e) => setCommand(e.target.value)}
                    disabled={!running || selected === "velocity" || !!busy}
                  />
                  <button
                    disabled={
                      !running ||
                      selected === "velocity" ||
                      !!busy ||
                      !command.trim()
                    }
                  >
                    Send ↵
                  </button>
                </form>
              </div>
              {output && (
                <pre className="command-output" aria-live="polite">
                  {output}
                </pre>
              )}
              <div className="console-note">
                <span>ⓘ</span> Commands run with console privileges. Server
                changes may disconnect players.
              </div>
            </section>
          </>
        )}
        <footer>
          <span>Vampyuyu Network</span>
          <span>Built for the worlds you create.</span>
        </footer>
      </main>
    </div>
  );
}
function Logo() {
  return (
    <svg width="35" height="35" viewBox="0 0 36 36" aria-hidden="true">
      <path d="M3 8 18 1l15 7v20l-15 7L3 28Z" fill="var(--gold)" />
      <path d="m9 11 9 4 9-4v11l-9 7-9-7Z" fill="var(--sidebar)" />
      <path d="M18 15v14m-9-18 9 7 9-7" stroke="var(--gold)" strokeWidth="2" />
    </svg>
  );
}
createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <ConfirmProvider>
      <App />
    </ConfirmProvider>
  </React.StrictMode>,
);

import { useConfirm } from "./ConfirmDialog";
import { FormEvent, useEffect, useRef, useState } from "react";
import { api, APIError } from "./api";

type Metrics = {
  id: string;
  sampledAt: string;
  cpuPercent: number | null;
  memoryBytes: number | null;
  memoryLimit: number | null;
  pids: number | null;
  restarts: number;
  startedAt: string;
  storageBytes: number | null;
  storageSampledAt: string;
  diskAvailable: number | null;
  diskTotal: number | null;
  processes: { pid: string; name: string; cpu: string; memory: string }[];
  warnings: string[];
};
type Plugin = {
  filename: string;
  name: string;
  version: string;
  bytes: number;
  managed: boolean;
  problem?: string;
};
export function bytes(value: number | null | undefined) {
  if (value == null) return "—";
  if (value < 1024) return `${value} B`;
  const index = Math.min(Math.floor(Math.log(value) / Math.log(1024)), 3);
  return `${(value / 1024 ** index).toFixed(1)} ${["B", "KiB", "MiB", "GiB"][index]}`;
}
function uptime(start: string | undefined) {
  const seconds = start
    ? Math.floor((Date.now() - Date.parse(start)) / 1000)
    : 0;
  if (!Number.isFinite(seconds) || seconds < 0) return "—";
  return seconds < 3600
    ? `${Math.floor(seconds / 60)}m ${seconds % 60}s`
    : `${Math.floor(seconds / 3600)}h ${Math.floor((seconds % 3600) / 60)}m`;
}
function Sparkline({ values, label }: { values: number[]; label: string }) {
  const max = Math.max(...values, 1);
  const points = values
    .map(
      (v, i) =>
        `${(i * 280) / Math.max(values.length - 1, 1)},${44 - (v / max) * 40}`,
    )
    .join(" ");
  return (
    <svg
      className="resource-spark"
      viewBox="0 0 280 48"
      role="img"
      aria-label={label}
    >
      <polyline points={points} />
    </svg>
  );
}
export function ServerTools({
  server,
  running,
  csrf,
  onError,
  onRestart,
  mode = "resources",
}: {
  mode?: "resources" | "plugins";
  server: string;
  running: boolean;
  csrf: string;
  onError: (error: unknown) => void;
  onRestart: () => void;
}) {
  const tab = mode;
  const confirm = useConfirm();
  const [metrics, setMetrics] = useState<Metrics | null>(null);
  const [history, setHistory] = useState<{ cpu: number[]; ram: number[] }>({
    cpu: [],
    ram: [],
  });
  const [metricError, setMetricError] = useState("");
  const [plugins, setPlugins] = useState<Plugin[]>([]);
  const [pluginError, setPluginError] = useState("");
  const [busy, setBusy] = useState(false),
    [notice, setNotice] = useState("");
  const [loading, setLoading] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);
  const errorHandler = useRef(onError);
  errorHandler.current = onError;
  useEffect(() => {
    if (mode === "plugins") return;
    let active = true;
    let timer: ReturnType<typeof setTimeout>;
    const poll = async () => {
      try {
        const data = await api<Metrics>(`/servers/${server}/metrics`);
        if (!active) return;
        setMetrics(data);
        setMetricError("");
        setHistory((h) => ({
          cpu:
            data.cpuPercent == null
              ? h.cpu
              : [...h.cpu, data.cpuPercent].slice(-36),
          ram:
            data.memoryBytes == null
              ? h.ram
              : [...h.ram, data.memoryBytes].slice(-36),
        }));
      } catch (e) {
        if (!active) return;
        setMetrics(null);
        setMetricError(e instanceof Error ? e.message : "Metrics unavailable");
        if (e instanceof APIError && e.status === 401) errorHandler.current(e);
      } finally {
        if (active) timer = setTimeout(poll, 5000);
      }
    };
    poll();
    return () => {
      active = false;
      clearTimeout(timer);
    };
  }, [server, mode]);
  async function loadPlugins() {
    setLoading(true);
    setPluginError("");
    try {
      setPlugins(await api<Plugin[]>(`/servers/${server}/plugins`));
    } catch (e) {
      setPluginError(
        e instanceof Error ? e.message : "Plugin list unavailable",
      );
      if (e instanceof APIError && e.status === 401) errorHandler.current(e);
    } finally {
      setLoading(false);
    }
  }
  useEffect(() => {
    if (tab === "plugins" && server !== "velocity") void loadPlugins();
  }, [tab, server]);
  async function upload(e: FormEvent) {
    e.preventDefault();
    const file = fileInput.current?.files?.[0];
    if (!file) return;
    if (file.size > 64 * 1024 * 1024) {
      setPluginError("Choose a JAR no larger than 64 MiB.");
      return;
    }
    setBusy(true);
    setPluginError("");
    setNotice("");
    try {
      const response = await fetch(
        `/api/v1/servers/${server}/plugins/${encodeURIComponent(file.name)}`,
        {
          method: "POST",
          headers: {
            "Content-Type": "application/octet-stream",
            "X-CSRF-Token": csrf,
          },
          body: file,
        },
      );
      const result = await response.json();
      if (!response.ok)
        throw new APIError(result.error || "Upload failed", response.status);
      setNotice(`${file.name} installed. Restart ${server} to load it.`);
      if (fileInput.current) fileInput.current.value = "";
      await loadPlugins();
    } catch (e) {
      errorHandler.current(e);
    } finally {
      setBusy(false);
    }
  }
  async function remove(plugin: Plugin) {
    if (
      !(await confirm(
        `Remove ${plugin.name || plugin.filename} from ${server}? Its configuration and player data will be kept.`,
      ))
    )
      return;
    setBusy(true);
    setNotice("");
    try {
      await api(
        `/servers/${server}/plugins/${encodeURIComponent(plugin.filename)}`,
        "DELETE",
        csrf,
      );
      setNotice("Plugin removed. Its configuration is preserved.");
      await loadPlugins();
    } catch (e) {
      errorHandler.current(e);
    } finally {
      setBusy(false);
    }
  }
  return (
    <section className="management-section">
      <div className="management-heading">
        <div>
          <span className="eyebrow">SERVER INSIGHTS</span>
          <h2>
            {mode === "plugins" ? "Server plugins" : "Under the hood"}
            <span>.</span>
          </h2>
        </div>
      </div>
      {tab === "resources" ? (
        <>
          {metricError && (
            <p className="error" role="status">
              {metricError}
            </p>
          )}
          <div className="resource-grid">
            <article>
              <span className="eyebrow">CPU</span>
              <strong>
                {metrics?.cpuPercent == null
                  ? "—"
                  : `${metrics.cpuPercent.toFixed(1)}%`}
              </strong>
              <small>100% = one CPU core</small>
              <Sparkline
                values={history.cpu}
                label="Recent container CPU usage"
              />
            </article>
            <article>
              <span className="eyebrow">MEMORY</span>
              <strong>{bytes(metrics?.memoryBytes)}</strong>
              <small>of {bytes(metrics?.memoryLimit)} container limit</small>
              <Sparkline
                values={history.ram}
                label="Recent container memory usage"
              />
            </article>
            <article>
              <span className="eyebrow">SERVER STORAGE</span>
              <strong>{bytes(metrics?.storageBytes)}</strong>
              <small>
                {bytes(metrics?.diskAvailable)} available on host disk
              </small>
              <p className="storage-note">
                Worlds, plugins and server files · refreshed every 30s
              </p>
            </article>
          </div>
          <div className="process-summary">
            <span>
              <b>{running ? uptime(metrics?.startedAt) : "Stopped"}</b> uptime
            </span>
            <span>
              <b>{metrics?.pids ?? "—"}</b> threads / tasks
            </span>
            <span>
              <b>{metrics?.restarts ?? "—"}</b> automatic restarts
            </span>
            <span>
              Updated{" "}
              {metrics ? new Date(metrics.sampledAt).toLocaleTimeString() : "—"}
            </span>
          </div>
          <details className="process-details">
            <summary>
              Running processes <span>{metrics?.processes.length ?? 0}</span>
            </summary>
            <div className="player-table-wrap">
              <table className="player-table">
                <thead>
                  <tr>
                    <th>PID</th>
                    <th>Process</th>
                    <th>CPU avg.</th>
                    <th>Host RAM</th>
                  </tr>
                </thead>
                <tbody>
                  {metrics?.processes.map((p) => (
                    <tr key={p.pid}>
                      <td>{p.pid}</td>
                      <td>{p.name}</td>
                      <td>{p.cpu}%</td>
                      <td>{p.memory}%</td>
                    </tr>
                  ))}
                  {!metrics?.processes.length && (
                    <tr>
                      <td colSpan={4}>No running processes available.</td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          </details>
          {metrics?.warnings.map((w) => (
            <p className="console-note" key={w}>
              {w}
            </p>
          ))}
        </>
      ) : (
        <>
          <div className="plugin-intro">
            <p>
              Install a Paper plugin, then restart this server to load it.
              Network plugins are managed automatically.
            </p>
            <button disabled={!running || busy} onClick={onRestart}>
              ↻ Restart {server}
            </button>
          </div>
          <form className="plugin-upload" onSubmit={upload}>
            <label>
              Plugin JAR{" "}
              <input
                ref={fileInput}
                type="file"
                accept=".jar"
                required
                disabled={busy}
                aria-label="Plugin JAR"
              />
            </label>
            <span>Up to 64 MiB</span>
            <button className="primary" disabled={busy}>
              {busy ? "Working…" : "Upload plugin ↑"}
            </button>
          </form>
          {notice && (
            <p className="management-notice" role="status">
              {notice}
            </p>
          )}
          {pluginError && (
            <p className="error" role="alert">
              {pluginError}
            </p>
          )}
          <div className="plugin-list-heading">
            <h3>
              Installed JARs <span>{plugins.length}</span>
            </h3>
            <button onClick={loadPlugins} disabled={busy || loading}>
              Refresh
            </button>
          </div>
          <div className="player-table-wrap">
            <table className="player-table">
              <thead>
                <tr>
                  <th>Plugin</th>
                  <th>Version</th>
                  <th>Size</th>
                  <th>Manage</th>
                </tr>
              </thead>
              <tbody>
                {plugins.map((p) => (
                  <tr key={p.filename}>
                    <td>
                      <strong>{p.name || p.filename}</strong>
                      <small className="last-seen">{p.filename}</small>
                      {p.problem && (
                        <small className="error">{p.problem}</small>
                      )}
                    </td>
                    <td>{p.version || "—"}</td>
                    <td>{bytes(p.bytes)}</td>
                    <td>
                      {p.managed ? (
                        <span className="tag">MANAGED</span>
                      ) : (
                        <button
                          disabled={busy || running}
                          title={
                            running
                              ? "Stop this server before removing a plugin"
                              : "Remove JAR, preserve configuration"
                          }
                          onClick={() => remove(p)}
                        >
                          Remove
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
                {!plugins.length && (
                  <tr>
                    <td colSpan={4}>
                      {loading ? "Loading plugins…" : "No plugins found."}
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
          <p className="console-note">
            Stop this server before removing or replacing a plugin. Installed
            JARs reflect files on disk; changes take effect at the next start.
          </p>
        </>
      )}
    </section>
  );
}

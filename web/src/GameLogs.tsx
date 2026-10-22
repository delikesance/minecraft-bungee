import { useEffect, useState } from "react";
import { api } from "./api";

type Log = { name: string; size: number; modified: number; live: boolean };

/** Werewolf game journals kept by the arena engine, newest first, to download. */
export function GameLogs({ server }: { server: string }) {
  const [logs, setLogs] = useState<Log[] | null>(null);
  const [error, setError] = useState("");
  useEffect(() => {
    let live = true;
    setLogs(null);
    async function load() {
      try {
        const data = await api<{ logs: Log[] }>(`/servers/${server}/game/logs`);
        if (!live) return;
        setLogs(data.logs);
        setError("");
      } catch (e) {
        if (live)
          setError(e instanceof Error ? e.message : "Game logs unavailable");
      }
    }
    void load();
    const timer = setInterval(load, 10000);
    return () => {
      live = false;
      clearInterval(timer);
    };
  }, [server]);
  return (
    <section className="arena-moderation game-logs">
      <div>
        <span className="eyebrow">EVERY PHASE, VOTE AND DEATH</span>
        <h2>
          Game logs<span>.</span>
        </h2>
        <p className="console-note">
          One file per werewolf game: the deal, each phase and its timer, every
          click and why it was refused, the votes, the deaths and their cause. A
          running game's log downloads as far as it is written.
        </p>
      </div>
      {error && <p className="console-note">{error}</p>}
      {logs && logs.length === 0 && (
        <p className="console-note">No game has been logged yet.</p>
      )}
      {logs?.map((log) => {
        const [, date, time, arena, game] =
          /^(\d{4}-\d{2}-\d{2})_(\d{2}-\d{2}-\d{2})_(.+)_([^_]+)\.log$/.exec(
            log.name,
          ) || [, "", "", log.name, ""];
        return (
          <div className="arena-ban" key={log.name}>
            <code>{game || log.name}</code>
            <span>
              {arena} · {date} {time.replaceAll("-", ":")} ·{" "}
              {Math.max(1, Math.round(log.size / 1024))} KB
              {log.live ? " · game in progress" : ""}
            </span>
            <a
              className="button"
              href={`/api/v1/servers/${server}/game/logs/${encodeURIComponent(log.name)}`}
              download={log.name}
            >
              Download
            </a>
          </div>
        );
      })}
    </section>
  );
}

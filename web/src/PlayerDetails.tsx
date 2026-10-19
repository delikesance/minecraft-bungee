import { useConfirm } from "./ConfirmDialog";
import { useEffect, useRef, useState } from "react";
import { api, APIError } from "./api";

type Slot = {
  slot: number;
  material: string;
  amount: number;
  name: string;
  details: string;
  custom: boolean;
};
type Inventory = {
  uuid: string;
  online: boolean;
  container: string;
  revision: string;
  slots: Slot[];
  player: {
    gamemode: string;
    health: number;
    food: number;
    level: number;
    world: string;
    x: number;
    y: number;
    z: number;
    op: boolean;
    whitelisted: boolean;
    flying?: boolean;
    ping?: number;
  };
};
const worlds = { lobby: "Lobby", creative: "Creative", survival: "Survival" };
function itemName(material: string) {
  return material.replace("minecraft:", "").replaceAll("_", " ");
}
function label(slot: number, ender: boolean) {
  if (ender) return `Ender slot ${slot + 1}`;
  return slot < 9
    ? `Hotbar ${slot + 1}`
    : slot < 36
      ? `Storage ${slot - 8}`
      : {
          36: "Boots",
          37: "Leggings",
          38: "Chestplate",
          39: "Helmet",
          40: "Off hand",
        }[slot] || `Slot ${slot}`;
}
export function PlayerDetails({
  player,
  initialServer,
  csrf,
  onClose,
  onError,
}: {
  player: { uuid: string; name: string };
  initialServer: string;
  csrf: string;
  onClose: () => void;
  onError: (error: unknown) => void;
}) {
  const confirm = useConfirm();
  const [server, setServer] = useState(
      initialServer in worlds ? initialServer : "creative",
    ),
    [container, setContainer] = useState("inventory");
  const [view, setView] = useState<Inventory | null>(null),
    [materials, setMaterials] = useState<string[]>([]),
    [selected, setSelected] = useState(0),
    [material, setMaterial] = useState("minecraft:air"),
    [amount, setAmount] = useState(0);
  const [error, setError] = useState(""),
    [notice, setNotice] = useState(""),
    [busy, setBusy] = useState(false),
    [loading, setLoading] = useState(true),
    [conflict, setConflict] = useState(false);
  const [reason, setReason] = useState("Managed by network administration"),
    [hours, setHours] = useState(0);
  const [backups, setBackups] = useState<{ file: string; created: number }[]>(
    [],
  );
  const [backup, setBackup] = useState("");
  const backupRequest = useRef(0);
  const dialog = useRef<HTMLElement>(null);
  const dirty = useRef(false),
    active = useRef(true);
  const endpoint = `/servers/${server}/game/players/${player.uuid}/${container}`;
  useEffect(() => {
    const previous = document.activeElement;
    const wasOpen = document.body.classList.contains("player-dialog-open");
    document.body.classList.add("player-dialog-open");
    dialog.current?.focus();
    return () => {
      if (!wasOpen) document.body.classList.remove("player-dialog-open");
      if (previous instanceof HTMLElement) previous.focus();
    };
  }, []);

  function failure(e: unknown) {
    setError(e instanceof Error ? e.message : "Request failed");
    if (e instanceof APIError && e.status === 401) onError(e);
  }
  async function loadBackups() {
    const generation = ++backupRequest.current;
    try {
      const list = await api<{ file: string; created: number }[]>(
        `/servers/${server}/game/players/${player.uuid}/backups`,
      );
      if (generation !== backupRequest.current || !active.current) return;
      setBackups(list);
      setBackup((current) =>
        list.some((b) => b.file === current) ? current : list[0]?.file || "",
      );
    } catch {}
  }
  async function refresh() {
    setLoading(true);
    setError("");
    try {
      const result = await api<Inventory>(endpoint);
      dirty.current = false;
      setView(result);
      setConflict(false);
      void loadBackups();
    } catch (e) {
      setView(null);
      failure(e);
    } finally {
      setLoading(false);
    }
  }
  useEffect(() => {
    active.current = true;
    let live = true;
    dirty.current = false;
    setView(null);
    setBackups([]);
    setBackup("");
    setSelected(0);
    setNotice("");
    setConflict(false);
    setLoading(true);
    setError("");
    const poll = async () => {
      try {
        const next = await api<Inventory>(endpoint);
        if (!live) return;
        setView((old) => {
          if (dirty.current && old) {
            if (next.revision !== old.revision) setConflict(true);
            return { ...old, player: next.player, online: next.online };
          }
          return next;
        });
        setLoading(false);
      } catch (e) {
        if (live) {
          failure(e);
          setLoading(false);
        }
      }
    };
    poll();
    void loadBackups();
    api<string[]>(`/servers/${server}/game/materials`)
      .then((v) => {
        if (live) setMaterials(v);
      })
      .catch(() => {});
    const timer = setInterval(poll, 10000);
    return () => {
      live = false;
      active.current = false;
      clearInterval(timer);
    };
  }, [endpoint]);
  const slot = view?.slots.find((s) => s.slot === selected);
  useEffect(() => {
    if (!dirty.current && slot) {
      setMaterial(slot.material);
      setAmount(slot.amount);
    }
  }, [view?.revision, selected]);
  async function discard() {
    return (
      !dirty.current ||
      confirm(
        "Your changes to this slot have not been saved. Discard them and continue?",
        { title: "Discard unsaved changes?", confirmLabel: "Discard changes" },
      )
    );
  }
  async function save(clear = false) {
    if (!view) return;
    const mat = clear ? "minecraft:air" : material,
      qty = clear ? 0 : amount;
    if (
      (qty === 0 || mat !== slot?.material) &&
      slot?.amount &&
      !(await confirm(
        `Replace or clear ${itemName(slot.material)} in ${label(selected, container === "ender")}? Replacing the material removes the old item's custom properties.`,
      ))
    )
      return;
    setBusy(true);
    setError("");
    setNotice("");
    try {
      const result = await api<Inventory>(endpoint, "PUT", csrf, {
        revision: view.revision,
        slot: selected,
        material: mat,
        amount: qty,
      });
      if (!active.current) return;
      dirty.current = false;
      setView(result);
      setConflict(false);
      setNotice("Slot saved. A player-data backup was created on the server.");
      void loadBackups();
    } catch (e) {
      failure(e);
      if (e instanceof APIError && e.status === 409) setConflict(true);
    } finally {
      setBusy(false);
    }
  }
  async function action(action: string, extra: Record<string, unknown> = {}) {
    if (!(await discard())) return;
    if (
      !(await confirm(
        action === "restore-inventory"
          ? `Restore ${container} for ${player.name} on ${worlds[server as keyof typeof worlds]} from ${new Date(backups.find((b) => b.file === extra.backup)?.created || 0).toLocaleString()}? Position, game mode, XP and the other inventory are kept.`
          : `Apply ${action.replaceAll("-", " ")} to ${player.name} on ${worlds[server as keyof typeof worlds]}?`,
      ))
    )
      return;
    setBusy(true);
    setError("");
    try {
      await api(`/servers/${server}/game/action`, "POST", csrf, {
        action,
        uuid: player.uuid,
        ...extra,
      });
      setNotice("Player action completed.");
      await refresh();
    } catch (e) {
      failure(e);
    } finally {
      setBusy(false);
    }
  }
  const renderSlot = (index: number) => {
    const item = view?.slots.find((s) => s.slot === index);
    return (
      <button
        key={index}
        className={`inventory-slot ${selected === index ? "slot-selected" : ""} ${item?.amount ? "has-item" : ""}`}
        aria-label={`${label(index, container === "ender")}: ${item?.amount ? `${itemName(item.material)} × ${item.amount}` : "empty"}`}
        aria-pressed={selected === index}
        disabled={busy}
        title={item?.name}
        onClick={async () => {
          if (await discard()) {
            dirty.current = false;
            setSelected(index);
            setConflict(false);
            if (item) {
              setMaterial(item.material);
              setAmount(item.amount);
            }
          }
        }}
      >
        <small>{label(index, container === "ender")}</small>
        <span>{item?.amount ? itemName(item.material) : "—"}</span>
        {!!item?.amount && <b>{item.amount}</b>}
        {item?.custom && <i title="Has custom properties">✦</i>}
      </button>
    );
  };
  return (
    <div className="dialog-shade">
      <section
        className="player-dialog"
        ref={dialog}
        tabIndex={-1}
        onKeyDown={async (e) => {
          if (e.key === "Escape" && !busy) {
            e.preventDefault();
            if (await discard()) onClose();
          }
          if (e.key === "Tab") {
            const elements = Array.from(
              dialog.current?.querySelectorAll<HTMLElement>(
                "button:not(:disabled),input:not(:disabled),select:not(:disabled),summary",
              ) || [],
            ).filter((el) => el.getClientRects().length);
            const first = elements[0],
              last = elements[elements.length - 1];
            if (
              e.shiftKey &&
              (document.activeElement === first ||
                document.activeElement === dialog.current)
            ) {
              e.preventDefault();
              last?.focus();
            } else if (!e.shiftKey && document.activeElement === last) {
              e.preventDefault();
              first?.focus();
            }
          }
        }}
        role="dialog"
        aria-modal="true"
        aria-label={`Manage ${player.name}`}
      >
        <div className="management-heading">
          <div>
            <span className="eyebrow">PLAYER WORKSPACE</span>
            <h2>
              {player.name}
              <span>.</span>
            </h2>
            <small>{player.uuid}</small>
          </div>
          <button
            onClick={async () => {
              if (await discard()) onClose();
            }}
            disabled={busy}
            aria-label="Close player workspace"
          >
            Close ×
          </button>
        </div>
        <div className="detail-toolbar">
          <label>
            Server{" "}
            <select
              value={server}
              disabled={busy}
              onChange={async (e) => {
                const nextServer = e.target.value;
                if (await discard()) {
                  dirty.current = false;
                  setServer(nextServer);
                }
              }}
            >
              {Object.entries(worlds).map(([id, name]) => (
                <option key={id} value={id}>
                  {name}
                </option>
              ))}
            </select>
          </label>
          <span className={`status ${view?.online ? "on" : ""}`}>
            {view?.online ? "Online here" : "Saved / offline data"}
          </span>
          <button
            onClick={async () => {
              if (await discard()) void refresh();
            }}
            disabled={busy || loading}
          >
            Refresh
          </button>
        </div>
        {error && (
          <p className="error" role="alert">
            {error}
            {error.includes("file") || error.includes("unavailable")
              ? " · This player may not have saved data on this server yet."
              : ""}
          </p>
        )}
        {notice && (
          <p className="management-notice" role="status">
            {notice}
          </p>
        )}
        {loading && !view && <p>Loading player data…</p>}
        {view && (
          <>
            <div className="player-stats">
              <div>
                <small>Game mode</small>
                <select
                  value={view.player.gamemode}
                  disabled={busy}
                  aria-label="Player game mode"
                  onChange={(e) =>
                    action("gamemode", { value: e.target.value })
                  }
                >
                  {["SURVIVAL", "CREATIVE", "ADVENTURE", "SPECTATOR"].map(
                    (v) => (
                      <option key={v}>{v}</option>
                    ),
                  )}
                </select>
              </div>
              <div>
                <small>Health / Food</small>
                <strong>
                  {view.player.health.toFixed(1)} HP · {view.player.food}/20
                </strong>
              </div>
              <div>
                <small>XP level</small>
                <strong>{view.player.level}</strong>
              </div>
              <div>
                <small>
                  {view.online ? "Current" : "Saved"} position ·{" "}
                  {view.player.world}
                </small>
                <strong>
                  {[view.player.x, view.player.y, view.player.z]
                    .map((n) => (Number.isFinite(n) ? n.toFixed(1) : "—"))
                    .join(" / ")}
                </strong>
              </div>
            </div>
            <div className="tool-tabs inventory-tabs">
              <button
                aria-pressed={container === "inventory"}
                disabled={busy}
                onClick={async () => {
                  if (await discard()) {
                    dirty.current = false;
                    setContainer("inventory");
                  }
                }}
              >
                Inventory & equipment
              </button>
              <button
                aria-pressed={container === "ender"}
                disabled={busy}
                onClick={async () => {
                  if (await discard()) {
                    dirty.current = false;
                    setContainer("ender");
                  }
                }}
              >
                Ender chest
              </button>
            </div>
            {conflict && (
              <p className="error" role="status">
                The inventory changed while you were editing. Refresh to load
                the latest items before saving.
              </p>
            )}
            <div className="inventory-workspace">
              <div>
                {container === "inventory" ? (
                  <>
                    <div className="equipment-grid">
                      {[39, 38, 37, 36, 40].map(renderSlot)}
                    </div>
                    <p className="eyebrow">STORAGE</p>
                    <div className="inventory-grid">
                      {Array.from({ length: 27 }, (_, i) => renderSlot(i + 9))}
                    </div>
                    <p className="eyebrow">HOTBAR</p>
                    <div className="inventory-grid">
                      {Array.from({ length: 9 }, (_, i) => renderSlot(i))}
                    </div>
                  </>
                ) : (
                  <div className="inventory-grid">
                    {Array.from({ length: 27 }, (_, i) => renderSlot(i))}
                  </div>
                )}
              </div>
              <form
                className="slot-editor"
                onSubmit={(e) => {
                  e.preventDefault();
                  void save();
                }}
              >
                <span className="eyebrow">
                  {label(selected, container === "ender")}
                </span>
                <h3>{slot?.amount ? itemName(slot.material) : "Empty slot"}</h3>
                {slot?.custom && (
                  <p className="console-note">
                    ✦ Custom item. Changing only the amount preserves its
                    properties.
                  </p>
                )}
                <label>
                  Material
                  <input
                    list="minecraft-materials"
                    value={material}
                    maxLength={128}
                    disabled={busy}
                    onChange={(e) => {
                      dirty.current = true;
                      setMaterial(e.target.value);
                      if (amount === 0 && e.target.value !== "minecraft:air")
                        setAmount(1);
                    }}
                  />
                </label>
                <datalist id="minecraft-materials">
                  {materials.map((m) => (
                    <option key={m} value={m} />
                  ))}
                </datalist>
                <label>
                  Amount
                  <input
                    type="number"
                    min={0}
                    max={99}
                    value={amount}
                    disabled={busy}
                    onChange={(e) => {
                      dirty.current = true;
                      setAmount(Number(e.target.value));
                    }}
                  />
                </label>
                <button
                  className="primary"
                  disabled={busy || conflict || !Number.isInteger(amount)}
                >
                  {busy ? "Saving…" : "Save slot"}
                </button>
                <button
                  type="button"
                  disabled={busy || conflict || !slot?.amount}
                  onClick={() => save(true)}
                >
                  Clear slot
                </button>
                {slot?.details && (
                  <details>
                    <summary>Item properties</summary>
                    <pre>{slot.details}</pre>
                  </details>
                )}
              </form>
            </div>
            <p className="console-note">
              One inventory per server. Dimensions on the same server share
              items. Changes are checked against the latest inventory; the last
              ten player-data backups are retained on disk.
            </p>
            <details className="inventory-backups">
              <summary>Inventory backups ({backups.length})</summary>
              <p className="console-note">
                Restore only the selected inventory or ender chest. The player
                must be offline on this server. Current items are backed up
                before restoring.
              </p>
              {backups.length ? (
                <div className="inline-settings">
                  <label>
                    Snapshot
                    <select
                      value={backup}
                      onChange={(e) => setBackup(e.target.value)}
                    >
                      {backups.map((b) => (
                        <option key={b.file} value={b.file}>
                          {new Date(b.created).toLocaleString()}
                        </option>
                      ))}
                    </select>
                  </label>
                  <button
                    disabled={busy || view.online || !backup || conflict}
                    onClick={() =>
                      action("restore-inventory", {
                        container,
                        backup,
                        revision: view.revision,
                      })
                    }
                  >
                    Restore{" "}
                    {container === "ender" ? "ender chest" : "inventory"}
                  </button>
                </div>
              ) : (
                <p>
                  No snapshots yet. The first edit creates one automatically.
                </p>
              )}
            </details>
            <div className="player-action-row">
              {["heal", "feed", "clear-effects", "teleport-spawn"].map((a) => (
                <button
                  key={a}
                  disabled={busy || !view.online}
                  onClick={() => action(a)}
                >
                  {a.replaceAll("-", " ")}
                </button>
              ))}
              <button
                disabled={busy}
                onClick={() =>
                  action(
                    view.player.whitelisted
                      ? "whitelist-remove"
                      : "whitelist-add",
                  )
                }
              >
                {view.player.whitelisted
                  ? "Remove from whitelist"
                  : "Add to whitelist"}
              </button>
            </div>
          </>
        )}
        <section className="moderation-panel">
          <h3>Moderation · {worlds[server as keyof typeof worlds]}</h3>
          <label>
            Reason
            <input
              value={reason}
              maxLength={160}
              onChange={(e) => setReason(e.target.value)}
            />
          </label>
          <label>
            Ban duration (hours; 0 = permanent)
            <input
              type="number"
              min={0}
              max={8760}
              value={hours}
              onChange={(e) => setHours(Number(e.target.value))}
            />
          </label>
          <div className="player-action-row">
            <button
              disabled={busy || !view?.online}
              onClick={() => action("kick", { reason })}
            >
              Kick player
            </button>
            <button
              disabled={busy || !reason.trim()}
              onClick={() => action("ban", { reason, hours })}
            >
              Ban player
            </button>
            <button disabled={busy} onClick={() => action("pardon")}>
              Remove ban
            </button>
          </div>
          <p className="console-note">
            Moderation affects this server. OP and dashboard access remain
            separate from role command grants.
          </p>
        </section>
      </section>
    </div>
  );
}

import { useEffect, useState } from "react";
import { api } from "./api";
type Policy = { allowed: string[]; roles: Record<string, string[]> };
type Role = { id: string; name: string };
const descriptions: Record<string, string> = {
  ban: "Ban a player",
  kick: "Disconnect a player",
  pardon: "Remove a ban",
  fly: "Toggle your own flight",
};
export function RoleCommands({
  csrf,
  onError,
}: {
  csrf: string;
  onError: (error: unknown) => void;
}) {
  const [roles, setRoles] = useState<Role[]>([]),
    [policy, setPolicy] = useState<Policy | null>(null),
    [pending, setPending] = useState(""),
    [notice, setNotice] = useState("");
  useEffect(() => {
    let active = true;
    Promise.all([api<Role[]>("/roles"), api<Policy>("/role-commands")])
      .then(([r, p]) => {
        if (active) {
          setRoles(r);
          setPolicy(p);
        }
      })
      .catch(onError);
    return () => {
      active = false;
    };
  }, []);
  async function save(role: Role) {
    if (!policy) return;
    setPending(role.id);
    setNotice("");
    try {
      await api(`/roles/${role.id}/commands`, "PUT", csrf, {
        commands: policy.roles[role.id],
      });
      setNotice(
        `${role.name} permissions saved. Online players synchronize within a few seconds.`,
      );
    } catch (e) {
      onError(e);
    } finally {
      setPending("");
    }
  }
  return (
    <section className="management-section role-access">
      <span className="eyebrow">ROLE ACCESS</span>
      <h2>A role. The right commands.</h2>
      <p>
        Choose what each role can do in Minecraft. New roles start without
        command grants. OP is managed separately; roles cannot grant /op,
        /gamemode or arbitrary permissions.
      </p>
      {notice && (
        <p className="management-notice" role="status">
          {notice}
        </p>
      )}
      <div className="role-access-grid">
        {roles.map((role) => (
          <article key={role.id}>
            <span className={`role-badge role-${role.id}`}>{role.name}</span>
            <div>
              {policy?.allowed.map((command) => (
                <label key={command}>
                  <input
                    type="checkbox"
                    aria-label={`${role.name}: /${command}`}
                    disabled={pending !== ""}
                    checked={policy.roles[role.id]?.includes(command) || false}
                    onChange={(e) => {
                      const checked = e.target.checked;
                      setPolicy((p) =>
                        p
                          ? {
                              ...p,
                              roles: {
                                ...p.roles,
                                [role.id]: checked
                                  ? [...p.roles[role.id], command]
                                  : p.roles[role.id].filter(
                                      (c) => c !== command,
                                    ),
                              },
                            }
                          : p,
                      );
                    }}
                  />
                  <span>
                    <b>/{command}</b>
                    <small>{descriptions[command]}</small>
                  </span>
                </label>
              ))}
            </div>
            <button
              disabled={!policy || pending !== ""}
              onClick={() => save(role)}
            >
              {pending === role.id ? "Saving…" : "Save commands"}
            </button>
          </article>
        ))}
      </div>
      <p className="console-note">
        Changing a player's role also changes these grants. OP retains full
        Minecraft privileges. Role grants expire if the network bridge stays
        unavailable.
      </p>
    </section>
  );
}

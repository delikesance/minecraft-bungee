import type { Config } from "./Arenas";

export type WerewolfRules = {
  roles: Record<string, number>;
  cupidSeconds: number;
  witchSeconds: number;
  hunterSeconds: number;
  ambienceEnabled?: boolean;
};
export type FreeplayRules = {
  objective: string;
  targetScore: number;
  pointsPerEvent: number;
  allowFlight: boolean;
  hunger: boolean;
  personalTime: number;
  weather: string;
  kit: Record<string, number>;
  effects: Record<string, number>;
  checkpoints: { x: number; y: number; z: number; radius: number }[];
  instructions: string;
};
export const defaultWerewolf: WerewolfRules = {
  roles: {},
  cupidSeconds: 15,
  witchSeconds: 15,
  hunterSeconds: 15,
  ambienceEnabled: true,
};
export const defaultFreeplay: FreeplayRules = {
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
  instructions:
    "Enjoy the arena. Use your compass to follow the game or leave.",
};
const roles = [
  ["WEREWOLF", "Werewolf", "Hunt with the pack and reach parity.", "☾"],
  [
    "VILLAGER",
    "Villager",
    "Find the wolves through discussion and voting.",
    "♙",
  ],
  ["SEER", "Seer", "Privately inspect one role every night.", "✧"],
  ["CUPID", "Cupid", "Bind two lovers; their fates stay linked.", "♡"],
  ["HUNTER", "Hunter", "Take a final shot when eliminated.", "➶"],
  ["WITCH", "Witch", "One healing potion and one poison per game.", "⚗"],
  ["GUARD", "Guard", "Protect a player each night, alternating targets.", "◇"],
] as const;
export function ArenaRulesEditor({
  config,
  onPatch,
}: {
  config: Config;
  onPatch: (patch: Partial<Config>) => void;
}) {
  const ww = config.werewolf || defaultWerewolf,
    free = config.freeplay || defaultFreeplay;
  const automatic = Object.keys(ww.roles).length === 0;
  function cast(next: Record<string, number>) {
    const sum = Object.values(next).reduce((a, b) => a + b, 0);
    const minimum = Math.max(
      4,
      sum,
      (next.WEREWOLF || 1) * 2 + 1,
      config.minPlayers,
    );
    onPatch({
      werewolf: { ...ww, roles: next },
      minPlayers: minimum,
      maxPlayers: Math.max(config.maxPlayers, minimum),
    });
  }
  function rule(patch: Partial<FreeplayRules>) {
    onPatch({ freeplay: { ...free, ...patch } });
  }
  function objective(value: string) {
    const patch: Partial<Config> = { freeplay: { ...free, objective: value } };
    if (value === "PVP") {
      patch.allowPvP = true;
      patch.gameMode = "SURVIVAL";
      patch.freeplay = {
        ...patch.freeplay!,
        kit: { IRON_SWORD: 1, SHIELD: 1, COOKED_BEEF: 16 },
        instructions:
          "Win points by defeating other participants. You respawn inside the arena; your original belongings stay safe.",
      };
    }
    if (value === "BLOCK_BREAK") {
      patch.allowedBreakBlocks = config.allowedBreakBlocks.length
        ? config.allowedBreakBlocks
        : ["DIRT"];
      patch.gameMode = "SURVIVAL";
      patch.freeplay = {
        ...patch.freeplay!,
        kit: { IRON_SHOVEL: 1 },
        instructions:
          "Break the allowed blocks to earn points. The first player to the target score wins.",
      };
    }
    if (value === "BLOCK_PLACE") {
      patch.allowedPlaceBlocks = config.allowedPlaceBlocks.length
        ? config.allowedPlaceBlocks
        : ["OAK_PLANKS"];
      patch.gameMode = "SURVIVAL";
      patch.freeplay = {
        ...patch.freeplay!,
        kit: { OAK_PLANKS: 64 },
        instructions:
          "Build with the allowed blocks. Each valid placement earns points.",
      };
    }
    if (value === "RACE") {
      patch.allowPvP = false;
      patch.freeplay = {
        ...patch.freeplay!,
        targetScore: 1,
        checkpoints:
          free.checkpoints.length >= 2
            ? free.checkpoints
            : [
                {
                  x: config.bounds.minX + 1,
                  y: config.spawn.y,
                  z: (config.bounds.minZ + config.bounds.maxZ) / 2,
                  radius: 1,
                },
                {
                  x: config.bounds.maxX - 1,
                  y: config.spawn.y,
                  z: (config.bounds.minZ + config.bounds.maxZ) / 2,
                  radius: 1,
                },
              ],
        instructions:
          "Reach each checkpoint in order. Complete the required laps to win.",
      };
    }
    onPatch(patch);
  }
  return (
    <div className="arena-game-rules">
      {config.preset === "werewolf" ? (
        <>
          <div className="management-heading">
            <div>
              <span className="eyebrow">MAKE YOUR OWN CAST</span>
              <h3>Secret roles. Different stories.</h3>
            </div>
            <label className="arena-inline-toggle">
              <input
                type="checkbox"
                checked={automatic}
                onChange={(e) =>
                  e.target.checked
                    ? onPatch({ werewolf: { ...ww, roles: {} } })
                    : cast({
                        WEREWOLF: 1,
                        SEER: 1,
                        CUPID: 1,
                        HUNTER: 1,
                        WITCH: 1,
                        GUARD: 1,
                      })
                }
              />
              Automatic cast
            </label>
          </div>
          <p className="console-note">
            Automatic casting scales wolves with player count and adds Seer,
            Cupid, Hunter, Witch and Guard as space allows. Custom casting fills
            any remaining places with villagers.
          </p>
          <div className="arena-role-grid">
            {roles.map(([id, name, description, icon]) => (
              <label key={id} className="arena-role-card">
                <span aria-hidden="true">{icon}</span>
                <strong>{name}</strong>
                <small>{description}</small>
                {automatic ? (
                  <b>Automatic</b>
                ) : (
                  <input
                    type="number"
                    aria-label={`${name} count`}
                    value={ww.roles[id] || 0}
                    min={id === "WEREWOLF" ? 1 : 0}
                    max={id === "WEREWOLF" || id === "VILLAGER" ? 20 : 1}
                    onChange={(e) =>
                      cast({ ...ww.roles, [id]: Number(e.target.value) })
                    }
                  />
                )}
              </label>
            ))}
          </div>
          {!automatic && (
            <p className="console-note">
              {Object.values(ww.roles).reduce((a, b) => a + b, 0)} assigned
              roles · minimum {config.minPlayers} players. Wolves must start in
              the minority.
            </p>
          )}
          <div className="arena-form-grid">
            {(
              [
                ["cupidSeconds", "Night 1 · Cupid picks the lovers (seconds)"],
                ["witchSeconds", "Night · Witch potions time (seconds)"],
                ["hunterSeconds", "Hunter's final shot time (seconds)"],
              ] as const
            ).map(([key, label]) => (
              <label key={key}>
                {label}
                <input
                  type="number"
                  min={5}
                  max={120}
                  required
                  value={ww[key]}
                  onChange={(e) =>
                    onPatch({
                      werewolf: { ...ww, [key]: Number(e.target.value) },
                    })
                  }
                />
              </label>
            ))}
          </div>
        </>
      ) : (
        <>
          <span className="eyebrow">YOUR RULES. YOUR PLAYGROUND.</span>
          <h3>Give the game a purpose.</h3>
          <div className="arena-form-grid">
            <label>
              Objective
              <select
                value={free.objective}
                onChange={(e) => objective(e.target.value)}
              >
                <option value="SANDBOX">Sandbox · Play without scoring</option>
                <option value="PVP">PvP · Defeats earn points</option>
                <option value="BLOCK_BREAK">
                  Block challenge · Break to score
                </option>
                <option value="BLOCK_PLACE">
                  Build challenge · Place to score
                </option>
                <option value="RACE">Checkpoint race · Complete laps</option>
              </select>
            </label>
            <label>
              {free.objective === "RACE" ? "Laps to win" : "Points to win"}
              <input
                type="number"
                min={1}
                max={1000}
                disabled={free.objective === "SANDBOX"}
                value={free.targetScore}
                onChange={(e) => rule({ targetScore: Number(e.target.value) })}
              />
            </label>
            {!["SANDBOX", "RACE"].includes(free.objective) && (
              <label>
                Points per valid action
                <input
                  type="number"
                  min={1}
                  max={100}
                  required
                  value={free.pointsPerEvent}
                  onChange={(e) =>
                    rule({ pointsPerEvent: Number(e.target.value) })
                  }
                />
              </label>
            )}
            <label>
              Personal time
              <select
                value={free.personalTime}
                onChange={(e) => rule({ personalTime: Number(e.target.value) })}
              >
                <option value={-1}>Follow the world</option>
                <option value={0}>Dawn</option>
                <option value={6000}>Noon</option>
                <option value={12000}>Sunset</option>
                <option value={18000}>Midnight</option>
                {![-1, 0, 6000, 12000, 18000].includes(free.personalTime) && (
                  <option value={free.personalTime}>
                    {free.personalTime} ticks
                  </option>
                )}
              </select>
            </label>
            <label>
              Personal weather
              <select
                value={free.weather}
                onChange={(e) => rule({ weather: e.target.value })}
              >
                <option value="WORLD">Follow the world</option>
                <option value="CLEAR">Clear skies</option>
                <option value="DOWNFALL">Rain / snow</option>
              </select>
            </label>
          </div>
          <div className="arena-flags">
            {(
              [
                ["allowFlight", "Allow flight"],
                ["hunger", "Enable hunger"],
              ] as const
            ).map(([key, label]) => (
              <label key={key}>
                <input
                  type="checkbox"
                  checked={free[key]}
                  onChange={(e) => rule({ [key]: e.target.checked })}
                />
                {label}
              </label>
            ))}
          </div>
          <label>
            Instructions for players
            <textarea
              rows={3}
              maxLength={600}
              value={free.instructions}
              onChange={(e) => rule({ instructions: e.target.value })}
            />
          </label>
          <p className="console-note">
            Time and weather affect participants only. Lethal damage respawns
            participants at the arena spawn; PvP points require a valid
            opponent. At the time limit, the unique top scorer wins; a tie is a
            draw.
          </p>
          <h3>Starting kit</h3>
          <p className="console-note">
            This temporary kit replaces the arena inventory. Original items are
            restored when leaving. Use Minecraft material names and valid stack
            sizes.
          </p>
          {Object.entries(free.kit).map(([material, amount], index) => (
            <div className="arena-kit-row" key={index}>
              <label>
                Material
                <input
                  aria-label={`Kit material ${index + 1}`}
                  value={material}
                  onChange={(e) =>
                    rule({
                      kit: Object.fromEntries(
                        Object.entries(free.kit).map((entry, i) =>
                          i === index
                            ? [e.target.value.toUpperCase(), amount]
                            : entry,
                        ),
                      ),
                    })
                  }
                />
              </label>
              <label>
                Amount
                <input
                  type="number"
                  aria-label={`Kit amount ${index + 1}`}
                  min={1}
                  max={64}
                  value={amount}
                  onChange={(e) =>
                    rule({
                      kit: { ...free.kit, [material]: Number(e.target.value) },
                    })
                  }
                />
              </label>
              <button
                type="button"
                onClick={() =>
                  rule({
                    kit: Object.fromEntries(
                      Object.entries(free.kit).filter(
                        ([key]) => key !== material,
                      ),
                    ),
                  })
                }
              >
                Remove item
              </button>
            </div>
          ))}
          <button
            type="button"
            disabled={
              Object.keys(free.kit).length >= 24 || "NEW_ITEM" in free.kit
            }
            onClick={() => rule({ kit: { ...free.kit, NEW_ITEM: 1 } })}
          >
            + Add kit item
          </button>
          <h3>Player effects</h3>
          <div className="arena-effect-grid">
            {(
              [
                "speed",
                "jump_boost",
                "strength",
                "regeneration",
                "night_vision",
                "haste",
              ] as const
            ).map((effect) => (
              <label key={effect}>
                {effect.replaceAll("_", " ")}
                <select
                  value={free.effects[effect] || 0}
                  onChange={(e) => {
                    const effects = { ...free.effects };
                    if (Number(e.target.value))
                      effects[effect] = Number(e.target.value);
                    else delete effects[effect];
                    rule({ effects });
                  }}
                >
                  <option value={0}>Off</option>
                  {[1, 2, 3, 4, 5].map((n) => (
                    <option key={n} value={n}>
                      Level {n}
                    </option>
                  ))}
                </select>
              </label>
            ))}
          </div>
          {free.objective === "RACE" && (
            <>
              <h3>Ordered checkpoints</h3>
              <p className="console-note">
                Players must enter these areas in order. Use at least two
                non-overlapping checkpoints, all inside the play area. Build the
                route and review every coordinate.
              </p>
              {free.checkpoints.map((point, index) => (
                <div className="arena-checkpoint" key={index}>
                  <strong>#{index + 1}</strong>
                  {(["x", "y", "z", "radius"] as const).map((key) => (
                    <label key={key}>
                      {key}
                      <input
                        type="number"
                        step="any"
                        min={key === "radius" ? 1 : undefined}
                        max={key === "radius" ? 8 : undefined}
                        required
                        value={point[key]}
                        onChange={(e) =>
                          rule({
                            checkpoints: free.checkpoints.map((p, i) =>
                              i === index
                                ? { ...p, [key]: Number(e.target.value) }
                                : p,
                            ),
                          })
                        }
                      />
                    </label>
                  ))}
                  <button
                    type="button"
                    onClick={() =>
                      rule({
                        checkpoints: free.checkpoints.filter(
                          (_, i) => i !== index,
                        ),
                      })
                    }
                  >
                    Remove
                  </button>
                </div>
              ))}
              <button
                type="button"
                disabled={free.checkpoints.length >= 20}
                onClick={() =>
                  rule({
                    checkpoints: [
                      ...free.checkpoints,
                      {
                        x: config.spawn.x,
                        y: config.spawn.y,
                        z: config.spawn.z,
                        radius: 1,
                      },
                    ],
                  })
                }
              >
                + Add checkpoint
              </button>
            </>
          )}
        </>
      )}
    </div>
  );
}

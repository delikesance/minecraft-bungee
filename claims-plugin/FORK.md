# SimpleClaimSystem (fork for Minecraft 26.3)

Vendored copy of [Xyness/SimpleClaimSystem](https://github.com/Xyness/SimpleClaimSystem) **1.13.1**
(MIT, see `LICENSE`), commit `a98f624`, with these local changes so it builds against the
Paper/Folia 26.3 API and Java 25:

- renamed API constants (`Particle.DUST`, `Attribute.ARMOR`, `ItemFlag.HIDE_ADDITIONAL_TOOLTIP`,
  potion effect names, `TeleportCause.CONSUMABLE_EFFECT`);
- Java 25 toolchain, shadow plugin 9.6.1, spigot-api excluded from transitive dependencies;
- `api-version: 26.3`.

The jar is built in the `claims-build` stage of the root `Dockerfile` and installed **only on the
Creative server** by `infra/start.sh`. Existing claims data under `plugins/SimpleClaimSystem` is untouched.

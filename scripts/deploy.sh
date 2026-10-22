#!/usr/bin/env bash
# Deploys a published commit on the production host; CI runs it over SSH after each merge to main.
# Check out the commit first (its Compose and infra files are what runs), then: scripts/deploy.sh <commit>
# Each image is pinned to its content tag (scripts/image-tag.sh), so only services whose image or
# mounted infra file changed are recreated. Game servers that are stopped stay stopped and pick up
# the new image at their next start. To roll back, check out an earlier commit and deploy it.
set -euo pipefail
cd "$(dirname "$0")/.."
compose=compose.prod.yaml
commit=$(git rev-parse --verify "${1:-HEAD}^{commit}")
[[ "$(git rev-parse HEAD)" == "$commit" ]] || { echo "Check out $commit before deploying it" >&2; exit 1; }
[[ -f .env ]] || { echo 'Missing .env; follow the production setup in README.md first' >&2; exit 1; }

previous=$(sed -n 's/^IMAGE_TAG=//p' .env | tail -n 1)
set_env() {
 if grep -q "^$1=" .env; then sed -i "s|^$1=.*|$1=$2|" .env; else printf '%s=%s\n' "$1" "$2" >> .env; fi
}
cp -p .env .env.previous
restore_env() { cp -p .env.previous .env; echo "Deployment failed; .env restored to $previous" >&2; }
trap restore_env ERR
set_env IMAGE_TAG "$commit"
for target in panel controller server lobby tools; do
 set_env "${target^^}_IMAGE_TAG" "$(scripts/image-tag.sh "$target" "$commit")"
done

./scripts/up.sh "$compose" --check
docker compose -f "$compose" --profile tools pull --quiet

# Compose notices new images and Compose changes, but not edits inside bind-mounted infra files.
force=()
if [[ "$previous" =~ ^[0-9a-f]{40}$ ]] && git cat-file -e "$previous^{commit}" 2> /dev/null; then
 changed=$(git diff --name-only "$previous" "$commit" -- infra)
 grep -qx 'infra/start.sh' <<< "$changed" && force+=(velocity lobby creative survival)
 grep -qxE 'infra/(velocity\.toml|server-icon\.png)' <<< "$changed" && force+=(velocity)
 grep -qx 'infra/Caddyfile' <<< "$changed" && force+=(caddy)
 grep -qx 'infra/redis-start.sh' <<< "$changed" && force+=(redis)
fi

# From here on a failure leaves the new tags in .env; rerunning the deployment finishes it.
trap - ERR
running=$(docker compose -f "$compose" ps --status running --services)
for service in redis controller panel caddy velocity lobby creative survival; do
 flags=(--no-deps)
 [[ " ${force[*]} " == *" $service "* ]] && flags+=(--force-recreate)
 case $service in
  velocity | lobby | creative | survival)
   if ! grep -qx "$service" <<< "$running"; then
    docker compose -f "$compose" up --no-start "${flags[@]}" "$service"
    continue
   fi
   ;;
 esac
 docker compose -f "$compose" up -d "${flags[@]}" "$service"
done
# Old images are only needed for a rollback, which pulls them again.
docker image prune --all --force --filter 'until=168h' > /dev/null || true
echo "Deployed $commit"

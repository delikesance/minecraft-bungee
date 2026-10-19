#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
compose=${1:-compose.prod.yaml}
[[ "$compose" == compose.prod.yaml || "$compose" == compose.local.yaml ]] || { echo 'Choose compose.prod.yaml or compose.local.yaml' >&2; exit 1; }
[[ -f .env ]] || { echo 'Create .env first' >&2; exit 1; }
# Read resolved paths without sourcing shell code from .env.
config=$(docker compose -f "$compose" --profile tools config --format json)
state=$(python3 -c 'import json,sys; print(next(v["source"] for v in json.load(sys.stdin)["services"]["tools"]["volumes"] if v["target"]=="/state"))' <<< "$config")
secrets=$(python3 -c 'import json,sys; print(next(v["source"] for v in json.load(sys.stdin)["services"]["tools"]["volumes"] if v["target"]=="/secrets"))' <<< "$config")
mkdir -p backups
chmod 700 backups
backup="$(pwd)/backups/network-$(date -u +%Y%m%dT%H%M%SZ).tar.gz"
[[ ! -e "$backup" ]] || { echo 'Backup path already exists' >&2; exit 1; }
docker compose -f "$compose" stop
# Do not back up until every stack container, including the panel, has stopped.
if [[ -n "$(docker compose -f "$compose" ps --status running -q)" ]]; then echo 'A container is still running; backup aborted' >&2; exit 1; fi
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir "$work/config"
cp .env "$compose" "$work/config/"
cp -r infra "$work/config/"
umask 077
python3 - "$backup" "$state" "$secrets" "$work/config" <<'PYTHON'
import os,sys,tarfile
backup,state,secrets,config=sys.argv[1:]
partial=backup+".partial"
with open(partial,"xb") as output:
 os.chmod(partial,0o600)
 with tarfile.open(fileobj=output,mode="w:gz") as tar:
  for source,name in [(state,"state"),(secrets,"secrets"),(config,"config")]:
   tar.add(source,arcname=name)
os.link(partial,backup)
os.unlink(partial)
PYTHON
printf 'Backup saved: %s\nServices remain stopped. Start only the services you want running.\n' "$backup"

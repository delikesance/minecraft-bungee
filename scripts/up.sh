#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
compose=${1:-compose.local.yaml}
[[ "$compose" == compose.prod.yaml || "$compose" == compose.local.yaml ]] || { echo 'Invalid Compose file' >&2; exit 1; }
config=$(docker compose -f "$compose" config --format json)
python3 -c '
import json,sys,pathlib,re,platform
c=json.load(sys.stdin);s=c["services"]
if s["creative"]["environment"]["EULA"].upper()!="TRUE":sys.exit("Set EULA=TRUE after accepting the Minecraft EULA")
if "caddy" in s:
 if platform.machine()!="x86_64":sys.exit("Production images currently target linux/amd64")
 if not re.fullmatch(r".*:[0-9a-f]{40}",s["panel"]["image"]):sys.exit("Set IMAGE_TAG to a published 40-character commit SHA")
 if not s["panel"]["environment"]["PUBLIC_ORIGIN"].startswith("https://"):sys.exit("Production PUBLIC_ORIGIN must use HTTPS")
for name in ("lobby","creative","survival"):
 service=s[name];version=service["environment"]["VERSION"]
 data=pathlib.Path(next(v["source"] for v in service["volumes"] if v["target"]=="/data"))
 marker=data/".network-version"
 if not marker.exists() or marker.read_text().strip()!=version:sys.exit(name+": run offline configure with the selected Minecraft version first")
 if not (data/"config/paper-global.yml").is_file():sys.exit(name+": forwarding configuration missing")
' <<< "$config"
if [[ "$compose" == compose.local.yaml ]]; then
 docker compose -f "$compose" up -d --build
else
 docker compose -f "$compose" pull
 docker compose -f "$compose" up -d
fi

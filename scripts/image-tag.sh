#!/usr/bin/env bash
# Prints an image target's content tag: src-<hash of every tracked file its Docker build reads>.
# A commit that leaves those files untouched maps to the same tag, so CI reuses the published image
# instead of rebuilding it, and production keeps running that service instead of restarting it.
# Keep the lists in step with the Dockerfile stages each target copies from.
set -euo pipefail
cd "$(dirname "$0")/.."
target=${1:?usage: image-tag.sh panel|controller|tools|server|lobby [commit]}
rev=${2:-HEAD}
common=(Dockerfile .dockerignore)
go=(go.mod go.sum cmd internal)
pack=(scripts/build_pack.py resource-pack/pack)
plugins=(claims-plugin network-plugin arena-plugin)
case $target in
 panel) paths=("${common[@]}" "${go[@]}" web "${pack[@]}") ;;
 controller | tools) paths=("${common[@]}" "${go[@]}") ;;
 server) paths=("${common[@]}" "${plugins[@]}" "${pack[@]}") ;;
 lobby) paths=("${common[@]}" "${plugins[@]}" "${pack[@]}" lobby infra/lobby-palace) ;;
 *) echo "Unknown image target: $target" >&2; exit 1 ;;
esac
listing=$(git ls-tree -r "$rev" -- "${paths[@]}")
[[ -n $listing ]] || { echo "No inputs found for $target at $rev" >&2; exit 1; }
echo "src-$(printf '%s\n' "$listing" | sha1sum | cut -c1-40)"

#!/bin/sh
set -eu
case "$1" in
 paper)
  test -f /data/.network-version && test "$(cat /data/.network-version)" = "$VERSION" || {
   echo 'Startup refused: run offline configure with the selected Minecraft version.' >&2; exit 1;
  }
  test -s /data/config/paper-global.yml || { echo 'Forwarding configuration missing' >&2; exit 1; }
  if [ "${SERVER_ID:-}" = lobby ] && [ ! -f /data/world/level.dat ] && [ "$VERSION" != 1.21.9 ]; then
   echo "Bundled lobby palace is built for Minecraft 1.21.9; starting an empty lobby on $VERSION" >&2
  elif [ "${SERVER_ID:-}" = lobby ] && [ ! -f /data/world/level.dat ]; then
   mkdir -p /data/world/datapacks
   mkdir -p /data/world/datapacks/vampyuyu-palace
   cp -R /opt/vampyuyu-lobby-palace/. /data/world/datapacks/vampyuyu-palace/
   touch /data/.vampyuyu-palace
  fi
  # The bundled palace datapack uses 1.21.9 command syntax (e.g. gamerule doDaylightCycle); newer versions
  # refuse to parse it. A lobby built by it keeps its blocks and settings, so retire only our own copy.
  if [ "${SERVER_ID:-}" = lobby ] && [ "$VERSION" != 1.21.9 ] && [ -f /data/.vampyuyu-palace ] && [ -d /data/world/datapacks/vampyuyu-palace ]; then
   rm -rf /data/world/datapacks/vampyuyu-palace
   echo "Retired the 1.21.9 lobby palace datapack on $VERSION; the built world is kept" >&2
  fi
  # Claims exist only on Creative; the jar comes from the image, claim data stays in /data.
  if [ "${SERVER_ID:-}" = creative ]; then
   # Drop any previously installed copy so two jars never claim the same plugin name.
   rm -f /data/plugins/SimpleClaimSystem*.jar
   cp /opt/claims/SimpleClaimSystem.jar /plugins/SimpleClaimSystem.jar
  fi
  exec /image/scripts/start
  ;;
 velocity)
  test -s /server/forwarding.secret || { echo 'Startup refused: run offline configure first.' >&2; exit 1; }
  exec /__cacert_entrypoint.sh /usr/bin/run-bungeecord.sh
  ;;
 *) exit 1 ;;
esac

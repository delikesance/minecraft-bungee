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

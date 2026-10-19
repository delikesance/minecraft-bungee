#!/bin/sh
set -eu
case "$1" in
 paper)
  test -f /data/.network-version && test "$(cat /data/.network-version)" = "$VERSION" || {
   echo 'Startup refused: run offline configure with the selected Minecraft version.' >&2; exit 1;
  }
  test -s /data/config/paper-global.yml || { echo 'Forwarding configuration missing' >&2; exit 1; }
  if [ "${SERVER_ID:-}" = lobby ] && [ ! -f /data/world/level.dat ]; then
   test "$VERSION" = 1.21.9 || { echo "Bundled lobby datapack requires Minecraft 1.21.9" >&2; exit 1; }
   mkdir -p /data/world/datapacks
   mkdir -p /data/world/datapacks/vampyuyu-palace
   cp -R /opt/vampyuyu-lobby-palace/. /data/world/datapacks/vampyuyu-palace/
   touch /data/.vampyuyu-palace
  fi
  exec /image/scripts/start
  ;;
 velocity)
  test -s /server/forwarding.secret || { echo 'Startup refused: run offline configure first.' >&2; exit 1; }
  exec /__cacert_entrypoint.sh /usr/bin/run-bungeecord.sh
  ;;
 *) exit 1 ;;
esac

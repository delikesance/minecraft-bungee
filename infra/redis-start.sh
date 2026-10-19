#!/bin/sh
set -eu
umask 077
arena_password=$(cat /run/secrets/arena_redis_password)
case "$arena_password" in ''|*[!a-f0-9]*) echo 'Invalid Redis secret: generate it with network secrets.' >&2; exit 1;; esac
test "${#arena_password}" -ge 64
cat > /tmp/nexus-redis.conf <<'CONFIG'
bind 0.0.0.0
protected-mode yes
port 6379
dir /data
appendonly yes
appendfsync always
maxmemory 64mb
maxmemory-policy noeviction
save 900 1
CONFIG
printf 'requirepass %s\n' "$arena_password" >> /tmp/nexus-redis.conf
unset arena_password
exec redis-server /tmp/nexus-redis.conf

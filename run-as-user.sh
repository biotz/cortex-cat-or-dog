#!/usr/bin/env bash

set -eu -o pipefail

NEW_UID=$(stat -c '%u' /home/magnet)
NEW_GID=$(stat -c '%g' /home/magnet)

groupmod -g "$NEW_GID" -o magnet > /dev/null 2>&1
usermod -u "$NEW_UID" -o magnet > /dev/null 2>&1

exec chpst -u magnet:magnet -U magnet:magnet env HOME="/home/magnet" "$@"

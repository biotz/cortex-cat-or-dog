#!/usr/bin/env bash

set -eu

DIFF="format.diff"

docker run \
    --rm \
    --volume "$(pwd)":/code \
    --workdir /code \
    peterdavehello/shfmt:2.5.0 \
    shfmt -d -i 4 -sr /code |
    tee "${DIFF}"

if [[ "$(wc -c ${DIFF} | cut -c1)" -gt 0 ]]; then
    exit 1
fi

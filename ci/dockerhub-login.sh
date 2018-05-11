#!/usr/bin/env bash

set -o errexit
set -o nounset
set -o pipefail

# enable interruption signal handling
trap - INT TERM

# shellcheck disable=SC2046
docker login \
    --username "${DOCKER_USERNAME}" \
    --password "${DOCKER_PASS}"

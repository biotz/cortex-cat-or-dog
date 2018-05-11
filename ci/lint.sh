#!/usr/bin/env bash

set -eu

docker run \
    --rm \
    --volume "$(pwd):/mnt" \
    koalaman/shellcheck-alpine \
    sh -c "find /mnt -name '*.sh' | xargs shellcheck"

docker run \
    --rm \
    --volume "$(pwd):/mnt" \
    --workdir /mnt \
    --interactive \
    hadolint/hadolint \
    hadolint Dockerfile

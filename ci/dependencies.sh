#!/usr/bin/env sh

set -eu

if [ "${CI:-}" = "true" ]; then
    # Add docker-compose
    apk add --no-cache py-pip bash
    pip install --no-cache-dir docker-compose
fi

docker-compose -v

# Image for checking bash code format
docker pull peterdavehello/shfmt:2.5.0

# Image for linting bash code
docker pull koalaman/shellcheck-alpine

# Image for linting Dockerfile
docker pull hadolint/hadolint

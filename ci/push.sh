#!/usr/bin/env bash

set -eu

GIT_COMMIT=$(git log --format='format:%H' -n 1)
TAG="${GIT_COMMIT:=local}"
TAG=$(echo "${TAG}" | cut -c-7)
IMAGE_NAME="magnetcoop/aluminium"

# Login to Dockerhub
# shellcheck disable=SC2091
./ci/dockerhub-login.sh

docker build \
    --tag "${IMAGE_NAME}:${TAG}" \
    --tag "${IMAGE_NAME}:latest" .

docker push "${IMAGE_NAME}:${TAG}"
docker push "${IMAGE_NAME}:latest"

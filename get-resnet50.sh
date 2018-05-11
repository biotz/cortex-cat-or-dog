#!/bin/bash

set -eu -o pipefail

mkdir -p models
wget -q https://s3-us-west-2.amazonaws.com/thinktopic.cortex/models/resnet50.nippy -O models/resnet50.nippy

#!/bin/bash

set -eu -o pipefail

wget -q https://s3-eu-west-1.amazonaws.com/cat-or-dog-cortex-nippy/trained-network.nippy -O trained-network.nippy

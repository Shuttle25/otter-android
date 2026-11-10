#!/usr/bin/env bash
# Copy shared protobuf schemas and cross-language test vectors from ../otter-server.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
server="${OTTER_SERVER_DIR:-$here/../otter-server}"
mkdir -p "$here/core/proto/src/main/proto/otter/v1" "$here/core/crypto/src/test/resources/vectors"
cp "$server"/proto/otter/v1/*.proto "$here/core/proto/src/main/proto/otter/v1/"
cp "$server"/testdata/vectors/*.json "$here/core/crypto/src/test/resources/vectors/"
echo "synced from $server ($(git -C "$server" rev-parse --short HEAD 2>/dev/null || echo no-git))"

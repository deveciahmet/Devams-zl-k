#!/bin/bash
# Simülatör için Debug derlemesi (ekran görüntüleri için).
set -euo pipefail
UDID=$(python3 ci/pick-simulator.py)
echo "UDID=$UDID" >> "${GITHUB_ENV:-/dev/null}"
echo "$UDID" > build-udid.txt
xcodebuild -project Devamsizlik.xcodeproj -scheme Devamsizlik -configuration Debug \
  -destination "id=$UDID" -derivedDataPath build/sim build -quiet

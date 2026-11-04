#!/bin/bash
# Uygulamayı simülatörde örnek veriyle (-demo) açıp her ekranın görüntüsünü alır.
set -euo pipefail
UDID=${UDID:-$(cat build-udid.txt)}
APP=build/sim/Build/Products/Debug-iphonesimulator/Devamsizlik.app
BUNDLE=com.deveciahmet.devamsizlik
xcrun simctl boot "$UDID" || true
xcrun simctl bootstatus "$UDID" -b
xcrun simctl status_bar "$UDID" override --time "12:00" --batteryState charged --batteryLevel 100 || true
xcrun simctl install "$UDID" "$APP"
mkdir -p shots
shoot() {
  xcrun simctl launch "$UDID" "$BUNDLE" -demo -screen "$1"
  sleep 6
  xcrun simctl io "$UDID" screenshot "shots/$2.png"
  xcrun simctl terminate "$UDID" "$BUNDLE" || true
  sleep 1
}
shoot home 1-home
shoot fill 2-fill
shoot course 3-course
shoot courseEdit 4-course-edit
shoot settings 5-settings
shoot import 6-import
xcrun simctl ui "$UDID" appearance dark
shoot home 7-home-dark
ls -la shots

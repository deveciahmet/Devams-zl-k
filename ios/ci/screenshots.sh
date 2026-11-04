#!/bin/bash
# Uygulamayı simülatörde örnek veriyle (-demo) açıp her ekranın görüntüsünü alır.
# Her ekran için uygulamanın kaydını (.log) ve ana iş parçacığının örneğini (-sample.txt) de saklar;
# bir ekran boş kalırsa uygulamanın nerede takıldığı buradan görülür.
set -uo pipefail
UDID=${UDID:-$(cat build-udid.txt)}
APP=build/sim/Build/Products/Debug-iphonesimulator/Devamsizlik.app
BUNDLE=com.deveciahmet.devamsizlik
xcrun simctl boot "$UDID" || true
xcrun simctl bootstatus "$UDID" -b
xcrun simctl status_bar "$UDID" override --time "12:00" --batteryState charged --batteryLevel 100 || true
xcrun simctl install "$UDID" "$APP"
mkdir -p shots

shoot() {
  local screen=$1 name=$2
  SIMCTL_CHILD_OS_ACTIVITY_DT_MODE=enable xcrun simctl launch --terminate-running-process \
    --stdout="$PWD/shots/$name.log" --stderr="$PWD/shots/$name.err.log" \
    "$UDID" "$BUNDLE" -demo -screen "$screen"
  sleep 8
  xcrun simctl io "$UDID" screenshot "shots/$name.png"
  local pid
  pid=$(pgrep -f "Devamsizlik.app/Devamsizlik" | head -1 || true)
  if [ -n "$pid" ]; then
    sample "$pid" 2 -file "shots/$name-sample.txt" >/dev/null 2>&1 || true
  else
    echo "UYGULAMA ÇALIŞMIYOR ($name) — çökmüş olabilir" | tee -a "shots/$name.err.log"
  fi
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

# Çökme raporları varsa onları da sakla
cp ~/Library/Logs/DiagnosticReports/Devamsizlik* shots/ 2>/dev/null || true
ls -la shots

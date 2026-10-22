#!/bin/bash
# KinoLel-App: Rauchtest im Emulator – Update von der veröffentlichten Vorversion (Meldung „Neu in …“ genau einmal),
# Startbild, Anmeldung kinolel.de, Sprache Ungarisch, Bildschirmfotos.
set -x
mkdir -p rauchtest
APK=$(ls apk/*.apk | head -1)
PAKET=$(grep '^APPLICATION_ID=' marke.properties | cut -d= -f2)
ui() { adb exec-out uiautomator dump /dev/tty 2>/dev/null; }

# 1. Vorversion (letztes Release) installieren und einmal starten
VOR=$(curl -s https://api.github.com/repos/Parcival1/kinolel-android/releases | python3 -c '
import json,sys
for r in json.load(sys.stdin):
    for a in r.get("assets", []):
        if a["name"].endswith(".apk"): print(a["browser_download_url"]); sys.exit()')
echo "Vorversion: $VOR"
if [ -n "$VOR" ] && curl -sSL -o vorversion.apk "$VOR"; then
  adb install vorversion.apk
  adb shell pm grant "$PAKET" android.permission.POST_NOTIFICATIONS || true
  adb shell am start -W -n "$PAKET/org.jellyfin.mobile.MainActivity"
  sleep 12
  adb shell am force-stop "$PAKET"
fi

# 2. Update auf diese Version: Meldung „Neu in …“ muss erscheinen – und beim nächsten Start nicht mehr
adb install -r "$APK"
adb shell pm grant "$PAKET" android.permission.POST_NOTIFICATIONS || true
adb logcat -c
adb shell am start -W -n "$PAKET/org.jellyfin.mobile.MainActivity"
sleep 6; adb exec-out screencap -p > rauchtest/00-neuigkeiten.png
if ui | grep -q "Neu in "; then echo "NEUIGKEITEN: nach Update gezeigt" | tee rauchtest/neuigkeiten.txt; else echo "NEUIGKEITEN: FEHLT nach Update" | tee rauchtest/neuigkeiten.txt; fi
adb shell am force-stop "$PAKET"
adb shell am start -W -n "$PAKET/org.jellyfin.mobile.MainActivity"
sleep 6
if ui | grep -q "Neu in "; then echo "NEUIGKEITEN: FEHLER – beim zweiten Start erneut gezeigt" | tee -a rauchtest/neuigkeiten.txt; else echo "NEUIGKEITEN: zweiter Start ohne Meldung (richtig)" | tee -a rauchtest/neuigkeiten.txt; fi

# 3. Bisheriger Rauchtest: Start, Anmeldung, Ungarisch
adb shell am force-stop "$PAKET"
adb shell am start -W -n "$PAKET/org.jellyfin.mobile.MainActivity"
sleep 3;  adb exec-out screencap -p > rauchtest/01-start-3s.png
sleep 12; adb exec-out screencap -p > rauchtest/02-nach-15s.png
sleep 20; adb exec-out screencap -p > rauchtest/03-nach-35s.png
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb pull /sdcard/ui.xml rauchtest/ui-35s.xml
adb logcat -d | grep -i -E "kinolel|jellyfin|chromium|AndroidRuntime" | tail -300 > rauchtest/logcat.txt
adb shell am force-stop "$PAKET"
adb shell cmd locale set-app-locales "$PAKET" --locales hu-HU || true
adb shell am start -W -n "$PAKET/org.jellyfin.mobile.MainActivity"
sleep 25; adb exec-out screencap -p > rauchtest/04-ungarisch-25s.png
ls -la rauchtest
exit 0

#!/bin/bash
# KinoLel-App: Rauchtest im Emulator – installieren, starten, Bildschirmfotos (Startbild, Anmeldung kinolel.de),
# Sprache Ungarisch, Prüfung der Einstellung „Hintergrund-Ton“ (Standard an).
set -x
mkdir -p rauchtest
APK=$(ls apk/*.apk | head -1)
adb install -r "$APK"
PAKET=$(grep '^APPLICATION_ID=' marke.properties | cut -d= -f2)
adb shell pm grant "$PAKET" android.permission.POST_NOTIFICATIONS || true
adb logcat -c
adb shell am start -W -n "$PAKET/org.jellyfin.mobile.MainActivity"
sleep 3;  adb exec-out screencap -p > rauchtest/01-start-3s.png
sleep 12; adb exec-out screencap -p > rauchtest/02-nach-15s.png
sleep 20; adb exec-out screencap -p > rauchtest/03-nach-35s.png
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb pull /sdcard/ui.xml rauchtest/ui-35s.xml
adb logcat -d | grep -i -E "kinolel|jellyfin|chromium|AndroidRuntime" | tail -300 > rauchtest/logcat.txt
# Ungarisch (Systemsprache) – Startbild-Texte
adb shell am force-stop "$PAKET"
adb shell cmd locale set-app-locales "$PAKET" --locales hu-HU || true
adb shell am start -W -n "$PAKET/org.jellyfin.mobile.MainActivity"
sleep 25; adb exec-out screencap -p > rauchtest/04-ungarisch-25s.png
ls -la rauchtest
exit 0

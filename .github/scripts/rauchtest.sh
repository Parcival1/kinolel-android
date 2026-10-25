#!/bin/bash
# KinoLel-App: Rauchtest im Emulator – Update von der veröffentlichten Vorversion (Meldung „Neu in …“ genau einmal),
# Startbild, Anmeldung kinolel.de, Sprache Ungarisch, Bildschirmfotos.
set -x
mkdir -p rauchtest
APK=$(ls apk/*.apk | head -1)
PAKET=$(grep '^APPLICATION_ID=' marke.properties | cut -d= -f2)
ui() { adb exec-out uiautomator dump /dev/tty 2>/dev/null; }
# Emulator-Systemmeldung „… isn't responding“ (z. B. Pixel Launcher nach dem Update) verdeckt sonst die Meldung der App
systemdialog_weg() {
  for _ in 1 2 3; do
    xml=$(ui)
    echo "$xml" | grep -qE "isn't responding|reagiert nicht" || return 0
    punkt=$(echo "$xml" | python3 -c 'import re,sys; m=re.search(r"text=\"(Wait|Warten)\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", sys.stdin.read()); print("%d %d" % ((int(m[2])+int(m[4]))//2, (int(m[3])+int(m[5]))//2) if m else "")')
    [ -n "$punkt" ] && adb shell input tap $punkt
    sleep 2
  done
}

# Fehlerdialoge anderer Apps (ANR des Launchers im Emulator) ausblenden – sie verdecken sonst die Meldungen der App
adb shell settings put global hide_error_dialogs 1 || true

# 1. Vorversion (letztes Release) installieren und einmal starten
# Vorversion = neuestes Release, das NICHT die gerade gebaute Version ist (beim Tag-Lauf ist sie schon veröffentlicht).
# Über gh mit Build-Token (die GitHub-API ohne Anmeldung liefert im Runner oft nichts).
AKTUELL=$(sed -n "s/^VERSION=//p" marke.properties)
VOR=$(gh api "repos/Parcival1/kinolel-android/releases?per_page=20" --jq "[.[] | select(.draft | not) | select(.tag_name != \"v$AKTUELL\") | .assets[] | select(.name | endswith(\".apk\")) | .browser_download_url][0] // empty" 2>rauchtest/gh-fehler.txt || true)
echo "Vorversion: $VOR"
if [ -n "$VOR" ] && curl -sSL -o vorversion.apk "$VOR"; then
  adb install vorversion.apk
  adb shell pm grant "$PAKET" android.permission.POST_NOTIFICATIONS || true
  adb shell am start -W -n "$PAKET/org.jellyfin.mobile.MainActivity"
  sleep 12
  # Lel 25.10.: KinoLel auf Ungarisch (wie „Magyar“ auf der Anmeldeseite), das Gerät auf Deutsch -> „Was ist neu“ ungarisch
  python3 .github/scripts/sprache_setzen.py hu | tee rauchtest/sprache.txt || true
  adb shell am force-stop "$PAKET"
fi
adb shell cmd locale set-app-locales "$PAKET" --locales de-DE || true

# 2. Update auf diese Version: Meldung „Neu in …“ muss erscheinen – und beim nächsten Start nicht mehr
adb install -r "$APK"
adb shell pm grant "$PAKET" android.permission.POST_NOTIFICATIONS || true
adb logcat -c
adb shell am start -W -n "$PAKET/org.jellyfin.mobile.MainActivity"
# „Was ist neu“ wartet bis zu 15 s auf die Sprache von kinolel.de
# Die Systemmeldung kann jederzeit wiederkommen -> den Stand auswerten, in dem die Meldung zu sehen war
: > rauchtest/neuigkeiten-ui.xml
for i in $(seq 1 10); do
  sleep 3; systemdialog_weg; xml=$(ui)
  if echo "$xml" | grep -qE "Neu in |New in |Újdonságok"; then echo "$xml" > rauchtest/neuigkeiten-ui.xml; adb exec-out screencap -p > rauchtest/00-neuigkeiten.png; break; fi
done
[ -s rauchtest/neuigkeiten-ui.xml ] || adb exec-out screencap -p > rauchtest/00-neuigkeiten.png
if grep -q "Újdonságok" rauchtest/neuigkeiten-ui.xml; then echo "SPRACHE: Was ist neu ungarisch (Gerät Deutsch, KinoLel Ungarisch)" | tee -a rauchtest/sprache.txt; else echo "SPRACHE: Was ist neu NICHT ungarisch" | tee -a rauchtest/sprache.txt; fi
if grep -qE "Neu in |New in |Újdonságok" rauchtest/neuigkeiten-ui.xml; then echo "NEUIGKEITEN: nach Update gezeigt" | tee rauchtest/neuigkeiten.txt; else if [ -z "${VOR:-}" ]; then echo "NEUIGKEITEN: nicht geprüft – Vorversion nicht gefunden (GitHub-API)" | tee rauchtest/neuigkeiten.txt; else echo "NEUIGKEITEN: FEHLT nach Update" | tee rauchtest/neuigkeiten.txt; fi; fi
adb shell am force-stop "$PAKET"
adb shell am start -W -n "$PAKET/org.jellyfin.mobile.MainActivity"
sleep 6
if ui | grep -qE "Neu in |New in |Újdonságok"; then echo "NEUIGKEITEN: FEHLER – beim zweiten Start erneut gezeigt" | tee -a rauchtest/neuigkeiten.txt; else echo "NEUIGKEITEN: zweiter Start ohne Meldung (richtig)" | tee -a rauchtest/neuigkeiten.txt; fi

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

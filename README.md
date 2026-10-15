# KinoLel – Android-App

Android-App für [KinoLel](https://kinolel.de). Sie öffnet direkt die Anmeldung von `https://kinolel.de` (ohne
Serverauswahl) und zeigt die gewohnte KinoLel-Oberfläche inkl. Sprachwahl Deutsch/Magyar. Filme laufen im nativen
Player (ExoPlayer/Media3) – **auch bei gesperrtem Bildschirm oder wenn die App im Hintergrund ist geht der Ton weiter**,
gesteuert über die Benachrichtigung bzw. den Sperrbildschirm.

Grundlage ist **[Jellyfin for Android](https://github.com/jellyfin/jellyfin-android)**; dieses Projekt ist eine
Abspaltung davon und steht wie das Original unter der **GNU General Public License v2.0** ([LICENSE.md](LICENSE.md)).
Original-README: [README-jellyfin.md](README-jellyfin.md).

## Was gegenüber Jellyfin for Android anders ist

| Bereich | KinoLel |
|---|---|
| Name, Server, Kennung, Version | an **einer** Stelle: [`marke.properties`](marke.properties) (`de.kinolel.app`) |
| Start | KinoLel-Logo mit Lavendel-Ladekreis, verbindet fest mit `https://kinolel.de`; bei Ausfall Hinweis + „Erneut versuchen“ (`ui/screens/connect/FesterServer.kt`) |
| Serverauswahl | entfernt (`multiserver` aus, „Anderer Server“ ausgeblendet) |
| **Hintergrund** | Einstellung „Hintergrundwiedergabe“ **standardmäßig an**: Video läuft bei gesperrtem Bildschirm als Ton weiter (Vordergrunddienst `mediaPlayback`, MediaSession, Steuerung auf dem Sperrbildschirm, WLAN-/CPU-Wachhaltung) |
| Symbole/Farben | KinoLel-Logo (adaptiv + Themensymbol), Grund `#0b0f18`, Akzent `#9aa4ff` (`marke/android_bilder.py`) |
| Build | GitHub Actions ([`.github/workflows/android.yml`](.github/workflows/android.yml)): signierte APK, Rauchtest im Emulator, Release bei Tag `v*` |

Alle Änderungen im Code sind mit `KinoLel` kommentiert (`git grep -n KinoLel`).

## Bauen

Automatisch bei jedem Push auf `main` (Ergebnis: Actions → android → Artifacts `kinolel-android-apk`, `rauchtest-bilder`).
Testversion: `VERSION` in `marke.properties` erhöhen, committen, Tag `v<Version>` pushen → Releases.

Signierschlüssel: liegt nur auf dem KinoLel-Server (nur root lesbar) und verschlüsselt als GitHub-Secrets
(`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`). Ohne denselben Schlüssel lassen
sich Updates nicht über eine installierte Version installieren.

## Neue Jellyfin-Android-Versionen übernehmen

```bash
git remote add upstream https://github.com/jellyfin/jellyfin-android.git   # einmalig
git fetch upstream && git merge upstream/master
```

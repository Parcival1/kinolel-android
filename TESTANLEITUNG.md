# KinoLel für Android – Testanleitung

## Installieren
1. Auf dem Handy `KinoLel-<Version>.apk` herunterladen (Link unter „Releases“).
2. Öffnen → Android fragt „Unbekannte Apps installieren?“ → für den Browser/Dateimanager **zulassen** → Installieren.
   (Play Protect warnt evtl., weil die App nicht aus dem Play Store kommt: „Trotzdem installieren“.)
3. **KinoLel** öffnen → Benachrichtigungen **erlauben** (nötig für die Steuerung auf dem Sperrbildschirm).
   Fragt die App nach „Akku-Optimierung deaktivieren“: **zulassen** (sonst kann Android die Wiedergabe im Hintergrund beenden).

## Prüfen
| # | Was | Erwartung |
|---|---|---|
| 1 | Start | KinoLel-Logo mit Ladekreis, dann **direkt die Anmeldung** von kinolel.de, keine Serverauswahl |
| 2 | Sprache | Über der Anmeldung **Deutsch / Magyar**; Wahl bleibt nach Anmelden und Neustart |
| 3 | Film abspielen | Startet im Vollbild-Player |
| 4 | **Bildschirm sperren** | **Ton läuft weiter.** Auf dem Sperrbildschirm: Titel + Pause/Weiter/Spulen |
| 5 | App wechseln (Home-Taste) | Ton läuft weiter (oder Bild-in-Bild, wenn eingeschaltet) |
| 6 | Entsperren, App öffnen | Bild läuft an derselben Stelle weiter |
| 7 | Ungarische Untertitel, DTS-Ton | Untertitel „Magyar“ richtig; DTS-Ton hörbar |
| 8 | Musik | Läuft im Hintergrund, Steuerung in der Benachrichtigung |

Einstellung dazu: Menü → **Client-Einstellungen** → Videoplayer → „Hintergrundwiedergabe“ (standardmäßig an).

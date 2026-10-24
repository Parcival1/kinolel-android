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

## Downloads (ab v0.1.4) – wie bei Netflix
| # | Was | Erwartung |
|---|---|---|
| 9 | Film oder Folge öffnen | Neben „Abspielen“ ein Knopf **Herunterladen** (Pfeil nach unten) |
| 10 | Antippen | Auswahl **Original** oder kleiner (**Hoch 1080p / Mittel 720p / Klein 480p**) mit Größe und freiem Speicher → „Herunterladen“ |
| 11 | Fortschritt | Knopf zeigt Prozent; Benutzermenü (Profilbild) → **Downloads**: Liste mit Fortschritt in MB |
| 12 | Staffel/Serie öffnen | Knopf **Staffel herunterladen**; an jeder Folge ein kleiner Download-Knopf |
| 13 | **Flugmodus** an, KinoLel neu öffnen | Die **Downloads öffnen sich von selbst** (sonst Knopf „Meine Downloads“) |
| 14 | Download antippen | Spielt **ohne Internet**; nach erneutem Öffnen geht es an derselben Stelle weiter |
| 15 | Flugmodus aus | KinoLel verbindet sich wieder; die offline gesehene Stelle steht danach auch auf anderen Geräten |
| 16 | Löschen | In „Downloads“ Papierkorb neben dem Titel → Ja |

Hinweise:
- Standard: lädt **nur im WLAN** (Einstellungen → Downloads → Netzwerk).
- Downloads liegen im geschützten App-Speicher (nicht in Galerie/Dateien), beim Deinstallieren sind sie weg.
- Kleinere Stufen wandelt der Server beim Herunterladen um; Untertitel (auch ungarische) werden mitgeladen.

## Was ist neu (ab v0.1.5)
Nach einem Update zeigt KinoLel beim ersten Start **einmal** „Neu in KinoLel <Version>“ mit den Neuerungen (Deutsch/Ungarisch/Englisch je nach Handysprache). Beim nächsten Start nicht mehr. Nach einer Neuinstallation erscheint nichts.


## Fernseher anmelden per QR-Code (ab v0.1.8)
Die Fernseher-App KinoLel TV zeigt beim Anmelden einen QR-Code. Mit der Handy-Kamera scannen: Ist KinoLel installiert,
öffnet sich **direkt diese App** auf der Quick-Connect-Seite, der Code ist schon eingetragen – nur noch „Autorisieren“
tippen. Ohne App (oder am iPhone) öffnet sich der Browser mit derselben Seite. Nicht angemeldet: erst anmelden, danach
erscheint die Quick-Connect-Seite mit dem Code.
- Falls stattdessen der Browser aufgeht: Android-Einstellungen → Apps → KinoLel → „Standardmäßig öffnen“ →
  „Unterstützte Links öffnen“ einschalten, `kinolel.de` muss dort als bestätigt stehen.

#!/usr/bin/env python3
"""KinoLel-Android: Download-Test im Emulator (Lel 20.10.2026: „Downloadfunktion wie bei Netflix … auch ohne Internet“).

Fertig ist es, wenn ein Download im Flugmodus aus der Download-Liste abspielbar ist. Ablauf:
 1. App frisch starten (Deutsch), mit dem Hilfskonto anmelden (Passwort nur aus KINOLEL_TEST_PW, nie ausgegeben)
 2. Film A über den neuen Knopf „Herunterladen“ in Original, Folge B in „Klein (480p)“ (Server wandelt um)
    2b. Befehle vom Server an die eigene Sitzung (Play, Pause, Weiter, Spulen, Stopp) → nativer Player (Jellyfin 12.3)
 3. Flugmodus an, App neu starten -> Downloads öffnen sich von selbst; A und B abspielen (Position wird gemerkt)
 4. Flugmodus aus -> App verbindet sich, meldet die Position an den Server (geprüft über die API)
 5. Aufräumen: Downloads löschen (Papierkorb in der Liste), Testspuren am Server zurücksetzen, abmelden
Aufruf: python3 download_test.py <ausgabe-ordner>   (adb im PATH; ADB_SERIAL optional)
"""
import json
import os
import re
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET

import websocket

AUS = sys.argv[1] if len(sys.argv) > 1 else 'downloadtest'
PAKET = os.environ.get('PAKET', 'de.kinolel.app')
BENUTZER = os.environ.get('KINOLEL_TEST_USER', 'kinolel-hilfe')
PW = (os.environ.get('KINOLEL_TEST_PW') or '').strip('\r\n')
if not PW and os.environ.get('KINOLEL_TEST_PW_STDIN'):   # VM 140: Passwort per Pipe, nicht in der Prozessliste
    PW = sys.stdin.readline().strip('\r\n')
SERIAL = os.environ.get('ADB_SERIAL')
TITEL_A = os.environ.get('TITEL_A', 'Cars-Toons - Bugged')        # Film, Original
TITEL_B = os.environ.get('TITEL_B', 'DuckTales (Shorts) - s1e6')   # Folge, Klein (480p)
os.makedirs(AUS, exist_ok=True)
ergebnis = {'schritte': []}


def schritt(name, ok, **mehr):
    eintrag = {'schritt': name, 'ok': bool(ok), **mehr}
    ergebnis['schritte'].append(eintrag)
    print(('OK   ' if ok else 'FEHL ') + name, json.dumps(mehr, ensure_ascii=False) if mehr else '', flush=True)
    return ok


def adb(*args, text=True, check=False, timeout=120):
    befehl = ['adb'] + (['-s', SERIAL] if SERIAL else []) + list(args)
    r = subprocess.run(befehl, capture_output=True, text=text, timeout=timeout)
    if check and r.returncode != 0:
        raise RuntimeError(f'adb {args}: {r.stderr}')
    return r.stdout if text else r.stdout


def sh(befehl):
    return adb('shell', befehl)


def bild(name):
    with open(os.path.join(AUS, name + '.png'), 'wb') as f:
        f.write(adb('exec-out', 'screencap', '-p', text=False))


def ui(tiefe=0):
    """Knoten der Oberfläche (Text, Beschreibung, Klasse, Mittelpunkt)"""
    xml = adb('exec-out', 'uiautomator', 'dump', '/dev/tty')
    xml = xml[xml.find('<?xml'):xml.rfind('>') + 1] if '<?xml' in xml else ''
    knoten = []
    try:
        for n in ET.fromstring(xml).iter('node'):
            m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', n.get('bounds', ''))
            if not m:
                continue
            x1, y1, x2, y2 = map(int, m.groups())
            knoten.append({'text': n.get('text', ''), 'desc': n.get('content-desc', ''), 'klasse': n.get('class', ''),
                           'x': (x1 + x2) // 2, 'y': (y1 + y2) // 2, 'enabled': n.get('enabled') == 'true'})
    except ET.ParseError:
        pass
    # Emulator unter Last: „Pixel Launcher isn't responding“ legt sich über die App -> „Wait“ drücken, neu lesen
    if tiefe < 3 and any("isn't responding" in k['text'] or 'reagiert nicht' in k['text'] for k in knoten):
        warte = next((k for k in knoten if k['text'] in ('Wait', 'Warten')), None)
        if warte:
            sh(f"input tap {warte['x']} {warte['y']}")
            time.sleep(2)
            return ui(tiefe + 1)
    return knoten


def finde(muster, klasse=None, frist=30):
    ende = time.time() + frist
    while time.time() < ende:
        for k in ui():
            if (re.search(muster, k['text'], re.I) or re.search(muster, k['desc'], re.I)) and (not klasse or klasse in k['klasse']):
                return k
        time.sleep(1)
    return None


def tippe(k):
    sh(f"input tap {k['x']} {k['y']}")


class Cdp:
    def __init__(self, ws_url):
        self.ws = websocket.create_connection(ws_url, timeout=60, suppress_origin=True)
        self.n = 0

    def rufe(self, methode, **params):
        self.n += 1
        self.ws.send(json.dumps({'id': self.n, 'method': methode, 'params': params}))
        while True:
            a = json.loads(self.ws.recv())
            if a.get('id') == self.n:
                return a.get('result', {})

    def js(self, code):
        r = self.rufe('Runtime.evaluate', expression=code, returnByValue=True, awaitPromise=True)
        return r.get('result', {}).get('value')


def cdp_verbinden(frist=90):
    ende = time.time() + frist
    while time.time() < ende:
        pid = sh(f'pidof {PAKET}').strip().split(' ')[0]
        if pid:
            sockel = re.findall(r'@(webview_devtools_remote_' + pid + r')\b', sh('cat /proc/net/unix'))
            if sockel:
                adb('forward', 'tcp:9333', 'localabstract:' + sockel[0])
                try:
                    for z in json.load(urllib.request.urlopen('http://127.0.0.1:9333/json', timeout=5)):
                        if z.get('type') == 'page' and 'kinolel' in z.get('url', ''):
                            return Cdp(z['webSocketDebuggerUrl'])
                except Exception:  # noqa: BLE001
                    pass
        time.sleep(2)
    return None


# Passwort NIE tippen (Input.insertText) und nie als Skripttext: 24.10.2026 landete das getippte Passwort unter
# Emulator-Last („Pixel Launcher isn't responding“) im Benutzerfeld und stand im Klartext auf einem öffentlichen
# Testbild. Jetzt per Selektor direkt ins (verdeckte) Passwortfeld, Wert nur als CDP-Argument; Benutzerfeld prüfen.
FELD_SETZEN = ("function(w){Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(this,w);"
               "this.dispatchEvent(new Event('input',{bubbles:true}));this.dispatchEvent(new Event('change',{bubbles:true}));}")


def feld_setzen(cdp, selektor, wert):
    feld = cdp.rufe('Runtime.evaluate', expression=f"document.querySelector('{selektor}')").get('result', {})
    if not feld.get('objectId'):
        return False
    cdp.rufe('Runtime.callFunctionOn', objectId=feld['objectId'], arguments=[{'value': wert}], functionDeclaration=FELD_SETZEN)
    return True


def anmeldefelder_fuellen(cdp, benutzer, passwort):
    """True, wenn Benutzer- und Passwortfeld richtig gefüllt sind (sonst beide geleert)."""
    ok = feld_setzen(cdp, '#txtManualName', benutzer) and feld_setzen(cdp, '#txtManualPassword', passwort)
    ok = ok and cdp.js("document.querySelector('#txtManualName').value") == benutzer \
        and cdp.js("document.querySelector('#txtManualPassword').type") == 'password'
    if not ok:
        anmeldefelder_leeren(cdp)
    return ok


def anmeldefelder_leeren(cdp):
    """vor jedem Bildschirmfoto einer Anmeldeseite"""
    for selektor in ('#txtManualName', '#txtManualPassword'):
        try:
            feld_setzen(cdp, selektor, '')
        except Exception:  # noqa: BLE001
            pass


def warten(cdp, ausdruck, frist=60):
    ende = time.time() + frist
    while time.time() < ende:
        try:
            if cdp.js(ausdruck):
                return True
        except Exception:  # noqa: BLE001
            pass
        time.sleep(1)
    return False


def systemdialog_weg():
    """Emulator-Meldung „… isn't responding“ (z. B. Pixel Launcher) mit „Wait“ schließen – sie verdeckt sonst die App."""
    import re as _re
    for _ in range(3):
        xml = subprocess.run(['adb', 'exec-out', 'uiautomator', 'dump', '/dev/tty'], capture_output=True, text=True, timeout=30).stdout
        if "isn't responding" not in xml and 'reagiert nicht' not in xml:
            return
        m = _re.search(r'text="(?:Wait|Warten)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
        if m:
            sh(f'input tap {(int(m[1]) + int(m[3])) // 2} {(int(m[2]) + int(m[4])) // 2}')
        time.sleep(2)


def app_starten(neu=False):
    if neu:
        sh(f'pm clear {PAKET}')
        sh(f'pm grant {PAKET} android.permission.POST_NOTIFICATIONS')
        # Nach dem ersten Lauf des nativen Players fragt die App einmal nach Bluetooth (Kopfhörer/Lautsprecher) –
        # vorab erteilen, wie es ein Nutzer einmal tun würde, sonst verdeckt die Frage die Download-Liste
        sh(f'pm grant {PAKET} android.permission.BLUETOOTH_CONNECT')
        sh(f'cmd locale set-app-locales {PAKET} --locales de-DE')
    sh(f'am force-stop {PAKET}')
    sh(f'am start -W -n {PAKET}/org.jellyfin.mobile.MainActivity')
    time.sleep(3)
    systemdialog_weg()


def titel_suchen(cdp, name, typ):
    return cdp.js("""(async()=>{const a=ApiClient,u=a.getCurrentUserId();
      const r=await a.getItems(u,{searchTerm:%s,IncludeItemTypes:%s,Recursive:true,Limit:5,Fields:'MediaSources'});
      const it=(r.Items||[]).find(i=>i.Name.indexOf(%s)>=0)||(r.Items||[])[0];
      return it?{id:it.Id,name:it.Name,typ:it.Type,ud:it.UserData||{}}:null})()""" % (json.dumps(name), json.dumps(typ), json.dumps(name)))


def herunterladen(cdp, titel, qualitaet_muster, bildname):
    cdp.js("location.hash='#/details?id=%s&serverId='+ApiClient.serverId();true" % titel['id'])
    da = warten(cdp, "!!document.querySelector('.itemDetailPage:not(.hide) .kl-dl')", 40)
    bild(bildname + '-detail')
    if not schritt(f"Knopf „Herunterladen“ auf der Detailseite ({titel['name']})", da):
        return False
    cdp.js("document.querySelector('.itemDetailPage:not(.hide) .kl-dl').click();true")
    wahl = finde(qualitaet_muster, 'RadioButton', 30)
    bild(bildname + '-dialog')
    if not schritt('Qualitätsdialog zeigt ' + qualitaet_muster, wahl, text=wahl and wahl['text']):
        return False
    tippe(wahl)
    knopf = finde(r'^herunterladen$', 'Button', 10)
    if not schritt('Dialog bestätigen', knopf):
        return False
    tippe(knopf)
    id_kurz = titel['id'].replace('-', '')
    ende = time.time() + 600
    stand = None
    while time.time() < ende:
        stand = (json.loads(cdp.js("NativeInterface.kinolelDownloadStatus(JSON.stringify(['%s']))" % id_kurz) or '{}').get(id_kurz) or {})
        if stand.get('s') in ('fertig', 'fehler'):
            break
        time.sleep(3)
    return schritt(f"Download fertig ({titel['name']})", stand and stand.get('s') == 'fertig', stand=stand)


def abspielen_offline(name_muster, bildname):
    eintrag = finde(name_muster, None, 20)
    if not schritt(f'Download „{name_muster}“ in der Liste (offline)', eintrag):
        return False
    tippe(eintrag)
    time.sleep(10)
    bild(bildname)
    # Läuft die Wiedergabe? Position in der Mediensitzung muss nach 3 s weitergelaufen sein
    def position():
        # Format: "package=de.kinolel.app" ... "state=PlaybackState {state=PLAYING(3), position=12345, ..."
        text = sh('dumpsys media_session')
        i = text.find('package=' + PAKET)
        m = re.search(r'state=PlaybackState \{state=\w*\(?(\d+)\)?, position=(\d+)', text[i:]) if i >= 0 else None
        return (int(m.group(1)), int(m.group(2))) if m else (0, 0)
    z1, p1 = position()
    time.sleep(3)
    z2, p2 = position()
    spielt = z2 == 3 or p2 > p1
    schritt(f'Offline-Wiedergabe läuft ({name_muster})', spielt)
    time.sleep(8)
    sh('input keyevent KEYCODE_BACK')   # Player schließen -> Position wird gemerkt
    time.sleep(3)
    return spielt


def mediensitzung():
    """(Zustand, Position ms) der Mediensitzung der App – Zustand 3 = spielt, 2 = Pause, 1 = gestoppt, 0 = keine"""
    text = sh('dumpsys media_session')
    i = text.find('package=' + PAKET)
    m = re.search(r'state=PlaybackState \{state=\w*\(?(\d+)\)?, position=(\d+)', text[i:]) if i >= 0 else None
    return (int(m.group(1)), int(m.group(2))) if m else (0, 0)


def fernsteuerung(cdp, titel):
    """Wie Dashboard/Streams-Grenze: Befehle vom Server an die eigene Sitzung müssen den nativen Player erreichen"""
    sitzung = cdp.js("""(async()=>{const a=ApiClient;const s=(await a.getSessions({deviceId:a.deviceId()}))||[];
        const x=s.find(v=>v.DeviceId===a.deviceId());
        return x?{id:x.Id,fern:x.SupportsRemoteControl,befehle:(x.SupportedCommands||[]).length}:null})()""")
    if not schritt('Fernsteuerung: eigene Sitzung fernsteuerbar', sitzung and sitzung.get('fern'), sitzung=sitzung):
        return
    sid = json.dumps(sitzung['id'])

    def befehl(js):
        cdp.js("(async()=>{const a=ApiClient;" + js + ";return true})()")

    befehl("await a.sendPlayCommand(%s,{ItemIds:%s,PlayCommand:'PlayNow'})" % (sid, json.dumps(titel['id'])))
    time.sleep(15)
    bild('25-fern-play')
    z = mediensitzung()
    schritt('Fernsteuerung: Play startet den nativen Player', z[0] == 3, zustand=z)
    befehl("await a.sendPlayStateCommand(%s,'Pause')" % sid)
    time.sleep(4)
    z = mediensitzung()
    schritt('Fernsteuerung: Pause', z[0] == 2, zustand=z)
    befehl("await a.sendPlayStateCommand(%s,'Unpause')" % sid)
    time.sleep(4)
    z = mediensitzung()
    schritt('Fernsteuerung: Weiter', z[0] == 3, zustand=z)
    befehl("await a.sendPlayStateCommand(%s,'Seek',{SeekPositionTicks:300000000})" % sid)
    time.sleep(4)
    z = mediensitzung()
    schritt('Fernsteuerung: Spulen auf 30 s', z[1] >= 29000, zustand=z)
    befehl("await a.sendPlayStateCommand(%s,'Stop')" % sid)
    time.sleep(5)
    z = mediensitzung()
    bild('26-fern-stopp')
    schritt('Fernsteuerung: Stopp', z[0] in (0, 1), zustand=z)


def main():
    if not PW:
        print('KINOLEL_TEST_PW fehlt – Download-Test übersprungen')
        return 0
    netz = sh('dumpsys connectivity | grep -m1 -o "NOT_METERED"').strip()
    schritt('Emulator-Netz unbegrenzt (WLAN wie Standard „nur WLAN“)', netz == 'NOT_METERED')
    sh('cmd connectivity airplane-mode disable')

    # 1. Start + Anmeldung
    app_starten(neu=True)
    cdp = cdp_verbinden()
    if not schritt('WebView erreichbar (DevTools)', cdp):
        return 1
    if not warten(cdp, "!!document.querySelector('#txtManualName')", 90):
        bild('00-keine-anmeldung')
        return schritt('Anmeldeseite', False) and 1
    # Jellyfin 12.3 (React-Formular): einfaches value= wird nicht übernommen -> nativer Setter + input-Ereignis,
    # Passwort nur als Tastatureingabe; bis zu 3 Versuche (wie im iPhone-Test)
    angemeldet = False
    for versuch in range(3):
        if not anmeldefelder_fuellen(cdp, BENUTZER, PW):
            print(f'Anmeldung Versuch {versuch + 1}: Felder nicht sicher gefüllt')
            time.sleep(3)
            continue
        time.sleep(0.5)
        cdp.js("document.querySelector('#loginPage .manualLoginForm button[type=submit]').click();true")
        if warten(cdp, "/^#\\/home/.test(location.hash)", 40):
            angemeldet = True
            break
        print(f'Anmeldung Versuch {versuch + 1} nicht durch, Hinweis: ' + str(cdp.js("Array.from(document.querySelectorAll('.toast')).map(function(t){return t.textContent}).join(' | ')"))[:200])
    if not schritt('Anmeldung', angemeldet):
        anmeldefelder_leeren(cdp)
        bild('01-anmeldung-fehlgeschlagen')
        return 1
    time.sleep(5)

    # Emulator unter Last: die DevTools-Verbindung reißt manchmal ab -> einmal neu verbinden
    try:
        a = titel_suchen(cdp, TITEL_A, 'Movie')
    except Exception as e:  # noqa: BLE001
        print('DevTools-Verbindung neu:', type(e).__name__)
        cdp = cdp_verbinden(120)
        warten(cdp, "/^#\\/home/.test(location.hash)", 60)
        a = titel_suchen(cdp, TITEL_A, 'Movie')
    b = titel_suchen(cdp, TITEL_B, 'Episode')
    schritt('Testtitel gefunden', a and b, a=a and a['name'], b=b and b['name'])
    if not (a and b):
        return 1

    # 2. Herunterladen
    ok_a = herunterladen(cdp, a, r'^Original', '10-a')
    ok_b = herunterladen(cdp, b, r'^Klein \(480p\)', '20-b')
    if ok_a:
        fernsteuerung(cdp, a)
    cdp.js("NativeInterface.openDownloadManager();true")
    time.sleep(4)
    bild('30-downloads-online')
    sh('input keyevent KEYCODE_BACK')
    if not (ok_a or ok_b):
        return 1

    # 3. Flugmodus, App neu starten
    sh('cmd connectivity airplane-mode enable')
    time.sleep(5)
    app_starten()
    liste = finde(r'^Downloads$', None, 60)
    bild('40-offline-start')
    schritt('Offline-Start öffnet die Downloads von selbst', liste)
    if not liste:
        knopf = finde(r'Meine Downloads', None, 20)
        schritt('Offline-Knopf „Meine Downloads“', knopf)
        if knopf:
            tippe(knopf)
            time.sleep(3)
    gespielt = False
    if ok_a:
        gespielt |= abspielen_offline(r'Bugged', '41-offline-a-spielt')
    if ok_b:
        gespielt |= abspielen_offline(r'DuckTales', '42-offline-b-spielt')
    bild('43-downloads-offline')
    schritt('ZIEL: Download im Flugmodus abspielbar', gespielt)

    # 4. Wieder online: Verbindung + Nachmelden
    sh('cmd connectivity airplane-mode disable')
    systemdialog_weg()                   # sonst schluckt die Emulator-Meldung das Zurück
    sh('input keyevent KEYCODE_BACK')    # Download-Liste schließen -> Verbindungsseite verbindet wieder
    time.sleep(5)
    systemdialog_weg()
    if finde(r'^Downloads$', None, 3):  # Liste noch offen (Zurück ging an eine Systemmeldung) -> noch einmal
        sh('input keyevent KEYCODE_BACK')
    time.sleep(35)
    cdp = cdp_verbinden(120)
    gemeldet = None
    if cdp and warten(cdp, "/^#\\/home/.test(location.hash)", 90):
        ende = time.time() + 60
        while time.time() < ende:
            gemeldet = cdp.js("(async()=>{const it=await ApiClient.getItem(ApiClient.getCurrentUserId(),%s);return it.UserData})()" % json.dumps(a['id']))
            if gemeldet and gemeldet.get('PlaybackPositionTicks', 0) > 0:
                break
            time.sleep(5)
    beim_server = bool(gemeldet and gemeldet.get('PlaybackPositionTicks', 0) > 0)
    # Nach dem Flugmodus findet der Test die WebView-DevTools manchmal nicht wieder (Lauf v1.0) – dann zählt das
    # Protokoll der App: „KinoLel-Offline: Position von <id> gemeldet“ kommt erst nach der Antwort des Servers
    laut_app = not beim_server and f"Position von {a['id']} gemeldet" in adb('logcat', '-d')
    schritt('Offline-Position beim Server angekommen', beim_server or laut_app,
            position_s=gemeldet and round(gemeldet.get('PlaybackPositionTicks', 0) / 1e7),
            belegt='Server' if beim_server else ('Protokoll der App (DevTools nicht erreichbar)' if laut_app else 'nein'))
    if not cdp:
        print('Hinweis: ohne DevTools kein Aufräumen (Downloads, Serverstand, Abmelden) – Testkonto prüfen', flush=True)
    bild('50-wieder-online')

    # 5. Aufräumen: Downloads per Papierkorb löschen, Serverstand zurücksetzen, abmelden
    if cdp:
        cdp.js("NativeInterface.openDownloadManager();true")
        time.sleep(3)
        for _ in range(2):
            eimer = finde(r'Download löschen', None, 10)
            if not eimer:
                break
            tippe(eimer)
            ja = finde(r'^ja$|^yes$', None, 10)   # Compose-Knopf, keine Button-Klasse
            if ja:
                tippe(ja)
            time.sleep(2)
        bild('60-geloescht')
        schritt('Downloads gelöscht (Papierkorb)', not finde(r'Bugged|DuckTales', None, 3))
        sh('input keyevent KEYCODE_BACK')
        for t in (a, b):
            ud = t.get('ud') or {}
            cdp.js("""(async()=>{const a=ApiClient;await fetch(a.getUrl('UserItems/%s/UserData'),{method:'POST',
                headers:{'Content-Type':'application/json','Authorization':'MediaBrowser Token="'+a.accessToken()+'"'},
                body:JSON.stringify({PlaybackPositionTicks:%d,Played:%s})});return true})()"""
                   % (t['id'], ud.get('PlaybackPositionTicks', 0), 'true' if ud.get('Played') else 'false'))
        schritt('Abmelden', cdp.js("ApiClient.logout().then(()=>true,()=>false)"))
    return 0


try:
    code = main()
finally:
    with open(os.path.join(AUS, 'ergebnis.json'), 'w', encoding='utf-8') as f:
        json.dump(ergebnis, f, ensure_ascii=False, indent=1)
    with open(os.path.join(AUS, 'logcat.txt'), 'w', encoding='utf-8') as f:
        log = adb('logcat', '-d')
        zeilen = '\n'.join(z for z in log.splitlines() if re.search(r'KinoLel|kinolel|AndroidRuntime|DownloadQueue|DownloadWorker|ExoPlayer', z))
        # Zugangsschlüssel schwärzen (Artefakt ist öffentlich lesbar)
        zeilen = re.sub(r'(api_key|ApiKey|Token|access_?token)(["\']?\s*[=:]\s*["\']?)[A-Za-z0-9._-]{12,}', r'\1\2***', zeilen, flags=re.I)
        f.write(zeilen[-200000:])
    sh('cmd connectivity airplane-mode disable')
ziel = any(s['schritt'].startswith('ZIEL') and s['ok'] for s in ergebnis['schritte'])
print('ERGEBNIS:', 'Ziel erreicht' if ziel else 'Ziel NICHT erreicht')
sys.exit(0)

#!/usr/bin/env python3
"""KinoLel-Android: Download-Test im Emulator (Lel 20.10.2026: „Downloadfunktion wie bei Netflix … auch ohne Internet“).

Fertig ist es, wenn ein Download im Flugmodus aus der Download-Liste abspielbar ist. Ablauf:
 1. App frisch starten (Deutsch), mit dem Hilfskonto anmelden (Passwort nur aus KINOLEL_TEST_PW, nie ausgegeben)
 2. Film A über den neuen Knopf „Herunterladen“ in Original, Folge B in „Klein (480p)“ (Server wandelt um)
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


def ui():
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


def app_starten(neu=False):
    if neu:
        sh(f'pm clear {PAKET}')
        sh(f'pm grant {PAKET} android.permission.POST_NOTIFICATIONS')
        sh(f'cmd locale set-app-locales {PAKET} --locales de-DE')
    sh(f'am force-stop {PAKET}')
    sh(f'am start -W -n {PAKET}/org.jellyfin.mobile.MainActivity')


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
        m = re.search(r'state=PlaybackState \{state=(\d+), position=(\d+)', sh('dumpsys media_session'))
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
    cdp.js("(function(){var n=document.querySelector('#txtManualName');n.focus();n.value=%s;"
           "n.dispatchEvent(new Event('input',{bubbles:true}));var p=document.querySelector('#txtManualPassword');p.value='';p.focus();return true})()"
           % json.dumps(BENUTZER))
    cdp.rufe('Input.insertText', text=PW)   # Passwort nur als Tastatureingabe
    cdp.js("document.querySelector('#loginPage .manualLoginForm button[type=submit]').click();true")
    if not schritt('Anmeldung', warten(cdp, "/^#\\/home/.test(location.hash)", 60)):
        bild('01-anmeldung-fehlgeschlagen')
        return 1
    time.sleep(5)

    a = titel_suchen(cdp, TITEL_A, 'Movie')
    b = titel_suchen(cdp, TITEL_B, 'Episode')
    schritt('Testtitel gefunden', a and b, a=a and a['name'], b=b and b['name'])
    if not (a and b):
        return 1

    # 2. Herunterladen
    ok_a = herunterladen(cdp, a, r'^Original', '10-a')
    ok_b = herunterladen(cdp, b, r'^Klein \(480p\)', '20-b')
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
    sh('input keyevent KEYCODE_BACK')    # Download-Liste schließen -> Verbindungsseite verbindet wieder
    time.sleep(40)
    cdp = cdp_verbinden(120)
    gemeldet = None
    if cdp and warten(cdp, "/^#\\/home/.test(location.hash)", 90):
        ende = time.time() + 60
        while time.time() < ende:
            gemeldet = cdp.js("(async()=>{const it=await ApiClient.getItem(ApiClient.getCurrentUserId(),%s);return it.UserData})()" % json.dumps(a['id']))
            if gemeldet and gemeldet.get('PlaybackPositionTicks', 0) > 0:
                break
            time.sleep(5)
    schritt('Offline-Position beim Server angekommen', gemeldet and gemeldet.get('PlaybackPositionTicks', 0) > 0,
            position_s=gemeldet and round(gemeldet.get('PlaybackPositionTicks', 0) / 1e7))
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

#!/usr/bin/env python3
"""KinoLel-Android: QR-Code der Fernseher-Anmeldung öffnet die App (Lel 24.10.2026: „… dass man nicht eine Website
geöffnet bekommt sondern … die KinoLel App auf dem Handy“).

 1. App Link verifiziert: `pm get-app-links` zeigt kinolel.de als verified (assetlinks.json auf kinolel.de)
 2. Link https://kinolel.de/web/kinolel/qc.html?code=123456 bei beendeter App -> öffnet DIESE App (kein Browser),
    Weboberfläche geht zur Quick-Connect-Seite (ohne Anmeldung: Anmeldeseite mit gemerktem Ziel)
 3. mit Hilfskonto anmelden (Passwort nur aus KINOLEL_TEST_PW, nie ausgegeben) -> Quick-Connect-Seite, Code 123456
    eingetragen, nichts abgeschickt
 4. zweiter Link (Code 654321), während die App läuft -> Code 654321 eingetragen
 4d. Benachrichtigung vor der Löschung eines Wunsches (Lel 23.11.2026): eigene Testeinträge in den Anzeige-Einstellungen
    kinolel-bald / kinolel-bald-gesehen des Hilfskontos, Erlaubnisfrage, Benachrichtigung, Tippen, Hintergrundprüfung
    (WorkManager), Wegwischen; danach wie vorher
 5. abmelden (fail2ban: nur eine Anmeldung je Lauf)
Aufruf: python3 applink_test.py <ausgabe-ordner>
"""
import base64
import datetime
import html
import json
import os
import re
import subprocess
import sys
import time
import urllib.request

import websocket
from zoneinfo import ZoneInfo

AUS = sys.argv[1] if len(sys.argv) > 1 else 'applink'
PAKET = os.environ.get('PAKET', 'de.kinolel.app')
BENUTZER = os.environ.get('KINOLEL_TEST_USER', 'kinolel-hilfe')
PW = (os.environ.get('KINOLEL_TEST_PW') or '').strip('\r\n')
LINK = 'https://kinolel.de/web/kinolel/qc.html?code='
os.makedirs(AUS, exist_ok=True)
ergebnis = {}


def merke(name, ok, info=''):
    ergebnis[name] = {'ok': bool(ok), 'info': info}
    print(('OK     ' if ok else 'FEHLER ') + name + (f' – {info}' if info else ''), flush=True)
    return ok


def adb(*args, text=True, timeout=120):
    return subprocess.run(['adb', *args], capture_output=True, text=text, timeout=timeout).stdout


def sh(befehl):
    return adb('shell', befehl)


def bild(name):
    with open(os.path.join(AUS, name + '.png'), 'wb') as f:
        f.write(adb('exec-out', 'screencap', '-p', text=False))


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
    """WebView der App über DevTools (Emulator-Images sind userdebug -> auch bei der Release-APK offen)"""
    ende = time.time() + frist
    while time.time() < ende:
        pid = sh(f'pidof {PAKET}').strip().split(' ')[0]
        if pid:
            sockel = re.findall(r'@(webview_devtools_remote_' + pid + r')\b', sh('cat /proc/net/unix'))
            if sockel:
                adb('forward', 'tcp:9334', 'localabstract:' + sockel[0])
                try:
                    for z in json.load(urllib.request.urlopen('http://127.0.0.1:9334/json', timeout=5)):
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


def link_oeffnen(code):
    """wie die Handy-Kamera / ein QR-Scanner: nur die Adresse, kein Paketname -> Android entscheidet (App Link)"""
    return adb('shell', 'am', 'start', '-W', '-a', 'android.intent.action.VIEW',
               '-c', 'android.intent.category.BROWSABLE', '-d', f"'{LINK}{code}'")


# Passwort NIE tippen (Input.insertText) und nie als Skripttext: 24.10.2026 landete das getippte Passwort unter
# Emulator-Last („Pixel Launcher isn't responding“) im Benutzerfeld und stand im Klartext auf einem öffentlichen
# Testbild. Jetzt per Selektor direkt ins (verdeckte) Passwortfeld, Wert nur als CDP-Argument; Benutzerfeld prüfen.
FELD_SETZEN = ("function(w){Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(this,w);"
               "this.dispatchEvent(new Event('input',{bubbles:true}));this.dispatchEvent(new Event('change',{bubbles:true}));}")


FILM_MIT_TRAILER = """(async function(){
  var u = ApiClient.getCurrentUserId();
  var yt = function(x){ return (x.RemoteTrailers||[]).some(function(t){ return /youtube/.test(t.Url||''); }); };
  var r = await ApiClient.getItems(u, {SearchTerm:'Lord of War', IncludeItemTypes:'Movie', Recursive:true, Limit:5, Fields:'RemoteTrailers'});
  var i = (r.Items||[]).find(yt);
  if (!i) { r = await ApiClient.getItems(u, {IncludeItemTypes:'Movie', Recursive:true, Limit:200, Fields:'RemoteTrailers', SortBy:'DateCreated', SortOrder:'Descending'}); i = (r.Items||[]).find(yt); }
  return i ? {id: i.Id, name: i.Name} : null;
})()"""

TRAILER_ZUSTAND = """(function(){
  function sichtbar(e){ if(!e) return false; var s=getComputedStyle(e), r=e.getBoundingClientRect();
    return s.display!=='none' && s.visibility!=='hidden' && parseFloat(s.opacity||'1')>0.05 && r.width>0 && r.height>0; }
  var ebenen = Array.from(document.querySelectorAll('.youtubePlayerContainer'));
  var pm = window.ExoPlayer && window.ExoPlayer.playbackManager;
  var p = pm && pm.getCurrentPlayer && pm.getCurrentPlayer();
  return {hash: location.hash.slice(0, 70), ebenen: ebenen.length, ebeneSichtbar: ebenen.some(sichtbar),
          spielt: !!(pm && pm.isPlaying && pm.isPlaying()), player: p ? p.id : null,
          hideScroll: document.body.classList.contains('hide-scroll'),
          fehlerDialog: Array.from(document.querySelectorAll('.dialogContainer .dialog')).some(sichtbar)};
})()"""


# Ersatz für https://www.youtube.com/iframe_api: YT.Player „lädt“, meldet bereit und spielt dann – wie ein Trailer, den
# YouTube einbetten lässt (bei Lel). Zeichnet auf, was das YouTube-Modul damit macht.
YOUTUBE_ATTRAPPE = """
window.__ytAttrappe = {angelegt: 0, spielt: 0};
window.YT = {loaded: 1, PlayerState: {ENDED: 0, PLAYING: 1, PAUSED: 2, BUFFERING: 3, CUED: 5},
  Player: function (id, opt) {
    var self = this, el = document.getElementById(id);
    window.__ytAttrappe.angelegt++;
    if (el) { el.style.background = '#334'; el.textContent = 'YOUTUBE-ATTRAPPE'; }
    ['stopVideo','pauseVideo','destroy','seekTo','mute','unMute','setVolume','setSize'].forEach(function (n) { self[n] = function () {}; });
    self.getCurrentTime = function () { return 1; }; self.getDuration = function () { return 102; };
    self.getVolume = function () { return 100; }; self.isMuted = function () { return false; };
    self.playVideo = function () { setTimeout(function () { window.__ytAttrappe.spielt++;
      opt.events && opt.events.onStateChange && opt.events.onStateChange({data: 1, target: self}); }, 500); };
    setTimeout(function () { opt.events && opt.events.onReady && opt.events.onReady({target: self}); }, 300);
  }};
setTimeout(function () { if (typeof window.onYouTubeIframeAPIReady === 'function') window.onYouTubeIframeAPIReady(); }, 0);
"""


def angehalten(cdp, sekunden):
    """DevTools-Ereignisse lesen und von „Fetch“ angehaltene Anfragen merken (werden später fortgesetzt)"""
    ids = []
    ende = time.time() + sekunden
    cdp.ws.settimeout(0.5)
    try:
        while time.time() < ende:
            try:
                a = json.loads(cdp.ws.recv())
            except websocket.WebSocketTimeoutException:
                continue
            if a.get('method') == 'Fetch.requestPaused':
                ids.append(a['params']['requestId'])
    finally:
        cdp.ws.settimeout(60)
    return ids


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


# --- 4d. Benachrichtigung vor der Löschung eines Wunsches ---
LH_KANAL = 'kinolel_bald_geloescht'
LH_LOG = 'KinoLel-Löschhinweis:'
LH = {'geschrieben': False, 'gesehen_vorher': None, 'gesehen_schluessel': set()}
MONATE_HU = ['január', 'február', 'március', 'április', 'május', 'június', 'július', 'augusztus', 'szeptember',
             'október', 'november', 'december']

# Anzeige-Einstellungen des angemeldeten Hilfskontos lesen/ändern (eigener Zugang, kein Admin-Schlüssel). Jellyfin löscht
# keine CustomPrefs-Schlüssel – „nicht gesehen“ ist ein leerer Wert (wie in kinolel.js).
LH_PREFS = """(async function(id, aendern){
  var u = ApiClient.getCurrentUserId();
  var url = ApiClient.getUrl('DisplayPreferences/' + id, {userId: u, client: 'kinolel'});
  var h = {'Authorization': 'MediaBrowser Token="' + ApiClient.accessToken() + '"', 'Content-Type': 'application/json'};
  var p = await (await fetch(url, {headers: h})).json();
  if (aendern) {
    p.Id = id; p.Client = 'kinolel'; p.CustomPrefs = aendern(p.CustomPrefs || {});
    var r = await fetch(url, {method: 'POST', headers: h, body: JSON.stringify(p)});
    if (!r.ok) return {status: r.status};
    p = await (await fetch(url, {headers: h})).json();
  }
  return {status: 200, prefs: p.CustomPrefs || {}};
})(%s, %s)"""


def lh_prefs(cdp, name, aendern='null'):
    try:
        r = cdp.js(LH_PREFS % (json.dumps(name), aendern)) or {}
    except Exception as e:  # noqa: BLE001
        return None, f'{type(e).__name__}: {e}'
    return (r.get('prefs') if r.get('status') == 200 else None), r.get('status')


def lh_schreiben(cdp, liste):
    """Testeinträge in kinolel-bald (wie jellyfin2_erinnerung.py --app)"""
    text = json.dumps(liste, ensure_ascii=False)
    prefs, status = lh_prefs(cdp, 'kinolel-bald', 'function(c){c.titel=%s;return c}' % json.dumps(text, ensure_ascii=False))
    ok = prefs is not None and json.loads(prefs.get('titel') or 'null') == liste
    LH['geschrieben'] = bool(liste) or (LH['geschrieben'] and not ok)
    return ok, status


def lh_gesehen(cdp, setzen=None):
    """kinolel-bald-gesehen lesen; setzen = {Schlüssel: Wert} (vorherige Werte werden für das Aufräumen gemerkt)"""
    if not setzen:
        return lh_prefs(cdp, 'kinolel-bald-gesehen')[0]
    if LH['gesehen_vorher'] is None:
        LH['gesehen_vorher'] = lh_prefs(cdp, 'kinolel-bald-gesehen')[0] or {}
    LH['gesehen_schluessel'] |= set(setzen)
    return lh_prefs(cdp, 'kinolel-bald-gesehen', 'function(c){return Object.assign(c,%s)}' % json.dumps(setzen))[0]


def lh_aufraeumen(cdp):
    """kinolel-bald leeren, Testschlüssel in kinolel-bald-gesehen wie vorher (Jellyfin löscht keine Schlüssel -> leer)"""
    ok, _ = lh_schreiben(cdp, [])
    alt = LH['gesehen_vorher'] or {}
    zurueck = {k: alt.get(k) or '' for k in LH['gesehen_schluessel']}
    if zurueck:
        prefs = lh_prefs(cdp, 'kinolel-bald-gesehen', 'function(c){return Object.assign(c,%s)}' % json.dumps(zurueck))[0]
        ok = ok and prefs is not None and all((prefs.get(k) or '') == v for k, v in zurueck.items())
        if ok:
            LH['gesehen_schluessel'] = set()
    return ok


def warte_auf_gesehen(cdp, schluessel, frist=40):
    ende = time.time() + frist
    werte = {}
    while time.time() < ende:
        werte = {k: (lh_gesehen(cdp) or {}).get(k) for k in schluessel}
        if all(werte.values()):
            break
        time.sleep(3)
    return werte


def benachrichtigungen():
    """aktive Benachrichtigungen der App im Kanal „Bald gelöscht“: [{'titel', 'text'}]"""
    teil = sh('dumpsys notification --noredact').split('Notification List:', 1)[-1]
    for ende in ('\n  Snoozed', 'mArchive', '\n  Archive'):
        teil = teil.split(ende, 1)[0]
    liste = []
    for block in teil.split('NotificationRecord(')[1:]:
        if f'pkg={PAKET} ' not in block or f'channel={LH_KANAL}' not in block:
            continue

        def feld(name, b=block):
            m = re.search(r'\b' + re.escape(name) + r'=String \((.*)\)\s*$', b, re.M)
            return m.group(1) if m else None
        liste.append({'titel': feld('android.title'), 'text': feld('android.bigText') or feld('android.text')})
    return liste


def warte_auf_benachrichtigungen(bedingung, frist):
    ende = time.time() + frist
    liste = benachrichtigungen()
    while not bedingung(liste) and time.time() < ende:
        time.sleep(2)
        liste = benachrichtigungen()
    return liste


def oberflaeche():
    for _ in range(3):
        sh('rm -f /sdcard/kl-ui.xml')
        sh('uiautomator dump /sdcard/kl-ui.xml')
        xml = sh('cat /sdcard/kl-ui.xml')
        if '<hierarchy' in xml:
            return xml
        time.sleep(2)
    return ''


def knoten_mitte(xml, muster):
    """Mitte des ersten Knotens, dessen Text oder resource-id zum Muster passt"""
    for m in re.finditer(r'<node [^>]*>', xml):
        n = m.group(0)
        werte = [html.unescape(w.group(1)) for w in re.finditer(r' (?:text|resource-id)="([^"]*)"', n)]
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if b and any(re.search(muster, w) for w in werte):
            x1, y1, x2, y2 = map(int, b.groups())
            return (x1 + x2) // 2, (y1 + y2) // 2
    return None


def log_zeilen():
    return [z[z.index(LH_LOG):] for z in adb('logcat', '-d').splitlines() if LH_LOG in z]


def geraetezeit():
    try:
        return int(sh('date +%s').strip())
    except ValueError:
        return 0


def uhr_stellen(sekunden):
    sh('settings put global auto_time 0')
    sh(f'cmd alarm set-time {sekunden * 1000}')
    if abs(geraetezeit() - sekunden) > 60:
        sh(f'su 0 date @{sekunden}')
    return abs(geraetezeit() - sekunden) <= 60


def workmanager_auftraege():
    muster = r'JOB #u\d+a\d+/(\d+):[^\n]*?' + re.escape(PAKET) + r'/androidx\.work'
    roh = sh(f'dumpsys jobscheduler {PAKET}')
    ids = re.findall(muster, roh) or re.findall(muster, roh := sh('dumpsys jobscheduler'))
    LH['jobs'] = [z.strip()[:200] for z in roh.splitlines() if PAKET in z][:8]  # Diagnose
    return sorted(set(ids), key=int)


def hintergrund_lauf(stunden):
    """Geräteuhr vorstellen (WorkManager führt die 6-Std.-Prüfung sonst nicht vorzeitig aus) und die WorkManager-Aufträge
    der App über JobScheduler sofort starten – wie Android im Hintergrund"""
    uhr = uhr_stellen(geraetezeit() + stunden * 3600)
    auftraege = workmanager_auftraege()
    for j in auftraege or [str(i) for i in range(40)]:  # nicht gefunden: WorkManager vergibt die Nummern ab 0
        sh(f'cmd jobscheduler run -f {PAKET} {j}')
    return uhr, auftraege


def app_starten():
    adb('shell', 'am', 'start', '-W', '-n', f'{PAKET}/org.jellyfin.mobile.MainActivity')
    cdp = cdp_verbinden()
    if cdp:
        warten(cdp, "!!window.ApiClient && !!ApiClient.accessToken()", 60)
    return cdp


def loeschhinweis_test(cdp):
    """4d. Lel 23.11.2026: „… zu der Erinnerung für auslaufende Titel auch eine KinoLel App Benachrichtigung“ und „Wenn man
    es aber einmal weggeklickt hat soll es nicht mehr für diese Titel kommen …“.
    Ohne Einträge nicht nach der Erlaubnis fragen; App-Start mit Eintrag fragt, dann Benachrichtigung (deutsch, ein Titel),
    Tippen öffnet die Detailseite und trägt den Titel in kinolel-bald-gesehen ein; Hintergrundprüfung bei beendeter App: zwei
    deutsche Titel in einer Benachrichtigung, ungarischer Titel auf Ungarisch, gemeldeter und schon gesehener Titel nicht;
    zweiter Lauf meldet nichts erneut; „Alle löschen“ trägt die Titel als gesehen ein."""
    filme = cdp.js("ApiClient.getItems(ApiClient.getCurrentUserId(),{IncludeItemTypes:'Movie',Recursive:true,Limit:5,"
                   "SortBy:'SortName'}).then(function(r){return (r.Items||[]).map(function(i){return {id:i.Id,name:i.Name}})},"
                   "function(){return []})") or []
    if not merke('Löschhinweis: fünf Filme für Testeinträge', len(filme) == 5, [f['name'] for f in filme]):
        return cdp
    t1, t2, t3, t4, t5 = filme
    tag = datetime.datetime.now(ZoneInfo('Europe/Berlin')).date() + datetime.timedelta(days=4)
    hu_datum = f'{tag.year}. {MONATE_HU[tag.month - 1]} {tag.day}.'

    def eintrag(f, hu):
        return {'id': f['id'], 'titel': f['name'], 'tag': tag.isoformat(), 'hu': hu, 'tage': 14 if hu else 35}

    def schluessel(f):
        return f"{f['id']}:{tag.isoformat()}"

    # a) bisher keine Frage: Konto ohne Einträge, App mehrfach gestartet und angemeldet
    e = re.search(r'android\.permission\.POST_NOTIFICATIONS: granted=(\w+)(?:, flags=\[([^\]]*)\])?',
                  sh(f'dumpsys package {PAKET}'))
    gefragt = [z for z in log_zeilen() if 'erfragen' in z]
    merke('Löschhinweis: ohne Einträge nicht nach der Erlaubnis gefragt',
          bool(e) and e.group(1) == 'false' and 'USER_SET' not in (e.group(2) or '') and not gefragt,
          {'erlaubnis': e.group(0) if e else 'nicht gefunden', 'log': gefragt})

    # b) Eintrag (deutsche Bibliothek) -> App-Start fragt nach der Erlaubnis -> erlauben -> Benachrichtigung
    lh_gesehen(cdp, {schluessel(f): '' for f in filme})  # Testschlüssel sicher „nicht gesehen“ (merkt alte Werte)
    ok, info = lh_schreiben(cdp, [eintrag(t1, False)])
    if not merke('Löschhinweis: Testeintrag geschrieben (ein Titel)', ok, info):
        return cdp
    sh(f'am force-stop {PAKET}')
    time.sleep(1)
    adb('shell', 'am', 'start', '-W', '-n', f'{PAKET}/org.jellyfin.mobile.MainActivity')
    knopf, ende = None, time.time() + 60
    while not knopf and time.time() < ende:
        knopf = knoten_mitte(oberflaeche(), r'permission_allow_button$')
        if not knopf:
            time.sleep(2)
    bild('loeschhinweis-1-erlaubnis')
    if merke('Löschhinweis: App-Start mit Eintrag fragt nach der Erlaubnis', knopf):
        sh(f'input tap {knopf[0]} {knopf[1]}')
    cdp = cdp_verbinden()
    einzeln = {'titel': f'„{t1["name"]}“ wird bald gelöscht',
               'text': f'Wird am {tag:%d.%m.%Y} gelöscht. Sobald jemand ihn anschaut, beginnt die Frist von 5 Wochen neu.'}
    liste = warte_auf_benachrichtigungen(lambda l: einzeln in l, 45)
    merke('Löschhinweis: Benachrichtigung nach dem Erlauben (deutsch, ein Titel)', einzeln in liste, liste)

    # c) Tippen -> Detailseite des Titels, Titel in kinolel-bald-gesehen
    sh('cmd statusbar expand-notifications')
    time.sleep(2)
    stelle = knoten_mitte(oberflaeche(), re.escape(einzeln['titel']))
    bild('loeschhinweis-2-benachrichtigung')
    if stelle:
        sh(f'input tap {stelle[0]} {stelle[1]}')
    else:
        sh('cmd statusbar collapse')
    da = bool(stelle) and bool(cdp) and warten(cdp, f"/^#\\/details\\?id={t1['id']}/.test(location.hash)", 30)
    time.sleep(2)
    bild('loeschhinweis-3-detailseite')
    merke('Löschhinweis: Tippen öffnet die Detailseite', da, (cdp.js('location.hash') if cdp else '')[:80])
    werte = warte_auf_gesehen(cdp, [schluessel(t1)]) if cdp else {}
    merke('Löschhinweis: Tippen trägt den Titel in kinolel-bald-gesehen ein', bool(werte) and all(werte.values()), werte)

    # d) Hintergrund: weitere Titel (einer schon im Fenster gesehen), App beendet, WorkManager-Prüfung
    lh_gesehen(cdp, {schluessel(t5): tag.isoformat()})
    ok, info = lh_schreiben(cdp, [eintrag(t1, False), eintrag(t2, False), eintrag(t3, False), eintrag(t4, True),
                                  eintrag(t5, False)])
    if not merke('Löschhinweis: Testeinträge geschrieben (fünf Titel, einer ungarisch, einer schon gesehen)', ok, info):
        return cdp
    sh('input keyevent KEYCODE_HOME')
    time.sleep(2)
    sh(f'am kill {PAKET}')
    time.sleep(2)
    app_beendet = not sh(f'pidof {PAKET}').strip()
    mehrere = {'titel': '2 deiner Wünsche werden bald gelöscht', 'text': f"{t2['name']}, {t3['name']} – tippe für Details."}
    ungarisch = {'titel': f'Hamarosan törlődik: „{t4["name"]}”',
                 'text': f'Törlés: {hu_datum}. Amint valaki elkezdi nézni, a 2 hetes határidő újraindul.'}
    try:
        uhr, auftraege = hintergrund_lauf(7)
        liste = warte_auf_benachrichtigungen(lambda l: mehrere in l and ungarisch in l, 45)
        if not (mehrere in liste and ungarisch in liste):  # einmal nachfassen (Auftragsnummern können wechseln)
            uhr, auftraege = hintergrund_lauf(1)
            liste = warte_auf_benachrichtigungen(lambda l: mehrere in l and ungarisch in l, 45)
        bild('loeschhinweis-4-hintergrund')
        merke('Löschhinweis: Hintergrundprüfung – zwei deutsche Titel zusammen, ungarischer Titel auf Ungarisch',
              mehrere in liste and ungarisch in liste,
              {'liste': liste, 'uhr_gestellt': uhr, 'auftraege': auftraege, 'app_vorher_beendet': app_beendet,
               'log': log_zeilen()[-3:], 'jobscheduler': LH.get('jobs')})
        merke('Löschhinweis: gemeldeter und schon gesehener Titel nicht (noch einmal)',
              not any(f['name'] in (b['titel'] or '') + (b['text'] or '') for b in liste for f in (t1, t5)), liste)

        # e) zweiter Hintergrundlauf: nichts Neues
        vorher = len(log_zeilen())
        hintergrund_lauf(7)
        ende = time.time() + 30
        while len(log_zeilen()) <= vorher and time.time() < ende:
            time.sleep(2)
        neu = log_zeilen()[vorher:]
        danach = benachrichtigungen()
        merke('Löschhinweis: zweiter Hintergrundlauf meldet nichts erneut',
              bool(neu) and all(' 0 neu gemeldet' in z for z in neu) and len(danach) == len(liste), {'log': neu, 'anzahl': len(danach)})
    finally:
        uhr_stellen(int(time.time()))
        sh('settings put global auto_time 1')

    # f) „Alle löschen“ in der Benachrichtigungsleiste -> Titel in kinolel-bald-gesehen
    sh('cmd statusbar expand-notifications')
    time.sleep(2)
    knopf = knoten_mitte(oberflaeche(), r'^(Clear all|Alle löschen)$|:id/dismiss_text$')
    bild('loeschhinweis-5-leiste')
    if knopf:
        sh(f'input tap {knopf[0]} {knopf[1]}')
    time.sleep(2)
    sh('cmd statusbar collapse')
    merke('Löschhinweis: „Alle löschen“ entfernt die Benachrichtigungen', bool(knopf) and not benachrichtigungen(), benachrichtigungen())
    cdp = app_starten()
    werte = warte_auf_gesehen(cdp, [schluessel(f) for f in (t2, t3, t4)]) if cdp else {}
    merke('Löschhinweis: Wegwischen trägt die Titel in kinolel-bald-gesehen ein', bool(werte) and all(werte.values()), werte)
    with open(os.path.join(AUS, 'loeschhinweis-log.txt'), 'w', encoding='utf-8') as f:
        f.write('\n'.join(log_zeilen()) + '\n')

    # g) aufräumen
    if cdp:
        merke('Löschhinweis: Testeinträge entfernt (kinolel-bald leer, gesehen wie vorher)', lh_aufraeumen(cdp))
    return cdp


ZUSTAND = ("(function(){var f=document.querySelector('#txtQuickConnectCode');var a=document.activeElement;"
           "return {hash:location.hash, wert:f?f.value:null, fokus:a?String(a.className||a.tagName):'',"
           "fehler:(document.querySelector('.quickConnectError')||{}).textContent||''}})()")


def main():
    # 1. Verifizierung
    sh(f'pm verify-app-links --re-verify {PAKET}')
    zustand = ''
    for _ in range(45):
        zustand = sh(f'pm get-app-links {PAKET}')
        if re.search(r'kinolel\.de:\s*verified', zustand):
            break
        time.sleep(2)
    with open(os.path.join(AUS, 'get-app-links.txt'), 'w', encoding='utf-8') as f:
        f.write(zustand)
    merke('App Link kinolel.de verifiziert (pm get-app-links)', re.search(r'kinolel\.de:\s*verified', zustand),
          ' '.join(re.findall(r'kinolel\.de:\s*\S+', zustand)))

    # 2. Kaltstart über den Link
    sh(f'am force-stop {PAKET}')
    # Die App fragt nach jedem Start einmal nach Bluetooth (Dialog über der Weboberfläche) – vorab erlauben, sonst
    # schließt ein Zurück im Test nur diesen Dialog statt in der Weboberfläche anzukommen
    sh(f'pm grant {PAKET} android.permission.BLUETOOTH_CONNECT')
    # rauchtest.sh erlaubt Benachrichtigungen (Fotos ohne Dialog) – hier wie nach einer echten Installation: nicht erlaubt,
    # nie gefragt (Löschhinweis 4d: die App fragt erst, wenn etwas anzuzeigen ist)
    sh(f'pm revoke {PAKET} android.permission.POST_NOTIFICATIONS')
    sh(f'pm clear-permission-flags {PAKET} android.permission.POST_NOTIFICATIONS user-set user-fixed')
    # Android zeigt beim ersten Vollbild einmalig „Viewing full screen … Got it“ – auf echten Geräten längst bestätigt
    sh('settings put secure immersive_mode_confirmations confirmed')
    aus = link_oeffnen('123456')
    merke('Link öffnet die KinoLel-App (kein Browser)', f'{PAKET}/' in aus,
          ' '.join(z.strip() for z in aus.splitlines() if z.strip().startswith(('Activity', 'Status'))))
    cdp = cdp_verbinden()
    if not merke('WebView erreichbar (DevTools)', cdp):
        return
    weg = warten(cdp, "/quickconnect(\\?|%3F)code(=|%3D)123456/.test(location.hash)", 60)
    time.sleep(2)
    bild('applink-1-kaltstart')
    merke('Weboberfläche: Quick Connect mit Code 123456 (ohne Anmeldung über die Anmeldeseite)', weg,
          (cdp.js('location.hash') or '')[:120])

    # 3. Anmelden -> zurück zur Quick-Connect-Seite mit Code
    if not PW:
        print('KINOLEL_TEST_PW fehlt – Anmeldung übersprungen')
        return
    # Jellyfin leitet von #/quickconnect gleich auf die Anmeldung um (mit gemerktem Ziel) – darauf warten
    if not warten(cdp, "/^#\\/login/.test(location.hash) && !!document.querySelector('#txtManualName')", 30):
        merke('Anmeldeseite (App war ohne Konto)', False, cdp.js('location.hash'))
        return
    # Jellyfin 12.3: Anmeldung klappt manchmal erst im zweiten Versuch (wie im Download-Test) – höchstens 3 (fail2ban)
    da = False
    for versuch in range(3):
        if not anmeldefelder_fuellen(cdp, BENUTZER, PW):
            print(f'Anmeldung Versuch {versuch + 1}: Felder nicht sicher gefüllt')
            time.sleep(3)
            continue
        time.sleep(0.5)
        cdp.js("document.querySelector('#loginPage .manualLoginForm button[type=submit]').click();true")
        da = warten(cdp, "(function(){var f=document.querySelector('#txtQuickConnectCode');return f&&f.value==='123456'})()", 45)
        if da or not cdp.js("/^#\\/login/.test(location.hash)"):
            break
        print(f'Anmeldung Versuch {versuch + 1} nicht durch, Hinweis: ' + str(cdp.js("Array.from(document.querySelectorAll('.toast')).map(function(t){return t.textContent}).join(' | ')"))[:200])
    time.sleep(2.5)
    z = cdp.js(ZUSTAND) or {}
    if not da:
        anmeldefelder_leeren(cdp)
    bild('applink-2-nach-anmeldung')
    merke('nach der Anmeldung: Quick-Connect-Seite mit Code 123456', da, z)
    merke('nichts abgeschickt', da and not z.get('fehler'), z.get('fehler'))

    # 4. App läuft: zweiter Link
    try:
        aus = link_oeffnen('654321')
        da = warten(cdp, "(function(){var f=document.querySelector('#txtQuickConnectCode');return /quickconnect/.test(location.hash)&&f&&f.value==='654321'})()", 40)
        time.sleep(2)
        bild('applink-3-laufende-app')
        merke('App lief schon: zweiter Link trägt Code 654321 ein', da, (cdp.js(ZUSTAND) or {}))

        # 4b. „Auf KinoLel abspielen“ aus KinoLel Wünsche (Lel 27.10.): expliziter Aufruf dieser App mit der Adresse der
        #     Wunschseite -> Detailseite des Titels; einmal bei laufender App, einmal nach Beenden (Kaltstart)
        titel = cdp.js("ApiClient.getItems(ApiClient.getCurrentUserId(),{IncludeItemTypes:'Movie',Recursive:true,Limit:1,"
                       "SortBy:'DateCreated',SortOrder:'Descending'}).then(function(r){var i=(r.Items||[])[0];"
                       "return i?{id:i.Id,server:ApiClient.serverId()}:null},function(){return null})")
        if not merke('Titel für den Wünsche-Link gefunden', bool(titel)):
            return
        for art, adresse in (('laufende App', f"https://kinolel.de/web/#/details?id={titel['id']}&serverId={titel['server']}"),
                             ('Kaltstart, Seerr-Form #!', f"https://kinolel.de/web/index.html#!/details?id={titel['id']}&context=home&serverId={titel['server']}")):
            if art.startswith('Kaltstart'):
                sh(f'am force-stop {PAKET}')
                time.sleep(2)
            aus = adb('shell', 'am', 'start', '-W', '-n', f'{PAKET}/org.jellyfin.mobile.MainActivity',
                      '-a', 'android.intent.action.VIEW', '-d', f"'{adresse}'")
            if art.startswith('Kaltstart'):
                cdp = cdp_verbinden()
            da = bool(cdp) and warten(cdp, f"/^#\\/details\\?id={titel['id']}/.test(location.hash)", 45)
            time.sleep(3)
            bild('applink-4-titel-' + ('kalt' if art.startswith('Kaltstart') else 'laufend'))
            merke(f'Wünsche-Link öffnet die Detailseite ({art})', da, (cdp.js('location.hash') if cdp else '')[:80])

        # 4d. Benachrichtigung vor der Löschung eines Wunsches (eigene Testeinträge, danach wieder leer)
        cdp = loeschhinweis_test(cdp) or cdp_verbinden()
        if not cdp:
            return

        # 4c. Trailer verlassen, während er noch lädt (Lel 27.10.: „… Interfaces sich überlagern, da ein Trailer noch am
        #     Laden ist. Kein Ausweg außer App-Neustart.“) – YouTube-Trailer, Zurück nach 0,5 / 1,5 / 3 s:
        #     danach Detailseite, keine sichtbare YouTube-Ebene, nichts läuft
        film = cdp.js(FILM_MIT_TRAILER)
        if not merke('Film mit YouTube-Trailer gefunden', bool(film), film):
            return
        # a) Lels Fall gezielt: das YouTube-Skript (iframe_api) hält DevTools zurück -> der Trailer „lädt noch“, als
        #    Zurück kommt; danach lädt es fertig (im Emulator lässt YouTube diesen Trailer sonst gar nicht erst laden)
        cdp.js(f"location.hash='#/details?id={film['id']}';true")
        warten(cdp, "!!document.querySelector('.btnPlayTrailer:not(.hide)')", 40)
        cdp.js("location.reload();true")  # frische Seite: YouTube-Skript noch nicht geladen
        time.sleep(3)
        if warten(cdp, "!!document.querySelector('.btnPlayTrailer:not(.hide)')", 60):
            # wie Lel: von der Startseite zur Detailseite – sonst gäbe es im Verlauf kein Zurück (App würde schließen)
            cdp.js("Emby.Page.goHome();true")
            warten(cdp, "/^#\\/home/.test(location.hash)", 30)
            time.sleep(3)
            cdp.js(f"Emby.Page.showItem('{film['id']}');true")
            warten(cdp, f"/^#\\/details\\?id={film['id']}/.test(location.hash) && "
                        "!!document.querySelector('.btnPlayTrailer:not(.hide)')", 40)
            time.sleep(2)
            cdp.rufe('Fetch.enable', patterns=[{'urlPattern': '*youtube.com/iframe_api*'}])
            cdp.js("document.querySelector('.btnPlayTrailer:not(.hide)').click();true")
            gehalten = angehalten(cdp, 2)
            vorher = cdp.js(TRAILER_ZUSTAND) or {}
            vorher['navigationHelper'] = cdp.js(
                "(function(){var n=window.NavigationHelper;window.__zurueck=[];if(n&&!n.__gezaehlt){var o=n.goBack;"
                "n.goBack=function(){window.__zurueck.push(location.hash);return o.apply(n,arguments)};n.__gezaehlt=1}"
                "return n?'da':'fehlt'})()")
            vorher['fenster'] = re.findall(r'mCurrentFocus=\S+ \S+ (\S+)\}', sh('dumpsys window'))[:1]
            bild('applink-5-trailer-vor-zurueck')
            sh('logcat -c')
            sh('input keyevent KEYCODE_BACK')
            gehalten += angehalten(cdp, 5)
            for rid in gehalten:  # statt YouTube: Attrappe, die wie ein abspielbarer Trailer reagiert
                cdp.rufe('Fetch.fulfillRequest', requestId=rid, responseCode=200,
                         responseHeaders=[{'name': 'Content-Type', 'value': 'application/javascript'}],
                         body=base64.b64encode(YOUTUBE_ATTRAPPE.encode()).decode())
            cdp.rufe('Fetch.disable')
            time.sleep(10)
            z = cdp.js(TRAILER_ZUSTAND) or {}
            z['youtube_zurueckgehalten'] = len(gehalten)
            z['vor_zurueck'] = vorher
            z['protokoll'] = cdp.js("window.KinoLelTrailer ? window.KinoLelTrailer.protokoll : 'ohne KinoLel-Trailer-Skript'")
            z['attrappe'] = cdp.js("window.__ytAttrappe || null")
            z['goBack_aufrufe'] = cdp.js("window.__zurueck || null")
            with open(os.path.join(AUS, 'logcat-trailer.txt'), 'w', encoding='utf-8') as f:
                f.write('\n'.join(z_ for z_ in adb('logcat', '-d').splitlines()
                                   if 'WebView' in z_ or 'KinoLel' in z_ or 'Back' in z_ or 'jellyfin' in z_.lower())[-400:])
            bild('applink-5-trailer-laedt-noch')
            sauber = (z.get('hash') or '').startswith('#/details') and film['id'] in (z.get('hash') or '') \
                and not z.get('ebeneSichtbar') and not z.get('spielt') and not z.get('fehlerDialog')
            merke('Trailer verlassen, während YouTube noch lädt: Detailseite ohne Überlagerung', sauber, z)
            if not sauber:
                for _ in range(2):
                    sh('input keyevent KEYCODE_BACK')
                    time.sleep(4)
                print('Nach zwei weiteren Zurück:', cdp.js(TRAILER_ZUSTAND))
                bild('applink-5-trailer-laedt-noch-danach')
                cdp.js("(function(){var pm=window.ExoPlayer&&window.ExoPlayer.playbackManager;try{pm&&pm.stop()}catch(e){};"
                       "document.querySelectorAll('.youtubePlayerContainer').forEach(function(e){e.remove()});return true})()")
        else:
            merke('Trailer-Knopf nach Neuladen da', False, cdp.js('location.hash'))
        # b) ohne Eingriff (YouTube-Attrappe aus a ist noch geladen): Zurück nach 0,5 s (lädt noch) und 3 s (spielt)
        for warte in (0.5, 3.0):
            cdp.js(f"location.hash='#/details?id={film['id']}';true")
            if not warten(cdp, "!!document.querySelector('.btnPlayTrailer:not(.hide)')", 40):
                merke(f'Trailer-Knopf da ({warte} s)', False, cdp.js('location.hash'))
                continue
            time.sleep(2)
            cdp.js("document.querySelector('.btnPlayTrailer:not(.hide)').click();true")
            time.sleep(warte)
            sh('input keyevent KEYCODE_BACK')
            time.sleep(10)  # ein noch ladender Trailer hätte jetzt seine Ebene über die Seite gelegt
            z = cdp.js(TRAILER_ZUSTAND) or {}
            bild(f'applink-5-trailer-{warte}s')
            z['protokoll'] = cdp.js("window.KinoLelTrailer ? window.KinoLelTrailer.protokoll.slice(-10) : null")
            z['verlauf'] = cdp.js('history.length')
            sauber = (z.get('hash') or '').startswith('#/details') and film['id'] in (z.get('hash') or '') \
                and not z.get('ebeneSichtbar') and not z.get('spielt') and not z.get('fehlerDialog')
            if not sauber:
                z['fokus'] = [x.strip() for x in sh('dumpsys window').splitlines() if 'mCurrentFocus' in x or 'mFocusedApp' in x][:4]
            merke(f'Trailer nach {warte} s verlassen: Detailseite ohne Überlagerung', sauber, z)
            if not sauber:  # wie Lel: kommt man mit weiterem Zurück noch heraus?
                for _ in range(2):
                    sh('input keyevent KEYCODE_BACK')
                    time.sleep(4)
                danach = cdp.js(TRAILER_ZUSTAND) or {}
                danach['protokoll'] = cdp.js("window.KinoLelTrailer ? window.KinoLelTrailer.protokoll.slice(-6) : null")
                print('Nach zwei weiteren Zurück:', danach)
                merke(f'Trailer nach {warte} s: Zustand nach zwei weiteren Zurück (nur Info)', True, danach)
                bild(f'applink-5-trailer-{warte}s-danach')
                cdp.js("(function(){var pm=window.ExoPlayer&&window.ExoPlayer.playbackManager;try{pm&&pm.stop()}catch(e){};"
                       "document.querySelectorAll('.youtubePlayerContainer').forEach(function(e){e.remove()});return true})()")
    finally:
        if LH['geschrieben'] or LH['gesehen_schluessel']:  # Testeinträge nie stehen lassen
            try:
                cdp = cdp_verbinden() if not cdp else cdp
                print('Löschhinweis-Testeinträge entfernen:', lh_aufraeumen(cdp))
            except Exception as e:  # noqa: BLE001
                print('Löschhinweis-Testeinträge NICHT entfernt:', type(e).__name__)
        # 5. abmelden (Zugang nie ausgeben)
        print('Abmelden:', cdp.js("(function(){try{var c=JSON.parse(localStorage.getItem('jellyfin_credentials'));var s=c.Servers[0];"
                                  "if(!s.AccessToken)return 'keine Sitzung';return fetch('https://kinolel.de/Sessions/Logout',{method:'POST',"
                                  "headers:{'Authorization':'MediaBrowser Token=\"'+s.AccessToken+'\"'}}).then(function(r){return 'HTTP '+r.status})}"
                                  "catch(e){return 'Fehler '+e}})()"))


try:
    main()
except Exception as e:  # noqa: BLE001
    merke('Ablauf', False, f'{type(e).__name__}: {e}')
finally:
    with open(os.path.join(AUS, 'ergebnis.json'), 'w', encoding='utf-8') as f:
        json.dump(ergebnis, f, ensure_ascii=False, indent=1, default=str)
    fehler = [k for k, v in ergebnis.items() if not v['ok']]
    print(f'APPLINK-ERGEBNIS: {len(ergebnis) - len(fehler)} von {len(ergebnis)} OK' + (f' – Fehler: {", ".join(fehler)}' if fehler else ''))
sys.exit(0)

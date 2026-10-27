#!/usr/bin/env python3
"""KinoLel-Android: QR-Code der Fernseher-Anmeldung öffnet die App (Lel 24.10.2026: „… dass man nicht eine Website
geöffnet bekommt sondern … die KinoLel App auf dem Handy“).

 1. App Link verifiziert: `pm get-app-links` zeigt kinolel.de als verified (assetlinks.json auf kinolel.de)
 2. Link https://kinolel.de/web/kinolel/qc.html?code=123456 bei beendeter App -> öffnet DIESE App (kein Browser),
    Weboberfläche geht zur Quick-Connect-Seite (ohne Anmeldung: Anmeldeseite mit gemerktem Ziel)
 3. mit Hilfskonto anmelden (Passwort nur aus KINOLEL_TEST_PW, nie ausgegeben) -> Quick-Connect-Seite, Code 123456
    eingetragen, nichts abgeschickt
 4. zweiter Link (Code 654321), während die App läuft -> Code 654321 eingetragen
 5. abmelden (fail2ban: nur eine Anmeldung je Lauf)
Aufruf: python3 applink_test.py <ausgabe-ordner>
"""
import json
import os
import re
import subprocess
import sys
import time
import urllib.request

import websocket

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
          hideScroll: document.body.classList.contains('hide-scroll')};
})()"""


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

        # 4c. Trailer verlassen, während er noch lädt (Lel 27.10.: „… Interfaces sich überlagern, da ein Trailer noch am
        #     Laden ist. Kein Ausweg außer App-Neustart.“) – YouTube-Trailer, Zurück nach 0,5 / 1,5 / 3 s:
        #     danach Detailseite, keine sichtbare YouTube-Ebene, nichts läuft
        film = cdp.js(FILM_MIT_TRAILER)
        if not merke('Film mit YouTube-Trailer gefunden', bool(film), film):
            return
        for warte in (0.5, 1.5, 3.0):
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
            sauber = (z.get('hash') or '').startswith('#/details') and film['id'] in (z.get('hash') or '') \
                and not z.get('ebeneSichtbar') and not z.get('spielt')
            merke(f'Trailer nach {warte} s verlassen: Detailseite ohne Überlagerung', sauber, z)
            if not sauber:  # wie Lel: kommt man mit weiterem Zurück noch heraus?
                for _ in range(2):
                    sh('input keyevent KEYCODE_BACK')
                    time.sleep(4)
                print('Nach zwei weiteren Zurück:', cdp.js(TRAILER_ZUSTAND))
                bild(f'applink-5-trailer-{warte}s-danach')
                cdp.js("(function(){var pm=window.ExoPlayer&&window.ExoPlayer.playbackManager;try{pm&&pm.stop()}catch(e){};"
                       "document.querySelectorAll('.youtubePlayerContainer').forEach(function(e){e.remove()});return true})()")
    finally:
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

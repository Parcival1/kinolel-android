#!/usr/bin/env python3
"""KinoLel-Android-Test: in der WebView kinolel.de auf eine Sprache stellen – wie „Magyar“ auf der Anmeldeseite
(localStorage „kl-sprache“, ohne Anmeldung, nichts am Server) – und neu laden. Aufruf: sprache_setzen.py <de|hu>"""
import json
import os
import re
import subprocess
import sys
import time
import urllib.request

import websocket

SPRACHE = sys.argv[1] if len(sys.argv) > 1 else 'hu'
PAKET = os.environ.get('PAKET', 'de.kinolel.app')


def sh(befehl):
    return subprocess.run(['adb', 'shell', befehl], capture_output=True, text=True, timeout=60).stdout


def verbinden(frist=90):
    ende = time.time() + frist
    while time.time() < ende:
        pid = sh(f'pidof {PAKET}').strip().split(' ')[0]
        if pid:
            sockel = re.findall(r'@(webview_devtools_remote_' + pid + r')\b', sh('cat /proc/net/unix'))
            if sockel:
                subprocess.run(['adb', 'forward', 'tcp:9335', 'localabstract:' + sockel[0]], capture_output=True)
                try:
                    for z in json.load(urllib.request.urlopen('http://127.0.0.1:9335/json', timeout=5)):
                        if z.get('type') == 'page' and 'kinolel' in z.get('url', ''):
                            return websocket.create_connection(z['webSocketDebuggerUrl'], timeout=60, suppress_origin=True)
                except Exception:  # noqa: BLE001
                    pass
        time.sleep(2)
    return None


def js(ws, ausdruck, n=[0]):
    n[0] += 1
    ws.send(json.dumps({'id': n[0], 'method': 'Runtime.evaluate', 'params': {'expression': ausdruck, 'returnByValue': True}}))
    while True:
        a = json.loads(ws.recv())
        if a.get('id') == n[0]:
            return a.get('result', {}).get('result', {}).get('value')


ws = verbinden()
if not ws:
    print('SPRACHE: WebView nicht erreichbar')
    sys.exit(0)
js(ws, f"localStorage.setItem('kl-sprache', '{SPRACHE}'); location.reload(); true")
ws.close()
time.sleep(8)
ws = verbinden(60)
lang = None
for _ in range(20):
    try:
        lang = js(ws, 'document.documentElement.lang')
    except Exception:  # noqa: BLE001
        ws = verbinden(30)
    if lang and lang.startswith(SPRACHE):
        break
    time.sleep(2)
print(f'SPRACHE: kinolel.de zeigt jetzt „{lang}“')
time.sleep(5)  # App merkt sich die Sprache (3 s nach dem Laden)

#!/usr/bin/env python3
"""KinoLel-App (Android): PNG-Symbole aus dem KinoLel-Logo erzeugen (braucht Pillow, z. B. auf CT 103).
Nutzt die Zeichenfunktionen aus dem Windows-Player (kinolel-player/marke/bilder_erzeugen.py).
Aufruf: python3 android_bilder.py <repo> <pfad/zu/bilder_erzeugen.py> <banner-light.png>"""
import importlib.util
import os
import sys

from PIL import Image, ImageDraw

repo, modul, banner = sys.argv[1:4]
spec = importlib.util.spec_from_file_location('be', modul)
be = importlib.util.module_from_spec(spec)
spec.loader.exec_module(be)
marke = {'APP_NAME': 'KinoLel', 'APP_COLOR_ACCENT': '#9aa4ff', 'APP_COLOR_BACKGROUND': '#0b0f18'}
res = os.path.join(repo, 'app', 'src', 'main', 'res')


def speichern(bild, *teile):
    p = os.path.join(res, *teile)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    bild.save(p)


def rund(groesse):
    """Rundes Symbol: Kreis-Platte + Logo."""
    platte = be.hintergrund(groesse, groesse, marke['APP_COLOR_BACKGROUND'])
    mk = Image.new('L', (groesse * be.SS, groesse * be.SS), 0)
    ImageDraw.Draw(mk).ellipse((0, 0, groesse * be.SS - 1, groesse * be.SS - 1), fill=255)
    mk = mk.resize((groesse, groesse), Image.LANCZOS)
    bild = Image.new('RGBA', (groesse, groesse), (0, 0, 0, 0))
    bild.paste(platte, (0, 0), mk)
    logo = be.logo_bild(round(groesse * 0.62), akzent=be.farbe(marke['APP_COLOR_ACCENT']), dick=groesse <= 48)
    return be.mittig(bild, logo)


for ordner, g in (('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144), ('xxxhdpi', 192)):
    speichern(be.symbol(g, marke), f'mipmap-{ordner}', 'ic_launcher.png')
    speichern(rund(g), f'mipmap-{ordner}', 'ic_launcher_round.png')

# Benachrichtigungssymbol: nur weiße Silhouette (Android färbt selbst), 24 dp
for ordner, g in (('ldpi', 18), ('mdpi', 24), ('hdpi', 36), ('xhdpi', 48), ('xxhdpi', 72), ('xxxhdpi', 96)):
    bild = Image.new('RGBA', (g, g), (0, 0, 0, 0))
    speichern(be.mittig(bild, be.logo_bild(round(g * 0.92), dick=g <= 48)), f'drawable-{ordner}', 'ic_notification.png')

# Logo mit Schriftzug (Kopf), Schriftzug allein (Startbild)
speichern(be.logo_mit_name(marke, 640, 144, banner, None, grund=False, anteil=0.7), 'drawable-nodpi', 'app_logo.png')
speichern(be.schriftzug(marke, 84, banner, None), 'drawable-nodpi', 'kinolel_schriftzug.png')
print('fertig')
